#!/usr/bin/env python3
"""Compact view of one soak timeline: population, store, construction and what workers were doing.

    python3 tools/soak/digest.py build/soak-reports/<batch>/<seed>-<difficulty> [--every 2] [--statuses 4]
"""
import argparse
import json
from collections import Counter
from pathlib import Path


def load(folder):
    path = Path(folder)
    if path.is_dir():
        path = path / 'soak-timeline.jsonl'
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('report', help='report folder or timeline file')
    parser.add_argument('--every', type=int, default=1, help='print every Nth sample')
    parser.add_argument('--statuses', type=int, default=3, help='top worker statuses per sample')
    parser.add_argument('--workers', action='store_true', help='print every worker of every printed sample')
    args = parser.parse_args()
    rows = load(args.report)
    failures, statuses = Counter(), Counter()
    print(f"{'day':>5} {'vill':>4} {'bab':>3} {'b/d':>5} {'beds':>4} {'idle':>4}  {'plk':>4} {'log':>4} {'cob':>4} {'brd':>4} {'wht':>4} {'trc':>4}  {'project':>9}  statuses")
    for i, r in enumerate(rows):
        mod = r.get('mod', {})
        stock = mod.get('stock', {})
        for k, n in mod.get('lastFailures', {}).items():
            failures[k] += n
        for k, n in mod.get('statuses', {}).items():
            statuses[k] += n
        if i % args.every and i != len(rows) - 1:
            continue
        top = sorted(mod.get('statuses', {}).items(), key=lambda kv: -kv[1])[:args.statuses]
        text = '; '.join(f'{n}×{s}' for s, n in top)
        print(f"{r['day']:>5} {r['villagers']:>4} {r['babies']:>3} {str(r['births'])+'/'+str(r['deaths']):>5} {r['beds']:>4} {r['idle']:>4}  "
              f"{stock.get('planks','-'):>4} {stock.get('logs','-'):>4} {stock.get('cobble','-'):>4} {stock.get('bread','-'):>4} "
              f"{stock.get('wheat','-'):>4} {stock.get('torches','-'):>4}  {mod.get('projectBlocks','-'):>9}  {text}")
        if args.workers:
            for w in mod.get('workers', []):
                print(f"        {w['name']:<9} {w['role']:<10} {w['job']:<8} {w['age']:>5}t nav={w['nav']:<9} at {w['pos']:<16} -> {w.get('target','-'):<16} {w['status']}")
    last = rows[-1].get('mod', {}) if rows else {}
    if rows and 'nav' in rows[-1]:
        a, b = rows[0]['nav'], rows[-1]['nav']
        span = max(1, rows[-1]['tick'] - rows[0]['tick'])
        print('\nnavigation per 1000 ticks: ' + ', '.join(f"{k} {(b[k]-a[k])*1000/span:.1f}" for k in
              ['snapshots', 'submitted', 'searches', 'rejected', 'cancelled', 'backpressure', 'nodeBudget', 'replans']))
        print(f"  avg snapshot {b['snapshotMs']} ms, search {b['searchMs']} ms, bot tick {b['botTickMs']} ms, queue {b['queued']}/{b['active']}")
    print('\nstage:', last.get('stage'), '| growth:', last.get('growth'), '| missing:', last.get('missing'))
    print('chronicle:', last.get('chronicle'))
    print('\nworker-samples by status:')
    for s, n in statuses.most_common(15):
        print(f'  {n:>5}  {s}')
    print('\njob failures since start (kind: reason):')
    for s, n in sorted(last.get('failures', {}).items(), key=lambda kv: -kv[1])[:15]:
        print(f'  {n:>5}  {s}')
    print('\nlast failures (worker-samples):')
    for s, n in failures.most_common(12):
        print(f'  {n:>5}  {s}')


if __name__ == '__main__':
    main()
