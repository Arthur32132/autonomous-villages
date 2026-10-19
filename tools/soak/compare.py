#!/usr/bin/env python3
"""Markdown table comparing soak batches run by seed and difficulty.

    python3 tools/soak/compare.py --base build/soak-reports/baseline-0.2.2-tps100 \\
        --candidate build/soak-reports/v03-rc2n build/soak-reports/v03-rc2p
"""
import argparse
import json
from pathlib import Path


def runs(folders):
    out = {}
    for folder in folders:
        for result in Path(folder).glob('*/soak-result.json'):
            data = json.loads(result.read_text())
            out[result.parent.name] = data
    return out


def cell(r):
    if not r:
        return '—'
    if not r.get('completed'):
        return 'не завершён'
    o, s, e = r.get('outcome', {}), r.get('start', {}), r.get('end', {})
    parts = [f"{s.get('villagers')}→{e.get('villagers')}"]
    if o.get('births'):
        parts.append(f"рожд. {o['births']}")
    parts.append(f"смертей {o.get('deaths', 0)}")
    if o.get('newBeds'):
        parts.append(f"кроватей +{o['newBeds']}")
    parts.append(f"блоков {o.get('builtBlocks', 0)}")
    return ', '.join(parts)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--base', nargs='+', required=True)
    parser.add_argument('--candidate', nargs='+', required=True)
    parser.add_argument('--base-name', default='0.2.2')
    parser.add_argument('--candidate-name', default='v0.3')
    args = parser.parse_args()
    base, cand = runs(args.base), runs(args.candidate)
    labels = sorted(set(base) | set(cand), key=lambda l: (l.split('-')[1], l))
    print(f'| прогон | {args.candidate_name} | {args.base_name} |')
    print('|---|---|---|')
    totals = {name: {'births': 0, 'deaths': 0, 'built': 0} for name in ('c', 'b')}
    for label in labels:
        print(f'| {label} | {cell(cand.get(label))} | {cell(base.get(label))} |')
        for key, source in (('c', cand), ('b', base)):
            o = source.get(label, {}).get('outcome', {})
            totals[key]['births'] += o.get('births', 0)
            totals[key]['deaths'] += o.get('deaths', 0)
            totals[key]['built'] += o.get('builtBlocks', 0)
    c, b = totals['c'], totals['b']
    print(f"| **итого** | рожд. {c['births']}, смертей {c['deaths']}, блоков {c['built']} | "
          f"рожд. {b['births']}, смертей {b['deaths']}, блоков {b['built']} |")


if __name__ == '__main__':
    main()
