#!/usr/bin/env python3
"""Markdown table comparing soak batches run by seed and difficulty.

    python3 tools/soak/compare.py --base build/soak-reports/baseline-0.2.2-tps100 \\
        --candidate build/soak-reports/v03-rc2n build/soak-reports/v03-rc2p
"""
import argparse
import json
from pathlib import Path


def runs(folders):
    """Results by run label; a label run more than once (a repeated batch) keeps every result."""
    out = {}
    for folder in folders:
        for result in Path(folder).glob('*/soak-result.json'):
            data = json.loads(result.read_text())
            out.setdefault(result.parent.name, []).append(data)
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
    # Repeated runs of a label are averaged, so totals stay comparable with a single batch.
    totals = {name: {'births': 0.0, 'deaths': 0.0, 'built': 0.0, 'villagers': 0.0} for name in ('c', 'b')}
    for label in labels:
        cells = [' / '.join(cell(r) for r in source.get(label, [])) or '—' for source in (cand, base)]
        print(f'| {label} | {cells[0]} | {cells[1]} |')
        for key, source in (('c', cand), ('b', base)):
            results = source.get(label, [])
            for r in results:
                o = r.get('outcome', {})
                totals[key]['births'] += o.get('births', 0) / len(results)
                totals[key]['deaths'] += o.get('deaths', 0) / len(results)
                totals[key]['built'] += o.get('builtBlocks', 0) / len(results)
                totals[key]['villagers'] += (r.get('end', {}).get('villagers') or 0) / len(results)

    def total(t):
        return (f"жителей {t['villagers']:g}, рожд. {t['births']:g}, смертей {t['deaths']:g}, "
                f"блоков {t['built']:g}")
    repeated = any(len(v) > 1 for v in list(cand.values()) + list(base.values()))
    print(f"| **итого{' (среднее повторов)' if repeated else ''}** | {total(totals['c'])} | {total(totals['b'])} |")


if __name__ == '__main__':
    main()
