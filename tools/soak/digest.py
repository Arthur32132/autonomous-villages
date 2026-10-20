#!/usr/bin/env python3
"""Compact view of one soak timeline: population, store, construction, what workers were doing,
how often the village visibly changed and how long each kind of work waited for routes.

    python3 tools/soak/digest.py build/soak-reports/<batch>/<seed>-<difficulty> [--every 2] [--statuses 4]
"""
import argparse
import json
import sys
from collections import Counter
from pathlib import Path

NAV_KEYS = ['snapshots', 'submitted', 'searches', 'rejected', 'cancelled', 'backpressure', 'nodeBudget', 'replans']
# Chronicle totals that show in the world; their sum is progress.visible (same list as SoakProbe.VISIBLE and the README table).
VISIBLE = ('placed', 'trees', 'mined', 'harvested', 'torches', 'paths', 'lamps', 'lanterns', 'flowers', 'nameplates', 'graves',
           'spareBeds', 'hazards')
PROGRESS = ('projectBlocks', 'projectsCompleted', 'beds', 'births', 'visible')
# Navigator status -> route bucket, as SoakProbe.routeBucket: COMPUTING waits for a route, RUNNING walks, STUCK is stuck, the rest works.
ROUTE = ('route', 'walk', 'stuck', 'work')
ROUTE_OF = {'COMPUTING': 'route', 'RUNNING': 'walk', 'STUCK': 'stuck'}
# One sample every 1200 ticks = one game hour = one real minute at 20 TPS.
TICKS_PER_MINUTE = 1200
STALL_MINUTES = 5


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


def number(x):
    return x if isinstance(x, (int, float)) and not isinstance(x, bool) else None


def result_of(report):
    """soak-result.json next to the timeline, or None: optional extras (start beds, features) for the digest."""
    path = Path(report)
    path = path / 'soak-result.json' if path.is_dir() else path.parent / 'soak-result.json'
    try:
        data = json.loads(path.read_text(encoding='utf-8'))
    except (OSError, ValueError):
        return None
    return data if isinstance(data, dict) else None


def progress(row):
    """Visible-progress numbers of a sample: the probe's `progress`, else the same formulas over the older keys (None = unknown)."""
    if isinstance(row.get('progress'), dict):
        return {k: number(row['progress'].get(k)) for k in PROGRESS}
    mod = row.get('mod') if isinstance(row.get('mod'), dict) else {}
    built, _, planned = str(mod.get('projectBlocks', '')).partition('/')
    chronicle = mod.get('chronicle')
    visible = sum(n for k, n in counts(chronicle).items() if k in VISIBLE and number(n) is not None) if isinstance(chronicle, dict) else None
    return {'projectBlocks': int(built) if built.strip().isdigit() and planned else None, 'projectsCompleted': number(mod.get('projectsCompleted')),
            'beds': number(row.get('beds')), 'births': number(row.get('births')), 'visible': visible}


def cadence(rows, start=None):
    """How often the village visibly changed: a sample changed when one of its progress numbers differs from the last known one.

    The probe's own `changed` wins when present; an unknown number (null, unclaimed sample) keeps the last known value.
    The run starts from zero projects, births and chronicle; start beds come from soak-result.json when given.
    Stalls are runs of samples without change, in real minutes at 20 TPS (sample tick spacing / 1200). The day* keys
    repeat this over daytime samples only: a night sample (`night`) folds its change into the next daytime sample.
    """
    last = {'projectBlocks': 0, 'projectsCompleted': 0, 'births': 0, 'visible': 0, 'beds': None}
    if isinstance(start, dict):
        last.update({k: number(v) for k, v in start.items() if k in last and number(v) is not None})
    first_completed, source = last['projectsCompleted'], 'legacy'
    marks, prev_tick, first_day = [], 0, None
    for row in rows:
        row = row if isinstance(row, dict) else {}
        p, tick = progress(row), number(row.get('tick'))
        minutes = (tick - prev_tick) / TICKS_PER_MINUTE if tick is not None and prev_tick is not None and tick > prev_tick else 1.0
        prev_tick = tick
        known = {k: v for k, v in p.items() if v is not None}
        if isinstance(row.get('changed'), bool):
            change, source = row['changed'], 'probe'
        else:
            change = any(last.get(k) is not None and last[k] != v for k, v in known.items())
        last.update(known)
        day = number(row.get('day'))
        if day is None and tick is not None:
            day = round(tick / 24000, 2)
        if first_day is None and known.get('projectsCompleted', first_completed) > first_completed:
            first_day = day
        marks.append((day, minutes, change, row.get('night') is True))
    daytime, pending = [], False
    for day, minutes, change, night in marks:
        pending = pending or change
        if not night:
            daytime.append((day, minutes, pending, False))
            pending = False
    out, d = stall_summary(marks), stall_summary(daytime)
    out.update({'daySamples': d['samples'], 'dayChanged': d['changed'], 'dayChangeShare': d['changeShare'],
                'longestDayStallMinutes': d['longestStallMinutes'], 'dayStallsOver5Minutes': d['stallsOver5Minutes'],
                'dayStalls': d['stalls'], 'firstProjectDay': first_day, 'source': source})
    return out


def stall_summary(marks):
    """Changed share and stalls of (day, minutes, changed, night) marks."""
    changed, stalls, stall = 0, [], None
    for day, minutes, change, _ in marks:
        if change:
            changed += 1
            stall = None
        elif stall is None:
            stall = {'from': day, 'to': day, 'minutes': minutes}
            stalls.append(stall)
        else:
            stall['to'], stall['minutes'] = day, stall['minutes'] + minutes
    longest = max((s['minutes'] for s in stalls), default=0.0)
    return {'samples': len(marks), 'changed': changed, 'changeShare': round(changed / len(marks), 3) if marks else None,
            'longestStallMinutes': round(longest, 1), 'stallsOver5Minutes': sum(1 for s in stalls if s['minutes'] >= STALL_MINUTES),
            'stalls': [s for s in stalls if s['minutes'] >= STALL_MINUTES]}


