package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class AmbienceTest {
    @BeforeAll static void bootstrapRegistries(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}

    @Test void hearthsSmokeFromEveningUntilMorning(){
        assertFalse(Ambience.hearthHours(6000));
        assertTrue(Ambience.hearthHours(11000));
        assertTrue(Ambience.hearthHours(18000));
        assertTrue(Ambience.hearthHours(24000*3+500));
        assertFalse(Ambience.hearthHours(24000*3+1500));
    }

    @Test void smokeRisesFromTheTopOfTheRoofNearestItsMiddle(){
        var b=new TownBuilding("house",BlockPos.ZERO);
        for(int x=0;x<5;x++)for(int z=0;z<5;z++)b.add(new BlockPos(x,0,z),Blocks.COBBLESTONE.defaultBlockState());
        for(int x=0;x<5;x++)b.add(new BlockPos(x,3,2),Blocks.COBBLESTONE.defaultBlockState());
        assertEquals(new BlockPos(2,3,2),Ambience.roofTop(b));
        assertNull(Ambience.roofTop(new TownBuilding("house",BlockPos.ZERO)));
    }

    @Test void theSquareIsARoundPatchAroundTheBell(){
        assertTrue(PathJob.inPlaza(0,0));
        assertTrue(PathJob.inPlaza(3,0));
        assertTrue(PathJob.inPlaza(3,1));
        assertFalse(PathJob.inPlaza(3,3));
        assertFalse(PathJob.inPlaza(4,0));
    }

    @Test void aLanternIsEightNuggetsAroundATorch(){
        var symbols=VillageCrafting.LANTERN.symbols();
        assertEquals(8,symbols.get('M'));
        assertEquals(1,symbols.get('T'));
        assertSame(VillageCrafting.TORCH_ITEM,VillageCrafting.LANTERN.ingredient('T'));
        assertSame(VillageCrafting.NUGGET,VillageCrafting.LANTERN.ingredient('M'));
        assertTrue(VillageCrafting.LANTERN.needsTable());
        assertFalse(VillageCrafting.NUGGETS.needsTable());
        assertSame(VillageCrafting.IRON,VillageCrafting.NUGGETS.ingredient('M'));
    }

    @Test void newSwitchesAreOnByDefault(){
        var config=new ModConfig();
        assertTrue(config.lanterns&&config.plaza&&config.ambience);
    }
}
