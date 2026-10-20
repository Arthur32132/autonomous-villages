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
    /** The tree's logs, lowest first: surveyed once, then only the lowest one is looked at again. */
    private ArrayDeque<BlockPos> logs;
    private final ArrayDeque<BlockPos> falling=new ArrayDeque<>();
    private final ArrayDeque<BlockPos> leaves=new ArrayDeque<>();
    /** The own stump first, then a couple of older stumps nearby while saplings last. */
    private ArrayDeque<BlockPos> spots;
    private int felled;
    private long collectUntil,lastBreak;
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
                BlockPos log=next();
                if(log==null){
                    // A build's logs or a tree already gone: nobody walks here again today.
                    if(!ForestWork.felling(v,base))v.rejected.put(base.asLong(),now()+24000);
                    ForestWork.complete(v,base);return false;
                }
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
                // The log is down while its drops are still collected: record the felling now, or an interruption orphans the trunk.
                if(!ForestWork.felling(v,base)&&v.level.hasChunkAt(cut.target)&&!v.level.getBlockState(cut.target).is(BlockTags.LOGS)){next();ForestWork.started(v,base,logs);}
                if(state==Harvest.State.WORKING)return true;
                if(state==Harvest.State.FAILED)return fail("не срубить: "+cut.error);
                felled++;lastBreak=now();startFall();
            }
            case FALL->{
                for(int n=0;n<3&&!falling.isEmpty();n++){
                    BlockPos p=falling.poll();
                    if(v.level.hasChunkAt(p)&&v.level.getBlockState(p).is(BlockTags.LOGS)&&ForestWork.usable(v,p)&&v.level.destroyBlock(p,true,body())){felled++;lastBreak=now();}
                }
                for(int n=0;n<10&&falling.isEmpty()&&!leaves.isEmpty();n++){
                    BlockPos p=leaves.poll();
                    if(v.level.hasChunkAt(p)&&v.level.getBlockState(p).getBlock() instanceof LeavesBlock&&ForestWork.usable(v,p)&&v.level.destroyBlock(p,true,body()))lastBreak=now();
                }
                if(falling.isEmpty()&&leaves.isEmpty()){phase=Phase.COLLECT;collectUntil=now()+200;status="собирает брёвна";}
            }
            case COLLECT->{
                if(Gathering.keepCollecting(collect(base,9),now(),lastBreak,collectUntil))return true;
                stopPickup();phase=Phase.REPLANT;approach=null;
            }
            case REPLANT->{
                if(spots==null){spots=new ArrayDeque<>();spots.add(base);spots.addAll(ForestWork.stumps(v,base,2));}
                while(!spots.isEmpty()){
                    BlockPos spot=spots.peek();
                    BlockItem sapling=plantable(spot)?sapling(spot):null;
                    if(sapling==null){spots.poll();approach=null;continue;}
                    if(approach==null||!approach.target.equals(spot))approach=new Approach(spot,4.0,false);
                    var state=approach.tick();
                    if(state==Approach.State.MOVING)return true;
                    if(state==Approach.State.ARRIVED&&Construction.place(v,bot(),new Construction.Step(spot,sapling.getBlock().defaultBlockState(),sapling)))status="сажает дерево";
                    spots.poll();approach=null;
                }
                return finish();
            }
        }
        return true;
    }
    private boolean finish(){ForestWork.complete(v,base);v.chronicle.treeFelled(felled);return false;}
    /** The lowest log still standing; a log gone or now off limits drops out of the cached list. */
    private BlockPos next(){
        if(logs==null)logs=new ArrayDeque<>(ForestWork.remaining(v,base));
        while(!logs.isEmpty()){
            BlockPos p=logs.peek();
            if(v.level.hasChunkAt(p)&&v.level.getBlockState(p).is(BlockTags.LOGS)&&ForestWork.usable(v,p))return p;
            logs.poll();
        }
        return null;
    }
    private boolean plantable(BlockPos p){return v.level.hasChunkAt(p)&&Construction.clear(v.level.getBlockState(p))&&v.state.mayRecordPlacement(p,true);}
    /** A sapling from the bag that grows on this spot. */
    private BlockItem sapling(BlockPos spot){
        for(var s:w.bag())if(s.is(ItemTags.SAPLINGS)&&s.getItem() instanceof BlockItem item&&item.getBlock() instanceof SaplingBlock&&item.getBlock().defaultBlockState().canSurvive(v.level,spot))return item;
        return null;
    }
    private void startFall(){
        next();
        ForestWork.started(v,base,logs);
        falling.clear();falling.addAll(logs);
        var seen=new HashSet<BlockPos>();var queue=new ArrayDeque<BlockPos>(falling);
        while(!queue.isEmpty()&&leaves.size()<200){
            BlockPos p=queue.poll();
            for(Direction d:Direction.values()){
                BlockPos q=p.relative(d);
                if(!seen.add(q)||Math.abs(q.getX()-base.getX())>7||Math.abs(q.getZ()-base.getZ())>7||q.getY()<base.getY()||!v.level.hasChunkAt(q))continue;
                var state=v.level.getBlockState(q);
                if(state.getBlock() instanceof LeavesBlock&&!state.getValue(LeavesBlock.PERSISTENT)&&ForestWork.usable(v,q)){leaves.add(q);queue.add(q);}
            }
        }
        phase=Phase.FALL;status="дерево падает";
    }
    @Override void stop(){if(approach!=null)approach.cancel();if(cut!=null)cut.cancel();stopPickup();}
}
