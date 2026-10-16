"""Launch installed Minecraft/Fabric binaries in a disposable client profile, with no account credentials.
Copies mods only; never modifies the original profile, settings, saves or launcher.
"""
from pathlib import Path
import argparse,gzip,hashlib,json,os,re,shutil,subprocess,time,sys

ROOT=Path(__file__).resolve().parents[2]
parser=argparse.ArgumentParser()
parser.add_argument('--profile',type=Path,default=Path(os.environ['APPDATA'])/'ModrinthApp/profiles/Fabulously Optimized (3)')
parser.add_argument('--colony',action='store_true')
parser.add_argument('--growth',action='store_true',help='Also require a completed new house with real beds')
parser.add_argument('--daylight',action='store_true',help='Isolate construction/production in daylight; native night/sleep is tested separately')
parser.add_argument('--live-threats',action='store_true',help='Normal difficulty and a flying creative observer so natural monster spawning remains active')
parser.add_argument('--world',type=Path)
parser.add_argument('--max-ticks',type=int,default=48000,help='Bound colony observation; writes separate 48000/96000-tick checkpoints for longer runs')
parser.add_argument('--keep-lab',action='store_true',help='Retain one <=1 GiB diagnostic lab until the next run; otherwise delete the copied profile on exit')
args=parser.parse_args()
if not 4800<=args.max_ticks<=144000:parser.error('--max-ticks must be between 4800 and 144000')
sys.path.insert(0,str(ROOT/'tools'))
from test_session import acquire_test_session
validation_session=acquire_test_session(ROOT,'graphical client')
from test_artifacts import ValidationRun, reject_disposable_source, compact_observation
reject_disposable_source(ROOT,args.profile)
if args.world:
    if not args.colony:raise ValueError('--world requires --colony')
    reject_disposable_source(ROOT,args.world)
mod_version=re.search(r'^version=(.+)$',(ROOT/'gradle.properties').read_text(),re.M).group(1).strip()
meta=Path(os.environ['APPDATA'])/'ModrinthApp/meta'
version='26.2-0.19.5'
data=json.loads((meta/f'versions/{version}/{version}.json').read_text(encoding='utf8'))
artifacts=ValidationRun(ROOT,'client',args.keep_lab)
out=artifacts.output
lab=out/'profile';(lab/'mods').mkdir(parents=True)
sources=[src for src in (args.profile/'mods').glob('*.jar') if not src.name.startswith(('autonomous-villages-','navigation-ai-'))]
sources += [ROOT/f'build/libs/autonomous-villages-{mod_version}.jar',ROOT.parent/'MinecraftmodNavigationAi/build/libs/navigation-ai-0.2.1.jar',ROOT/f'build/libs/autonomous-villages-client-validation-{mod_version}.jar']
artifacts.check_copy_budget(sources+([args.world] if args.world else []))
for src in sources:
    shutil.copy2(src,lab/'mods'/src.name)
if args.world:
    shutil.copytree(args.world,lab/'saves/ColonySmoke')
(lab/'options.txt').write_text('onboardAccessibility:true\njoinedFirstServer:true\ntutorialStep:none\npauseOnLostFocus:false\nfullscreen:false\noverrideWidth:960\noverrideHeight:540\nrenderDistance:5\nsimulationDistance:5\nmaxFps:30\nsoundCategory_master:0.0\n',encoding='utf8')

def allowed(lib):
    rules=lib.get('rules',[])
    if not rules:return True
    result=False
    for rule in rules:
        target=rule.get('os',{})
        if target.get('name','windows')!='windows':continue
        if 'arch' in target and not re.fullmatch(target['arch'],'amd64'):continue
        if 'version' in target and not re.search(target['version'],'10.0'):continue
        if rule.get('features'):continue
        result=rule['action']=='allow'
    return result

classpath=[]
for lib in data['libraries']:
    if not allowed(lib) or not lib.get('include_in_classpath',True):continue
    artifact=lib.get('downloads',{}).get('artifact',{}).get('path')
    if not artifact:
        group,name,version_string=lib['name'].split(':')[:3]
        artifact=f'{group.replace(".","/")}/{name}/{version_string}/{name}-{version_string}.jar'
    path=meta/'libraries'/artifact
    if not path.is_file():raise FileNotFoundError(path)
    classpath.append(str(path))
