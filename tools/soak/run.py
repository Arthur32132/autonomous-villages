#!/usr/bin/env python3
"""Headless soak runs on Linux: build, disposable Fabric server, real generated village, metrics.

Example:
    python3 tools/soak/run.py --days 3 --difficulty normal --seeds 8675309,42 --parallel 2

Each run writes build/soak-reports/<batch>/<seed>-<difficulty>/{soak-result.json,soak-timeline.jsonl,server-tail.log.gz,events.log}
and a batch summary.json. Labs are deleted afterwards unless --keep-lab is given.
Exit code 0 only when every run completed; 1 when any run failed (runner error, no soak-result.json, probe gave up).
"""
import argparse, gzip, json, os, random, re, shutil, subprocess, sys, threading, time, traceback, urllib.request, zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
NAV = ROOT.parent / 'MinecraftmodNavigationAi'
TEMPLATE = Path(os.environ.get('AV_SOAK_TEMPLATE', Path.home() / '.cache/av-soak/template'))
MC, LOADER, INSTALLER = '26.2', '0.19.5', '1.1.2'
FABRIC_API = '0.161.0+26.2'
# events.log: the probe's own SOAK lines plus the village's story (deaths with context, traps, chronicle).
EVENT_MARKERS = (b'SOAK ', b'DEATH ', b'TRAPPED ', b'UNSTUCK ', b'SETTLEMENT ', b'JOB_FAILED ', b'HUNT ')
ERROR_CAP = 200


def java_home():
    """Minecraft 26.2 needs Java 25; the container's default JAVA_HOME is often older."""
    for candidate in [os.environ.get('AV_JAVA_HOME'), *sorted(Path.home().glob('jdk/jdk-2[5-9]*')), *sorted(Path('/home/user/jdk').glob('jdk-2[5-9]*')), os.environ.get('JAVA_HOME')]:
        if not candidate or not (Path(candidate) / 'bin/java').exists():
            continue
        release = Path(candidate) / 'release'
        major = next((l.split('=', 1)[1].strip('"').split('.')[0] for l in release.read_text().splitlines() if l.startswith('JAVA_VERSION=')), '0') if release.exists() else '0'
        if int(major) >= 25:
            return Path(candidate)
    sys.exit('JDK 25+ not found: set AV_JAVA_HOME')


def version(project):
    return next(l.split('=', 1)[1].strip() for l in (project / 'gradle.properties').read_text().splitlines() if l.startswith('version='))


def valid_jar(path):
    """A jar counts only as a complete zip: a cut-off download must not pass the readiness check forever."""
    return Path(path).is_file() and zipfile.is_zipfile(path)


def download(url, target, cached=None):
    """Fetches into <target>.part (from `cached` when given) and renames it only once it opens as a zip."""
    target = Path(target)
    part = target.with_name(target.name + '.part')
    try:
        if cached:
            shutil.copy2(cached, part)
        else:
            urllib.request.urlretrieve(url, part)
        if not zipfile.is_zipfile(part):
            raise RuntimeError(f'{cached or url}: not a complete jar (truncated download?)')
        os.replace(part, target)
    finally:
        part.unlink(missing_ok=True)
    return target


def ensure_template(java):
    """Download the Fabric server launcher and libraries once; later runs hard-link them."""
    launcher, api = TEMPLATE / 'fabric-server-launch.jar', TEMPLATE / f'fabric-api-{FABRIC_API}.jar'
    if valid_jar(launcher) and valid_jar(api) and all((TEMPLATE / p).exists() for p in ['libraries', 'versions', '.fabric']):
        return
    TEMPLATE.mkdir(parents=True, exist_ok=True)
    if not valid_jar(launcher):
        download(f'https://meta.fabricmc.net/v2/versions/loader/{MC}/{LOADER}/{INSTALLER}/server/jar', launcher)
    if not valid_jar(api):
        cached = [p for p in Path.home().glob(f'.gradle/caches/modules-2/files-2.1/net.fabricmc.fabric-api/fabric-api/{FABRIC_API}/*/fabric-api-{FABRIC_API}.jar') if valid_jar(p)]
        download(f'https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/{FABRIC_API}/fabric-api-{FABRIC_API}.jar', api, cached[0] if cached else None)
    (TEMPLATE / 'eula.txt').write_text('eula=true\n')
    boot = TEMPLATE / 'boot'
    shutil.rmtree(boot, ignore_errors=True)
    boot.mkdir()
    for name in ['fabric-server-launch.jar', 'eula.txt']:
        shutil.copy2(TEMPLATE / name, boot / name)
    (boot / 'server.properties').write_text('online-mode=false\nserver-port=25599\nlevel-type=minecraft:flat\n')
    try:
        subprocess.run([str(java / 'bin/java'), '-Xmx2G', '-jar', 'fabric-server-launch.jar', 'nogui'], cwd=boot, input=b'stop\n',
                       stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT, timeout=900)
    except subprocess.TimeoutExpired:
        # 26.2 can fail the piped "stop" ("unexpected error") and idle on; the download is done by then.
        if not all((boot / n).exists() for n in ['libraries', 'versions', '.fabric']):
            raise
    for name in ['libraries', 'versions', '.fabric']:
        shutil.rmtree(TEMPLATE / name, ignore_errors=True)
        shutil.move(boot / name, TEMPLATE / name)
    shutil.rmtree(boot)


