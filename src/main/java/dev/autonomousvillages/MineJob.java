package dev.autonomousvillages;

import dev.navigationai.api.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * Either breaks a short list of exposed stone/ore blocks, or extends the village staircase mine
 * with the library excavator and remembers the ore it uncovered.
 */
final class MineJob extends Job {
    private final ArrayDeque<BlockPos> targets;
    private final BlockPos entrance;
    private Approach approach;
    private Harvest harvest;
    private Walk walk;
    private ExcavationTask excavation;
    private int broken;
    MineJob(Worker w,List<BlockPos> targets){super(w,"добывает камень и руду");this.targets=new ArrayDeque<>(targets);this.entrance=null;}
    MineJob(Worker w,BlockPos entrance){super(w,"копает шахту");this.targets=new ArrayDeque<>();this.entrance=entrance;}
    @Override String kind(){return entrance==null?"quarry":"shaft";}
    @Override String activity(){return "mine";}
    @Override BlockPos target(){return entrance!=null?entrance:targets.peek();}
    @Override int timeLimit(){return entrance==null?2400:6000;}
    @Override boolean atomic(){return harvest!=null||excavation!=null&&!excavation.completion().isDone();}
    @Override boolean tick(){return entrance==null?quarry():shaft();}

    private boolean quarry(){
        if(harvest!=null){
            var state=harvest.tick();
            if(state==Harvest.State.WORKING)return true;
            harvest=null;approach=null;
            if(state==Harvest.State.DONE){broken++;v.chronicle.mined();}
            targets.poll();
        }
        while(!targets.isEmpty()&&(!VillageIndex.oreResource(v.level.getBlockState(targets.peek()))&&!v.level.getBlockState(targets.peek()).is(net.minecraft.world.level.block.Blocks.STONE)
                ||!VillageCrafting.hasPickFor(bot(),v.level.getBlockState(targets.peek()))))targets.poll();
        if(targets.isEmpty()||broken>=12)return false;
        if(approach==null)approach=new Approach(targets.peek(),4.5,true);
        var state=approach.tick();
        if(state==Approach.State.MOVING)return true;
        if(state==Approach.State.FAILED){v.rejected.put(targets.poll().asLong(),now()+1200);approach=null;return !targets.isEmpty();}
        harvest=new Harvest(targets.peek());
        return true;
    }
    private boolean shaft(){
        if(excavation==null){
            if(walk==null)walk=new Walk(v.feetPosition(entrance),2.5);
            var state=walk.tick();
            if(state==Approach.State.FAILED)return fail("не дойти до шахты");
            if(state==Approach.State.MOVING)return true;
            int done=v.state.mineLength,length=Math.min(64,done+12);
            var request=new ExcavationRequest(entrance,Direction.from2DDataValue(v.state.mineDirection),ExcavationMode.STAIRCASE_DOWN,length,done);
            if(!v.state.contains(request.positionAt(length)))return fail("шахта упёрлась в границу деревни");
            excavation=bot().miner().excavate(request,new ExcavationPolicy(64,2400,4,true,true));
            status="прокладывает лестничную шахту вниз";
            return true;
        }
        if(!excavation.completion().isDone())return true;
        int segments=excavation.completedSegments();
        if(segments>v.state.mineLength){v.state.mineLength=Math.min(64,segments);v.chronicle.event("Шахта углубилась до "+v.state.mineLength+" ступеней");}
        uncoverOres();
        if(excavation.status()!=ExcavationStatus.SUCCESS){
            String reason=excavation.completion().join().reason();
            if(segments<=v.state.mineLength&&segments==0){v.state.mineEntrance=null;v.state.mineLength=0;}
            return fail("шахта: "+reason);
        }
        v.runtime.save();
        return false;
    }
    /** Ore next to the shaft becomes a known quarry target even below the surface scan. */
    private void uncoverOres(){
        var request=new ExcavationRequest(entrance,Direction.from2DDataValue(v.state.mineDirection),ExcavationMode.STAIRCASE_DOWN,Math.max(1,v.state.mineLength));
        for(int step=0;step<=v.state.mineLength;step++)for(BlockPos cell:request.clearanceAt(step))for(Direction d:Direction.values()){
            BlockPos p=cell.relative(d);
            if(v.level.hasChunkAt(p)&&VillageIndex.oreResource(v.level.getBlockState(p)))v.index.ore.add(p.immutable());
        }
    }
    @Override void stop(){
        if(approach!=null)approach.cancel();if(harvest!=null)harvest.cancel();if(walk!=null)walk.cancel();
        if(excavation!=null&&!excavation.completion().isDone())excavation.cancel();
    }
}
