"""Bound disposable Minecraft labs; keep small evidence instead of whole installs.

Call only while holding test_session.acquire_test_session(). No original profiles,
worlds, shared dependency caches or server templates are managed here.
"""
import atexit
from datetime import datetime
import gzip
import json
import os
from pathlib import Path
import re
import shutil
import threading
import time

MIB = 1024 ** 2
LAB_LIMIT = 1024 * MIB
FREE_SPACE_FLOOR = 2048 * MIB
REPORT_LIMIT = 128 * MIB
REPORT_COUNT = 20
def compact_observation(snapshot):
    """Keep decisions and actual inventories, not the unchanged architectural blueprint."""
    actors=[{key:actor[key] for key in ('uuid','role','job','status','pos','health','air','nav','target','failure','bag','nativeFood','sleeping') if key in actor} for actor in snapshot.get('actors',[])]
    evidence={key:value for key,value in snapshot.get('evidence',{}).items() if key not in ('sharedChestContents','newHouseContents','damagedHouseBlocks','completeFootprint')}
    expedition=snapshot.get('state',{}).get('expedition')
    if expedition:
        expedition={key:value for key,value in expedition.items() if key not in ('returnTrails','oreTargets')}
    return {key:snapshot[key] for key in ('ticks','status','wood','trees','crops','projects') if key in snapshot}|{'actors':actors,'evidence':evidence,'expedition':expedition}
KINDS = {'client': 'client-validation', 'server': 'validation'}
RUN_NAME = re.compile(r'\d{8}-\d{6}(?:-\d{6})?\Z')
REPORT_NAME = re.compile(r'(?:client|server)-\d{8}-\d{6}(?:-\d{6})?\Z')


def _process_alive(pid):
    if os.name == 'nt':
        import ctypes
        from ctypes import wintypes
        kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        kernel.OpenProcess.restype = wintypes.HANDLE
        kernel.GetExitCodeProcess.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD)]
        kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        handle = kernel.OpenProcess(0x1000, False, pid)
        if not handle:
            return ctypes.get_last_error() == 5  # Access denied: conservatively keep it.
        try:
            code = wintypes.DWORD()
            if not kernel.GetExitCodeProcess(handle, ctypes.byref(code)):
                raise OSError('Cannot inspect validation game process')
            return code.value == 259  # STILL_ACTIVE
        finally:
            kernel.CloseHandle(handle)
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True


def size_bytes(path):
    """Never follow links/junctions when measuring generated data."""
    if not path.exists() or path.is_symlink() or path.is_junction():
        return 0
    if path.is_file():
        return path.stat().st_size
    import os
    total = 0
    for directory, folders, files in os.walk(path, followlinks=False):
        folders[:] = [name for name in folders
                      if not (Path(directory) / name).is_symlink()
                      and not (Path(directory) / name).is_junction()]
        for name in files:
            item = Path(directory) / name
            if not item.is_symlink():
                try:
                    total += item.stat().st_size
                except FileNotFoundError:
                    pass  # The running game can rotate logs between stat calls.
    return total


def _base(root, name):
    root = Path(root).resolve(strict=True)
    base = root / 'build' / name
    if base.resolve() != base or base.is_symlink() or base.is_junction():
        raise ValueError(f'Refusing redirected artifact directory: {base}')
    return base


def _checked_child(root, path, name, pattern):
    base = _base(root, name)
    path = Path(path).absolute()
    if (path.parent != base or not pattern.fullmatch(path.name)
            or path.resolve() != path or path.is_symlink() or path.is_junction()):
        raise ValueError(f'Refusing path outside managed generated runs: {path}')
    return path


def _remove(root, path, name, pattern):
    # Crash Assistant/AV can briefly retain a copied JAR after Minecraft exits.
    # Retry only Windows sharing violations; never remove or relocate other data.
    for attempt in range(12):
        path = _checked_child(root, path, name, pattern)
        if not path.exists():
            return
        try:
            shutil.rmtree(path)
            return
        except OSError as error:
            if getattr(error, 'winerror', None) not in (32, 33) or attempt == 11:
                raise
            time.sleep(min(2, .25 * 2 ** attempt))


def reject_disposable_source(root, source):
    """Fail before pruning if --world would point into the disposable labs."""
    source = Path(source).resolve(strict=True)
    for folder in KINDS.values():
        if source.is_relative_to(_base(root, folder)):
            raise ValueError('--world must not use a disposable validation lab: '
                             'old labs are removed before the next run.')


def _copy_small(source, destination, limit):
    if source.is_file() and not source.is_symlink() and source.stat().st_size <= limit:
        shutil.copy2(source, destination)


