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

/** Paid block placement and blueprint matching; never overwrites an existing nonmatching block. */
final class Construction {
    record Step(BlockPos pos, BlockState state, Item item) { }
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
        if((step.state().is(Blocks.COBBLESTONE)||masonry(step.state()))&&rubble(actual))return true;
        if(stoneStairs(step.state())&&(actual.is(Blocks.COBBLESTONE_STAIRS)||actual.is(Blocks.SANDSTONE_STAIRS)))return true;
        if(stoneSlab(step.state())&&(actual.is(Blocks.COBBLESTONE_SLAB)||actual.is(Blocks.SANDSTONE_SLAB)))return true;
        if(step.state().is(BlockTags.WALLS)&&(actual.is(Blocks.COBBLESTONE_WALL)||actual.is(Blocks.SANDSTONE_WALL)))return true;
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
        int slot=-1;
        // Rubble before cobblestone in a cobblestone plan: cobblestone is the one stone for furnaces and tools.
        if(step.state().is(Blocks.COBBLESTONE)||masonry(step.state()))slot=Supplies.find(b,s->VillageCrafting.RUBBLE.test(s)&&accepts.test(s)&&b.inventory().count(s.getItem())>=cost);
        if(slot<0)slot=Supplies.find(b,s->s.is(step.item())&&b.inventory().count(s.getItem())>=cost);
        if(slot<0)slot=Supplies.find(b,s->accepts.test(s)&&b.inventory().count(s.getItem())>=cost);
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
                :step.state().is(Blocks.COBBLESTONE)||masonry(step.state())?s->s.is(step.item())||VillageCrafting.COBBLE.test(s)||VillageCrafting.RUBBLE.test(s)
                :stoneStairs(step.state())?s->s.is(step.item())||s.is(Items.COBBLESTONE_STAIRS)||s.is(Items.SANDSTONE_STAIRS)
                :stoneSlab(step.state())?s->s.is(step.item())||s.is(Items.COBBLESTONE_SLAB)||s.is(Items.SANDSTONE_SLAB)
                :step.state().is(BlockTags.WALLS)?s->s.is(step.item())||s.is(Items.COBBLESTONE_WALL)||s.is(Items.SANDSTONE_WALL)
                :step.state().is(BlockTags.WOODEN_TRAPDOORS)?s->s.is(net.minecraft.tags.ItemTags.WOODEN_TRAPDOORS)
                :step.state().is(BlockTags.WOODEN_PRESSURE_PLATES)?s->s.is(net.minecraft.tags.ItemTags.WOODEN_PRESSURE_PLATES)
                :step.state().is(BlockTags.WOODEN_BUTTONS)?s->s.is(net.minecraft.tags.ItemTags.WOODEN_BUTTONS)
                :step.state().getBlock() instanceof FenceGateBlock?s->s.getItem() instanceof BlockItem item&&item.getBlock() instanceof FenceGateBlock
                :step.state().getBlock() instanceof SignBlock?s->s.getItem() instanceof SignItem
                :step.state().getBlock() instanceof BedBlock?s->s.is(net.minecraft.tags.ItemTags.BEDS):s->s.is(step.item());
    }
    static boolean masonry(BlockState s){return s.is(BlockTags.TERRACOTTA)||s.is(BlockTags.STONE_BRICKS)||s.is(Blocks.MOSSY_COBBLESTONE)
            ||s.is(Blocks.PACKED_ICE)||s.is(Blocks.BLUE_ICE)
            ||s.is(Blocks.BRICKS)||s.is(Blocks.SANDSTONE)||s.is(Blocks.CUT_SANDSTONE)||s.is(Blocks.SMOOTH_SANDSTONE)
            ||s.is(Blocks.RED_SANDSTONE)||s.is(Blocks.CUT_RED_SANDSTONE)||s.is(Blocks.SMOOTH_RED_SANDSTONE)||s.is(Blocks.SNOW_BLOCK);}
    /** Any plain stone a cobblestone plan accepts: mixed rubble makes a rustic foundation. */
    static boolean rubble(BlockState s){return s.is(Blocks.COBBLESTONE)||s.is(Blocks.COBBLED_DEEPSLATE)||s.is(Blocks.BLACKSTONE)||s.is(Blocks.ANDESITE)
            ||s.is(Blocks.DIORITE)||s.is(Blocks.GRANITE)||s.is(Blocks.TUFF)||s.is(Blocks.STONE)||s.is(Blocks.MOSSY_COBBLESTONE)||s.is(Blocks.DEEPSLATE)
            ||s.is(Blocks.SANDSTONE)||s.is(Blocks.RED_SANDSTONE)||s.is(Blocks.SMOOTH_SANDSTONE)||s.is(Blocks.CUT_SANDSTONE);}
    static boolean stoneStairs(BlockState s){return s.is(BlockTags.STAIRS)&&!s.is(BlockTags.WOODEN_STAIRS);}
    static boolean stoneSlab(BlockState s){return s.is(BlockTags.SLABS)&&!s.is(BlockTags.WOODEN_SLABS);}
    @SuppressWarnings({"rawtypes","unchecked"})
    private static BlockState copyProperty(BlockState to,BlockState from,Property property){return to.setValue(property,from.getValue(property));}
    static boolean place(Village v, BotHandle b, Step step) {
        BlockPos p=step.pos();
        if(!v.state.policy.allows(step.state().getBlock() instanceof SaplingBlock?"wood":"build",p))return false;
        if(step.state().getBlock() instanceof SaplingBlock&&!v.state.plantedTrees.contains(p.asLong())&&v.state.plantedTrees.size()>=512)return false;
        // canWork() also admits expedition ground; metadata outside validate()'s area would block every later save.
        if((step.state().getBlock() instanceof SaplingBlock||step.item()==Items.CHEST||step.item()==Items.FURNACE)
                &&!v.state.mayRecordPlacement(p,step.state().getBlock() instanceof SaplingBlock))return false;
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
        // Fences, walls, panes and stairs join their neighbours as if a player had placed them;
        // the joined shape is the one that must not cut into the builder.
        BlockState placed=step.state().getBlock() instanceof CrossCollisionBlock||step.state().getBlock() instanceof WallBlock||step.state().getBlock() instanceof StairBlock
                ?Block.updateFromNeighbourShapes(step.state(),v.level,p):step.state();
        if (intersectsActor(v,b,new Step(p,placed,step.item())) || !v.level.getEntities(b.entity(),new AABB(p),e->e.isAlive()&&e.isPickable()).isEmpty()) return false;
        int selected=Supplies.find(b,s->s.is(step.item()));if(selected>=0)b.inventory().select(selected);
        if (!Supplies.consume(b,step.item(),cost(step))) return false;
        // No callbacks or yielding between item debit and the two vanilla block writes.
        v.level.setBlock(p,placed,second == null ? 3 : 18);
        v.town.invalidate();
        if (second != null) { v.level.setBlock(second,secondState,18); v.level.updateNeighborsAt(p,step.state().getBlock()); v.level.updateNeighborsAt(second,secondState.getBlock()); }
        if (step.item()==Items.CHEST) {v.state.storage.add(p.asLong());v.stock.invalidate();v.moved();}
        if (step.item()==Items.FURNACE) v.state.furnaces.add(p.asLong());
        if (step.item()==Items.CRAFTING_TABLE) v.index.tables.add(p);
        if(v.level.getBlockEntity(p) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign){
            var text=sign.getFrontText().setMessage(0,net.minecraft.network.chat.Component.literal("СКЛАД"))
                    .setMessage(1,net.minecraft.network.chat.Component.literal(v.state.name))
                    .setMessage(2,net.minecraft.network.chat.Component.literal("Общие припасы"));
            sign.setText(text,true);sign.setChanged();v.level.sendBlockUpdated(p,step.state(),step.state(),3);
        }
        if (step.state().getBlock() instanceof SaplingBlock) {v.state.plantedTrees.add(p.asLong());v.state.reforestation.remove(p.asLong());}
        if(step.item()==Items.CHEST || step.item()==Items.FURNACE)v.runtime.save();
        b.entity().swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        var sound=step.state().getSoundType();v.level.playSound(null,p,sound.getPlaceSound(),net.minecraft.sounds.SoundSource.BLOCKS,.7f,1f);
        Ambience.puff(v,p,placed);
        return true;
    }
    static boolean intersectsActor(Village v, BotHandle b, Step step) {
        return step.state().getCollisionShape(v.level,step.pos()).toAabbs().stream()
                .anyMatch(box -> box.move(step.pos()).intersects(b.entity().getBoundingBox()));
    }
    static int cost(Step step){return step.state().getBlock() instanceof SlabBlock&&step.state().getValue(SlabBlock.TYPE)==SlabType.DOUBLE?2:1;}
    /** The steps in their order without the cells nobody reached lately; those wait for the memory to expire. */
    static <T> List<T> deferUnreachable(List<T> steps,java.util.function.Function<T,BlockPos> at,UnreachableMemory memory,long now){
        var out=new ArrayList<T>(steps.size());
        for(T step:steps)if(!memory.isUnreachable(at.apply(step),now))out.add(step);
        return out;
    }
    static boolean clear(BlockState state){
        return state.isAir()||state.is(Blocks.SHORT_GRASS)||state.is(Blocks.TALL_GRASS)||state.is(Blocks.FERN)||state.is(Blocks.LARGE_FERN)||state.is(Blocks.SNOW);
    }
}
