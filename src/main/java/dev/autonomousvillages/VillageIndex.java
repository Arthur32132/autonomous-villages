package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.village.poi.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.state.BlockState;

/** Incremental loaded-only survey. At most 12 columns (360 blocks) per village/tick. */
final class VillageIndex {
    final Set<BlockPos> crops = new LinkedHashSet<>(), soil = new LinkedHashSet<>(), trees = new LinkedHashSet<>(),
            stone = new LinkedHashSet<>(), ore = new LinkedHashSet<>(), tables = new LinkedHashSet<>(), grass = new LinkedHashSet<>();
    List<BlockPos> beds = List.of();
    private int cursor;
    void tick(ServerLevel level, VillageState state, long time) {
        for(long packed:state.fellingTrees)trees.add(BlockPos.of(packed));
        int width = state.radius * 2 + 1, total = width * width;
        for (int n = 0; n < 12; n++) {
            int i = cursor++ % total, x = state.x + i % width - state.radius, z = state.z + i / width - state.radius;
            BlockPos column = new BlockPos(x, state.y, z);
            if (!state.contains(column) || !level.hasChunkAt(column)) continue;
            int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            for (int y = Math.max(level.getMinY(), surface - 24); y <= Math.min(level.getMaxY()-1, surface + 5); y++) {
                BlockPos p = new BlockPos(x, y, z); if (!state.contains(p)) continue;
                BlockState b = level.getBlockState(p);
                update(crops, p, b.getBlock() instanceof CropBlock);
                update(soil, p.above(), b.is(Blocks.FARMLAND));
                update(trees, p, state.fellingTrees.contains(p.asLong()) || VillageIndex.naturalSoil(level.getBlockState(p.below())) && naturalLog(level, p));
                update(stone, p, b.is(Blocks.STONE) && exposed(level, p));
                update(ore, p, (b.is(Blocks.IRON_ORE) || b.is(Blocks.COAL_ORE) || b.is(Blocks.DEEPSLATE_IRON_ORE) || b.is(Blocks.DEEPSLATE_COAL_ORE)) && exposed(level, p));
                update(tables, p, b.is(Blocks.CRAFTING_TABLE));
                update(grass, p, b.is(Blocks.SHORT_GRASS) || b.is(Blocks.TALL_GRASS));
            }
        }
        if (time % 200 == 0 || beds.isEmpty()) beds = level.getPoiManager()
                .getInRange(h -> h.is(PoiTypes.HOME), state.center(), state.radius, PoiManager.Occupancy.ANY)
                .map(PoiRecord::getPos).filter(state::contains).toList();
    }
    private static void update(Set<BlockPos> set, BlockPos pos, boolean keep) { if (keep) set.add(pos); else set.remove(pos); }
    static boolean exposed(ServerLevel level, BlockPos p) {
        for (Direction d : Direction.values()) if (level.getBlockState(p.relative(d)).isAir()) return true;
        return false;
    }
    static boolean naturalSoil(BlockState state){
        return state.is(BlockTags.DIRT)||state.is(BlockTags.GRASS_BLOCKS)||state.is(Blocks.PODZOL);
    }
    static boolean naturalLog(ServerLevel level, BlockPos p) {
        if (!level.getBlockState(p).is(BlockTags.LOGS)) return false;
        BlockPos base = p;
        for (int i = 0; i < 12 && level.getBlockState(base.below()).is(BlockTags.LOGS); i++) base = base.below();
        var ground = level.getBlockState(base.below());
        if (!VillageIndex.naturalSoil(ground) && !ground.is(Blocks.PODZOL)) return false;
        for (int dy = 1; dy <= 8; dy++) for (Direction d : Direction.Plane.HORIZONTAL)
            if (level.getBlockState(base.above(dy).relative(d, 2)).is(BlockTags.LEAVES)
                    || level.getBlockState(base.above(dy).relative(d)).is(BlockTags.LEAVES)) return true;
        return false;
    }
}