classpath.append(str(meta/f'versions/{version}/{version}.jar'))
java=meta/'java_versions/zulu25.32.21-ca-jre25.0.2-win_x64/bin/java.exe'
natives=meta/'natives'/version
command=[str(java),'-Xmx4G','--sun-misc-unsafe-memory-access=allow','--enable-native-access=ALL-UNNAMED',
    f'-Davillage.test.maxTicks={args.max_ticks}',
    f'-Davillage.test.colony={str(args.colony).lower()}',f'-Davillage.test.growth={str(args.growth).lower()}',f'-Davillage.test.liveThreats={str(args.live_threats).lower()}',f'-Davillage.test.daylight={str(args.daylight).lower()}',f'-Davillage.test.copiedWorld={str(args.world is not None).lower()}',
    f'-Djava.library.path={natives}/java',f'-Djna.tmpdir={natives}/jna',f'-Dorg.lwjgl.system.SharedLibraryExtractPath={natives}/lwjgl',
    '-cp',os.pathsep.join(classpath),data['mainClass'],'--username','VillageProbe','--uuid','00000000000000000000000000000001',
    '--accessToken','0','--version','26.2','--versionType','release','--gameDir',str(lab),'--assetsDir',str(meta/'assets'),
    '--assetIndex',data['assetIndex']['id'],'--width','960','--height','540']
report={'success':False,'originalProfile':str(args.profile),'lab':str(lab),'maxObservationTicks':args.max_ticks,
        'jars':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in (lab/'mods').glob('*.jar')}}
frozen_sources={}
for prefix,project in [('villages',ROOT),('navigation',ROOT.parent/'MinecraftmodNavigationAi')]:
    for folder in ['src','tools']:
        for path in (project/folder).rglob('*'):
            if path.is_file() and path.suffix in ('.java','.json','.py'):
                frozen_sources[prefix+'/'+str(path.relative_to(project))]=(path,hashlib.sha256(path.read_bytes()).hexdigest())
    for name in ['build.gradle','settings.gradle','gradle.properties']:
        path=project/name
        frozen_sources[prefix+'/'+name]=(path,hashlib.sha256(path.read_bytes()).hexdigest())
report['sourceSha256']={name:value[1] for name,value in frozen_sources.items()}
process=None
timeline=None
next_snapshot=0
last_snapshot=-1
try:
    artifacts.start_monitor()
    if args.colony:timeline=gzip.open(out/'timeline.jsonl.gz','wb')
    with (out/'console.txt').open('w',encoding='utf8') as log:
        process=subprocess.Popen(command,cwd=lab,stdout=log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
        artifacts.register_process(process)
        print(f'CLIENT_PID {process.pid}\nLAB {lab}',flush=True)
        deadline=time.monotonic()+(min(5100,180+args.max_ticks/20) if args.colony else 360)
        while process.poll() is None and time.monotonic()<deadline:
            if artifacts.budget_error:raise RuntimeError(artifacts.budget_error)
            if timeline is not None and time.monotonic()>=next_snapshot and timeline.tell()<32*1024**2:
                next_snapshot=time.monotonic()+5
                live=lab/'colony-live.json'
                if live.is_file():
                    try:
                        snapshot=json.loads(live.read_text(encoding='utf8'))
                        if snapshot['ticks']!=last_snapshot:
                            timeline.write(json.dumps(compact_observation(snapshot),ensure_ascii=False,separators=(',',':')).encode('utf8')+b'\n');timeline.flush();last_snapshot=snapshot['ticks']
                    except (OSError,json.JSONDecodeError):pass  # Writer may be between truncate and completion.
            time.sleep(.5)
        if process.poll() is None:raise TimeoutError('Client did not finish')
        report['exitCode']=process.returncode
    result=lab/'client-result.json'
    if result.exists():report.update(json.loads(result.read_text(encoding='utf8')))
    report['success']=report['success'] and report['exitCode']==0
    if (lab/'logs/latest.log').exists():shutil.copy2(lab/'logs/latest.log',out/'client.txt')
except Exception as e:
    report['error']=str(e)
finally:
    if process is not None and process.poll() is None:
        process.terminate()
        try:process.wait(timeout=20)
        except subprocess.TimeoutExpired:process.kill();process.wait(timeout=10)
    if artifacts.budget_error:report.update(success=False,error=artifacts.budget_error)
    if timeline is not None:
        report['timelineTruncated']=timeline.tell()>=32*1024**2
        timeline.close()
    changed=[name for name,(path,expected) in frozen_sources.items() if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest()!=expected]
    report['sourcesUnchanged']=not changed
    if changed:report.update(success=False,error='Candidate sources changed during observation',changedSources=changed)
    (out/'summary.json').write_text(json.dumps(report,indent=2),encoding='utf8')
    artifacts.finish()
raise SystemExit(0 if report['success'] else 1)
