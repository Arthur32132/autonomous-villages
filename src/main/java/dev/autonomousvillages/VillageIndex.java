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
            stone = new LinkedHashSet<>(), ore = new LinkedHashSet<>(), tables = new LinkedHashSet<>(), grass = new LinkedHashSet<>(),sand=new LinkedHashSet<>(),farmSites=new LinkedHashSet<>(),
            hazards=new LinkedHashSet<>();
    List<BlockPos> beds = List.of();
    /** Ore seen next to the village staircase mine: reachable down the stairs at any depth. */
    final Set<BlockPos> shaftOre = new LinkedHashSet<>();
    private int cursor;
    private int localCursor;
    private List<BlockPos> residents=List.of();
    private static final List<BlockPos> NEAR;
    static{var offsets=new ArrayList<BlockPos>();for(int x=-24;x<=24;x++)for(int z=-24;z<=24;z++)offsets.add(new BlockPos(x,0,z));
        offsets.sort(Comparator.comparingDouble(p->p.distSqr(BlockPos.ZERO)));NEAR=List.copyOf(offsets);}
    void tick(ServerLevel level, VillageState state, long time) {
        for(long packed:state.fellingTrees)trees.add(BlockPos.of(packed));
        if(time%100==0||residents.isEmpty())residents=state.residents.stream().map(level::getEntity).filter(Objects::nonNull).map(e->e.blockPosition()).toList();
        int minX=state.bounds==null?state.x-state.radius:state.bounds.minX,minZ=state.bounds==null?state.z-state.radius:state.bounds.minZ;
        int width=state.bounds==null?state.radius*2+1:state.bounds.maxX-minX+1,depth=state.bounds==null?width:state.bounds.maxZ-minZ+1,total=width*depth;
        for (int n = 0; n < 12; n++) {
            int i = Math.floorMod(cursor,total), x=minX+i%width,z=minZ+i/width;
            if(n<8&&!residents.isEmpty()){
                int local=localCursor++;BlockPos r=residents.get(Math.floorMod(local,residents.size())),offset=NEAR.get(Math.floorMod(local/residents.size(),NEAR.size()));
                x=r.getX()+offset.getX();z=r.getZ()+offset.getZ();
            }else cursor++;
            BlockPos column = new BlockPos(x, state.y, z);
            // Trees count in the outskirts too; everything else only inside the footprint.
            boolean inside=state.contains(column);
            if (!state.nearby(column) || !level.hasChunkAt(column)) continue;
            int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            for (int y = Math.max(level.getMinY(), surface - 24); y <= Math.min(level.getMaxY()-1, surface + 5); y++) {
                BlockPos p = new BlockPos(x, y, z);
                BlockState b = level.getBlockState(p);
                update(trees, p, state.fellingTrees.contains(p.asLong()) || VillageIndex.naturalSoil(level.getBlockState(p.below())) && naturalLog(level, p));
                if (!inside || !state.contains(p)) continue;
                update(crops, p, b.getBlock() instanceof CropBlock);
                update(soil, p.above(), b.is(Blocks.FARMLAND));
                update(stone, p, b.is(Blocks.STONE) && exposed(level, p));
                update(sand,p,b.is(Blocks.SAND)&&exposed(level,p));
                // Only the top of a powder snow drift is a trap; what lies under it is covered once the top is packed.
                update(hazards,p,b.is(Blocks.POWDER_SNOW)&&!level.getBlockState(p.above()).is(Blocks.POWDER_SNOW)&&!level.getBlockState(p.above()).isRedstoneConductor(level,p.above()));
                update(ore, p, oreResource(b) && exposed(level, p));
                update(tables, p, b.is(Blocks.CRAFTING_TABLE));
                update(grass, p, b.is(Blocks.SHORT_GRASS) || b.is(Blocks.TALL_GRASS));
                if(b.is(Blocks.WATER)&&level.getBlockState(p.above()).isAir()&&level.canSeeSky(p.above())){
                    for(int dx=-4;dx<=4;dx++)for(int dz=-4;dz<=4;dz++)for(int dy:new int[]{-1,0}){
                        BlockPos bank=p.offset(dx,dy,dz),plant=bank.above();
                        if(state.contains(plant)&&surfaceFarmSite(level,plant)&&naturalSoil(level.getBlockState(bank))&&Construction.clear(level.getBlockState(plant)))farmSites.add(plant);
                    }
                }
            }
        }
        if (time % 200 == 0 || beds.isEmpty()) beds = level.getPoiManager()
                .getInRange(h -> h.is(PoiTypes.HOME), state.center(), state.radius+384, PoiManager.Occupancy.ANY)
                .map(PoiRecord::getPos).filter(state::contains).toList();
    }
    private static void update(Set<BlockPos> set, BlockPos pos, boolean keep) { if (keep) set.add(pos); else set.remove(pos); }
    static boolean surfaceFarmSite(ServerLevel level,BlockPos plant){
        return level.canSeeSky(plant)&&plant.getY()>=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,plant.getX(),plant.getZ())-1;
    }
    static boolean exposed(ServerLevel level, BlockPos p) {
        for (Direction d : Direction.values()) if (level.getBlockState(p.relative(d)).isAir()) return true;
        return false;
    }
    static boolean oreResource(BlockState b){return b.is(Blocks.IRON_ORE)||b.is(Blocks.DEEPSLATE_IRON_ORE)||b.is(Blocks.COAL_ORE)||b.is(Blocks.DEEPSLATE_COAL_ORE)
            ||b.is(Blocks.COPPER_ORE)||b.is(Blocks.DEEPSLATE_COPPER_ORE)||b.is(Blocks.GOLD_ORE)||b.is(Blocks.DEEPSLATE_GOLD_ORE)
            ||b.is(Blocks.DIAMOND_ORE)||b.is(Blocks.DEEPSLATE_DIAMOND_ORE)||b.is(Blocks.REDSTONE_ORE)||b.is(Blocks.DEEPSLATE_REDSTONE_ORE)
            ||b.is(Blocks.EMERALD_ORE)||b.is(Blocks.DEEPSLATE_EMERALD_ORE)||b.is(Blocks.LAPIS_ORE)||b.is(Blocks.DEEPSLATE_LAPIS_ORE);}
    static boolean naturalSoil(BlockState state){
        return state.is(BlockTags.DIRT)||state.is(BlockTags.GRASS_BLOCKS)||state.is(Blocks.PODZOL);
    }
    static boolean hazard(BlockState s){return s.is(Blocks.POWDER_SNOW)||s.is(Blocks.FIRE)||s.is(Blocks.SOUL_FIRE)||s.is(Blocks.LAVA)
            ||s.is(Blocks.MAGMA_BLOCK)||s.is(Blocks.CACTUS)||s.is(Blocks.SWEET_BERRY_BUSH)||s.is(Blocks.CAMPFIRE)||s.is(Blocks.SOUL_CAMPFIRE);}
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
