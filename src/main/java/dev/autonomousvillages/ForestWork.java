package dev.autonomousvillages;

import java.util.*;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;

/** Felling state: from the first cut on, a tree's remaining logs stay on the persistent job (saved with the periodic snapshot) until the last is down. */
final class ForestWork {
    private ForestWork(){}
    static boolean felling(Village v,BlockPos base){return v.state.fellingTrees.contains(base.asLong());}
    /** Logs the village may fell here: outside every protected plan and no-felling zone. Leaves follow the same rule. */
    static boolean usable(Village v,BlockPos p){return !v.town.protects(p)&&v.state.policy.allows("wood",p);}

    /** Logs still to fell, lowest first: the saved list once felling started, otherwise a fresh survey of a natural tree. */
    static List<BlockPos> remaining(Village v,BlockPos base){
        var work=felling(v,base)?v.state.treeWork.get(base.asLong()):null;
        if(work==null)return survey(v,base);
        work.removeIf(p->{BlockPos q=BlockPos.of(p);return !usable(v,q)||v.level.hasChunkAt(q)&&!v.level.getBlockState(q).is(BlockTags.LOGS);});
        return work.stream().map(BlockPos::of).toList();
    }
    static BlockPos next(Village v,BlockPos base){var left=remaining(v,base);return left.isEmpty()?null:left.getFirst();}
    private static List<BlockPos> survey(Village v,BlockPos base){
        BlockPos seed=null;
        for(int dy=0;dy<32&&seed==null;dy++){BlockPos q=base.above(dy);if(!v.level.hasChunkAt(q))break;if(v.level.getBlockState(q).is(BlockTags.LOGS))seed=q;}
        if(seed==null||!felling(v,base)&&!VillageIndex.naturalLog(v.level,base))return List.of();
        return treeLogs(base,seed,q->v.level.hasChunkAt(q)&&v.level.getBlockState(q).is(BlockTags.LOGS),
                q->v.level.hasChunkAt(q)&&VillageIndex.crafted(v.level.getBlockState(q)),q->usable(v,q));
    }
    /** Logs of the tree at {@code base}, lowest first; one touching planks, stairs or a door makes it all a build, and nothing is returned. */
    static List<BlockPos> treeLogs(BlockPos base,BlockPos seed,Predicate<BlockPos> log,Predicate<BlockPos> crafted,Predicate<BlockPos> usable){
        var todo=new ArrayDeque<BlockPos>();var seen=new HashSet<BlockPos>();var found=new ArrayList<BlockPos>();todo.add(seed);
        while(!todo.isEmpty()&&found.size()<256){
            BlockPos p=todo.removeFirst();
            if(!seen.add(p)||p.getY()<base.getY()||p.getY()-base.getY()>40||Math.abs(p.getX()-base.getX())>10||Math.abs(p.getZ()-base.getZ())>10
                    ||!log.test(p)||!usable.test(p))continue;
            if(!VillageIndex.treeLog(p,log,crafted))return List.of();
            found.add(p);
            for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++)if(dx!=0||dy!=0||dz!=0)todo.add(p.offset(dx,dy,dz));
        }
        found.sort(Comparator.comparingInt(BlockPos::getY));
        return found;
    }
    /** The first cut is made: the tree is a persistent job until its last log is down. */
    static void started(Village v,BlockPos base,Collection<BlockPos> logs){
        v.state.fellingTrees.add(base.asLong());
        v.state.treeWork.put(base.asLong(),new ArrayList<>(logs.stream().map(BlockPos::asLong).toList()));
    }
    /** The tree is down or was none: a stump this village cut waits for a sapling (see {@link #stumps}). */
    static void complete(Village v,BlockPos base){
        v.state.treeWork.remove(base.asLong());
        if(v.state.fellingTrees.remove(base.asLong())&&v.level.hasChunkAt(base)&&Construction.clear(v.level.getBlockState(base)))v.state.reforestation.add(base.asLong());
    }
    /** Felling whose last logs someone else took ends here; a saved list without felling is left over from an older save. */
    static void prune(ServerLevel level,VillageState state){
        state.treeWork.keySet().removeIf(b->!state.fellingTrees.contains(b));
        state.fellingTrees.removeIf(b->{
            BlockPos base=BlockPos.of(b);
            if(!level.hasChunkAt(base))return false;
            var work=state.treeWork.get(b);
            List<BlockPos> logs=work!=null?work.stream().map(BlockPos::of).toList():java.util.stream.IntStream.range(0,32).mapToObj(base::above).toList();
            if(logs.stream().anyMatch(q->!level.hasChunkAt(q)||level.getBlockState(q).is(BlockTags.LOGS)))return false;
            state.treeWork.remove(b);
            if(Construction.clear(level.getBlockState(base)))state.reforestation.add(b);
            return true;
        });
    }
    /** Stumps near {@code near} still without a sapling, nearest first; one something else now stands on is forgotten. */
    static List<BlockPos> stumps(Village v,BlockPos near,int limit){
        var out=new ArrayList<BlockPos>();
        v.state.reforestation.removeIf(p->{
            BlockPos q=BlockPos.of(p);
            if(q.equals(near)||Math.abs(q.getX()-near.getX())>12||Math.abs(q.getZ()-near.getZ())>12||Math.abs(q.getY()-near.getY())>4||!v.level.hasChunkAt(q))return false;
            if(!Construction.clear(v.level.getBlockState(q))||!VillageIndex.naturalSoil(v.level.getBlockState(q.below())))return true;
            if(usable(v,q)&&v.canWork(q))out.add(q);
            return false;
        });
        out.sort(Comparator.comparingDouble(q->q.distSqr(near)));
        return List.copyOf(out.subList(0,Math.min(limit,out.size())));
    }
}