def build(java):
    env = dict(os.environ, JAVA_HOME=str(java), PATH=f'{java}/bin:' + os.environ['PATH'])
    subprocess.run(['sh', './gradlew', 'build', 'soakJar', ':navigationAi:jar', '-x', 'test', '--no-daemon', '-q', '--console=plain'],
                   cwd=ROOT, env=env, check=True)


def artifact(jar):
    """Mod name of a jar without its version: navigation-ai-0.2.2.jar -> navigation-ai."""
    stem = Path(jar).name.removesuffix('.jar')
    match = re.match(r'(.+?)-\d', stem)
    return (match.group(1) if match else stem).lower()


def select_jars(primary, defaults):
    """Every primary jar (--jars folder or build/libs) plus each default whose mod the primary set lacks.

    A folder's navigation-ai (or fabric-api) therefore wins over the build/template copy, and one mod never
    lands in mods/ twice; two versions of one mod among the primary jars are refused as ambiguous.
    """
    chosen = {}
    for jar in sorted(primary):
        if jar.name.endswith('-sources.jar'):
            continue
        other = chosen.setdefault(artifact(jar), jar)
        if other != jar:
            raise ValueError(f'two jars of {artifact(jar)}: {other.name} and {jar.name}; keep one')
    for jar in defaults:
        chosen.setdefault(artifact(jar), jar)
    return list(chosen.values())


def snapshot_jars(batch_dir, jars_from=None, mod_version=None):
    """Copies the mod jars once per batch, so a rebuild while the batch runs cannot mix versions."""
    target = batch_dir / 'jars'
    shutil.rmtree(target, ignore_errors=True)
    target.mkdir(parents=True)
    v, nav = mod_version or version(ROOT), version(NAV)
    if jars_from:
        primary = list(Path(jars_from).glob('*.jar'))
    else:
        primary = [ROOT / f'build/libs/autonomous-villages-{v}.jar', ROOT / f'build/libs/autonomous-villages-soak-{v}.jar']
    for jar in select_jars(primary, [NAV / f'build/libs/navigation-ai-{nav}.jar', TEMPLATE / f'fabric-api-{FABRIC_API}.jar']):
        if not jar.is_file():
            raise FileNotFoundError(f'jar not found: {jar}')
        shutil.copy2(jar, target / jar.name)
    return target


def prepare_lab(lab, seed, difficulty, args, label, jars):
    lab.mkdir(parents=True)
    for name in ['libraries', 'versions', '.fabric']:
        subprocess.run(['cp', '-al', str(TEMPLATE / name), str(lab / name)], check=True)
    for name in ['fabric-server-launch.jar', 'eula.txt']:
        shutil.copy2(TEMPLATE / name, lab / name)
    mods = lab / 'mods'
    mods.mkdir()
    for jar in jars.glob('*.jar'):
        shutil.copy2(jar, mods / jar.name)
    port = random.randint(26000, 29999)
    (lab / 'server.properties').write_text('\n'.join([
        'online-mode=false', f'server-port={port}', f'level-seed={seed}', f'difficulty={difficulty}', 'level-name=world',
        'view-distance=6', 'simulation-distance=6', 'pause-when-empty-seconds=0', 'spawn-protection=0', 'max-tick-time=-1',
        'sync-chunk-writes=false', 'enable-rcon=false', 'enable-query=false', 'max-players=4', 'motd=soak', '']))
    (lab / 'soak.properties').write_text('\n'.join([
        f'days={args.days}', f'tickRate={args.tick_rate}', f'seed={seed}', f'difficulty={difficulty}', f'label={label}',
        f'maxRealSeconds={args.max_real_seconds}', f'claim={str(not args.no_claim).lower()}', f'radius={args.radius}',
        'out=soak-result.json', 'timeline=soak-timeline.jsonl', '']))


