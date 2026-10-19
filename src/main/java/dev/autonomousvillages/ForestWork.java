package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;

/** Connected branch logs stay on the persistent job, including across rest and server reload. */
final class ForestWork {
    static List<Long> remaining(Village v,BlockPos base){
        var work=v.state.treeWork.get(base.asLong());
        if(work==null){
            BlockPos seed=base;
            for(int dy=0;dy<32;dy++)if(v.level.getBlockState(base.above(dy)).is(BlockTags.LOGS)){seed=base.above(dy);break;}
            var todo=new ArrayDeque<BlockPos>();var seen=new HashSet<Long>();var found=new ArrayList<Long>();todo.add(seed);
            while(!todo.isEmpty()&&found.size()<256){
                BlockPos p=todo.removeFirst();if(!seen.add(p.asLong())||p.getY()<base.getY()||p.getY()-base.getY()>40
                        ||Math.abs(p.getX()-base.getX())>10||Math.abs(p.getZ()-base.getZ())>10||v.town.protects(p)||!v.state.policy.allows("wood",p)||!v.level.getBlockState(p).is(BlockTags.LOGS))continue;
                found.add(p.asLong());
                for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++)if(dx!=0||dy!=0||dz!=0)todo.add(p.offset(dx,dy,dz));
            }
            work=found;v.state.treeWork.put(base.asLong(),work);
        }
        work.removeIf(p->!v.level.getBlockState(BlockPos.of(p)).is(BlockTags.LOGS)||v.town.protects(BlockPos.of(p))||!v.state.policy.allows("wood",BlockPos.of(p)));
        work.sort(Comparator.comparingInt(p->BlockPos.of(p).getY()));return work;
    }
    static BlockPos next(Village v,BlockPos base){var left=remaining(v,base);return left.isEmpty()?null:BlockPos.of(left.getFirst());}
    static void complete(Village v,BlockPos base){v.state.treeWork.remove(base.asLong());v.state.fellingTrees.remove(base.asLong());
        if(Construction.clear(v.level.getBlockState(base)))v.state.reforestation.add(base.asLong());v.runtime.save();}
}
