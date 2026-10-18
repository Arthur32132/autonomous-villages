#!/usr/bin/env python3
"""Headless soak runs on Linux: build, disposable Fabric server, real generated village, metrics.

Example:
    python3 tools/soak/run.py --days 3 --difficulty normal --seeds 8675309,42 --parallel 2

Each run writes build/soak-reports/<batch>/<seed>-<difficulty>/{soak-result.json,soak-timeline.jsonl,server-tail.log.gz}
and a batch summary.json. Labs are deleted afterwards unless --keep-lab is given.
"""
import argparse, gzip, json, os, random, shutil, subprocess, sys, threading, time, urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
NAV = ROOT.parent / 'MinecraftmodNavigationAi'
TEMPLATE = Path(os.environ.get('AV_SOAK_TEMPLATE', Path.home() / '.cache/av-soak/template'))
MC, LOADER, INSTALLER = '26.2', '0.19.5', '1.1.2'
FABRIC_API = '0.161.0+26.2'


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


def ensure_template(java):
    """Download the Fabric server launcher and libraries once; later runs hard-link them."""
    ready = all((TEMPLATE / p).exists() for p in ['fabric-server-launch.jar', 'libraries', 'versions', '.fabric', f'fabric-api-{FABRIC_API}.jar'])
    if ready:
        return
    TEMPLATE.mkdir(parents=True, exist_ok=True)
    launcher = TEMPLATE / 'fabric-server-launch.jar'
    if not launcher.exists():
        urllib.request.urlretrieve(f'https://meta.fabricmc.net/v2/versions/loader/{MC}/{LOADER}/{INSTALLER}/server/jar', launcher)
    api = TEMPLATE / f'fabric-api-{FABRIC_API}.jar'
    if not api.exists():
        cached = list(Path.home().glob(f'.gradle/caches/modules-2/files-2.1/net.fabricmc.fabric-api/fabric-api/{FABRIC_API}/*/fabric-api-{FABRIC_API}.jar'))
        if cached:
            shutil.copy2(cached[0], api)
        else:
            urllib.request.urlretrieve(f'https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/{FABRIC_API}/fabric-api-{FABRIC_API}.jar', api)
    (TEMPLATE / 'eula.txt').write_text('eula=true\n')
    boot = TEMPLATE / 'boot'
    shutil.rmtree(boot, ignore_errors=True)
    boot.mkdir()
    for name in ['fabric-server-launch.jar', 'eula.txt']:
        shutil.copy2(TEMPLATE / name, boot / name)
    (boot / 'server.properties').write_text('online-mode=false\nserver-port=25599\nlevel-type=minecraft:flat\n')
    subprocess.run([str(java / 'bin/java'), '-Xmx2G', '-jar', 'fabric-server-launch.jar', 'nogui'], cwd=boot, input=b'stop\n',
                   stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT, timeout=900)
    for name in ['libraries', 'versions', '.fabric']:
        shutil.rmtree(TEMPLATE / name, ignore_errors=True)
        shutil.move(boot / name, TEMPLATE / name)
    shutil.rmtree(boot)


def build(java):
    env = dict(os.environ, JAVA_HOME=str(java), PATH=f'{java}/bin:' + os.environ['PATH'])
    subprocess.run(['sh', './gradlew', 'build', 'soakJar', ':navigationAi:jar', '-x', 'test', '--no-daemon', '-q', '--console=plain'],
                   cwd=ROOT, env=env, check=True)


