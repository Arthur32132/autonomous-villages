"""An isolated real 26.2 server. No production worlds, settings, mods or accounts are used."""
from pathlib import Path
import importlib.util, shutil, os, subprocess, time, json, hashlib, sys, xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('nav_validation',ROOT.parent/'MinecraftmodNavigationAi/tools/regression/run-validation.py')
nav=importlib.util.module_from_spec(spec);spec.loader.exec_module(nav)
out=ROOT/'build/validation'/time.strftime('%Y%m%d-%H%M%S');out.mkdir(parents=True)
lab=out/'server';lab.mkdir();template=ROOT.parent/'navigation-fixes-validation/server'
for folder in ['libraries','versions','.fabric/server']:shutil.copytree(template/folder,lab/folder)
launcher=next(template.glob('fabric-server-*-loader.0.19.5-*.jar'));shutil.copy2(launcher,lab)
shutil.copy2(template/'eula.txt',lab/'eula.txt')
mods=lab/'mods';mods.mkdir()
for jar in [next((template/'mods').glob('fabric-api-*.jar')),ROOT.parent/'MinecraftmodNavigationAi/build/libs/navigation-ai-0.1.1.jar',ROOT/'build/libs/autonomous-villages-0.1.1.jar',ROOT/'build/libs/autonomous-villages-validation-0.1.1.jar']:
 shutil.copy2(jar,mods/jar.name)
(lab/'server.properties').write_text('server-ip=127.0.0.1\nserver-port=25589\nonline-mode=false\nlevel-name=validation-world\nlevel-seed=8675309\nlevel-type=minecraft:normal\ndifficulty=peaceful\nspawn-protection=0\nview-distance=3\nsimulation-distance=3\npause-when-empty-seconds=0\nenable-rcon=false\nmax-tick-time=120000\n',encoding='utf8')
home=nav.java_home(None);binary=home/'bin/java.exe'
# Commands are supplied by a separate probe JAR which is never in the distribution.
report={'success':False,'output':str(out),'jars':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in mods.glob('*.jar')}}
server=None
try:
 server=nav.Server(binary,lab,lab/launcher.name,out/'server.log')
 server.wait(r'Done \([\d.]+s\)!',300)
 server.command('avtest')
 line,_=server.wait(r'VILLAGE_TEST (DONE|FAIL)',900);print(line,flush=True)
 if 'FAIL' in line:raise RuntimeError(line)
 server.close();server=None
 properties=lab/'server.properties'
 properties.write_text(properties.read_text(encoding='utf8').replace('pause-when-empty-seconds=0','pause-when-empty-seconds=1'),encoding='utf8')
 server=nav.Server(binary,lab,lab/launcher.name,out/'restart.log')
 server.wait(r'Done \([\d.]+s\)!',300);server.command('avtest restart')
 line,_=server.wait(r'VILLAGE_TEST (RESTART_DONE|FAIL)',180);print(line,flush=True)
 if 'FAIL' in line:raise RuntimeError(line)
 server.close();server=None
 damaged=lab/'validation-world/autonomous_villages.json'
 bad_bytes=b'{"version":1,"villages":[CORRUPTED_TEST_DATA'
 damaged.write_bytes(bad_bytes)
 server=nav.Server(binary,lab,lab/launcher.name,out/'corrupt-state.log')
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
 report['sourceSha256']={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for folder in ['src','tools/validation'] for p in (ROOT/folder).rglob('*') if p.is_file() and p.suffix in ['.java','.json','.py']}
 (out/'summary.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf8')
 print('REPORT '+str(out/'summary.json'),flush=True)
sys.exit(0 if report['success'] else 1)
