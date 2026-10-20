#!/usr/bin/env python3
"""Markdown table comparing soak batches run by seed and difficulty.

    python3 tools/soak/compare.py --base build/soak-reports/baseline-0.2.2-tps100 \\
        --candidate build/soak-reports/v03-rc2n build/soak-reports/v03-rc2p

The total covers only runs completed in both batches (repeats averaged over their completed runs);
a missing or failed run is named under the table and never counts as zero. Exit code 1 when no run is comparable.
"""
import argparse
import json
import sys
from pathlib import Path

KEYS = ('villagers', 'births', 'deaths', 'built')


def load_json(path):
    try:
        data = json.loads(Path(path).read_text(encoding='utf-8'))
    except (OSError, ValueError) as error:
        return None, str(error)
    return (data, None) if isinstance(data, dict) else (None, 'not a JSON object')


def runs(folders):
    """Results by run label; a label run more than once (a repeated batch) keeps every result.

    A run folder without a readable soak-result.json, or a run the batch summary.json lists without one,
    is kept as a failed result so it shows up instead of vanishing.
    """
    out = {}
    for folder in map(Path, folders):
        if not folder.is_dir():
            raise SystemExit(f'{folder}: no such batch folder')
        summary = (load_json(folder / 'summary.json')[0] or {}).get('runs') if (folder / 'summary.json').exists() else None
        summary = summary if isinstance(summary, dict) else {}
        found = {}
        for run in sorted(p for p in folder.iterdir() if p.is_dir()):
            if (run / 'soak-result.json').exists():
                data, error = load_json(run / 'soak-result.json')
                found[run.name] = data if data is not None else {'completed': False, 'reason': f'unreadable soak-result.json: {error}'}
            elif run.name in summary or (run / 'server-tail.log.gz').exists():
                found[run.name] = missing(summary.get(run.name))
        for label, entry in summary.items():
            found.setdefault(label, missing(entry))
        if not found:
            raise SystemExit(f'{folder}: no soak runs (expected <seed>-<difficulty>/soak-result.json)')
        for label, data in found.items():
            out.setdefault(label, []).append(data)
    return out


def missing(entry):
    """A run without soak-result.json; the runner's summary entry gives the reason when it has one."""
    entry = entry if isinstance(entry, dict) else {}
    failed = not entry.get('completed')
    return {'completed': False, 'reason': (failed and entry.get('reason')) or 'no_result_file', 'error': failed and entry.get('error') or None}


def values(r):
    """Compared numbers of a completed run, or None when it failed or lacks one of them (never zero-filled)."""
    if not isinstance(r, dict) or not r.get('completed'):
        return None
    o, e = r.get('outcome') or {}, r.get('end') or {}
    v = {'villagers': e.get('villagers'), 'births': o.get('births'), 'deaths': o.get('deaths'), 'built': o.get('builtBlocks')}
    return v if all(isinstance(x, (int, float)) and not isinstance(x, bool) for x in v.values()) else None


def escape(text):
    return str(text).replace('|', '\\|').replace('\n', ' ')


def failure(r):
    """Why a result cannot be compared."""
    if not r.get('completed'):
        return 'не завершён: ' + escape(r.get('error') or r.get('reason') or '?')[:80]
    return 'нет итогов' + (': ' + escape(r['summaryError'])[:80] if r.get('summaryError') else '')


def cell(r):
    if not r:
        return 'нет прогона'
    if values(r) is None:
        return failure(r)
    o, s, e = r.get('outcome', {}), r.get('start') or {}, r.get('end', {})
    parts = [f"{s.get('villagers', '?')}→{e.get('villagers')}"]
    if o.get('births'):
        parts.append(f"рожд. {o['births']}")
    parts.append(f"смертей {o.get('deaths')}")
    if o.get('newBeds'):
        parts.append(f"кроватей +{o['newBeds']}")
    parts.append(f"блоков {o.get('builtBlocks')}")
    return ', '.join(parts)


def problem(results):
    """Why a side of a label stays out of the total: no run at all, or no completed run with numbers."""
    if not results:
        return 'нет прогона'
    done = [r for r in results if values(r) is not None]
    return None if done else failure(results[0])


def compare(base, cand):
    """Labels, the ones comparable on both sides, why the others are left out, and totals over the comparable ones."""
    labels = sorted(set(base) | set(cand), key=lambda l: (l.rpartition('-')[2], l))
    totals = {side: dict.fromkeys(KEYS, 0.0) for side in ('c', 'b')}
    common, excluded, partial = [], {}, []
    for label in labels:
        sides = {'c': cand.get(label, []), 'b': base.get(label, [])}
        problems = {side: problem(results) for side, results in sides.items()}
        if any(problems.values()):
            excluded[label] = {side: p for side, p in problems.items() if p}
            continue
        common.append(label)
        for side, results in sides.items():
            done = [v for v in map(values, results) if v is not None]
            if len(done) < len(results):
                partial.append((label, side))
            for key in KEYS:
                totals[side][key] += sum(v[key] for v in done) / len(done)
    return {'labels': labels, 'common': common, 'excluded': excluded, 'partial': partial, 'totals': totals}


def total(t):
    return (f"жителей {round(t['villagers'], 2):g}, рожд. {round(t['births'], 2):g}, смертей {round(t['deaths'], 2):g}, "
            f"блоков {round(t['built'], 2):g}")


def table(base, cand, base_name='0.2.2', cand_name='v0.3'):
    """Markdown lines and the comparison behind them."""
    c, names = compare(base, cand), {'c': cand_name, 'b': base_name}
    lines = [f'| прогон | {cand_name} | {base_name} |', '|---|---|---|']
    for label in c['labels']:
        cells = [' / '.join(cell(r) for r in source.get(label, [])) or 'нет прогона' for source in (cand, base)]
        lines.append(f'| {label} | {cells[0]} | {cells[1]} |')
    # Repeated runs of a label are averaged, so totals stay comparable with a single batch.
    repeated = any(len(v) > 1 for v in list(cand.values()) + list(base.values()))
    head = f"**итого{' (среднее повторов)' if repeated else ''}**, общих прогонов {len(c['common'])} из {len(c['labels'])}"
    if c['common']:
        lines.append(f"| {head} | {total(c['totals']['c'])} | {total(c['totals']['b'])} |")
    else:
        lines.append(f'| {head} | нет общих завершённых прогонов | — |')
    if c['excluded']:
        lines += ['', 'Не вошли в итог: ' + '; '.join(f'{label} ({", ".join(f"{names[s]}: {p}" for s, p in problems.items())})'
                                                     for label, problems in c['excluded'].items()) + '.']
    if c['partial']:
        lines.append('В итоге только завершённые повторы: ' + ', '.join(f'{label} ({names[s]})' for label, s in c['partial']) + '.')
    return lines, c


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--base', nargs='+', required=True)
    parser.add_argument('--candidate', nargs='+', required=True)
    parser.add_argument('--base-name', default='0.2.2')
    parser.add_argument('--candidate-name', default='v0.3')
    args = parser.parse_args(argv)
    lines, c = table(runs(args.base), runs(args.candidate), args.base_name, args.candidate_name)
    print('\n'.join(lines))
    if not c['common']:
        sys.exit('compare.py: no run completed in both batches, nothing to compare')


if __name__ == '__main__':
    main()