def routes(rows):
    """Worker time by job kind and route bucket, and where it came from.

    The probe's cumulative `routeByJob` (every routeTicks) of the last sample that has it; for older timelines one count
    per worker per sample from mod.workers job/nav, bucketed the same way.
    """
    for row in reversed(rows):
        if isinstance(row, dict) and isinstance(row.get('routeByJob'), dict):
            return {kind: {k: number(b.get(k)) or 0 for k in ROUTE} for kind, b in row['routeByJob'].items() if isinstance(b, dict)}, 'probe'
    out = {}
    for row in rows:
        mod = row.get('mod') if isinstance(row, dict) and isinstance(row.get('mod'), dict) else {}
        for w in mod.get('workers') or []:
            if isinstance(w, dict) and isinstance(w.get('nav'), str):
                kind = w.get('job') if isinstance(w.get('job'), str) else 'none'
                out.setdefault(kind, dict.fromkeys(ROUTE, 0))[ROUTE_OF.get(w['nav'], 'work')] += 1
    return out, 'legacy'


def shares(buckets):
    total = sum(buckets.get(k, 0) for k in ROUTE)
    return {k: buckets.get(k, 0) / total for k in ROUTE} if total else None


def route_wait_share(table):
    """Share of all counted worker time spent waiting for a route (bucket `route`), or None without counts."""
    total = dict.fromkeys(ROUTE, 0)
    for buckets in table.values():
        for k in ROUTE:
            total[k] += buckets.get(k, 0)
    s = shares(total)
    return round(s['route'], 3) if s else None


def percent(x):
    return '-' if x is None else f'{round(100 * x)} %'


def cadence_lines(rows, start=None):
    c = cadence(rows, start)
    origin = 'по полю changed зонда' if c['source'] == 'probe' else 'по старым полям: chronicle, projectBlocks, projectsCompleted, beds, births'
    first = '-' if c['firstProjectDay'] is None else f"день {c['firstProjectDay']}"
    out = [f'\nВидимые изменения ({origin}; строка = игровой час = минута при 20 TPS):',
           f"  изменения в {c['changed']} из {c['samples']} строк ({percent(c['changeShare'])}), самый долгий простой {c['longestStallMinutes']:g} мин, "
           f"простоев ≥ {STALL_MINUTES} мин: {c['stallsOver5Minutes']}, первый завершённый проект: {first}"]
    if c['stalls']:
        out.append('  простои: ' + '; '.join(f"день {get(s, 'from')}–{get(s, 'to')} ({s['minutes']:g} мин)" for s in c['stalls'][:8]))
    out.append(f"  днём (ночные строки входят в следующую дневную): изменения в {c['dayChanged']} из {c['daySamples']} строк "
               f"({percent(c['dayChangeShare'])}), самый долгий простой {c['longestDayStallMinutes']:g} мин, "
               f"простоев ≥ {STALL_MINUTES} мин: {c['dayStallsOver5Minutes']}")
    if c['dayStalls']:
        out.append('  дневные простои: ' + '; '.join(f"день {get(s, 'from')}–{get(s, 'to')} ({s['minutes']:g} мин)" for s in c['dayStalls'][:8]))
    return out


def route_lines(rows):
    table, source = routes(rows)
    origin = 'каждые routeTicks, зонд' if source == 'probe' else 'по строкам таймлайна: житель × строка'
    out = [f'\nМаршруты по видам работ ({origin}):', f"  {'вид':<12} {'всего':>6} {'маршрут':>8} {'идёт':>6} {'застрял':>8} {'работа':>7}"]
    total = dict.fromkeys(ROUTE, 0)
    for kind, buckets in sorted(table.items(), key=lambda kv: (-sum(kv[1].values()), kv[0])):
        for k in ROUTE:
            total[k] += buckets[k]
        out.append(route_row(kind, buckets))
    if not table:
        out.append('  нет данных о жителях')
        return out
    out.append(route_row('всего', total))
    out.append(f'  ожидание маршрута: {percent(route_wait_share(table))} учтённого времени жителей')
    return out


def route_row(kind, buckets):
    s = shares(buckets) or dict.fromkeys(ROUTE)
    return f"  {kind:<12} {sum(buckets.values()):>6} {percent(s['route']):>8} {percent(s['walk']):>6} {percent(s['stuck']):>8} {percent(s['work']):>7}"


def feature_lines(result):
    """outcome.features of soak-result.json, when the run has it."""
    outcome = result.get('outcome') if isinstance(result, dict) and isinstance(result.get('outcome'), dict) else {}
    features = outcome.get('features')
    if not isinstance(features, dict) or not features:
        return []
    parts = []
    for key, f in features.items():
        f = f if isinstance(f, dict) else {}
        state = 'вкл' if f.get('on') is True else 'выкл' if f.get('on') is False else '?'
        parts.append(f"{key} {state}: записано {get(f, 'recorded')}, в мире {get(f, 'observed')}")
    return ['\nНовые механики в конце прогона: ' + '; '.join(parts)]


def digest(rows, every=1, statuses=3, workers=False, result=None):
    """Printed lines for the timeline samples; result (soak-result.json) adds start beds and the features line."""
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
    start = result.get('start') if isinstance(result, dict) and isinstance(result.get('start'), dict) else None
    out += cadence_lines(rows, start) + route_lines(rows) + feature_lines(result)
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
    print('\n'.join(digest(load(args.report), args.every, args.statuses, args.workers, result_of(args.report))))


if __name__ == '__main__':
    main()
