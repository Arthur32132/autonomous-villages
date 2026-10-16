"""An isolated real 26.2 server. No production worlds, settings, mods or accounts are used."""
from pathlib import Path
import importlib.util, shutil, os, subprocess, time, json, hashlib, sys, argparse, xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[2]
parser=argparse.ArgumentParser()
parser.add_argument('--safety-only',action='store_true',help='Only real ledge/corner/surfacing physics fixtures; not full release acceptance')
parser.add_argument('--longrun-only',action='store_true',help='Only the regressions for observed long-run failures; not full release acceptance')
parser.add_argument('--night-only',action='store_true',help='Only actual home entry and door closure, without a long generated-world soak')
parser.add_argument('--keep-lab',action='store_true',help='Retain one <=1 GiB diagnostic lab until the next run; otherwise delete copied server/world on exit')
args=parser.parse_args()
if sum((args.safety_only,args.longrun_only,args.night_only))>1:parser.error('Choose only one focused scenario')
sys.path.insert(0,str(ROOT/'tools'))
from test_session import acquire_test_session
validation_session=acquire_test_session(ROOT,'dedicated server')
from test_artifacts import ValidationRun
spec=importlib.util.spec_from_file_location('nav_validation',ROOT.parent/'MinecraftmodNavigationAi/tools/regression/run-validation.py')
nav=importlib.util.module_from_spec(spec);spec.loader.exec_module(nav)
artifacts=ValidationRun(ROOT,'server',args.keep_lab)
out=artifacts.output
lab=out/'server';lab.mkdir();template=ROOT.parent/'navigation-fixes-validation/server'
artifacts.check_copy_budget([template/folder for folder in ['libraries','versions','.fabric/server']]+list((template/'mods').glob('fabric-api-*.jar')))
for folder in ['libraries','versions','.fabric/server']:shutil.copytree(template/folder,lab/folder)
launcher=next(template.glob('fabric-server-*-loader.0.19.5-*.jar'));shutil.copy2(launcher,lab)
shutil.copy2(template/'eula.txt',lab/'eula.txt')
mods=lab/'mods';mods.mkdir()
for jar in [next((template/'mods').glob('fabric-api-*.jar')),ROOT.parent/'MinecraftmodNavigationAi/build/libs/navigation-ai-0.2.1.jar',ROOT/'build/libs/autonomous-villages-0.2.1.jar',ROOT/'build/libs/autonomous-villages-validation-0.2.1.jar']:
 shutil.copy2(jar,mods/jar.name)
(lab/'server.properties').write_text('server-ip=127.0.0.1\nserver-port=25589\nonline-mode=false\nlevel-name=validation-world\nlevel-seed=8675309\nlevel-type=minecraft:normal\ndifficulty=peaceful\nspawn-protection=0\nview-distance=3\nsimulation-distance=3\npause-when-empty-seconds=0\nenable-rcon=false\nmax-tick-time=120000\n',encoding='utf8')
home=nav.java_home(None);binary=home/'bin/java.exe'
# Commands are supplied by a separate probe JAR which is never in the distribution.
report={'success':False,'scope':'safety_only' if args.safety_only else 'longrun_only' if args.longrun_only else 'night_only' if args.night_only else 'full','output':str(out),'jars':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in mods.glob('*.jar')}}
report['sourceSha256']={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for folder in ['src','tools/validation'] for p in (ROOT/folder).rglob('*') if p.is_file() and p.suffix in ['.java','.json','.py']}
server=None
try:
 artifacts.start_monitor()
 server=nav.Server(binary,lab,lab/launcher.name,out/'server.log')
 artifacts.register_process(server.process)
 server.wait(r'Done \([\d.]+s\)!',300)
 server.command('avtest safety' if args.safety_only else 'avtest longrun' if args.longrun_only else 'avtest night' if args.night_only else 'avtest')
 line,_=server.wait(r'VILLAGE_TEST (DONE|FAIL)',900);print(line,flush=True)
 if 'FAIL' in line:raise RuntimeError(line)
 server.close();server=None
 if not args.safety_only and not args.longrun_only and not args.night_only:
  legacy=json.loads((lab/'validation-world/validation-legacy-mine.json').read_text(encoding='utf8'))
  metadata=lab/'validation-world/autonomous_villages.json'
  snapshot=json.loads(metadata.read_text(encoding='utf8'))
  old=next(v for v in snapshot['villages'] if v['name']=='Test')
  old.update(mineEntrance=legacy['entrance'],mineDirection=legacy['direction'],mineLength=legacy['length'])
  metadata.write_text(json.dumps(snapshot,ensure_ascii=False),encoding='utf8')
  properties=lab/'server.properties'
  properties.write_text(properties.read_text(encoding='utf8').replace('pause-when-empty-seconds=0','pause-when-empty-seconds=1'),encoding='utf8')
  server=nav.Server(binary,lab,lab/launcher.name,out/'restart.log')
  artifacts.register_process(server.process)
  server.wait(r'Done \([\d.]+s\)!',300);server.command('avtest restart')
  line,_=server.wait(r'VILLAGE_TEST (RESTART_DONE|FAIL)',180);print(line,flush=True)
  if 'FAIL' in line:raise RuntimeError(line)
  server.close();server=None
  damaged=lab/'validation-world/autonomous_villages.json'
  bad_bytes=b'{"version":1,"villages":[CORRUPTED_TEST_DATA'
  damaged.write_bytes(bad_bytes)
  server=nav.Server(binary,lab,lab/launcher.name,out/'corrupt-state.log')
  artifacts.register_process(server.process)
  server.wait(r'Done \([\d.]+s\)!',300)
  server.command('avillage status')
  server.wait(r'Ошибка загрузки autonomous_villages.json',30)
  server.close();server=None
  if damaged.read_bytes()!=bad_bytes:raise RuntimeError('Corrupt metadata was overwritten')
  report['corruptStatePreserved']=True
 report['success']=True
except Exception as e:
 report['error']=str(e);print(str(e),flush=True)
finally:
 if server:server.close()
 report['checks']=[line.strip() for f in ['server.log','restart.log'] if (out/f).exists() for line in (out/f).read_text(encoding='utf8').splitlines() if 'VILLAGE_TEST PASS' in line]
 tests=[ET.parse(p).getroot() for p in (ROOT/'build/test-results/test').glob('TEST-*.xml')]
 report['junit']={k:sum(int(t.get(k,'0')) for t in tests) for k in ['tests','failures','errors']}
 if artifacts.budget_error:report.update(success=False,error=artifacts.budget_error)
 (out/'summary.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf8')
 artifacts.finish()
sys.exit(0 if report['success'] else 1)