def collect_log_issues(path):
    """Keep startup problems that would otherwise fall outside a 256 KiB log tail."""
    profile = path / 'profile/logs/latest.log'
    logs = [profile if profile.is_file() else path / 'console.txt']
    logs += [path / name for name in ('server.log', 'restart.log', 'corrupt-state.log')]
    groups = {}
    overflow = 0
    for source in logs:
        if not source.is_file() or source.is_symlink():
            continue
        with source.open(encoding='utf8', errors='replace') as stream:
            for line in stream:
                if not re.search(r'/(?:WARN|ERROR)\]', line):
                    continue
                message = re.sub(r'^\[[^]]+\] ', '', line.strip())[:4096]
                key = (source.name, message)
                if key in groups:
                    groups[key]['count'] += 1
                    groups[key]['last'] = line.strip()[:4096]
                elif len(groups) < 256:
                    groups[key] = {'source': source.name, 'message': message, 'count': 1,
                                   'first': line.strip()[:4096], 'last': line.strip()[:4096]}
                else:
                    overflow += 1
    return {'groups': list(groups.values()), 'overflowEvents': overflow,
            'note': 'Raw severity retained; offline authentication, third-party and deliberate corrupt-state errors require separate interpretation.'}


def archive_run(root, kind, path):
    """Keep exact small results, log tails and up to six native screenshots."""
    path = _checked_child(root, path, KINDS[kind], RUN_NAME)
    reports = _base(root, 'validation-reports')
    destination = reports / f'{kind}-{path.name}'
    _checked_child(root, destination, 'validation-reports', REPORT_NAME)
    destination.mkdir(parents=True, exist_ok=True)
    issues = collect_log_issues(path)
    if issues['groups'] or not (destination / 'log-issues.json').exists():
        (destination / 'log-issues.json').write_text(json.dumps(issues, ensure_ascii=False, indent=2), encoding='utf8')
    profile = path / 'profile'
    for source, name in [(path / 'summary.json', 'summary.json'),
                         (profile / 'client-result.json', 'client-result.json'),
                         (profile / 'colony-checkpoint.json', 'colony-checkpoint.json'),
                         (profile / 'colony-checkpoint-96000.json', 'colony-checkpoint-96000.json'),
                         (profile / 'colony-live.json', 'colony-live.json')]:
        _copy_small(source, destination / name, 2 * MIB)
    for source, name in [(path / 'console.txt', 'console-tail.txt.gz'),
                         (path / 'client.txt', 'client-tail.txt.gz'),
                         (profile / 'logs/latest.log', 'latest-tail.log.gz'),
                         (path / 'server.log', 'server-tail.log.gz'),
                         (path / 'restart.log', 'restart-tail.log.gz'),
                         (path / 'corrupt-state.log', 'corrupt-state-tail.log.gz')]:
        if source.is_file() and not source.is_symlink():
            with source.open('rb') as stream:
                stream.seek(max(0, source.stat().st_size - 256 * 1024))
                with gzip.open(destination / name, 'wb') as output:
                    output.write(stream.read(256 * 1024))
    frames = sorted((profile / 'screenshots').glob('*.png'))[-6:]
    for frame in frames:
        _copy_small(frame, destination / frame.name, MIB)
    _copy_small(path / 'timeline.jsonl.gz', destination / 'timeline.jsonl.gz', 16*MIB)
    return destination


def trim_reports(root):
    base = _base(root, 'validation-reports')
    if not base.exists():
        return
    reports = sorted((p for p in base.iterdir() if p.is_dir()
                      and REPORT_NAME.fullmatch(p.name)),
                     key=lambda p: p.name.split('-', 1)[1], reverse=True)
    used = 0
    for index, path in enumerate(reports):
        size = size_bytes(path)
        if index >= REPORT_COUNT or used + size > REPORT_LIMIT:
            _remove(root, path, 'validation-reports', REPORT_NAME)
        else:
            used += size


def cleanup_labs(root):
    """Archive and delete every prior lab. No unlimited dated directories."""
    removed = 0
    freed = 0
    for kind, folder in KINDS.items():
        base = _base(root, folder)
        if not base.exists():
            continue
        for path in sorted(base.iterdir()):
            if not RUN_NAME.fullmatch(path.name):
                raise ValueError(f'Unexpected lab entry; inspect it manually: {path}')
            _checked_child(root, path, folder, RUN_NAME)
            state = path / 'process-state.json'
            if state.is_file():
                data = json.loads(state.read_text(encoding='utf8'))
                if data.get('active') and any(_process_alive(pid) for pid in data['pids']):
                    raise RuntimeError(f'Validation game still running; refusing cleanup: {path}')
            size = size_bytes(path)
            archive_run(root, kind, path)
            _remove(root, path, folder, RUN_NAME)
            removed += 1
            freed += size
    trim_reports(root)
    return removed, freed


