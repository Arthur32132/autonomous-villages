"""One validation process at a time: avoid competing Minecraft windows and heaps."""
import atexit
import json
import os
from pathlib import Path


def acquire_test_session(root: Path, purpose: str):
    folder = root / 'build'
    folder.mkdir(exist_ok=True)
    lock = (folder / 'validation-session.lock').open('a+b')
    lock.seek(0, os.SEEK_END)
    if lock.tell() == 0:
        lock.write(b'\0')
        lock.flush()
    lock.seek(0)
    try:
        if os.name == 'nt':
            import msvcrt
            msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        lock.close()
        raise SystemExit('Another validation session is running. No additional Minecraft process was launched.')
    info = folder / 'validation-session.json'
    info.write_text(json.dumps({'pid': os.getpid(), 'purpose': purpose}), encoding='utf8')

    def release():
        try:
            info.unlink(missing_ok=True)
        finally:
            lock.close()
    atexit.register(release)
    return lock
