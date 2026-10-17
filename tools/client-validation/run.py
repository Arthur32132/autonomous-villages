"""Launch installed Minecraft/Fabric binaries in a disposable client profile, with no account credentials.
Copies mods only; never modifies the original profile, settings, saves or launcher.
"""
from pathlib import Path
import argparse,gzip,hashlib,json,os,re,shutil,subprocess,time,sys

ROOT=Path(__file__).resolve().parents[2]
parser=argparse.ArgumentParser()
parser.add_argument('--profile',type=Path,default=Path(os.environ['APPDATA'])/'ModrinthApp/profiles/Fabulously Optimized (3)')
parser.add_argument('--colony',action='store_true')
parser.add_argument('--benchmark-tps',action='store_true',help='Measure tick throughput in the normal colony scene; not autonomy acceptance')
parser.add_argument('--benchmark-rates',default='40,100,200,400,800,1600,4000,10000,0,40',help='Comma-separated TPS targets; 0 means sprint')
parser.add_argument('--benchmark-seconds',type=int,default=20,help='Wall-clock sample seconds per rate, from 5 to 30')
parser.add_argument('--tick-rate',type=int,default=400,help='Target simulation TPS for the ordinary colony run; use 40 for comparison')
parser.add_argument('--residents',type=int,default=0,help='Artificial adult minimum in disposable colony; no items supplied')
parser.add_argument('--growth',action='store_true',help='Also require a completed new house with real beds')
parser.add_argument('--daylight',action='store_true',help='Isolate construction/production in daylight; native night/sleep is tested separately')
parser.add_argument('--live-threats',action='store_true',help='Normal difficulty and a flying creative observer so natural monster spawning remains active')
parser.add_argument('--world',type=Path)
parser.add_argument('--max-ticks',type=int,default=48000,help='Bound colony observation; writes separate 48000/96000-tick checkpoints for longer runs')
parser.add_argument('--keep-lab',action='store_true',help='Retain one <=1 GiB diagnostic lab until the next run; otherwise delete the copied profile on exit')
args=parser.parse_args()
try:
    benchmark_rates=[int(rate) for rate in args.benchmark_rates.split(',')]
    if not 1<=len(benchmark_rates)<=12 or any(rate<0 or rate>10000 for rate in benchmark_rates):raise ValueError()
except ValueError:parser.error('--benchmark-rates requires 1 to 12 integers from 0 to 10000')
if not 5<=args.benchmark_seconds<=30:parser.error('--benchmark-seconds must be between 5 and 30')
if not 0<=args.residents<=32:parser.error('--residents must be between 0 and 32')
if args.residents and not args.colony:parser.error('--residents requires --colony')
if not 1<=args.tick_rate<=10000:parser.error('--tick-rate must be between 1 and 10000')
if args.benchmark_tps and not args.colony:parser.error('--benchmark-tps requires --colony')
if not 4800<=args.max_ticks<=144000:parser.error('--max-ticks must be between 4800 and 144000')
sys.path.insert(0,str(ROOT/'tools'))
from validation_timing import colony_timeout_seconds
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
    f'-Davillage.test.tickRate={args.tick_rate}',f'-Davillage.test.wallTimeout={660 if args.benchmark_tps else colony_timeout_seconds(args.max_ticks,args.tick_rate)-30}',f'-Davillage.test.maxTicks={args.max_ticks}',f'-Davillage.test.benchmarkRates={args.benchmark_rates}',f'-Davillage.test.benchmarkSeconds={args.benchmark_seconds}',f'-Davillage.test.benchmarkTps={str(args.benchmark_tps).lower()}',
    f'-Davillage.test.residents={args.residents}',f'-Davillage.test.colony={str(args.colony).lower()}',f'-Davillage.test.growth={str(args.growth).lower()}',f'-Davillage.test.liveThreats={str(args.live_threats).lower()}',f'-Davillage.test.daylight={str(args.daylight).lower()}',f'-Davillage.test.copiedWorld={str(args.world is not None).lower()}',
    f'-Djava.library.path={natives}/java',f'-Djna.tmpdir={natives}/jna',f'-Dorg.lwjgl.system.SharedLibraryExtractPath={natives}/lwjgl',
    '-cp',os.pathsep.join(classpath),data['mainClass'],'--username','VillageProbe','--uuid','00000000000000000000000000000001',
    '--accessToken','0','--version','26.2','--versionType','release','--gameDir',str(lab),'--assetsDir',str(meta/'assets'),
    '--assetIndex',data['assetIndex']['id'],'--width','960','--height','540']
report={'success':False,'scope':'tick_speed_benchmark' if args.benchmark_tps else 'colony' if args.colony else 'client_smoke','originalProfile':str(args.profile),'lab':str(lab),'maxObservationTicks':args.max_ticks,'requestedTps':args.tick_rate,
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
        deadline=time.monotonic()+(720 if args.benchmark_tps else colony_timeout_seconds(args.max_ticks,args.tick_rate) if args.colony else 360)
        while process.poll() is None and time.monotonic()<deadline:
            if artifacts.budget_error:raise RuntimeError(artifacts.budget_error)
            if timeline is not None and time.monotonic()>=next_snapshot and timeline.tell()<32*1024**2:
                next_snapshot=time.monotonic()+.5
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
