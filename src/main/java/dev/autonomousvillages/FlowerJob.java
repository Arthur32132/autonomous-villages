package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

/**
 * Flowers picked up while clearing building sites go back into the ground: a few along the walls of
 * every home, never by a door or on a path.
 */
final class FlowerJob extends Job {
    static final int PER_HOME=6;
    private final ArrayDeque<BlockPos> beds;
    private Fetch fetch;
    private Approach approach;
    private int planted;
    FlowerJob(Worker w,List<BlockPos> beds){super(w,"сажает цветы у дома");this.beds=new ArrayDeque<>(beds);}
    @Override String kind(){return "flowers";}
    @Override BlockPos target(){return beds.peek();}
    @Override boolean keeps(ItemStack s){return s.is(ItemTags.SMALL_FLOWERS);}
    @Override boolean tick(){
        if(!w.has(s->s.is(ItemTags.SMALL_FLOWERS))){
            if(planted>0)return finish();
            if(fetch==null)fetch=new Fetch(List.of(new Need(s->s.is(ItemTags.SMALL_FLOWERS),beds.size(),"цветы")));
            var fetched=fetch.tick();
            if(fetched==Approach.State.FAILED)return fail("нет цветов");
            if(fetched==Approach.State.MOVING)return true;
        }
        while(!beds.isEmpty()&&!fits(v,beds.peek()))beds.poll();
        if(beds.isEmpty())return finish();
        if(approach==null)approach=new Approach(beds.peek(),4.0,false);
        var reach=approach.tick();
        if(reach==Approach.State.MOVING)return true;
        BlockPos spot=beds.poll();approach=null;
        if(reach==Approach.State.FAILED){v.rejected.put(spot.asLong(),now()+2400);return true;}
        int slot=Supplies.find(bot(),s->s.is(ItemTags.SMALL_FLOWERS));
        if(slot>=0&&w.bag().get(slot).getItem() instanceof BlockItem flower
                &&Construction.place(v,bot(),new Construction.Step(spot,flower.getBlock().defaultBlockState(),flower)))planted++;
        return true;
    }
    private boolean finish(){if(planted>0)v.chronicle.add("flowers",planted);return false;}
    @Override void stop(){if(fetch!=null)fetch.cancel();if(approach!=null)approach.cancel();}

    /** Open ground right against a home's wall: grass or dirt under it, sky above, no door or path beside. */
    static boolean fits(Village v,BlockPos p){
        if(!v.level.hasChunkAt(p)||!v.state.contains(p)||v.town.protects(p)||!v.state.policy.allows("build",p)||Lighting.unreachable(v,p))return false;
        var here=v.level.getBlockState(p);var ground=v.level.getBlockState(p.below());
        if(!(here.isAir()||here.is(Blocks.SHORT_GRASS))||!v.level.canSeeSky(p))return false;
        if(!ground.is(Blocks.GRASS_BLOCK)&&!ground.is(Blocks.DIRT)&&!ground.is(Blocks.PODZOL)&&!ground.is(Blocks.COARSE_DIRT))return false;
        if(Lighting.byDoor(v,p)||v.index.beds.stream().anyMatch(b->b.distSqr(p)<2))return false;
        boolean wall=false;
        for(Direction d:Direction.Plane.HORIZONTAL){
            var n=v.level.getBlockState(p.relative(d));
            if(n.is(Blocks.DIRT_PATH)||n.is(Blocks.FARMLAND))return false;
            if(v.town.protects(p.relative(d))&&!n.isAir())wall=true;
        }
        return wall;
    }
    /** Flower spots along one home that has fewer than {@link #PER_HOME} flowers by its walls. */
    static List<BlockPos> spots(Village v){
        for(var b:v.state.buildings){
            if(!NameplateJob.home(b))continue;
            var around=new LinkedHashSet<BlockPos>();var flowers=new HashSet<BlockPos>();
            for(var p:b.blocks){
                BlockPos at=p.position();
                for(Direction d:Direction.Plane.HORIZONTAL){
                    BlockPos q=at.relative(d);
                    if(v.level.getBlockState(q).is(BlockTags.SMALL_FLOWERS))flowers.add(q.immutable());
                    else around.add(q.immutable());
                }
            }
            if(flowers.size()>=PER_HOME)continue;
            var out=new ArrayList<BlockPos>();
            for(BlockPos q:around){if(out.size()>=PER_HOME-flowers.size())break;if(fits(v,q)&&out.stream().noneMatch(o->o.distManhattan(q)<2))out.add(q);}
            if(!out.isEmpty())return out;
        }
        return List.of();
    }
}
