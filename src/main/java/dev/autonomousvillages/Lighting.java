package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.Heightmap;

/** Finds dark walkable ground near homes; monsters spawn only where block light is zero. */
final class Lighting {
    private Lighting(){}
    static boolean needsLight(Village v,BlockPos p){
        if(!v.level.hasChunkAt(p)||!v.state.contains(p)||v.town.protects(p)||!v.state.policy.allows("build",p))return false;
        var below=v.level.getBlockState(p.below());
        if(!Construction.clear(v.level.getBlockState(p))||!v.level.getBlockState(p).getFluidState().isEmpty()||!below.isFaceSturdy(v.level,p.below(),Direction.UP)
                ||below.is(net.minecraft.world.level.block.Blocks.FARMLAND)||below.is(net.minecraft.world.level.block.Blocks.DIRT_PATH))return false;
        return v.level.getBrightness(LightLayer.BLOCK,p)<5;
    }
    /** Grid samples around beds and buildings, nearest to the meeting point first. */
    static List<BlockPos> darkSpots(Village v,int limit){
        var anchors=new ArrayList<BlockPos>(v.index.beds);anchors.add(v.meetingPoint());
        for(var b:v.state.buildings)if(!b.kind.equals("native_house"))anchors.add(BlockPos.of(b.origin));
        var seen=new HashSet<Long>();var out=new ArrayList<BlockPos>();BlockPos hub=v.meetingPoint();
        for(var anchor:anchors)for(int dx=-18;dx<=18;dx+=6)for(int dz=-18;dz<=18;dz+=6){
            int x=Math.floorDiv(anchor.getX()+dx,6)*6+3,z=Math.floorDiv(anchor.getZ()+dz,6)*6+3;
            if(!seen.add(BlockPos.asLong(x,0,z))||!v.level.hasChunkAt(new BlockPos(x,v.state.y,z)))continue;
            var p=new BlockPos(x,v.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z),z);
            if(needsLight(v,p))out.add(p);
        }
        out.sort(Comparator.comparingDouble(p->p.distSqr(hub)));
        return out.size()>limit?out.subList(0,limit):out;
    }
}
