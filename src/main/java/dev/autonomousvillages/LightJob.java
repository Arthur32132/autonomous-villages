package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Puts torches on dark ground between houses so that monsters cannot spawn inside the village. */
final class LightJob extends Job {
    private final ArrayDeque<BlockPos> spots;
    private Fetch fetch;
    private Approach approach;
    private int placed;
    LightJob(Worker w,List<BlockPos> spots){super(w,"освещает деревню");this.spots=new ArrayDeque<>(spots);}
    @Override String kind(){return "light";}
    @Override BlockPos target(){return spots.peek();}
    @Override boolean keeps(ItemStack s){return s.is(Items.TORCH);}
    @Override boolean tick(){
        if(w.count(s->s.is(Items.TORCH))<1){
            if(fetch==null)fetch=new Fetch(List.of(new Need(s->s.is(Items.TORCH),Math.min(16,spots.size()),"факелы")));
            var state=fetch.tick();
            if(state==Approach.State.FAILED)return placed>0||fail("нет факелов");
            if(state==Approach.State.MOVING)return true;
        }
        while(!spots.isEmpty()&&!Lighting.needsLight(v,spots.peek()))spots.poll();
        if(spots.isEmpty()||w.count(s->s.is(Items.TORCH))<1)return false;
        if(approach==null)approach=new Approach(spots.peek(),4.0,false);
        var state=approach.tick();
        if(state==Approach.State.MOVING)return true;
        BlockPos spot=spots.poll();approach=null;
        if(state==Approach.State.ARRIVED&&Construction.place(v,bot(),new Construction.Step(spot,Blocks.TORCH.defaultBlockState(),Items.TORCH))){
            placed++;v.state.lights.add(spot.asLong());v.chronicle.lit();
        }
        return !spots.isEmpty();
    }
    @Override void stop(){if(fetch!=null)fetch.cancel();if(approach!=null)approach.cancel();}
}
