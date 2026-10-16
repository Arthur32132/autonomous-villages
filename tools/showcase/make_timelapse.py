"""Assemble explicitly selected native screenshots before a disposable lab is cleaned."""
from pathlib import Path
import argparse, json, subprocess

root=Path(__file__).resolve().parents[2]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--frames',type=Path,required=True,help='Screenshot folder in a retained lab or compact evidence report; historical lab copies are not kept')
parser.add_argument('--title',default='Строительство склада · ускоренная запись')
parser.add_argument('--frame-duration',type=float,default=.08)
args=parser.parse_args()
if args.frame_duration<=0:parser.error('--frame-duration must be positive')
frames=sorted(args.frames.glob('*.png'))
if not frames:parser.error('No PNG frames. Supply an existing screenshot folder with --frames; old disposable labs have been cleaned.')
out=root/'build/showcase';out.mkdir(parents=True,exist_ok=True)
last=frames[-1]
manifest={'source':'Minecraft Screenshot.grab; isolated real graphical client',
          'outputFrameDurationSeconds':args.frame_duration,
          'note':'Selected input frames; their source run report defines gameplay conditions. Silent time lapse.',
          'frames':[str(p.resolve()) for p in frames],'lastFrame':str(last.resolve())}
(out/'source-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf8')
def quoted(p):return str(p.resolve()).replace('\\','/').replace("'","'\\''")
lines=[]
for p in frames:lines.extend([f"file '{quoted(p)}'",f'duration {args.frame_duration}'])
lines.extend([f"file '{quoted(last)}'",'duration 3',f"file '{quoted(last)}'"])
(out/'frames.txt').write_text('\n'.join(lines)+'\n',encoding='utf8')
(out/'title.txt').write_text(args.title,encoding='utf8')
ffmpeg=Path.home()/'AppData/Local/universal-modder/ffmpeg/bin/ffmpeg.exe'
label="drawbox=x=0:y=ih-38:w=iw:h=38:color=black@0.55:t=fill,drawtext=fontfile='C\\:/Windows/Fonts/arial.ttf':textfile='title.txt':fontcolor=white:fontsize=20:x=14:y=h-29"
subprocess.run([str(ffmpeg),'-hide_banner','-loglevel','error','-y','-safe','0','-f','concat','-i',str(out/'frames.txt'),'-vf',label,'-r','30','-c:v','libx264','-preset','fast','-crf','20','-pix_fmt','yuv420p','-movflags','+faststart',str(out/'warehouse-timelapse.mp4')],cwd=out,check=True)
print(out/'warehouse-timelapse.mp4')
