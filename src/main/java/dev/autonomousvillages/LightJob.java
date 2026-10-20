package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Lights dark ground between houses and dark bedrooms so that monsters cannot spawn inside the
 * village: a lamp post (fence with a torch on top) outdoors when the store has fences, otherwise a
 * torch on the ground.
 */
final class LightJob extends Job {
    private final ArrayDeque<BlockPos> spots;
    private Fetch fetch;
    private Approach approach;
    private int placed;
    LightJob(Worker w,List<BlockPos> spots){super(w,"освещает деревню");this.spots=new ArrayDeque<>(spots);}
    @Override String kind(){return "light";}
    @Override BlockPos target(){return spots.peek();}
    @Override boolean keeps(ItemStack s){return s.is(Items.TORCH)||s.is(Items.LANTERN)||s.is(net.minecraft.tags.ItemTags.WOODEN_FENCES);}
    @Override boolean tick(){
        if(w.count(s->s.is(Items.TORCH))<1){
            if(fetch==null){
                var needs=new java.util.ArrayList<Need>();needs.add(new Need(s->s.is(Items.TORCH),Math.min(16,spots.size()),"факелы"));
                if(v.stock.count(s->s.is(net.minecraft.tags.ItemTags.WOODEN_FENCES))>0)needs.add(new Need(s->s.is(net.minecraft.tags.ItemTags.WOODEN_FENCES),Math.min(16,spots.size()),"заборы"));
                fetch=new Fetch(needs);
            }
            var state=fetch.tick();
            if(state==Approach.State.FAILED)return placed>0?false:fail("нет факелов");
            if(state==Approach.State.MOVING)return true;
        }
        while(!spots.isEmpty()&&!Lighting.needsLight(v,spots.peek()))spots.poll();
        if(spots.isEmpty()||w.count(s->s.is(Items.TORCH))<1)return false;
        if(approach==null)approach=new Approach(spots.peek(),4.0,false);
        var state=approach.tick();
        if(state==Approach.State.MOVING)return true;
        BlockPos spot=spots.poll();approach=null;
        if(state==Approach.State.FAILED)v.rejected.put(spot.asLong(),now()+2400);
        if(state==Approach.State.ARRIVED){
            int fence=Supplies.find(bot(),s->s.is(net.minecraft.tags.ItemTags.WOODEN_FENCES));
            BlockPos torch=spot;net.minecraft.world.item.BlockItem fencePost=null;
            // A lamp post outside; indoors, by a door, on a path or in a narrow gap the torch stands on the ground.
            if(fence>=0&&!Lighting.indoors(v,spot)&&!Lighting.byDoor(v,spot)&&Construction.clear(v.level.getBlockState(spot.above()))&&v.state.contains(spot.above())
                    &&!Lighting.postInTheWay(v,spot)&&bot().inventory().snapshot().slots().get(fence).getItem() instanceof net.minecraft.world.item.BlockItem post
                    &&Construction.place(v,bot(),new Construction.Step(spot,post.getBlock().defaultBlockState(),post))){torch=spot.above();fencePost=post;}
            // A lamp post carries a lantern when there is one.
            boolean lantern=!torch.equals(spot)&&w.has(s->s.is(Items.LANTERN))
                    &&Construction.place(v,bot(),new Construction.Step(torch,Blocks.LANTERN.defaultBlockState(),Items.LANTERN));
            if(lantern||Construction.place(v,bot(),new Construction.Step(torch,Blocks.TORCH.defaultBlockState(),Items.TORCH))){
                if(lantern)v.chronicle.add("lanterns",1);
                placed++;v.state.lights.add(torch.asLong());v.chronicle.lit();
                if(!torch.equals(spot))v.chronicle.add("lamps",1);
                if(Lighting.indoors(v,spot))v.chronicle.add("roomsLit",1);
            }else{
                // A fence without its torch is no lamp post: it comes down and goes back to the bag.
                if(fencePost!=null)takeBack(spot,fencePost);
                v.rejected.put(spot.asLong(),now()+600); // someone standing there, most likely
            }
        }
        return !spots.isEmpty();
    }
    /** Lifts a block this job just set back into the bag, or the store, or at worst drops it: never lost. */
    private void takeBack(BlockPos p,net.minecraft.world.item.BlockItem item){
        if(!v.level.getBlockState(p).is(item.getBlock()))return;
        v.level.setBlock(p,Blocks.AIR.defaultBlockState(),3);
        var rest=bot().inventory().insert(new ItemStack(item));
        if(!rest.isEmpty())rest=v.stock.put(rest);
        if(!rest.isEmpty())net.minecraft.world.level.block.Block.popResource(v.level,p,rest);
    }
    @Override void stop(){if(fetch!=null)fetch.cancel();if(approach!=null)approach.cancel();}
}
