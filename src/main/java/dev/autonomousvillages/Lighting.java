package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Finds dark walkable ground near homes; monsters spawn only where block light is zero. That holds
 * indoors too: an unlit room spawns a zombie right by the sleepers, so the rooms with beds come first.
 */
final class Lighting {
    private Lighting(){}
    static boolean needsLight(Village v,BlockPos p){
        if(!v.level.hasChunkAt(p)||!v.state.contains(p)||v.town.protects(p)||!v.state.policy.allows("build",p))return false;
        var below=v.level.getBlockState(p.below());
        if(!Construction.clear(v.level.getBlockState(p))||!v.level.getBlockState(p).getFluidState().isEmpty()||!below.isFaceSturdy(v.level,p.below(),Direction.UP)
                ||below.is(net.minecraft.world.level.block.Blocks.FARMLAND)||below.is(net.minecraft.world.level.block.Blocks.DIRT_PATH))return false;
        return v.level.getBrightness(LightLayer.BLOCK,p)<5;
    }
    /** An anchor whose room needed no torch is looked at again only after two minutes. */
    static final long ROOM_FINE_TICKS=2400;
    /**
     * One floor cell per dark room people use: the darkest under a roof within a few blocks of a bed,
     * a workstation or a shared chest, nearest to it on a tie. A zombie that spawned in an unlit smithy
     * stays there all day and kills whoever comes for the chest (rc10, seed 1337). Only a few anchors
     * per call, in turn: a lit village rescanned every room block by block every ten seconds.
     */
    static List<BlockPos> darkRooms(Village v,RoomScan scan,int anchorsPerCall){
        long now=v.level.getGameTime();
        if(scan.stale(now))scan.anchors(roomAnchors(v),now);
        var out=new ArrayList<BlockPos>();
        for(BlockPos bed:scan.next(anchorsPerCall,now)){
            if(!v.level.hasChunkAt(bed)||!v.state.contains(bed))continue;
            BlockPos best=null;int bestLight=0;
            for(BlockPos q:BlockPos.betweenClosed(bed.offset(-3,-1,-3),bed.offset(3,1,3))){
                if(!Construction.clear(v.level.getBlockState(q))||!Construction.clear(v.level.getBlockState(q.above()))||v.level.canSeeSky(q)||besideBed(v,q))continue;
                int light=v.level.getBrightness(LightLayer.BLOCK,q);
                if(best!=null&&(light>bestLight||light==bestLight&&q.distSqr(bed)>=best.distSqr(bed))||!needsLight(v,q)||unreachable(v,q))continue;
                best=q.immutable();bestLight=light;
            }
            if(best==null)scan.fine(bed,now+ROOM_FINE_TICKS);
            else if(!out.contains(best))out.add(best);
        }
        return out;
    }
    private static List<BlockPos> roomAnchors(Village v){
        var anchors=new ArrayList<BlockPos>(v.index.beds);
        var b=v.state.bounds;
        if(b!=null){
            BlockPos center=new BlockPos((b.minX+b.maxX)/2,v.state.y,(b.minZ+b.maxZ)/2);
            int r=Math.max(b.maxX-b.minX,b.maxZ-b.minZ)/2+1;
            v.level.getPoiManager().getInSquare(h->h.is(net.minecraft.tags.PoiTypeTags.ACQUIRABLE_JOB_SITE),center,r,net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.ANY)
                    .map(net.minecraft.world.entity.ai.village.poi.PoiRecord::getPos).filter(v.state::contains).forEach(anchors::add);
        }
        for(long chest:v.state.storage)anchors.add(BlockPos.of(chest));
        return anchors;
    }
    /** Room anchors taken a few per call in turn; one found lit is skipped until its mark runs out. */
    static final class RoomScan{
        private List<BlockPos> anchors=List.of();
        private long built=Village.LONG_AGO;
        private int cursor;
        private final Map<Long,Long> fineUntil=new HashMap<>();
        /** The anchor list (beds, job sites, chests) is rebuilt every ten seconds. */
        boolean stale(long now){return now-built>=200;}
        void anchors(List<BlockPos> list,long now){anchors=List.copyOf(list);built=now;}
        List<BlockPos> next(int count,long now){
            if(fineUntil.size()>256)fineUntil.values().removeIf(t->t<=now);
            var out=new ArrayList<BlockPos>();
            for(int seen=0;seen<anchors.size()&&out.size()<count;seen++){
                cursor=Math.floorMod(cursor,anchors.size());
                BlockPos a=anchors.get(cursor++);
                if(fineUntil.getOrDefault(a.asLong(),Long.MIN_VALUE)<=now)out.add(a);
            }
            return out;
        }
        void fine(BlockPos anchor,long until){fineUntil.put(anchor.asLong(),until);}
    }
    /**
     * The ground beside every door, on both sides: residents come home there at dusk, and a porch roof
     * keeps it dark while the heightmap grid only sees the roof (seed 8675309: deaths at one doorstep
     * under an awning in nearly every version). The torch goes beside the step, not on it.
     */
    static List<BlockPos> darkDoorsteps(Village v,int limit){
        var out=new ArrayList<BlockPos>();
        for(BlockPos door:v.town.doors()){
            if(out.size()>=limit)break;
            var state=v.level.getBlockState(door);
            if(!v.level.hasChunkAt(door)||!(state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock))continue;
            Direction facing=state.getValue(net.minecraft.world.level.block.DoorBlock.FACING);
            for(Direction side:new Direction[]{facing,facing.getOpposite()}){
                BlockPos step=door.relative(side);
                for(Direction d:new Direction[]{side.getClockWise(),side.getCounterClockWise()}){
                    BlockPos p=step.relative(d);
                    if(needsLight(v,p)&&!unreachable(v,p)&&!besideBed(v,p)&&!out.contains(p)){out.add(p);break;}
                }
            }
        }
        return out.size()>limit?out.subList(0,limit):out;
    }
    static boolean indoors(Village v,BlockPos p){return !v.level.canSeeSky(p);}
    /** No lamp post right by a door: a fence there narrows the way in. */
    static boolean byDoor(Village v,BlockPos p){
        for(BlockPos q:BlockPos.betweenClosed(p.offset(-1,0,-1),p.offset(1,1,1)))
            if(v.level.getBlockState(q).getBlock() instanceof net.minecraft.world.level.block.DoorBlock)return true;
        return false;
    }
    /** The floor beside a bed stays free for a second bed and for whoever gets up. */
    private static boolean besideBed(Village v,BlockPos p){
        for(var d:Direction.Plane.HORIZONTAL)if(v.level.getBlockState(p.relative(d)).getBlock() instanceof net.minecraft.world.level.block.BedBlock)return true;
        return false;
    }
    /** A spot nobody could walk to lately (a locked room, a roof). */
    static boolean unreachable(Village v,BlockPos p){return v.rejected.getOrDefault(p.asLong(),0L)>v.level.getGameTime();}
    /** Grid samples around beds and buildings, nearest to the meeting point first: open ground only, never a roof or a path. */
    static List<BlockPos> darkSpots(Village v,int limit){
        var anchors=new ArrayList<BlockPos>(v.index.beds);anchors.add(v.meetingPoint());
        for(var b:v.state.buildings)if(!b.kind.equals("native_house"))anchors.add(BlockPos.of(b.origin));
        var seen=new HashSet<Long>();var out=new ArrayList<BlockPos>();BlockPos hub=v.meetingPoint();var cells=cells(v);
        for(var anchor:anchors)for(int dx=-18;dx<=18;dx+=6)for(int dz=-18;dz<=18;dz+=6){
            int x=Math.floorDiv(anchor.getX()+dx,6)*6+3,z=Math.floorDiv(anchor.getZ()+dz,6)*6+3;
            if(!seen.add(BlockPos.asLong(x,0,z))||!v.level.hasChunkAt(new BlockPos(x,v.state.y,z)))continue;
            var p=groundNear(cells,x,z);
            if(p!=null&&needsLight(v,p)&&!unreachable(v,p))out.add(p);
        }
        out.sort(Comparator.comparingDouble(p->p.distSqr(hub)));
        return out.size()>limit?out.subList(0,limit):out;
    }
    /**
     * Outdoor lights the village keeps up: one per grid cell of its footprint and a few per resident for
     * rooms and doorsteps, so a growing village does not stop lighting at a fixed count (it did at 160).
     */
    static int lightCap(long area,int residents){return (int)Math.clamp(area/36+4L*residents,160L,960L);}
    static int lightCap(Village v){
        var b=v.state.bounds;long side=2L*v.state.radius+1;
        return lightCap(b!=null?((long)b.maxX-b.minX+1)*((long)b.maxZ-b.minZ+1):side*side,v.state.residents.size());
    }
    /** The block facts the placement rules need, so the rules can be checked without a level. */
    interface Cells{
        /** The first open cell of a column, or Integer.MIN_VALUE where the chunk is not loaded. */
        int top(int x,int z);
        boolean solid(BlockPos p);
        boolean ground(BlockPos p);
        boolean path(BlockPos p);
        boolean planned(BlockPos p);
        /** The facing of a door there, or null. */
        Direction door(BlockPos p);
    }
    static Cells cells(Village v){
        var paths=new HashSet<Long>();for(var b:v.state.buildings)paths.addAll(b.path);
        return new Cells(){
            @Override public int top(int x,int z){return v.level.hasChunkAt(new BlockPos(x,v.state.y,z))?v.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z):Integer.MIN_VALUE;}
            @Override public boolean solid(BlockPos p){return !v.level.hasChunkAt(p)||!v.level.getBlockState(p).getCollisionShape(v.level,p).isEmpty();}
            @Override public boolean ground(BlockPos p){
                if(!v.level.hasChunkAt(p))return false;
                var s=v.level.getBlockState(p);
                return VillageIndex.naturalSoil(s)||s.is(BlockTags.SAND)||s.is(Blocks.GRAVEL)||s.is(Blocks.SNOW_BLOCK)||s.is(Blocks.DIRT_PATH)||s.is(BlockTags.BASE_STONE_OVERWORLD);
            }
            @Override public boolean path(BlockPos p){return paths.contains(p.asLong())||v.level.hasChunkAt(p)&&v.level.getBlockState(p).is(Blocks.DIRT_PATH);}
            @Override public boolean planned(BlockPos p){return v.town.protects(p);}
            @Override public Direction door(BlockPos p){
                if(!v.level.hasChunkAt(p))return null;
                var s=v.level.getBlockState(p);
                return s.getBlock() instanceof DoorBlock?s.getValue(DoorBlock.FACING):null;
            }
        };
    }
    private static final int[][] NEAR={{0,0},{1,0},{-1,0},{0,1},{0,-1}};
    /** The grid cell, or the first open ground beside it when the cell is a roof, a wall top or a path. */
    static BlockPos groundNear(Cells c,int x,int z){
        for(int[] o:NEAR){
            int y=c.top(x+o[0],z+o[1]);
            if(y==Integer.MIN_VALUE)continue;
            var p=new BlockPos(x+o[0],y,z+o[1]);
            if(groundSample(c,p))return p;
        }
        return null;
    }
    /**
     * Open natural ground for a grid light: the heightmap also stops on flat roofs, and torches and lamp
     * posts went up there. A roof is part of a plan or has a room under it; real ground has ground below.
     */
    static boolean groundSample(Cells c,BlockPos p){
        BlockPos g=p.below();
        return c.ground(g)&&!c.path(g)&&!c.planned(g)&&!c.planned(p)&&c.solid(g.below());
    }
    /** A lamp post would be in the way: on a path, by a door or two cells straight out from it, or in a one-block gap. */
    static boolean postInTheWay(Cells c,BlockPos spot){
        if(c.path(spot.below()))return true;
        for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)for(int dy=-1;dy<=1;dy++){
            var facing=c.door(spot.offset(dx,dy,dz));
            if(facing==null)continue;
            if(Math.abs(dx)<=1&&Math.abs(dz)<=1)return true;
            if(facing.getAxis()==Direction.Axis.X?dz==0&&Math.abs(dx)==2:dx==0&&Math.abs(dz)==2)return true;
        }
        for(var d:new Direction[]{Direction.EAST,Direction.SOUTH})
            if(blocked(c,spot.relative(d))&&blocked(c,spot.relative(d.getOpposite())))return true;
        return false;
    }
    static boolean postInTheWay(Village v,BlockPos spot){return postInTheWay(cells(v),spot);}
    private static boolean blocked(Cells c,BlockPos p){return c.solid(p)||c.solid(p.above());}
}
