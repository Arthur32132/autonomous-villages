package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Lights dark ground between houses so that monsters cannot spawn inside the village: a lamp post
 * (fence with a torch on top) when the store has fences, otherwise a torch on the ground.
 */
final class LightJob extends Job {
    private final ArrayDeque<BlockPos> spots;
    private Fetch fetch;
    private Approach approach;
    private int placed;
    LightJob(Worker w,List<BlockPos> spots){super(w,"освещает деревню");this.spots=new ArrayDeque<>(spots);}
    @Override String kind(){return "light";}
    @Override BlockPos target(){return spots.peek();}
    @Override boolean keeps(ItemStack s){return s.is(Items.TORCH)||s.is(net.minecraft.tags.ItemTags.WOODEN_FENCES);}
    @Override boolean tick(){
        if(w.count(s->s.is(Items.TORCH))<1){
            if(fetch==null){
                var needs=new java.util.ArrayList<Need>();needs.add(new Need(s->s.is(Items.TORCH),Math.min(16,spots.size()),"факелы"));
                if(v.stock.count(s->s.is(net.minecraft.tags.ItemTags.WOODEN_FENCES))>0)needs.add(new Need(s->s.is(net.minecraft.tags.ItemTags.WOODEN_FENCES),Math.min(16,spots.size()),"заборы"));
                fetch=new Fetch(needs);
            }
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
        if(state==Approach.State.ARRIVED){
            int fence=Supplies.find(bot(),s->s.is(net.minecraft.tags.ItemTags.WOODEN_FENCES));
            BlockPos torch=spot;
            if(fence>=0&&Construction.clear(v.level.getBlockState(spot.above()))&&v.state.contains(spot.above())
                    &&bot().inventory().snapshot().slots().get(fence).getItem() instanceof net.minecraft.world.item.BlockItem post
                    &&Construction.place(v,bot(),new Construction.Step(spot,post.getBlock().defaultBlockState(),post)))torch=spot.above();
            if(Construction.place(v,bot(),new Construction.Step(torch,Blocks.TORCH.defaultBlockState(),Items.TORCH))){
                placed++;v.state.lights.add(torch.asLong());v.chronicle.lit();
                if(!torch.equals(spot))v.chronicle.add("lamps",1);
            }
        }
        return !spots.isEmpty();
    }
    @Override void stop(){if(fetch!=null)fetch.cancel();if(approach!=null)approach.cancel();}
}
