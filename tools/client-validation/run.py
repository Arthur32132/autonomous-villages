"""Launch installed Minecraft/Fabric binaries in a disposable client profile, with no account credentials.
Copies mods only; never modifies the original profile, settings, saves or launcher.
"""
from pathlib import Path
import argparse,ctypes,hashlib,json,os,re,shutil,subprocess,time

ROOT=Path(__file__).resolve().parents[2]
parser=argparse.ArgumentParser()
parser.add_argument('--profile',type=Path,default=Path(os.environ['APPDATA'])/'ModrinthApp/profiles/Fabulously Optimized (3)')
args=parser.parse_args()
meta=Path(os.environ['APPDATA'])/'ModrinthApp/meta'
version='26.2-0.19.5'
data=json.loads((meta/f'versions/{version}/{version}.json').read_text(encoding='utf8'))
out=ROOT/'build/client-validation'/time.strftime('%Y%m%d-%H%M%S');out.mkdir(parents=True)
lab=out/'profile';(lab/'mods').mkdir(parents=True)
for src in (args.profile/'mods').glob('*.jar'):
    if not src.name.startswith(('autonomous-villages-','navigation-ai-')):shutil.copy2(src,lab/'mods'/src.name)
for src in [ROOT/'build/libs/autonomous-villages-0.1.1.jar',ROOT.parent/'MinecraftmodNavigationAi/build/libs/navigation-ai-0.1.1.jar',ROOT/'build/libs/autonomous-villages-client-validation-0.1.1.jar']:
    shutil.copy2(src,lab/'mods'/src.name)
(lab/'options.txt').write_text('onboardAccessibility:true\njoinedFirstServer:true\ntutorialStep:none\npauseOnLostFocus:false\nfullscreen:false\noverrideWidth:960\noverrideHeight:540\nrenderDistance:4\nsimulationDistance:4\nmaxFps:30\nsoundCategory_master:0.0\n',encoding='utf8')

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
    f'-Djava.library.path={natives}/java',f'-Djna.tmpdir={natives}/jna',f'-Dorg.lwjgl.system.SharedLibraryExtractPath={natives}/lwjgl',
    '-cp',os.pathsep.join(classpath),data['mainClass'],'--username','VillageProbe','--uuid','00000000000000000000000000000001',
    '--accessToken','0','--version','26.2','--versionType','release','--gameDir',str(lab),'--assetsDir',str(meta/'assets'),
    '--assetIndex',data['assetIndex']['id'],'--width','960','--height','540']
report={'success':False,'originalProfile':str(args.profile),'lab':str(lab),
        'jars':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in (lab/'mods').glob('*.jar')}}
process=None
try:
    with (out/'console.txt').open('w',encoding='utf8') as log:
        process=subprocess.Popen(command,cwd=lab,stdout=log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
        print(f'CLIENT_PID {process.pid}\nLAB {lab}',flush=True)
        deadline=time.monotonic()+360;captured=False
        while process.poll() is None and time.monotonic()<deadline:
            time.sleep(.5)
            text=(out/'console.txt').read_text(encoding='utf8',errors='replace')
            if 'CLIENT_TEST READY_SCREENSHOT' in text and not captured:
                captured=True
                handles=[]
                callback=ctypes.WINFUNCTYPE(ctypes.c_bool,ctypes.c_void_p,ctypes.c_void_p)
                def visit(hwnd,_):
                    owner=ctypes.c_ulong();ctypes.windll.user32.GetWindowThreadProcessId(hwnd,ctypes.byref(owner))
                    if owner.value==process.pid and ctypes.windll.user32.IsWindowVisible(hwnd):handles.append(hwnd)
                    return True
                ctypes.windll.user32.EnumWindows(callback(visit),0)
                if handles:
                    capture=subprocess.run([str(Path.home()/'.local/bin/um.exe'),'win','shot',str(out/'client.png'),'--hwnd',str(handles[0]),'--scale','0.5'],capture_output=True,text=True,timeout=25)
                    report['screenshotExitCode']=capture.returncode
                    (out/'capture.txt').write_text(capture.stdout+capture.stderr,encoding='utf8')
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
    (out/'summary.json').write_text(json.dumps(report,indent=2),encoding='utf8')
    print('REPORT '+str(out/'summary.json'),flush=True)
raise SystemExit(0 if report['success'] else 1)
