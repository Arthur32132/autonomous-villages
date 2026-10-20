#!/usr/bin/env python3
"""Markdown table comparing soak batches run by seed and difficulty, and a verdict table "not worse than base".

    python3 tools/soak/compare.py --base build/soak-reports/baseline-master \\
        --candidate build/soak-reports/final-wave3 --fail-if-worse [--tolerance deaths=0 builtBlocks=5%]

The total covers only runs completed in both batches (repeats averaged over their completed runs);
a missing or failed run is named under the table and never counts as zero. The verdict compares the totals
of deaths, births, builtBlocks and newBeds within tolerances. Exit code 1 when no run is comparable,
2 with --fail-if-worse when a verdict metric is worse, else 0.
"""
import argparse
import json
import math
import re
import sys
from pathlib import Path

KEYS = ('villagers', 'births', 'deaths', 'built')
# Verdict metrics: outcome key, label, +1 when more is worse (deaths), -1 when less is worse.
GATE = (('deaths', 'смерти', 1), ('births', 'рождения', -1), ('builtBlocks', 'построено блоков', -1), ('newBeds', 'новых кроватей', -1))
# Allowed slack per metric: (absolute, percent of the base total); the larger one, percent rounded up.
TOLERANCE = {'deaths': (1, 20), 'births': (1, 20), 'builtBlocks': (0, 10), 'newBeds': (0, 0)}
# Shown beside the verdict, never part of it: averages over the comparable runs that have them.
INFO = (('longestStallMinutes', 'самый долгий простой, мин'), ('longestDayStallMinutes', 'самый долгий дневной простой, мин'),
        ('changeShare', 'доля строк с изменениями'), ('routeWaitShare', 'доля ожидания маршрута'))


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


def number(x):
    return x if isinstance(x, (int, float)) and not isinstance(x, bool) else None


def parse_tolerance(text):
    """'deaths=0', 'builtBlocks=5%', 'births=max(1,20%)' -> ('deaths', (absolute, percent)); ValueError when unreadable."""
    key, sep, value = text.partition('=')
    key, value = key.strip(), value.replace(' ', '')
    if not sep or key not in TOLERANCE:
        raise ValueError(f"{text!r}: expected key=value with key one of {', '.join(TOLERANCE)}")
    inner = re.fullmatch(r'max\((.*)\)', value)
    absolute, percent = 0.0, 0.0
    for part in (inner.group(1) if inner else value).split(','):
        match = re.fullmatch(r'(\d+(?:\.\d+)?)(%?)', part)
        if not match:
            raise ValueError(f'{text!r}: tolerance is a number, a percent or max(number,percent%)')
        if match.group(2):
            percent = float(match.group(1))
        else:
            absolute = float(match.group(1))
    return key, (absolute, percent)


def allowance(base, tolerance):
    """Slack for a base total: max(absolute, percent of |base| rounded up)."""
    absolute, percent = tolerance
    return max(absolute, math.ceil(round(percent * abs(base) / 100, 9)))


def describe(tolerance, direction):
    absolute, percent = tolerance
    sign = '+' if direction > 0 else '−'
    a, p = f'{absolute:g}', f'{percent:g} %'
    return sign + (f'max({a}, {p})' if absolute and percent else p if percent else a)


def side_total(source, labels, key):
    """Sum over labels of the mean over completed runs; None when a label has no completed run with the key."""
    total = 0.0
    for label in labels:
        found = [number((r.get('outcome') or {}).get(key)) for r in source.get(label, []) if values(r) is not None]
        found = [v for v in found if v is not None]
        if not found:
            return None
        total += sum(found) / len(found)
    return total


def side_mean(source, labels, key):
    found = [number((r.get('outcome') or {}).get(key)) for label in labels for r in source.get(label, []) if values(r) is not None]
    found = [v for v in found if v is not None]
    return sum(found) / len(found) if found else None


def show(x):
    return '–' if x is None else f'{round(x, 2):g}'


def verdict(base, cand, common, tolerances=None, base_name='0.2.2', cand_name='v0.3'):
    """Markdown verdict over the runs completed on both sides and the metrics that are worse than the tolerance allows."""
    tolerances = dict(TOLERANCE, **(tolerances or {}))
    lines = ['', f'| метрика | {cand_name} | {base_name} | допуск | вердикт |', '|---|---|---|---|---|']
    worse = []
    for key, label, direction in GATE:
        c, b = side_total(cand, common, key), side_total(base, common, key)
        if c is None or b is None:
            lines.append(f'| {label} (`{key}`) | {show(c)} | {show(b)} | {describe(tolerances[key], direction)} | нет данных |')
            continue
        limit = b + direction * allowance(b, tolerances[key])
        bad = c > limit if direction > 0 else c < limit
        if bad:
            worse.append(key)
        bound = f"{'≤' if direction > 0 else '≥'} {show(limit)}"
        lines.append(f"| {label} (`{key}`) | {show(c)} | {show(b)} | {bound} ({describe(tolerances[key], direction)}) | {'ХУЖЕ' if bad else 'OK'} |")
    for key, label in INFO:
        lines.append(f'| {label} (`{key}`), среднее | {show(side_mean(cand, common, key))} | {show(side_mean(base, common, key))} | – | справочно |')
    lines.append('')
    lines.append(f"**Вердикт: хуже базы** ({', '.join(worse)})." if worse else '**Вердикт: не хуже базы.**')
    lines.append('Допуски от суммы базы по общим завершённым прогонам: '
                 + ', '.join(f'{key} {describe(tolerances[key], direction)}' for key, _, direction in GATE)
                 + ' (`--tolerance key=value`).')
    return lines, worse


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--base', nargs='+', required=True)
    parser.add_argument('--candidate', nargs='+', required=True)
    parser.add_argument('--base-name', default='0.2.2')
    parser.add_argument('--candidate-name', default='v0.3')
    parser.add_argument('--fail-if-worse', action='store_true', help='exit 2 when a verdict metric is worse than its tolerance')
    parser.add_argument('--tolerance', nargs='+', action='extend', default=[], metavar='KEY=VALUE',
                        help=f"override slack: {', '.join(TOLERANCE)}; N, P%% or max(N,P%%)")
    args = parser.parse_args(argv)
    try:
        tolerances = dict(map(parse_tolerance, args.tolerance))
    except ValueError as error:
        parser.error(str(error))
    base, cand = runs(args.base), runs(args.candidate)
    lines, c = table(base, cand, args.base_name, args.candidate_name)
    print('\n'.join(lines))
    if not c['common']:
        sys.exit('compare.py: no run completed in both batches, nothing to compare')
    lines, worse = verdict(base, cand, c['common'], tolerances, args.base_name, args.candidate_name)
    print('\n'.join(lines))
    if worse and args.fail_if_worse:
        print(f"compare.py: worse than base: {', '.join(worse)}", file=sys.stderr)
        sys.exit(2)


if __name__ == '__main__':
    main()
