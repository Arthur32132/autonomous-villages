package dev.autonomousvillages;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/** Ore kinds the village actually wants. */
final class MineKnowledge {
    private MineKnowledge(){}
    static boolean iron(Village v,BlockPos p){var s=v.level.getBlockState(p);return s.is(Blocks.IRON_ORE)||s.is(Blocks.DEEPSLATE_IRON_ORE);}
    static boolean coal(Village v,BlockPos p){var s=v.level.getBlockState(p);return s.is(Blocks.COAL_ORE)||s.is(Blocks.DEEPSLATE_COAL_ORE);}
}
