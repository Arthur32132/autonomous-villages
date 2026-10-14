package dev.autonomousvillages;

import dev.navigationai.api.BotHandle;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.*;

/** A finite, resource-paid shelter blueprint; never overwrites an existing nonmatching block. */
final class Construction {
    record Step(BlockPos pos, BlockState state, Item item) { }
    static List<Step> shelter(BlockPos base) {
        List<Step> steps = new ArrayList<>();
        for (int x=0;x<5;x++) for (int z=0;z<5;z++) steps.add(new Step(base.offset(x,0,z),Blocks.OAK_PLANKS.defaultBlockState(),Items.OAK_PLANKS));
        steps.add(new Step(base.offset(2,0,-1),Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING,Direction.SOUTH),Items.OAK_STAIRS));
        for (int y=1;y<=2;y++) for (int x=0;x<5;x++) for (int z=0;z<5;z++) if ((x==0||z==0||x==4||z==4) && !(x==2&&z==0))
            steps.add(new Step(base.offset(x,y,z),Blocks.OAK_PLANKS.defaultBlockState(),Items.OAK_PLANKS));
        for (int x=0;x<5;x++) for (int z=0;z<5;z++) steps.add(new Step(base.offset(x,3,z),Blocks.OAK_PLANKS.defaultBlockState(),Items.OAK_PLANKS));
        steps.add(new Step(base.offset(2,1,0),Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING,Direction.SOUTH),Items.OAK_DOOR));
        steps.add(new Step(base.offset(1,1,2),Blocks.BED.white().defaultBlockState().setValue(BedBlock.FACING,Direction.SOUTH).setValue(BedBlock.PART,BedPart.FOOT),Items.BED.white()));
        steps.add(new Step(base.offset(3,1,2),Blocks.BED.white().defaultBlockState().setValue(BedBlock.FACING,Direction.SOUTH).setValue(BedBlock.PART,BedPart.FOOT),Items.BED.white()));
        steps.add(new Step(base.offset(2,1,3),Blocks.TORCH.defaultBlockState(),Items.TORCH));
        return steps;
    }
    static Step next(Village v) {
        if (v.state.shelter == null) return null;
        for (Step step : shelter(BlockPos.of(v.state.shelter))) {
            BlockState actual = v.level.getBlockState(step.pos());
            if (!matches(step,actual)) return step;
        }
        v.state.shelter = null; v.state.completedShelters++; v.runtime.save(); return null;
    }
    static boolean matches(Step step, BlockState actual) {
        if(step.item()==Items.OAK_PLANKS)return actual.is(BlockTags.PLANKS);
        if(step.item()==Items.OAK_DOOR)return actual.is(BlockTags.WOODEN_DOORS);
        if(step.item()==Items.OAK_STAIRS)return actual.is(BlockTags.WOODEN_STAIRS);
        if(step.state().getBlock() instanceof BedBlock)return actual.getBlock() instanceof BedBlock;
        return actual.is(step.state().getBlock());
    }
    static Step available(BotHandle b, Step step) {
        java.util.function.Predicate<ItemStack> accepts=step.item()==Items.OAK_PLANKS?s->s.is(net.minecraft.tags.ItemTags.PLANKS)
                :step.item()==Items.OAK_STAIRS?s->s.is(net.minecraft.tags.ItemTags.WOODEN_STAIRS)
                :step.item()==Items.OAK_DOOR?s->s.is(net.minecraft.tags.ItemTags.WOODEN_DOORS)
                :step.state().getBlock() instanceof BedBlock?s->s.is(net.minecraft.tags.ItemTags.BEDS):s->s.is(step.item());
        int slot=Supplies.find(b,accepts);
        if(slot<0)return null;
        Item item=b.inventory().snapshot().slots().get(slot).getItem();
        BlockState state=step.state();
        if(item instanceof BlockItem block){
            state=block.getBlock().defaultBlockState();
            if(state.getBlock() instanceof DoorBlock)state=state.setValue(DoorBlock.FACING,Direction.SOUTH);
            if(state.getBlock() instanceof StairBlock)state=state.setValue(StairBlock.FACING,Direction.SOUTH);
            if(state.getBlock() instanceof BedBlock)state=state.setValue(BedBlock.FACING,Direction.SOUTH).setValue(BedBlock.PART,BedPart.FOOT);
        }
        return new Step(step.pos(),state,item);
    }
    static boolean site(Village v, BlockPos base) {
        return siteProblem(v,base)==null;
    }
    static String siteProblem(Village v,BlockPos base){
        if (!v.state.contains(base.offset(-2,0,-2)) || !v.state.contains(base.offset(6,4,6))) return "outside claim "+v.state.center();
        for (int x=-1;x<=5;x++) for (int z=-1;z<=5;z++) {
            BlockPos p=base.offset(x,0,z);
            var ground=v.level.getBlockState(p.below());
            if (!VillageIndex.naturalSoil(ground) || !ground.isFaceSturdy(v.level,p.below(),Direction.UP)) return "ground "+p.below()+" "+ground;
            for (int y=0;y<4;y++) if (!clear(v.level.getBlockState(p.above(y)))) return "blocked "+p.above(y)+" "+v.level.getBlockState(p.above(y));
        }
        return null;
    }
    static boolean start(Village v) {
        if (v.state.shelter != null || v.state.completedShelters >= 8 || v.index.beds.size() >= Math.min(24,v.state.residents.size()+2)) return false;
        // Rotate bounded site search, don't clear houses/trees/player blocks to make space.
        int phase=(int)(v.level.getGameTime()/200);
        for (int attempt=0;attempt<12;attempt++) {
            int angle=(phase*12+attempt)*37;
            double radians=Math.toRadians(angle);
            int distance=10+((phase+attempt)%Math.max(1,v.state.radius-20));
            int x=v.state.x+(int)(Math.cos(radians)*distance),z=v.state.z+(int)(Math.sin(radians)*distance);
            int y=v.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z);
            BlockPos p=new BlockPos(x,y,z);
            if (site(v,p)) { v.state.shelter=p.asLong();v.runtime.save();return true; }
        }
        return false;
    }
    static boolean place(Village v, BotHandle b, Step step) {
        BlockPos p=step.pos();
        if (!v.state.contains(p) || b.entity().getEyePosition().distanceToSqr(Vec3.atCenterOf(p))>20.25 || !clear(v.level.getBlockState(p))
                || b.inventory().count(step.item()) == 0 || !v.level.getWorldBorder().isWithinBounds(p)) return false;
        BlockPos second = null; BlockState secondState = null;
        if (step.state().getBlock() instanceof BedBlock) {
            second=p.relative(step.state().getValue(BedBlock.FACING)); secondState=step.state().setValue(BedBlock.PART,BedPart.HEAD);
            if (!v.level.getBlockState(p.below()).isFaceSturdy(v.level,p.below(),Direction.UP)
                    || !v.level.getBlockState(second.below()).isFaceSturdy(v.level,second.below(),Direction.UP)) return false;
        } else if (step.state().getBlock() instanceof DoorBlock) { second=p.above(); secondState=step.state().setValue(DoorBlock.HALF,DoubleBlockHalf.UPPER); }
        if (second != null && (!v.state.contains(second)||!v.level.getBlockState(second).isAir())) return false;
        if (intersectsActor(v,b,step) || !v.level.getEntities(b.entity(),new AABB(p),e->e.isAlive()&&e.isPickable()).isEmpty()) return false;
        if (!Supplies.consume(b,step.item(),1)) return false;
        // No callbacks or yielding between item debit and the two vanilla block writes.
        v.level.setBlock(p,step.state(),second == null ? 3 : 18);
        if (second != null) { v.level.setBlock(second,secondState,18); v.level.updateNeighborsAt(p,step.state().getBlock()); v.level.updateNeighborsAt(second,secondState.getBlock()); }
        if (step.item()==Items.CHEST) v.state.storage.add(p.asLong());
        if (step.item()==Items.FURNACE) v.state.furnaces.add(p.asLong());
        if (step.item()==Items.CRAFTING_TABLE) v.index.tables.add(p);
        if (step.state().getBlock() instanceof SaplingBlock) v.state.plantedTrees.add(p.asLong());
        if(step.item()==Items.CHEST || step.item()==Items.FURNACE)v.runtime.save();
        return true;
    }
    static boolean intersectsActor(Village v, BotHandle b, Step step) {
        return step.state().getCollisionShape(v.level,step.pos()).toAabbs().stream()
                .anyMatch(box -> box.move(step.pos()).intersects(b.entity().getBoundingBox()));
    }
    static boolean clear(BlockState state){
        return state.isAir()||state.is(Blocks.SHORT_GRASS)||state.is(Blocks.TALL_GRASS)||state.is(Blocks.FERN)||state.is(Blocks.LARGE_FERN)||state.is(Blocks.SNOW);
    }
}