def run_one(java, batch_dir, seed, difficulty, args, jars):
    label = f'{seed}-{difficulty}'
    lab = ROOT / 'build/soak-labs' / f'{batch_dir.name}-{label}'
    report = batch_dir / label
    report.mkdir(parents=True, exist_ok=True)
    shutil.rmtree(lab, ignore_errors=True)
    prepare_lab(lab, seed, difficulty, args, label, jars)
    started = time.time()
    with open(lab / 'server.log', 'wb') as log:
        process = subprocess.Popen([str(java / 'bin/java'), f'-Xmx{args.heap}', '-XX:+UseG1GC', '-jar', 'fabric-server-launch.jar', 'nogui'],
                                   cwd=lab, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT)
        try:
            process.wait(timeout=args.max_real_seconds + 300)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
    result = {'completed': False, 'reason': 'no_result_file', 'seed': seed, 'difficulty': difficulty}
    if (lab / 'soak-result.json').exists():
        shutil.copy2(lab / 'soak-result.json', report / 'soak-result.json')
        try:
            result = json.loads((lab / 'soak-result.json').read_text())
        except ValueError as error:
            result = dict(result, reason='bad_result_file', error=f'soak-result.json: {error}')
    for name in ['soak-timeline.jsonl', 'map-start.json', 'map-end.json'] + sorted(p.name for p in lab.glob('map-t*.json')):
        if (lab / name).exists():
            shutil.copy2(lab / name, report / name)
    lines = (lab / 'server.log').read_bytes().splitlines()
    with gzip.open(report / 'server-tail.log.gz', 'wb') as tail:
        tail.write(b'\n'.join(lines[-4000:]))
    events, problems, total = log_digest(lines)
    (report / 'events.log').write_bytes(b'\n'.join(events))
    note = [f'... cap of {ERROR_CAP} reached: {total - len(problems)} more error lines not shown ({total} in all)'] if total > len(problems) else []
    (report / 'errors.txt').write_text('\n'.join(problems + note), encoding='utf-8')
    result['wallSeconds'] = round(time.time() - started, 1)
    result['logErrors'] = len(problems)
    result['logErrorsTotal'] = total
    if not args.keep_lab:
        shutil.rmtree(lab, ignore_errors=True)
    return label, result


def log_digest(lines, cap=ERROR_CAP):
    """(events.log lines, error lines up to `cap`, total error lines) from the raw server log lines."""
    events = [l for l in lines if any(m in l for m in EVENT_MARKERS)]
    problems = [l.decode('utf8', 'replace') for l in lines if b'/ERROR]' in l or b'Exception' in l]
    return events, problems[:cap], len(problems)


def row(label, r):
    o = r.get('outcome') or {}
    errors, total = r.get('logErrors', '-'), r.get('logErrorsTotal')
    if isinstance(total, int) and isinstance(errors, int) and total > errors:
        errors = f'{errors} (cap hit: {total} in log)'
    return (f"{label:<22} {'OK ' if r.get('completed') else 'FAIL'} {str(r.get('reason',''))[:28]:<28} days={str(r.get('gameDays','-')):<5} "
            f"tps={str(r.get('avgTps','-')):<6} vill={(r.get('start') or {}).get('villagers','-')}->{(r.get('end') or {}).get('villagers','-')} "
            f"births={o.get('births','-')} deaths={o.get('deaths','-')} newBeds={o.get('newBeds','-')} built={o.get('builtBlocks','-')} "
            f"logs={o.get('logsChange','-')} score={o.get('score','-')} errors={errors}" + (f" error={r['error']}" if r.get('error') else ''))


