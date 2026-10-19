package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MaterialsTest {
    @BeforeAll static void bootstrapRegistries(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}

    @Test void everydayMaterialsAreWhatTheVillageMakesItself(){
        // A house short of these waits for the woodcutters; it does not let a second site start.
        // (Wooden blocks are matched by tags, which a unit test has no data pack to bind.)
        for(Block b:new Block[]{Blocks.TORCH,Blocks.WALL_TORCH,Blocks.COBBLESTONE,Blocks.COBBLESTONE_STAIRS,Blocks.COBBLESTONE_SLAB,Blocks.COBBLESTONE_WALL,
                Blocks.GLASS_PANE,Blocks.LADDER,Blocks.CHEST,Blocks.CRAFTING_TABLE,Blocks.FURNACE})
            assertTrue(Projects.EVERYDAY.contains(Projects.materialKey(b.defaultBlockState())),b.toString());
        // A bed needs wool, which needs shears and iron: that is worth starting the next house for.
        assertFalse(Projects.EVERYDAY.contains(Projects.materialKey(Blocks.BED.white().defaultBlockState())));
    }

    @Test void aPlacedWallMatchesItsPlanWhateverItJoins(){
        var plan=new Construction.Step(BlockPos.ZERO,Blocks.COBBLESTONE_WALL.defaultBlockState(),Items.COBBLESTONE_WALL);
        var joined=Blocks.COBBLESTONE_WALL.defaultBlockState().setValue(net.minecraft.world.level.block.WallBlock.UP,false)
                .setValue(net.minecraft.world.level.block.WallBlock.NORTH,net.minecraft.world.level.block.state.properties.WallSide.LOW);
        assertTrue(Construction.matches(plan,joined));
        assertFalse(Construction.matches(plan,Blocks.COBBLESTONE.defaultBlockState()));
    }

    @Test void cacheTicksStartLongAgoWithoutOverflow(){
        // now - Long.MIN_VALUE overflows to a negative number and reads as "just now".
        assertTrue(0L-Long.MIN_VALUE<0);
        assertTrue(0L-Village.LONG_AGO>200);
    }
}
