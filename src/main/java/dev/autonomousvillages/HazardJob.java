package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/**
 * Packs powder snow inside the village into snow blocks. Children wander into it and freeze, and
 * adults slip into it off the edge of a path; a packed cell is firm ground, while a dug-out one would
 * only leave a pit with more powder snow under it. The worker does it from the edge, never stepping in,
 * never over anyone standing in the snow and never in a zone closed to building.
 */
final class HazardJob extends Job {
    private final ArrayDeque<BlockPos> cells;
    private Approach approach;
    private int packed;
    HazardJob(Worker w,List<BlockPos> cells){super(w,"утаптывает рыхлый снег");this.cells=new ArrayDeque<>(cells);}
    @Override String kind(){return "hazard";}
    @Override BlockPos target(){return cells.peek();}
    @Override boolean tick(){
        while(!cells.isEmpty()&&!packable(cells.peek()))cells.poll();
        if(cells.isEmpty())return false;
        if(approach==null)approach=new Approach(cells.peek(),4.5,true);
        var state=approach.tick();
        if(state==Approach.State.MOVING)return true;
        if(state==Approach.State.FAILED){v.rejected.put(cells.poll().asLong(),now()+2400);approach=null;return !cells.isEmpty()||packed>0?!cells.isEmpty():fail("не подойти к рыхлому снегу");}
        BlockPos cell=cells.poll();approach=null;
        // Never packed over someone standing in it: a full block there suffocates them. Tried again shortly.
        if(occupied(cell)){v.rejected.put(cell.asLong(),now()+200);return !cells.isEmpty();}
        v.level.setBlock(cell,Blocks.SNOW_BLOCK.defaultBlockState(),3);v.index.hazards.remove(cell);packed++;
        // Counted at once: a job cut short by nightfall or the time limit lost the whole tally.
        v.chronicle.add("hazards",1);
        body().swing(InteractionHand.MAIN_HAND);v.level.playSound(null,cell,SoundEvents.SNOW_PLACE,SoundSource.BLOCKS,1f,.8f);
        return !cells.isEmpty();
    }
    /** Still powder snow, in a loaded chunk, and in no zone where the players keep the village from building. */
    private boolean packable(BlockPos p){
        if(!v.level.hasChunkAt(p))return false;
        if(!v.level.getBlockState(p).is(Blocks.POWDER_SNOW)){v.index.hazards.remove(p);return false;}
        if(!v.state.policy.allows("build",p)){v.rejected.put(p.asLong(),now()+24_000);return false;}
        return true;
    }
    private boolean occupied(BlockPos p){return !v.level.getEntitiesOfClass(Entity.class,new AABB(p),e->e.isAlive()&&!(e instanceof ItemEntity)).isEmpty();}
    @Override void stop(){if(approach!=null)approach.cancel();}
}
