package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Puts a lantern on the lamp posts in place of their torch, one post after another, and takes the
 * torch back to the store. Purely for looks: a lantern gives one light level more than the torch.
 */
final class LanternJob extends Job {
    private final ArrayDeque<BlockPos> posts;
    private Fetch fetch;
    private Approach approach;
    private int hung;
    LanternJob(Worker w,List<BlockPos> posts){super(w,"вешает фонари на столбы");this.posts=new ArrayDeque<>(posts);}
    @Override String kind(){return "lantern";}
    @Override BlockPos target(){return posts.peek();}
    @Override boolean keeps(ItemStack s){return s.is(Items.LANTERN);}
    @Override boolean tick(){
        if(!w.has(s->s.is(Items.LANTERN))){
            if(hung>0)return false;
            if(fetch==null)fetch=new Fetch(List.of(new Need(s->s.is(Items.LANTERN),Math.min(8,posts.size()),"фонари")));
            var fetched=fetch.tick();
            if(fetched==Approach.State.FAILED)return fail("нет фонарей");
            if(fetched==Approach.State.MOVING)return true;
        }
        while(!posts.isEmpty()&&!post(v,posts.peek()))posts.poll();
        if(posts.isEmpty())return false;
        if(approach==null)approach=new Approach(posts.peek(),4.0,false);
        var reach=approach.tick();
        if(reach==Approach.State.MOVING)return true;
        BlockPos top=posts.poll();approach=null;
        if(reach==Approach.State.FAILED){v.rejected.put(top.asLong(),now()+2400);return true;}
        if(!v.state.policy.allows("build",top))return true;
        // The torch comes off only when the bag or the store has room for it: it is never thrown away.
        if(!room(w.freeSlots(),w.count(s->s.is(Items.TORCH)),v.stock.freeSlots())){w.cooldown("lantern",2400);return fail("некуда убрать факел");}
        // The torch comes off first: a lantern is placed only into an empty cell.
        v.level.setBlock(top,Blocks.AIR.defaultBlockState(),3);
        if(Construction.place(v,bot(),new Construction.Step(top,Blocks.LANTERN.defaultBlockState(),Items.LANTERN))){
            hung++;v.chronicle.add("lanterns",1);
            var rest=bot().inventory().insert(new ItemStack(Items.TORCH));
            if(!rest.isEmpty())rest=v.stock.put(rest);
            if(!rest.isEmpty())net.minecraft.world.level.block.Block.popResource(v.level,top,rest);
        }else v.level.setBlock(top,Blocks.TORCH.defaultBlockState(),3);
        return true;
    }
    @Override void stop(){if(fetch!=null)fetch.cancel();if(approach!=null)approach.cancel();}

    /** Room for the torch taken off a post: a free bag slot, a torch stack short of 64, or a free slot in the store. */
    static boolean room(int bagFreeSlots,int torchesInBag,int storeFreeSlots){return bagFreeSlots>0||torchesInBag%64!=0||storeFreeSlots>0;}
    /** A lamp post the village put up: a torch on top of a wooden fence, out in the open. */
    static boolean post(Village v,BlockPos top){
        return v.level.hasChunkAt(top)&&v.level.getBlockState(top).is(Blocks.TORCH)&&v.level.getBlockState(top.below()).is(BlockTags.WOODEN_FENCES)
                &&v.level.canSeeSky(top.above())&&!Lighting.unreachable(v,top);
    }
    /** Lamp posts still carrying a torch, nearest to the meeting point first. */
    static List<BlockPos> posts(Village v,int limit){
        BlockPos hub=v.meetingPoint();
        return v.state.lights.stream().map(BlockPos::of).filter(p->post(v,p)).sorted(Comparator.comparingDouble(p->p.distSqr(hub))).limit(limit).toList();
    }
}
