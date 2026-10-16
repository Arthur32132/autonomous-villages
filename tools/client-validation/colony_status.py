"""Compact read-only status from a disposable graphical lab, including its final result."""
from pathlib import Path
import json,sys

root=Path(__file__).resolve().parents[2]
if len(sys.argv)>1:
    lab=Path(sys.argv[1])
else:
    labs=sorted(p for p in (root/'build/client-validation').glob('*') if p.is_dir())
    reports=sorted(p for p in (root/'build/validation-reports').glob('client-*') if p.is_dir())
    if not labs and not reports:raise SystemExit('No active lab or saved client report.')
    lab=(labs or reports)[-1]
if not lab.is_dir():raise SystemExit('Lab no longer exists. Use a saved report from build/validation-reports.')
profile=lab/'profile' if (lab/'profile').is_dir() else lab
print('LAB',profile)
result=profile/'client-result.json'
if result.exists():
    d=json.loads(result.read_text(encoding='utf8'))
    print('RESULT',{k:d.get(k) for k in ('success','reason','ticks')})
live=profile/'colony-live.json'
if live.exists():
    d=json.loads(live.read_text(encoding='utf8'));e=d['evidence']
    print('TICKS',d['ticks'],'RESIDENTS',len(d['actors']))
    print('PROJECTS',[(b['kind'],b['built'],b['total']) for b in d['projects']])
    print('EVIDENCE',{k:e.get(k) for k in ('repairTicks','peakWool','completedNewHouses','observedNightTicks','observedSleepTicks','clockTime','lostOriginalResidents')})
    print('EXPEDITION',d['state'].get('expedition'))
    for a in d['actors']:
        print('ACTOR',a['uuid'][:8],a['job'],a['pos'],'health',a['health'],'air',a['air'],a['nav'],a['target'],a['failure'])
        if any('shears' in s for s in a['bag']):print('SHEARER BAG',a['bag'],'SHEEP',a['nearbySheep'])
log=profile/'logs/latest.log'
if log.exists():print('LOG TAIL',log.read_text(encoding='utf8')[-350:])
elif (profile/'latest-tail.log.gz').is_file():
    import gzip
    print('LOG TAIL',gzip.decompress((profile/'latest-tail.log.gz').read_bytes()).decode('utf8',errors='replace')[-350:])
