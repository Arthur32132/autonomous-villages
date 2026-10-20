#!/usr/bin/env python3
"""Compact view of one soak timeline: population, store, construction and what workers were doing.

    python3 tools/soak/digest.py build/soak-reports/<batch>/<seed>-<difficulty> [--every 2] [--statuses 4]
"""
import argparse
import json
import sys
from collections import Counter
from pathlib import Path

NAV_KEYS = ['snapshots', 'submitted', 'searches', 'rejected', 'cancelled', 'backpressure', 'nodeBudget', 'replans']


def explain(folder):
    """What a folder without a usable timeline holds: a failed run's reason, or the runs of a batch folder."""
    if (folder / 'soak-result.json').exists():
        try:
            r = json.loads((folder / 'soak-result.json').read_text(encoding='utf-8'))
            return f"soak-result.json: completed={r.get('completed')}, reason={r.get('reason')}."
        except (ValueError, AttributeError):
            return 'soak-result.json is unreadable.'
    runs = sorted(p.parent.name for p in folder.glob('*/soak-timeline.jsonl'))
    if runs:
        return 'This is a batch folder; pass one run: ' + ', '.join(runs)
    return 'No soak-result.json either: the run failed before the probe started (see server-tail.log.gz, events.log).'


def load(folder):
    """Timeline samples; a missing or empty timeline stops with a message instead of a traceback."""
    path = Path(folder)
    if not path.exists():
        raise SystemExit(f'{path}: no such report folder or timeline file')
    if path.is_dir():
        path = path / 'soak-timeline.jsonl'
    if not path.is_file():
        raise SystemExit(f'{path}: no timeline. {explain(path.parent)}')
    rows, bad = [], 0
    for line in path.read_text(encoding='utf-8', errors='replace').splitlines():
        if not line.strip():
            continue
        try:
            row = json.loads(line)
        except ValueError:
            row = None
        if isinstance(row, dict):
            rows.append(row)
        else:
            bad += 1
    if bad:
        print(f'warning: {path}: {bad} unreadable line(s) skipped (a killed run can leave a cut last line)', file=sys.stderr)
    if not rows:
        raise SystemExit(f'{path}: timeline has no samples (the probe writes one every 1200 ticks of the run). {explain(path.parent)}')
    return rows


def get(d, key):
    """A field for printing: '-' when absent or null, so a partial sample never breaks the table."""
    value = d.get(key) if isinstance(d, dict) else None
    return '-' if value is None else value


def counts(d):
    return {k: n for k, n in d.items() if isinstance(n, (int, float))} if isinstance(d, dict) else {}


def digest(rows, every=1, statuses=3, workers=False):
    """Printed lines for the timeline samples."""
    every, out = max(1, every), []
    failures, seen = Counter(), Counter()
    out.append(f"{'day':>5} {'vill':>4} {'bab':>3} {'b/d':>5} {'beds':>4} {'idle':>4}  {'plk':>4} {'log':>4} {'cob':>4} {'brd':>4} {'wht':>4} {'trc':>4}  {'project':>9}  statuses")
    for i, r in enumerate(rows):
        mod = r.get('mod') if isinstance(r.get('mod'), dict) else {}
        stock = mod.get('stock') if isinstance(mod.get('stock'), dict) else {}
        failures.update(counts(mod.get('lastFailures')))
        seen.update(counts(mod.get('statuses')))
        if i % every and i != len(rows) - 1:
            continue
        top = sorted(counts(mod.get('statuses')).items(), key=lambda kv: -kv[1])[:statuses]
        text = '; '.join(f'{n}×{s}' for s, n in top)
        out.append(f"{get(r, 'day'):>5} {get(r, 'villagers'):>4} {get(r, 'babies'):>3} {str(get(r, 'births'))+'/'+str(get(r, 'deaths')):>5} {get(r, 'beds'):>4} {get(r, 'idle'):>4}  "
                   f"{get(stock, 'planks'):>4} {get(stock, 'logs'):>4} {get(stock, 'cobble'):>4} {get(stock, 'bread'):>4} "
                   f"{get(stock, 'wheat'):>4} {get(stock, 'torches'):>4}  {get(mod, 'projectBlocks'):>9}  {text}")
        if workers:
            for w in mod.get('workers') or []:
                if isinstance(w, dict):
                    out.append(f"        {get(w, 'name'):<9} {get(w, 'role'):<10} {get(w, 'job'):<8} {get(w, 'age'):>5}t nav={get(w, 'nav'):<9} "
                               f"at {get(w, 'pos'):<16} -> {get(w, 'target'):<16} {get(w, 'status')}")
    last = rows[-1].get('mod') if isinstance(rows[-1].get('mod'), dict) else {}
    a, b = rows[0].get('nav'), rows[-1].get('nav')
    if isinstance(a, dict) and isinstance(b, dict):
        first, final = rows[0].get('tick'), rows[-1].get('tick')
        span = max(1, final - first) if isinstance(first, (int, float)) and isinstance(final, (int, float)) else 1
        keys = [k for k in NAV_KEYS if isinstance(a.get(k), (int, float)) and isinstance(b.get(k), (int, float))]
        out.append('\nnavigation per 1000 ticks: ' + ', '.join(f"{k} {(b[k]-a[k])*1000/span:.1f}" for k in keys))
        out.append(f"  avg snapshot {get(b, 'snapshotMs')} ms, search {get(b, 'searchMs')} ms, bot tick {get(b, 'botTickMs')} ms, queue {get(b, 'queued')}/{get(b, 'active')}")
    out.append(f"\nstage: {last.get('stage')} | growth: {last.get('growth')} | missing: {last.get('missing')}")
    out.append(f"chronicle: {last.get('chronicle')}")
    out.append('\nworker-samples by status:')
    out += [f'  {n:>5}  {s}' for s, n in seen.most_common(15)]
    out.append('\njob failures since start (kind: reason):')
    out += [f'  {n:>5}  {s}' for s, n in sorted(counts(last.get('failures')).items(), key=lambda kv: -kv[1])[:15]]
    out.append('\nlast failures (worker-samples):')
    out += [f'  {n:>5}  {s}' for s, n in failures.most_common(12)]
    return out


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('report', help='report folder or timeline file')
    parser.add_argument('--every', type=int, default=1, help='print every Nth sample')
    parser.add_argument('--statuses', type=int, default=3, help='top worker statuses per sample')
    parser.add_argument('--workers', action='store_true', help='print every worker of every printed sample')
    args = parser.parse_args(argv)
    print('\n'.join(digest(load(args.report), args.every, args.statuses, args.workers)))


if __name__ == '__main__':
    main()
