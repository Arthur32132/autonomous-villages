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
        var world=snow(v);
        while(!cells.isEmpty()&&!remember(verdict(world,cells.peek(),false),cells.peek(),v.index.hazards,v.rejected,now()))cells.poll();
        // Nothing left that can be packed now: a short pause, so the planner does not hand out the same cells every tick.
        if(cells.isEmpty()){if(packed==0)w.cooldown("hazard",600);return false;}
        if(approach==null)approach=new Approach(cells.peek(),4.5,true);
        var state=approach.tick();
        if(state==Approach.State.MOVING)return true;
        if(state==Approach.State.FAILED){v.rejected.put(cells.poll().asLong(),now()+2400);approach=null;return !cells.isEmpty()||packed>0?!cells.isEmpty():fail("не подойти к рыхлому снегу");}
        BlockPos cell=cells.poll();approach=null;
        // Never packed over someone standing in it: a full block there suffocates them. Tried again shortly.
        if(!remember(verdict(world,cell,true),cell,v.index.hazards,v.rejected,now()))return !cells.isEmpty();
        v.level.setBlock(cell,Blocks.SNOW_BLOCK.defaultBlockState(),3);v.index.hazards.remove(cell);packed++;
        // Counted at once: a job cut short by nightfall or the time limit lost the whole tally.
        v.chronicle.add("hazards",1);
        body().swing(InteractionHand.MAIN_HAND);v.level.playSound(null,cell,SoundEvents.SNOW_PLACE,SoundSource.BLOCKS,1f,.8f);
        return !cells.isEmpty();
    }

    /** What packing asks of the world: the level in game, a map of cells in tests. */
    interface Snow { boolean loaded(BlockPos p); boolean powder(BlockPos p); boolean allowed(BlockPos p); boolean occupied(BlockPos p); }
    /** Why a cell is left alone, and for how many ticks; PACK when it can be packed now, GONE when it is no longer powder snow. */
    enum Verdict { PACK(0), GONE(0), UNLOADED(1200), CLOSED(24_000), OCCUPIED(200);
        final int wait; Verdict(int wait){this.wait=wait;} }
    /** Still powder snow, in a loaded chunk, in no zone closed to building and, when about to be packed, with nobody in it. */
    static Verdict verdict(Snow s,BlockPos p,boolean placing){
        if(!s.loaded(p))return Verdict.UNLOADED;
        if(!s.powder(p))return Verdict.GONE;
        if(!s.allowed(p))return Verdict.CLOSED;
        if(placing&&s.occupied(p))return Verdict.OCCUPIED;
        return Verdict.PACK;
    }
    /** True to pack; otherwise the cell leaves the index or is rejected for a while, so the planner does not offer it again at once. */
    static boolean remember(Verdict verdict,BlockPos p,Collection<BlockPos> hazards,Map<Long,Long> rejected,long now){
        if(verdict==Verdict.GONE)hazards.remove(p);
        else if(verdict!=Verdict.PACK)rejected.merge(p.asLong(),now+verdict.wait,Math::max);
        return verdict==Verdict.PACK;
    }
    /** The level; a cell in an unloaded chunk is not loaded for the question. */
    static Snow snow(Village v){
        var level=v.level;
        return new Snow(){
            @Override public boolean loaded(BlockPos p){return level.hasChunkAt(p);}
            @Override public boolean powder(BlockPos p){return level.getBlockState(p).is(Blocks.POWDER_SNOW);}
            @Override public boolean allowed(BlockPos p){return v.state.policy.allows("build",p);}
            @Override public boolean occupied(BlockPos p){return !level.getEntitiesOfClass(Entity.class,new AABB(p),e->e.isAlive()&&!(e instanceof ItemEntity)).isEmpty();}
        };
    }
    @Override void stop(){if(approach!=null)approach.cancel();}
}
