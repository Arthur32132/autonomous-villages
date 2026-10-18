package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/**
 * Clears powder snow inside the village. Children wander into it and freeze, and adults step into it
 * when a route home leads through the dark; an empty hole is harmless.
 */
final class HazardJob extends Job {
    private final ArrayDeque<BlockPos> cells;
    private Approach approach;
    private Harvest harvest;
    private int cleared;
    HazardJob(Worker w,List<BlockPos> cells){super(w,"убирает рыхлый снег");this.cells=new ArrayDeque<>(cells);}
    @Override String kind(){return "hazard";}
    @Override String activity(){return "build";}
    @Override BlockPos target(){return cells.peek();}
    @Override boolean atomic(){return harvest!=null;}
    @Override boolean mayBreak(BlockPos p){return harvest!=null&&harvest.target.equals(p);}
    @Override boolean tick(){
        if(harvest!=null){
            var state=harvest.tick();
            if(state==Harvest.State.WORKING)return true;
            if(state==Harvest.State.DONE){cleared++;v.index.hazards.remove(harvest.target);}
            harvest=null;approach=null;cells.poll();
        }
        while(!cells.isEmpty()&&!v.level.getBlockState(cells.peek()).is(Blocks.POWDER_SNOW)){v.index.hazards.remove(cells.peek());cells.poll();}
        if(cells.isEmpty()){if(cleared>0)v.chronicle.add("hazards",cleared);return false;}
        if(approach==null)approach=new Approach(cells.peek(),4.5,true);
        var state=approach.tick();
        if(state==Approach.State.MOVING)return true;
        if(state==Approach.State.FAILED){v.rejected.put(cells.poll().asLong(),now()+2400);approach=null;return !cells.isEmpty()||cleared>0?!cells.isEmpty():fail("не подойти к рыхлому снегу");}
        harvest=new Harvest(cells.peek());
        return true;
    }
    @Override void stop(){if(approach!=null)approach.cancel();if(harvest!=null)harvest.cancel();}
}
