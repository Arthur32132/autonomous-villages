package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;

/**
 * Packs powder snow inside the village into snow blocks. Children wander into it and freeze, and
 * adults slip into it off the edge of a path; a packed cell is firm ground, while a dug-out one would
 * only leave a pit with more powder snow under it. The worker does it from the edge, never stepping in.
 */
final class HazardJob extends Job {
    private final ArrayDeque<BlockPos> cells;
    private Approach approach;
    private int packed;
    HazardJob(Worker w,List<BlockPos> cells){super(w,"утаптывает рыхлый снег");this.cells=new ArrayDeque<>(cells);}
    @Override String kind(){return "hazard";}
    @Override BlockPos target(){return cells.peek();}
    @Override boolean tick(){
        while(!cells.isEmpty()&&!v.level.getBlockState(cells.peek()).is(Blocks.POWDER_SNOW)){v.index.hazards.remove(cells.peek());cells.poll();}
        if(cells.isEmpty()){if(packed>0)v.chronicle.add("hazards",packed);return false;}
        if(approach==null)approach=new Approach(cells.peek(),4.5,true);
        var state=approach.tick();
        if(state==Approach.State.MOVING)return true;
        if(state==Approach.State.FAILED){v.rejected.put(cells.poll().asLong(),now()+2400);approach=null;return !cells.isEmpty()||packed>0?!cells.isEmpty():fail("не подойти к рыхлому снегу");}
        BlockPos cell=cells.poll();approach=null;
        v.level.setBlock(cell,Blocks.SNOW_BLOCK.defaultBlockState(),3);v.index.hazards.remove(cell);packed++;
        body().swing(InteractionHand.MAIN_HAND);v.level.playSound(null,cell,SoundEvents.SNOW_PLACE,SoundSource.BLOCKS,1f,.8f);
        return !cells.isEmpty();
    }
    @Override void stop(){if(approach!=null)approach.cancel();}
}
