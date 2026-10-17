package dev.autonomousvillages;

import java.util.*;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;

/** Refillable physical input batches for a primary builder, served by the existing couriers. */
final class ConstructionSupply {
    static String prefix(Resident r){
        var bytes=java.nio.ByteBuffer.allocate(16).putLong(r.id.getMostSignificantBits()).putLong(r.id.getLeastSignificantBits()).array();
        return "b_"+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)+"_";
    }
    static boolean wood(net.minecraft.world.level.block.state.BlockState s){
        return s.is(BlockTags.PLANKS)||s.is(BlockTags.LOGS)||s.is(BlockTags.WOODEN_STAIRS)||s.is(BlockTags.WOODEN_DOORS)||s.is(BlockTags.WOODEN_FENCES)
                ||s.is(Blocks.CHEST)||s.is(Blocks.CRAFTING_TABLE)||s.getBlock() instanceof BedBlock||s.getBlock() instanceof FenceGateBlock;
    }
    static boolean awaiting(Resident r){
        return r.v.state.orders.stream().anyMatch(o->o.purpose.startsWith(prefix(r))&&DeliveryRuns.canServe(r.v,o));
    }
}