class ValidationRun:
    """One <=1 GiB lab; remove payload on exit and bound saved reports.

    --keep-lab is for one explicit diagnostic checkpoint only. The next run
    removes it. A crashed Python process is also cleaned on the next start.
    """
    def __init__(self, root, kind, keep_lab=False):
        self.root = Path(root).resolve(strict=True)
        self.kind = kind
        self.keep_lab = keep_lab
        self.processes = []
        self.budget_error = None
        self.finished = False
        self._stop = threading.Event()
        cleanup_labs(self.root)
        self.output = _base(self.root, KINDS[kind]) / datetime.now().strftime('%Y%m%d-%H%M%S-%f')
        self.output.mkdir(parents=True)
        atexit.register(self.finish)
        (self.output / 'summary.json').write_text(json.dumps({
            'success': False, 'reason': 'validation_setup_or_runner_did_not_complete',
            'kind': kind, 'output': str(self.output)
        }, indent=2), encoding='utf8')
        self.check_budget()

    def register_process(self, process):
        self.processes.append(process)
        self._write_process_state(True)

    def _write_process_state(self, active):
        (self.output / 'process-state.json').write_text(
            json.dumps({'active': active, 'pids': [p.pid for p in self.processes]}),
            encoding='utf8')

    def check_copy_budget(self, sources):
        required = 0
        for source in sources:
            source = Path(source)
            if source.is_symlink() or source.is_junction():
                raise ValueError(f'Refusing redirected lab input: {source}')
            if source.is_dir():
                import os
                for directory, folders, files in os.walk(source, followlinks=False):
                    for name in folders + files:
                        item = Path(directory) / name
                        if item.is_symlink() or item.is_junction():
                            raise ValueError(f'Refusing linked lab input: {item}')
            required += size_bytes(source)
        if size_bytes(self.output) + required > LAB_LIMIT:
            self.budget_error = 'Copied lab inputs would exceed the 1 GiB budget'
        elif shutil.disk_usage(self.output).free - required < FREE_SPACE_FLOOR:
            self.budget_error = 'Copying lab inputs would leave less than 2 GiB free'
        if self.budget_error:
            raise RuntimeError(self.budget_error)

    def check_budget(self):
        self.prune_screenshots()
        size = size_bytes(self.output)
        free = shutil.disk_usage(self.output).free
        if size > LAB_LIMIT:
            self.budget_error = f'Validation lab exceeds 1 GiB ({size} bytes)'
        elif free < FREE_SPACE_FLOOR:
            self.budget_error = 'Validation requires at least 2 GiB free disk space'
        if self.budget_error:
            raise RuntimeError(self.budget_error)

    def prune_screenshots(self):
        _checked_child(self.root, self.output, KINDS[self.kind], RUN_NAME)
        folder = self.output / 'profile/screenshots'
        if not folder.exists():
            return
        if folder.resolve() != folder or folder.is_symlink() or folder.is_junction():
            raise ValueError('Refusing redirected screenshot folder')
        frames = sorted(p for p in folder.iterdir() if re.fullmatch(r'colony-\d{5}\.png', p.name))
        for frame in frames[:-6]:
            if frame.resolve().parent != folder or frame.is_symlink():
                raise ValueError('Refusing redirected screenshot')
            frame.unlink()

    def start_monitor(self):
        self.check_budget()

        def watch():
            while not self._stop.wait(10):
                try:
                    self.check_budget()
                except Exception as error:
                    self.budget_error = str(error)
                    for path in [self.output / 'stop-requested',
                                 self.output / 'profile/stop-requested']:
                        if path.parent.is_dir():
                            path.write_text(self.budget_error, encoding='utf8')
                    print('ARTIFACT_BUDGET ' + self.budget_error, flush=True)
                    return
        self._monitor = threading.Thread(target=watch, daemon=True)
        self._monitor.start()

    def finish(self):
        if self.finished:
            return
        if any(process.poll() is None for process in self.processes):
            raise RuntimeError('Refusing cleanup while a validation game is still running')
        self._stop.set()
        monitor = getattr(self, '_monitor', None)
        if monitor:
            monitor.join(timeout=15)
        self._write_process_state(False)
        saved = archive_run(self.root, self.kind, self.output)
        if not self.keep_lab or self.budget_error or size_bytes(self.output) > LAB_LIMIT:
            _remove(self.root, self.output, KINDS[self.kind], RUN_NAME)
        trim_reports(self.root)
        self.finished = True
        print('EVIDENCE ' + str(saved), flush=True)
