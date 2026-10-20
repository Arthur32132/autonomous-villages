package dev.autonomousvillages;

import java.util.*;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/**
 * The village's shared store: every unlocked shared chest acts as one inventory. A worker only
 * takes or puts items while standing at a chest, but nobody negotiates who may use what.
 */
final class VillageStock {
    private final Village v;
    private long cachedTick=Long.MIN_VALUE;
    private List<ChestBlockEntity> chests=List.of();
    private final Map<Predicate<ItemStack>,Integer> counts=new IdentityHashMap<>();
    /** Chests, workbenches and building cells no route reached lately; the village's one memory of them. */
    final UnreachableMemory unreachable=new UnreachableMemory();
    VillageStock(Village v){this.v=v;}

    /**
     * The chests a route currently reaches. One that two different residents (or the only one) failed
     * to reach lately (Job.unreachable) is left out for a while, so the planner neither counts its items
     * nor its free slots: otherwise jobs are planned for goods nobody can fetch and fail at once, over
     * and over. A single report hides nothing from the village: a trapped resident fails everywhere.
     */
    List<ChestBlockEntity> chests(){
        long now=v.level.getGameTime();
        if(now!=cachedTick){
            cachedTick=now;counts.clear();int quorum=quorum();
            chests=v.chests().stream().filter(c->v.rejected.getOrDefault(c.getBlockPos().asLong(),0L)<=now&&!unreachable.isUnreachable(c.getBlockPos(),now,quorum)).toList();
        }
        return chests;
    }
    void invalidate(){cachedTick=Long.MIN_VALUE;}
    /** Reports a shared store block needs before everyone skips it: two residents, or the only one (as Village.unreachableFrom). */
    int quorum(){return Math.min(2,Math.max(1,v.workers.size()));}
    /** True when this resident does not use the store block right now: the village skips it, or it failed there itself lately. */
    boolean skips(BlockPos p,long now,UUID by){return unreachable.isUnreachable(p,now,quorum())||unreachable.reported(p,now,by);}
    /** Targets (chests, workbenches, building cells) currently skipped as unreachable; for the plan text. */
    int unreachableCount(){return unreachable.count(v.level.getGameTime());}
    /** A shared chest holding the goods exists, but this resident cannot use it right now (skipped as unreachable). */
    boolean hidden(Predicate<ItemStack> holding,UUID by){
        long now=v.level.getGameTime();
        for(var c:v.chests())if((!chests().contains(c)||skips(c.getBlockPos(),now,by))&&contains(c,holding))return true;
        return false;
    }

    /** Total matching items in shared chests; cached per tick and per predicate instance. */
    int count(Predicate<ItemStack> test){
        chests();
        return counts.computeIfAbsent(test,t->{int n=0;for(var c:chests)for(int i=0;i<c.getContainerSize();i++){var s=c.getItem(i);if(!s.isEmpty()&&t.test(s))n+=s.getCount();}return n;});
    }
    int freeSlots(){int n=0;for(var c:chests())for(int i=0;i<c.getContainerSize();i++)if(c.getItem(i).isEmpty())n++;return n;}

    /** Nearest chest to a position, preferring one that holds matching items when a test is given. */
    ChestBlockEntity nearest(BlockPos from,Predicate<ItemStack> holding){return nearest(from,holding,Set.of());}
    ChestBlockEntity nearest(BlockPos from,Predicate<ItemStack> holding,Set<BlockPos> excluded){
        return pick(chests().stream().filter(c->holding==null||contains(c,holding)).toList(),ChestBlockEntity::getBlockPos,from,excluded,unreachable,v.level.getGameTime(),quorum());
    }
    /** The nearest candidate by the store's distance (height weighs more), skipping excluded positions and those {@code quorum} residents failed to reach. */
    static <T> T pick(Collection<T> items,java.util.function.Function<T,BlockPos> at,BlockPos from,Set<BlockPos> excluded,UnreachableMemory memory,long now,int quorum){
        return items.stream().filter(c->!excluded.contains(at.apply(c))&&!memory.isUnreachable(at.apply(c),now,quorum))
                .min(Comparator.comparingDouble(c->{BlockPos p=at.apply(c);double dy=p.getY()-from.getY();return p.distSqr(from)+15*dy*dy;})).orElse(null);
    }
    /**
     * A chest with a free slot: the store by the bell first, nearest to the worker; a house chest only
     * when the store is full. Goods spread over far house chests were walked to twice, and deposits
     * timed out on house chests nobody reaches (rc13, seed 1337).
     */
    ChestBlockEntity nearestWithRoom(BlockPos from,Set<BlockPos> excluded){
        BlockPos hub=v.meetingPoint();long now=v.level.getGameTime();int quorum=quorum();
        return chests().stream().filter(c->!excluded.contains(c.getBlockPos())&&!unreachable.isUnreachable(c.getBlockPos(),now,quorum)).filter(c->{for(int i=0;i<c.getContainerSize();i++)if(c.getItem(i).isEmpty())return true;return false;})
                .min(Comparator.<ChestBlockEntity>comparingInt(c->c.getBlockPos().distSqr(hub)<=12*12?0:1).thenComparingDouble(c->c.getBlockPos().distSqr(from))).orElse(null);
    }
    static boolean contains(ChestBlockEntity c,Predicate<ItemStack> test){
        for(int i=0;i<c.getContainerSize();i++){var s=c.getItem(i);if(!s.isEmpty()&&test.test(s))return true;}
        return false;
    }
    /**
     * Puts items straight into the shared chests, the ones by the bell first: a chest in a far house
     * may be one nobody reaches (rc11: a fletcher's sticks sat there and 19 crafts failed fetching them).
     * Returns what did not fit.
     */
    ItemStack put(ItemStack stack){
        var left=stack.copy();BlockPos hub=v.meetingPoint();
        for(var c:chests().stream().sorted(java.util.Comparator.comparingDouble(c->c.getBlockPos().distSqr(hub))).toList()){
            if(left.isEmpty())break;
            var slots=new ArrayList<ItemStack>(c.getContainerSize());for(int i=0;i<c.getContainerSize();i++)slots.add(c.getItem(i).copy());
            int moved=Supplies.insertPartial(slots,left);
            if(moved==0)continue;
            for(int i=0;i<slots.size();i++)c.setItem(i,slots.get(i));
            c.setChanged();left.shrink(moved);
        }
        invalidate();return left;
    }
    /** Removes matching items straight from the chests (used when the household itself eats). */
    int consume(Predicate<ItemStack> test,int amount){
        int left=amount;
        for(var c:chests()){
            for(int i=0;i<c.getContainerSize()&&left>0;i++){var s=c.getItem(i);if(!s.isEmpty()&&test.test(s)){int n=Math.min(left,s.getCount());c.removeItem(i,n);left-=n;}}
            if(left<amount)c.setChanged();
            if(left==0)break;
        }
        invalidate();return amount-left;
    }
    void changed(ChestBlockEntity chest){chest.setChanged();invalidate();v.presentation.chest(chest);}
}
