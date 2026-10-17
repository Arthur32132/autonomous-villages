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
        if(!sameMaterial(step,actual))return false;
        for(var property:step.state().getProperties())if((Set.of("facing","half","axis","part","hinge","rotation").contains(property.getName())
                ||step.state().getBlock() instanceof SlabBlock&&property==SlabBlock.TYPE)
                &&actual.hasProperty(property)&&!step.state().getValue(property).equals(actual.getValue(property)))return false;
        return true;
    }
    static boolean sameMaterial(Step step,BlockState actual){
        if(step.state().is(BlockTags.PLANKS))return actual.is(BlockTags.PLANKS);
        if(step.state().is(BlockTags.WOODEN_DOORS))return actual.is(BlockTags.WOODEN_DOORS);
        if(step.state().is(BlockTags.WOODEN_STAIRS))return actual.is(BlockTags.WOODEN_STAIRS);
        if(step.state().is(BlockTags.WOODEN_SLABS))return actual.is(BlockTags.WOODEN_SLABS);
        if(step.state().is(BlockTags.WOODEN_FENCES))return actual.is(BlockTags.WOODEN_FENCES);
        if(step.state().is(BlockTags.LOGS))return actual.is(BlockTags.LOGS);
        if(masonry(step.state())&&actual.is(Blocks.COBBLESTONE))return true;
        if(stoneStairs(step.state())&&actual.is(Blocks.COBBLESTONE_STAIRS))return true;
        if(stoneSlab(step.state())&&actual.is(Blocks.COBBLESTONE_SLAB))return true;
        if(step.state().is(BlockTags.WALLS)&&actual.is(Blocks.COBBLESTONE_WALL))return true;
        if(step.state().is(BlockTags.WOODEN_TRAPDOORS))return actual.is(BlockTags.WOODEN_TRAPDOORS);
        if(step.state().is(BlockTags.WOODEN_PRESSURE_PLATES))return actual.is(BlockTags.WOODEN_PRESSURE_PLATES);
        if(step.state().is(BlockTags.WOODEN_BUTTONS))return actual.is(BlockTags.WOODEN_BUTTONS);
        if(step.state().getBlock() instanceof FenceGateBlock)return actual.getBlock() instanceof FenceGateBlock;
        if(step.state().getBlock() instanceof SignBlock)return actual.getBlock() instanceof SignBlock;
        if(step.state().getBlock() instanceof BedBlock)return actual.getBlock() instanceof BedBlock;
        return actual.is(step.state().getBlock());
    }
    static Step available(BotHandle b, Step step) {
        var accepts=material(step);
        int cost=cost(step);
        int slot=Supplies.find(b,s->s.is(step.item())&&b.inventory().count(s.getItem())>=cost);if(slot<0)slot=Supplies.find(b,s->accepts.test(s)&&b.inventory().count(s.getItem())>=cost);
        if(slot<0)return null;
        Item item=b.inventory().snapshot().slots().get(slot).getItem();
        BlockState state=step.state();
        if(item!=step.item()&&item instanceof BlockItem block){
            state=block.getBlock().defaultBlockState();
            if(step.state().getBlock() instanceof WallSignBlock){
                String id=net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString();
                var wall=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.parse(id.replace("_sign","_wall_sign")));
                if(wall instanceof WallSignBlock)state=wall.defaultBlockState();
            }
            for(var property:step.state().getProperties())if(state.hasProperty(property))state=copyProperty(state,step.state(),property);
        }
        return new Step(step.pos(),state,item);
    }
    static java.util.function.Predicate<ItemStack> material(Step step){
        return step.state().is(BlockTags.PLANKS)?s->s.is(net.minecraft.tags.ItemTags.PLANKS)
                :step.state().is(BlockTags.WOODEN_STAIRS)?s->s.is(net.minecraft.tags.ItemTags.WOODEN_STAIRS)
                :step.state().is(BlockTags.WOODEN_DOORS)?s->s.is(net.minecraft.tags.ItemTags.WOODEN_DOORS)
                :step.state().is(BlockTags.WOODEN_SLABS)?s->s.is(net.minecraft.tags.ItemTags.WOODEN_SLABS)
                :step.state().is(BlockTags.WOODEN_FENCES)?s->s.is(net.minecraft.tags.ItemTags.WOODEN_FENCES)
                :step.state().is(BlockTags.LOGS)?s->s.is(net.minecraft.tags.ItemTags.LOGS)
                :masonry(step.state())?s->s.is(step.item())||s.is(Items.COBBLESTONE)
                :stoneStairs(step.state())?s->s.is(step.item())||s.is(Items.COBBLESTONE_STAIRS)
                :stoneSlab(step.state())?s->s.is(step.item())||s.is(Items.COBBLESTONE_SLAB)
                :step.state().is(BlockTags.WALLS)?s->s.is(step.item())||s.is(Items.COBBLESTONE_WALL)
                :step.state().is(BlockTags.WOODEN_TRAPDOORS)?s->s.is(net.minecraft.tags.ItemTags.WOODEN_TRAPDOORS)
                :step.state().is(BlockTags.WOODEN_PRESSURE_PLATES)?s->s.is(net.minecraft.tags.ItemTags.WOODEN_PRESSURE_PLATES)
                :step.state().is(BlockTags.WOODEN_BUTTONS)?s->s.is(net.minecraft.tags.ItemTags.WOODEN_BUTTONS)
                :step.state().getBlock() instanceof FenceGateBlock?s->s.getItem() instanceof BlockItem item&&item.getBlock() instanceof FenceGateBlock
                :step.state().getBlock() instanceof SignBlock?s->s.getItem() instanceof SignItem
                :step.state().getBlock() instanceof BedBlock?s->s.is(net.minecraft.tags.ItemTags.BEDS):s->s.is(step.item());
    }
    static boolean masonry(BlockState s){return s.is(BlockTags.TERRACOTTA)||s.is(BlockTags.STONE_BRICKS)||s.is(Blocks.MOSSY_COBBLESTONE)
            ||s.is(Blocks.BRICKS)||s.is(Blocks.SANDSTONE)||s.is(Blocks.CUT_SANDSTONE)||s.is(Blocks.SMOOTH_SANDSTONE)
            ||s.is(Blocks.RED_SANDSTONE)||s.is(Blocks.CUT_RED_SANDSTONE)||s.is(Blocks.SMOOTH_RED_SANDSTONE)||s.is(Blocks.SNOW_BLOCK);}
    static boolean stoneStairs(BlockState s){return s.is(BlockTags.STAIRS)&&!s.is(BlockTags.WOODEN_STAIRS);}
    static boolean stoneSlab(BlockState s){return s.is(BlockTags.SLABS)&&!s.is(BlockTags.WOODEN_SLABS);}
    @SuppressWarnings({"rawtypes","unchecked"})
    private static BlockState copyProperty(BlockState to,BlockState from,Property property){return to.setValue(property,from.getValue(property));}
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
        var resident=v.workers.get(b.id());
        if(resident!=null&&!SettlementNeeds.mayPlace(v,resident,step))return false;
        BlockPos p=step.pos();
        if(!v.state.policy.allows(step.state().getBlock() instanceof SaplingBlock?"wood":"build",p))return false;
        if(step.state().getBlock() instanceof SaplingBlock&&!v.state.plantedTrees.contains(p.asLong())&&v.state.plantedTrees.size()>=512)return false;
        if(step.item()==Items.CRAFTING_TABLE&&!v.town.protects(p)&&v.state.temporaryWorkbenches.size()>=256)return false;
        if(step.item()==Items.CHEST&&v.state.storage.size()>=128&&!v.state.storage.contains(p.asLong()))return false;
        if(step.item()==Items.FURNACE&&v.state.furnaces.size()>=16&&!v.state.furnaces.contains(p.asLong()))return false;
        if (!v.canWork(p) || b.entity().getEyePosition().distanceToSqr(Vec3.atCenterOf(p))>20.25 || !clear(v.level.getBlockState(p))
                || b.inventory().count(step.item()) == 0 || !v.level.getWorldBorder().isWithinBounds(p)||!step.state().canSurvive(v.level,p)) return false;
        BlockPos second = null; BlockState secondState = null;
        if (step.state().getBlock() instanceof BedBlock) {
            second=p.relative(step.state().getValue(BedBlock.FACING)); secondState=step.state().setValue(BedBlock.PART,BedPart.HEAD);
            if (!v.level.getBlockState(p.below()).isFaceSturdy(v.level,p.below(),Direction.UP)
                    || !v.level.getBlockState(second.below()).isFaceSturdy(v.level,second.below(),Direction.UP)) return false;
        } else if (step.state().getBlock() instanceof DoorBlock) { second=p.above(); secondState=step.state().setValue(DoorBlock.HALF,DoubleBlockHalf.UPPER); }
        if (second != null && (!v.state.contains(second)||!v.level.getBlockState(second).isAir())) return false;
        if (intersectsActor(v,b,step) || !v.level.getEntities(b.entity(),new AABB(p),e->e.isAlive()&&e.isPickable()).isEmpty()) return false;
        int selected=Supplies.find(b,s->s.is(step.item()));if(selected>=0)b.inventory().select(selected);
        if (!Supplies.consume(b,step.item(),cost(step))) return false;
        // No callbacks or yielding between item debit and the two vanilla block writes.
        v.level.setBlock(p,step.state(),second == null ? 3 : 18);
        v.town.invalidate();
        if (second != null) { v.level.setBlock(second,secondState,18); v.level.updateNeighborsAt(p,step.state().getBlock()); v.level.updateNeighborsAt(second,secondState.getBlock()); }
        if (step.item()==Items.CHEST) v.state.storage.add(p.asLong());
        if (step.item()==Items.FURNACE) v.state.furnaces.add(p.asLong());
        if (step.item()==Items.CRAFTING_TABLE) {
            v.index.tables.add(p);
            if(!v.town.protects(p))v.state.temporaryWorkbenches.add(p.asLong());
        }
        if(v.level.getBlockEntity(p) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign){
            var text=sign.getFrontText().setMessage(0,net.minecraft.network.chat.Component.literal("СКЛАД"))
                    .setMessage(1,net.minecraft.network.chat.Component.literal(v.state.name))
                    .setMessage(2,net.minecraft.network.chat.Component.literal("Общие припасы"));
            sign.setText(text,true);sign.setChanged();v.level.sendBlockUpdated(p,step.state(),step.state(),3);
        }
        if (step.state().getBlock() instanceof SaplingBlock) {v.state.plantedTrees.add(p.asLong());v.state.reforestation.remove(p.asLong());ManagedLand.planted(v,p);}
        if(step.item()==Items.CHEST || step.item()==Items.FURNACE)v.runtime.save();
        b.entity().swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        var sound=step.state().getSoundType();v.level.playSound(null,p,sound.getPlaceSound(),net.minecraft.sounds.SoundSource.BLOCKS,.7f,1f);
        return true;
    }
    static boolean intersectsActor(Village v, BotHandle b, Step step) {
        return step.state().getCollisionShape(v.level,step.pos()).toAabbs().stream()
                .anyMatch(box -> box.move(step.pos()).intersects(b.entity().getBoundingBox()));
    }
    static int cost(Step step){return step.state().getBlock() instanceof SlabBlock&&step.state().getValue(SlabBlock.TYPE)==SlabType.DOUBLE?2:1;}
    static boolean clear(BlockState state){
        return state.isAir()||state.is(Blocks.SHORT_GRASS)||state.is(Blocks.TALL_GRASS)||state.is(Blocks.FERN)||state.is(Blocks.LARGE_FERN)||state.is(Blocks.SNOW);
    }
}
