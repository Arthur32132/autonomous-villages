"""Remove only disposable labs, after archiving bounded compact evidence."""
import argparse
from pathlib import Path
from test_artifacts import cleanup_labs, size_bytes, KINDS
from test_session import acquire_test_session

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--apply', action='store_true', help='Archive results and remove generated lab copies')
args = parser.parse_args()
session = acquire_test_session(root, 'validation artifact cleanup')
if args.apply:
    count, freed = cleanup_labs(root)
    print(f'Removed {count} labs; freed {freed:,} bytes ({freed / 1024**3:.2f} GiB).')
else:
    for kind, folder in KINDS.items():
        print(f'{kind}: {size_bytes(root / "build" / folder):,} bytes')
    print('Dry run. Use --apply to archive results and delete disposable labs.')
