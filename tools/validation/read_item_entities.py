"""Read item positions from a stopped disposable world's entity regions; never writes saves."""
from pathlib import Path
import gzip,io,json,struct,sys,zlib

def number(stream,fmt):return struct.unpack('>'+fmt,stream.read(struct.calcsize('>'+fmt)))[0]
def string(stream):return stream.read(number(stream,'H')).decode('utf8')
def payload(stream,kind):
    if kind in (1,2,3,4,5,6):return number(stream,{1:'b',2:'h',3:'i',4:'q',5:'f',6:'d'}[kind])
    if kind==7:return stream.read(number(stream,'i'))
    if kind==8:return string(stream)
    if kind==9:
        child=number(stream,'B');return [payload(stream,child) for _ in range(number(stream,'i'))]
    if kind==10:
        result={}
        while (child:=number(stream,'B'))!=0:
            key=string(stream);result[key]=payload(stream,child)
        return result
    if kind in (11,12):return [number(stream,'i' if kind==11 else 'q') for _ in range(number(stream,'i'))]
    raise ValueError('Unexpected NBT tag '+str(kind))

world=Path(sys.argv[1]);wanted=sys.argv[2] if len(sys.argv)>2 else None;found=[]
directory=world/'dimensions/minecraft/overworld/entities'
if not directory.is_dir():directory=world/'entities'
if not directory.is_dir():raise FileNotFoundError('No entity region directory under '+str(world))
for region in sorted(directory.glob('*.mca')):
    with region.open('rb') as file:
        locations=file.read(4096)
        for slot in range(1024):
            sector=int.from_bytes(locations[slot*4:slot*4+3],'big')
            if not sector:continue
            file.seek(sector*4096);length=number(file,'I');kind=number(file,'B');data=file.read(length-1)
            if kind==1:data=gzip.decompress(data)
            elif kind==2:data=zlib.decompress(data)
            elif kind!=3:raise ValueError('Unsupported region compression '+str(kind))
            stream=io.BytesIO(data);root=number(stream,'B');string(stream);chunk=payload(stream,root)
            for entity in chunk.get('Entities',[]):
                if entity.get('id')=='minecraft:item':
                    stack=entity.get('Item',entity.get('item',{}))
                    if wanted is None or stack.get('id')==wanted:found.append({'region':region.name,'item':stack,'pos':entity.get('Pos'),'age':entity.get('Age')})
print(json.dumps(found,ensure_ascii=False,indent=2))
