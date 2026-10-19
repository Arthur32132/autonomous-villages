package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

class LifeEventsTest {
    @Test void aFairEverySeventhDayButNotOnTheFirst(){
        assertFalse(LifeEvents.fairDay(0));
        assertFalse(LifeEvents.fairDay(6));
        assertTrue(LifeEvents.fairDay(7));
        assertTrue(LifeEvents.fairDay(14));
        assertFalse(LifeEvents.fairDay(15));
    }

    @Test void theTuneStaysInTheNoteBlockRange(){
        for(int n:LifeEvents.TUNE)assertTrue(n>=-1&&n<=24);
        for(int n:LifeEvents.CRADLE)assertTrue(n>=-1&&n<=24);
        assertEquals(1f,LifeEvents.pitch(12),1e-6);
        assertEquals(2f,LifeEvents.pitch(24),1e-6);
        assertEquals(.5f,LifeEvents.pitch(0),1e-6);
    }

    @Test void theFeastIsHeldInTheMiddleOfTheHallAboveItsFloor(){
        var b=new TownBuilding("tavern",BlockPos.ZERO);
        for(int x=0;x<5;x++)for(int z=0;z<5;z++)b.add(new BlockPos(x,64,z),Blocks.COBBLESTONE.defaultBlockState());
        b.add(new BlockPos(2,68,2),Blocks.COBBLESTONE.defaultBlockState());
        assertEquals(new BlockPos(2,65,2),LifeEvents.middle(b));
        assertTrue(new ModConfig().lifeEvents);
        assertEquals(-1,new VillageState().fairDay);
    }
}