def run_jobs(jobs, parallel, run, report=lambda label, result: print(row(label, result), flush=True)):
    """Runs (seed, difficulty) jobs on `parallel` threads; an exception fails only its own run, recorded as {'error': ...}."""
    results, lock, queue = {}, threading.Lock(), list(jobs)

    def worker():
        while True:
            with lock:
                if not queue:
                    return
                seed, difficulty = queue.pop(0)
            label = f'{seed}-{difficulty}'
            try:
                label, result = run(seed, difficulty)
            except Exception as error:
                result = {'completed': False, 'reason': 'runner_error', 'seed': seed, 'difficulty': difficulty, 'error': f'{type(error).__name__}: {error}'}
                with lock:
                    print(f'{label}: runner error\n{traceback.format_exc()}', file=sys.stderr, flush=True)
            with lock:
                results[label] = result
                try:
                    report(label, result)
                except Exception:
                    traceback.print_exc()

    threads = [threading.Thread(target=worker) for _ in range(max(1, parallel))]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    for seed, difficulty in jobs:
        results.setdefault(f'{seed}-{difficulty}', {'completed': False, 'reason': 'missing', 'seed': seed, 'difficulty': difficulty, 'error': 'no result recorded'})
    return results


def failed_runs(results):
    """Labels of runs that did not complete: runner error, no or unreadable soak-result.json, probe gave up."""
    return [label for label, r in sorted(results.items()) if not r.get('completed')]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--days', type=float, default=3)
    parser.add_argument('--seeds', default='8675309')
    parser.add_argument('--difficulty', default='normal', help='comma list: peaceful,easy,normal,hard')
    parser.add_argument('--tick-rate', type=int, default=100, help='above ~100 the navigation planning budget (a share of real tick time) shrinks and villagers wait for routes far longer than in real play')
    parser.add_argument('--parallel', type=int, default=1)
    parser.add_argument('--max-real-seconds', type=int, default=3600)
    parser.add_argument('--radius', type=int, default=112)
    parser.add_argument('--heap', default='2G')
    parser.add_argument('--batch', default=None, help='report folder name; default timestamp')
    parser.add_argument('--no-claim', action='store_true', help='observe the untouched village (control run)')
    parser.add_argument('--skip-build', action='store_true')
    parser.add_argument('--mod-version', default=None, help='version suffix of the mod jars in build/libs, e.g. 0.3.0-dev from -Pversion=0.3.0-dev')
    parser.add_argument('--jars', default=None, help='folder with the mod and soak jars to test instead of build/libs (e.g. an older release); a navigation-ai or fabric-api jar in it replaces the default one')
    parser.add_argument('--keep-lab', action='store_true')
    parser.add_argument('--prepare-only', action='store_true', help='only download/boot the cached server template')
    args = parser.parse_args(argv)
    java = java_home()
    ensure_template(java)
    if args.prepare_only:
        print(f'server template ready: {TEMPLATE}')
        return
    if not args.skip_build:
        build(java)
    batch_dir = ROOT / 'build/soak-reports' / (args.batch or time.strftime('%Y%m%d-%H%M%S'))
    batch_dir.mkdir(parents=True, exist_ok=True)
    try:
        jars = snapshot_jars(batch_dir, args.jars, args.mod_version)
    except (OSError, ValueError) as error:
        sys.exit(f'jars: {error}')
    jobs = [(s.strip(), d.strip()) for s in args.seeds.split(',') for d in args.difficulty.split(',')]
    results = run_jobs(jobs, args.parallel, lambda seed, difficulty: run_one(java, batch_dir, seed, difficulty, args, jars))
    summary = {'batch': batch_dir.name, 'args': vars(args), 'runs': {k: {key: v.get(key) for key in
               ['completed', 'reason', 'error', 'gameDays', 'avgTps', 'outcome', 'wallSeconds', 'logErrors', 'logErrorsTotal', 'claimed', 'claimError', 'village']}
               for k, v in sorted(results.items())}}
    (batch_dir / 'summary.json').write_text(json.dumps(summary, indent=2, ensure_ascii=False))
    print(f'\nreports: {batch_dir}')
    for label in sorted(results):
        print(row(label, results[label]))
    failed = failed_runs(results)
    if failed:
        print(f'\nFAILED {len(failed)} of {len(results)}: ' + ', '.join(f"{l} ({results[l].get('error') or results[l].get('reason')})" for l in failed),
              file=sys.stderr)
        sys.exit(1)


if __name__ == '__main__':
    main()
