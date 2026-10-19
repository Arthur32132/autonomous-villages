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
    /** One floor cell per dark bedroom: the darkest under a roof within a few blocks of the bed, nearest to it on a tie. */
    static List<BlockPos> darkRooms(Village v,int limit){
        var out=new ArrayList<BlockPos>();
        for(BlockPos bed:v.index.beds){
            if(out.size()>=limit)break;
            if(!v.level.hasChunkAt(bed)||!v.state.contains(bed))continue;
            BlockPos best=null;int bestLight=0;
            for(BlockPos q:BlockPos.betweenClosed(bed.offset(-3,-1,-3),bed.offset(3,1,3))){
                if(!Construction.clear(v.level.getBlockState(q))||!Construction.clear(v.level.getBlockState(q.above()))||v.level.canSeeSky(q))continue;
                int light=v.level.getBrightness(LightLayer.BLOCK,q);
                if(best!=null&&(light>bestLight||light==bestLight&&q.distSqr(bed)>=best.distSqr(bed))||!needsLight(v,q)||unreachable(v,q))continue;
                best=q.immutable();bestLight=light;
            }
            if(best!=null&&!out.contains(best))out.add(best);
        }
        return out;
    }
    static boolean indoors(Village v,BlockPos p){return !v.level.canSeeSky(p);}
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
