package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LightLayer;
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
    /**
     * One floor cell per dark room people use: the darkest under a roof within a few blocks of a bed,
     * a workstation or a shared chest, nearest to it on a tie. A zombie that spawned in an unlit smithy
     * stays there all day and kills whoever comes for the chest (rc10, seed 1337).
     */
    static List<BlockPos> darkRooms(Village v,int limit){
        var anchors=new ArrayList<BlockPos>(v.index.beds);
        var b=v.state.bounds;
        if(b!=null){
            BlockPos center=new BlockPos((b.minX+b.maxX)/2,v.state.y,(b.minZ+b.maxZ)/2);
            int r=Math.max(b.maxX-b.minX,b.maxZ-b.minZ)/2+1;
            v.level.getPoiManager().getInSquare(h->h.is(net.minecraft.tags.PoiTypeTags.ACQUIRABLE_JOB_SITE),center,r,net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.ANY)
                    .map(net.minecraft.world.entity.ai.village.poi.PoiRecord::getPos).filter(v.state::contains).forEach(anchors::add);
        }
        for(long chest:v.state.storage)anchors.add(BlockPos.of(chest));
        var out=new ArrayList<BlockPos>();
        for(BlockPos bed:anchors){
            if(out.size()>=limit)break;
            if(!v.level.hasChunkAt(bed)||!v.state.contains(bed))continue;
            BlockPos best=null;int bestLight=0;
            for(BlockPos q:BlockPos.betweenClosed(bed.offset(-3,-1,-3),bed.offset(3,1,3))){
                if(!Construction.clear(v.level.getBlockState(q))||!Construction.clear(v.level.getBlockState(q.above()))||v.level.canSeeSky(q)||besideBed(v,q))continue;
                int light=v.level.getBrightness(LightLayer.BLOCK,q);
                if(best!=null&&(light>bestLight||light==bestLight&&q.distSqr(bed)>=best.distSqr(bed))||!needsLight(v,q)||unreachable(v,q))continue;
                best=q.immutable();bestLight=light;
            }
            if(best!=null&&!out.contains(best))out.add(best);
        }
        return out;
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
    /** Grid samples around beds and buildings, nearest to the meeting point first. */
    static List<BlockPos> darkSpots(Village v,int limit){
        var anchors=new ArrayList<BlockPos>(v.index.beds);anchors.add(v.meetingPoint());
        for(var b:v.state.buildings)if(!b.kind.equals("native_house"))anchors.add(BlockPos.of(b.origin));
        var seen=new HashSet<Long>();var out=new ArrayList<BlockPos>();BlockPos hub=v.meetingPoint();
        for(var anchor:anchors)for(int dx=-18;dx<=18;dx+=6)for(int dz=-18;dz<=18;dz+=6){
            int x=Math.floorDiv(anchor.getX()+dx,6)*6+3,z=Math.floorDiv(anchor.getZ()+dz,6)*6+3;
            if(!seen.add(BlockPos.asLong(x,0,z))||!v.level.hasChunkAt(new BlockPos(x,v.state.y,z)))continue;
            var p=new BlockPos(x,v.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z),z);
            if(needsLight(v,p)&&!unreachable(v,p))out.add(p);
        }
        out.sort(Comparator.comparingDouble(p->p.distSqr(hub)));
        return out.size()>limit?out.subList(0,limit):out;
    }
}
