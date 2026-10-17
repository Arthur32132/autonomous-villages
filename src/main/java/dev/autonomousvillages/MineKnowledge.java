package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/** Remember observed blocks and unsuccessful directions, not imaginary deposits. */
final class MineKnowledge {
    static ExpeditionState.Mine record(Village v,long entrance,int direction){
        var mine=v.state.mines.stream().filter(m->m.entrance==entrance&&m.direction==direction).findFirst().orElse(null);
        if(mine==null&&v.state.mines.size()<256){mine=new ExpeditionState.Mine();mine.entrance=entrance;mine.direction=direction;v.state.mines.add(mine);}
        return mine;
    }
    static ExpeditionState.Mine choose(List<ExpeditionState.Mine> mines,String resource){
        return mines.stream().filter(m->!m.blocked&&(!(resource.equals("iron")?m.iron:m.coal).isEmpty()||!m.exhausted&&m.length<64))
                .min(Comparator.<ExpeditionState.Mine>comparingInt(m->(resource.equals("iron")?m.iron:m.coal).isEmpty()?1:0)
                        .thenComparingInt(m->m.drySegments).thenComparingLong(m->m.entrance)).orElse(null);
    }
    static void observe(Village v,ExpeditionState e){
        var mine=record(v,e.target,e.direction);if(mine==null)return;
        int added=Math.max(0,e.workedSegments-mine.length);mine.length=Math.max(mine.length,e.workedSegments);
        mine.iron.removeIf(p->v.level.hasChunkAt(BlockPos.of(p))&&!iron(v,BlockPos.of(p)));
        mine.coal.removeIf(p->v.level.hasChunkAt(BlockPos.of(p))&&!coal(v,BlockPos.of(p)));
        for(long p:e.oreTargets){if(iron(v,BlockPos.of(p))&&mine.iron.size()<128)mine.iron.add(p);if(coal(v,BlockPos.of(p))&&mine.coal.size()<128)mine.coal.add(p);}
        mine.drySegments=(e.resource.equals("iron")?mine.iron:mine.coal).isEmpty()?Math.min(64,mine.drySegments+added):0;
        mine.exhausted=mine.length>=64||mine.drySegments>=24;mine.lastSurvey=v.level.getGameTime();
    }
    static boolean iron(Village v,BlockPos p){var s=v.level.getBlockState(p);return s.is(Blocks.IRON_ORE)||s.is(Blocks.DEEPSLATE_IRON_ORE);}
    static boolean coal(Village v,BlockPos p){var s=v.level.getBlockState(p);return s.is(Blocks.COAL_ORE)||s.is(Blocks.DEEPSLATE_COAL_ORE);}
}
