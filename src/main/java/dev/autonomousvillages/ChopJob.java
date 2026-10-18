package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SaplingBlock;

/**
 * Fells a whole tree: the worker cuts the lowest log himself, after which the rest of the trunk and
 * the surrounding leaves come down (with ordinary drops). Then he gathers the wood and replants.
 */
final class ChopJob extends Job {
    enum Phase { APPROACH, CUT, FALL, COLLECT, REPLANT }
    final BlockPos base;
    private Phase phase=Phase.APPROACH;
    private Approach approach;
    private Harvest cut;
    private final ArrayDeque<BlockPos> falling=new ArrayDeque<>();
    private final ArrayDeque<BlockPos> leaves=new ArrayDeque<>();
    private int felled;
    private long collectUntil;
    ChopJob(Worker w,BlockPos base){super(w,"рубит дерево");this.base=base.immutable();}
    @Override String kind(){return "chop";}
    @Override String activity(){return "wood";}
    @Override BlockPos target(){return base;}
    @Override int timeLimit(){return 3600;}
    @Override boolean atomic(){return phase==Phase.CUT||phase==Phase.FALL;}
    @Override boolean keeps(ItemStack s){return s.is(ItemTags.SAPLINGS)&&phase!=Phase.COLLECT;}

    @Override boolean tick(){
        switch(phase){
            case APPROACH->{
                BlockPos log=ForestWork.next(v,base);
                if(log==null){ForestWork.complete(v,base);return false;}
                // The miner refuses a log it cannot see, so the standing spot must have a clear line to it.
                if(approach==null||!approach.target.equals(log))approach=new Approach(log,4.5,true);
                var state=approach.tick();
                if(state==Approach.State.MOVING)return true;
                if(state==Approach.State.FAILED){
                    // Only canopy logs are left and the worker stands by the stump: the rest falls anyway.
                    if(body().blockPosition().distSqr(base)<=10*10&&log.getY()>base.getY()+2){startFall();return true;}
                    return fail("не подойти к дереву");
                }
                cut=new Harvest(log);phase=Phase.CUT;status="рубит дерево";
            }
            case CUT->{
                var state=cut.tick();
                if(state==Harvest.State.WORKING)return true;
                if(state==Harvest.State.FAILED)return fail("не срубить: "+cut.error);
                felled++;startFall();
            }
            case FALL->{
                for(int n=0;n<3&&!falling.isEmpty();n++){
                    BlockPos p=falling.poll();
                    if(v.level.getBlockState(p).is(BlockTags.LOGS)&&v.level.destroyBlock(p,true,body()))felled++;
                }
                for(int n=0;n<10&&falling.isEmpty()&&!leaves.isEmpty();n++){
                    BlockPos p=leaves.poll();
                    if(v.level.getBlockState(p).getBlock() instanceof LeavesBlock)v.level.destroyBlock(p,true,body());
                }
                if(falling.isEmpty()&&leaves.isEmpty()){phase=Phase.COLLECT;collectUntil=now()+200;status="собирает брёвна";}
            }
            case COLLECT->{
                if(collect(base,9)&&now()<collectUntil)return true;
                stopPickup();phase=Phase.REPLANT;approach=null;
            }
            case REPLANT->{
                int slot=Supplies.find(bot(),s->s.is(ItemTags.SAPLINGS));
                if(slot<0||!Construction.clear(v.level.getBlockState(base))||!v.state.mayRecordPlacement(base,true))return finish();
                var stack=bot().inventory().snapshot().slots().get(slot);
                if(!(stack.getItem() instanceof BlockItem item)||!(item.getBlock() instanceof SaplingBlock)||!item.getBlock().defaultBlockState().canSurvive(v.level,base))return finish();
                if(approach==null)approach=new Approach(base,4.0,false);
                var state=approach.tick();
                if(state==Approach.State.MOVING)return true;
                if(state==Approach.State.ARRIVED){
                    Construction.place(v,bot(),new Construction.Step(base,item.getBlock().defaultBlockState(),item));
                    status="сажает дерево";
                }
                return finish();
            }
        }
        return true;
    }
    private boolean finish(){ForestWork.complete(v,base);v.chronicle.treeFelled(felled);return false;}
    private void startFall(){
        falling.clear();falling.addAll(ForestWork.remaining(v,base).stream().map(BlockPos::of).sorted(Comparator.comparingInt(BlockPos::getY)).toList());
        var seen=new HashSet<BlockPos>();var queue=new ArrayDeque<BlockPos>(falling);
        while(!queue.isEmpty()&&leaves.size()<200){
            BlockPos p=queue.poll();
            for(Direction d:Direction.values()){
                BlockPos q=p.relative(d);
                if(!seen.add(q)||Math.abs(q.getX()-base.getX())>7||Math.abs(q.getZ()-base.getZ())>7||q.getY()<base.getY())continue;
                var state=v.level.getBlockState(q);
                if(state.getBlock() instanceof LeavesBlock&&!state.getValue(LeavesBlock.PERSISTENT)&&!v.town.protects(q)){leaves.add(q);queue.add(q);}
            }
        }
        phase=Phase.FALL;status="дерево падает";
    }
    @Override void stop(){if(approach!=null)approach.cancel();if(cut!=null)cut.cancel();stopPickup();}
}
