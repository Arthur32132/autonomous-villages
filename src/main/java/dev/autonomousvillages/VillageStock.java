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
    VillageStock(Village v){this.v=v;}

    /**
     * The chests a route currently reaches. One that no route reached a moment ago (Job.unreachable)
     * is left out for a while, so the planner neither counts its items nor its free slots: otherwise
     * jobs are planned for goods nobody can fetch and fail at once, over and over.
     */
    List<ChestBlockEntity> chests(){
        long now=v.level.getGameTime();
        if(now!=cachedTick){
            cachedTick=now;counts.clear();
            chests=v.chests().stream().filter(c->v.rejected.getOrDefault(c.getBlockPos().asLong(),0L)<=now).toList();
        }
        return chests;
    }
    void invalidate(){cachedTick=Long.MIN_VALUE;}

    /** Total matching items in shared chests; cached per tick and per predicate instance. */
    int count(Predicate<ItemStack> test){
        chests();
        return counts.computeIfAbsent(test,t->{int n=0;for(var c:chests)for(int i=0;i<c.getContainerSize();i++){var s=c.getItem(i);if(!s.isEmpty()&&t.test(s))n+=s.getCount();}return n;});
    }
    int freeSlots(){int n=0;for(var c:chests())for(int i=0;i<c.getContainerSize();i++)if(c.getItem(i).isEmpty())n++;return n;}

    /** Nearest chest to a position, preferring one that holds matching items when a test is given. */
    ChestBlockEntity nearest(BlockPos from,Predicate<ItemStack> holding){return nearest(from,holding,Set.of());}
    ChestBlockEntity nearest(BlockPos from,Predicate<ItemStack> holding,Set<BlockPos> excluded){
        return chests().stream().filter(c->!excluded.contains(c.getBlockPos())&&(holding==null||contains(c,holding)))
                .min(Comparator.comparingDouble(c->{double dy=c.getBlockPos().getY()-from.getY();return c.getBlockPos().distSqr(from)+15*dy*dy;})).orElse(null);
    }
    ChestBlockEntity nearestWithRoom(BlockPos from,Set<BlockPos> excluded){
        return chests().stream().filter(c->!excluded.contains(c.getBlockPos())).filter(c->{for(int i=0;i<c.getContainerSize();i++)if(c.getItem(i).isEmpty())return true;return false;})
                .min(Comparator.comparingDouble(c->c.getBlockPos().distSqr(from))).orElse(null);
    }
    static boolean contains(ChestBlockEntity c,Predicate<ItemStack> test){
        for(int i=0;i<c.getContainerSize();i++){var s=c.getItem(i);if(!s.isEmpty()&&test.test(s))return true;}
        return false;
    }
    /** Puts items straight into the shared chests; returns what did not fit. */
    ItemStack put(ItemStack stack){
        var left=stack.copy();
        for(var c:chests()){
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
