package dev.autonomousvillages;

import dev.navigationai.api.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.*;

/** Interrupt work and swim to actual breathable space; vanilla alone restores the air supply. */
final class BreathingSafety {
    private static final NavigationPolicy SURFACE=new NavigationPolicy(false,false,true,true,32_000,1024,20,200,0);
    private final Worker resident;
    private final Set<BlockPos> rejected=new HashSet<>();
    private NavigationTask task;
    private BlockPos target;
    private boolean active;
    BreathingSafety(Worker resident){this.resident=resident;}
    boolean tick(){
        var body=resident.body();var bot=resident.bot();
        boolean submerged=body.isEyeInFluid(FluidTags.WATER);
        if(!submerged){
            if(!active)return false;
            // A dry eye can be reached while the body is still deep under the chosen surface.
            // Finish that ascent before handing control back to a long-distance home route.
            if(task!=null&&!task.completion().isDone()&&target!=null&&body.getY()<target.getY()-.4)return true;
            if(body.getAirSupply()<280){if(task!=null){bot.stopAll();task=null;}bot.actions().stop();resident.status="отдышивается у поверхности";return true;}
            active=false;task=null;target=null;rejected.clear();resident.cancel();
            // Washed out of the village: walk back before taking new work.
            if(!resident.v.state.contains(body.blockPosition()))resident.start(new HomeJob(resident));
            return true;
        }
        if(!active&&body.getAirSupply()>=260)return false;
        if(!active){resident.cancel();active=true;}
        resident.status="поднимается к воздуху";
        if(task!=null&&!task.completion().isDone())return true;
        if(task!=null&&task.status()!=NavigationStatus.SUCCESS&&target!=null)rejected.add(target);
        target=findAir();task=null;
        if(target!=null){
            task=bot.navigator().goTo(new NavigationGoal.PositionGoal(Vec3.atBottomCenterOf(target),.3),SURFACE);
        }else bot.actions().setMovement(0,0,true,false,false);
        return true;
    }
    private BlockPos findAir(){
        var body=resident.body();var level=resident.v.level;BlockPos start=body.blockPosition(),best=null;double score=Double.POSITIVE_INFINITY;
        for(BlockPos p:BlockPos.betweenClosed(start.offset(-12,0,-12),start.offset(12,12,12))){
            if(rejected.contains(p)||!level.hasChunkAt(p)||!level.hasChunkAt(p.above()))continue;
            var feet=level.getBlockState(p);var head=level.getBlockState(p.above());var below=level.getBlockState(p.below());
            if(VillageIndex.hazard(feet)||VillageIndex.hazard(head)||!head.getFluidState().isEmpty())continue;
            if(!feet.getFluidState().isEmpty()&&!feet.getFluidState().is(FluidTags.WATER))continue;
            if(!feet.getFluidState().is(FluidTags.WATER)&&!below.getFluidState().is(FluidTags.WATER)&&below.getCollisionShape(level,p.below()).isEmpty())continue;
            Vec3 point=Vec3.atBottomCenterOf(p);
            if(!level.noBlockCollision(body,body.getDimensions(body.getPose()).makeBoundingBox(point)))continue;
            var hit=level.clip(new net.minecraft.world.level.ClipContext(body.getEyePosition(),point.add(0,1.62,0),net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,body));
            double candidate=body.position().distanceToSqr(point)+(hit.getType()==HitResult.Type.MISS?0:1000)+(feet.getFluidState().is(FluidTags.WATER)?20:0);
            if(candidate<score){score=candidate;best=p.immutable();}
        }
        return best;
    }
}