def snapshot_jars(batch_dir, jars_from=None):
    """Copies the mod jars once per batch, so a rebuild while the batch runs cannot mix versions."""
    target = batch_dir / 'jars'
    shutil.rmtree(target, ignore_errors=True)
    target.mkdir(parents=True)
    v, nav = version(ROOT), version(NAV)
    if jars_from:
        sources = [p for p in Path(jars_from).glob('*.jar') if not p.name.endswith('-sources.jar')]
    else:
        sources = [ROOT / f'build/libs/autonomous-villages-{v}.jar', ROOT / f'build/libs/autonomous-villages-soak-{v}.jar']
    sources += [NAV / f'build/libs/navigation-ai-{nav}.jar', TEMPLATE / f'fabric-api-{FABRIC_API}.jar']
    for jar in sources:
        if not any(jar.name == s.name for s in target.iterdir()):
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
        process = subprocess.Popen([str(java / 'bin/java'), f'-Xmx{args.heap}', '-XX:+UseZGC', '-jar', 'fabric-server-launch.jar', 'nogui'],
                                   cwd=lab, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT)
        try:
            process.wait(timeout=args.max_real_seconds + 300)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
    result = {'completed': False, 'reason': 'no_result_file', 'seed': seed, 'difficulty': difficulty}
    if (lab / 'soak-result.json').exists():
        result = json.loads((lab / 'soak-result.json').read_text())
        shutil.copy2(lab / 'soak-result.json', report / 'soak-result.json')
    if (lab / 'soak-timeline.jsonl').exists():
        shutil.copy2(lab / 'soak-timeline.jsonl', report / 'soak-timeline.jsonl')
    lines = (lab / 'server.log').read_bytes().splitlines()
    with gzip.open(report / 'server-tail.log.gz', 'wb') as tail:
        tail.write(b'\n'.join(lines[-4000:]))
    problems = [l.decode('utf8', 'replace') for l in lines if b'/ERROR]' in l or b'Exception' in l][:200]
    (report / 'errors.txt').write_text('\n'.join(problems))
    result['wallSeconds'] = round(time.time() - started, 1)
    result['logErrors'] = len(problems)
    if not args.keep_lab:
        shutil.rmtree(lab, ignore_errors=True)
    return label, result


def row(label, r):
    o = r.get('outcome', {})
    return (f"{label:<22} {'OK ' if r.get('completed') else 'FAIL'} {r.get('reason','')[:28]:<28} days={r.get('gameDays','-'):<5} "
            f"tps={r.get('avgTps','-'):<6} vill={r.get('start',{}).get('villagers','-')}->{r.get('end',{}).get('villagers','-')} "
            f"births={o.get('births','-')} deaths={o.get('deaths','-')} newBeds={o.get('newBeds','-')} built={o.get('builtBlocks','-')} "
            f"logs={o.get('logsChange','-')} score={o.get('score','-')} errors={r.get('logErrors','-')}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--days', type=float, default=3)
    parser.add_argument('--seeds', default='8675309')
    parser.add_argument('--difficulty', default='normal', help='comma list: peaceful,easy,normal,hard')
    parser.add_argument('--tick-rate', type=int, default=400)
    parser.add_argument('--parallel', type=int, default=1)
    parser.add_argument('--max-real-seconds', type=int, default=3600)
    parser.add_argument('--radius', type=int, default=112)
    parser.add_argument('--heap', default='3G')
    parser.add_argument('--batch', default=None, help='report folder name; default timestamp')
    parser.add_argument('--no-claim', action='store_true', help='observe the untouched village (control run)')
    parser.add_argument('--skip-build', action='store_true')
    parser.add_argument('--jars', default=None, help='folder with the mod and soak jars to test instead of build/libs (e.g. an older release)')
    parser.add_argument('--keep-lab', action='store_true')
    parser.add_argument('--prepare-only', action='store_true', help='only download/boot the cached server template')
    args = parser.parse_args()
    java = java_home()
    ensure_template(java)
    if args.prepare_only:
        print(f'server template ready: {TEMPLATE}')
        return
    if not args.skip_build:
        build(java)
    batch_dir = ROOT / 'build/soak-reports' / (args.batch or time.strftime('%Y%m%d-%H%M%S'))
    batch_dir.mkdir(parents=True, exist_ok=True)
    jars = snapshot_jars(batch_dir, args.jars)
    jobs = [(s.strip(), d.strip()) for s in args.seeds.split(',') for d in args.difficulty.split(',')]
    results, lock, queue = {}, threading.Lock(), list(jobs)

    def worker():
        while True:
            with lock:
                if not queue:
                    return
                seed, difficulty = queue.pop(0)
            label, result = run_one(java, batch_dir, seed, difficulty, args, jars)
            with lock:
                results[label] = result
                print(row(label, result), flush=True)

    threads = [threading.Thread(target=worker) for _ in range(max(1, args.parallel))]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    summary = {'batch': batch_dir.name, 'args': vars(args), 'runs': {k: {key: v.get(key) for key in
               ['completed', 'reason', 'gameDays', 'avgTps', 'outcome', 'wallSeconds', 'logErrors', 'claimed', 'claimError', 'village']}
               for k, v in sorted(results.items())}}
    (batch_dir / 'summary.json').write_text(json.dumps(summary, indent=2, ensure_ascii=False))
    print(f'\nreports: {batch_dir}')
    for label in sorted(results):
        print(row(label, results[label]))


if __name__ == '__main__':
    main()
