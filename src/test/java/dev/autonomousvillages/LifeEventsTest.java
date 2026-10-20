package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
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

    @Test void onlyABuildingTheVillageFinishedItselfIsFeasted(){
        var finished=Collections.<TownBuilding>newSetFromMap(new IdentityHashMap<>());
        var buildings=new ArrayList<TownBuilding>();
        var old=new TownBuilding("house",BlockPos.ZERO);old.completed=true;buildings.add(old);
        var tower=new TownBuilding("tower",new BlockPos(5,64,5));buildings.add(tower);
        // The first look only takes stock of what already stands.
        assertNull(LifeEvents.fresh(finished,buildings,true));
        assertNull(LifeEvents.fresh(finished,buildings,false));
        // Houses captured from the generated village come in finished: no feast for them.
        var captured=new TownBuilding("native_house",new BlockPos(9,64,9));captured.completed=true;buildings.add(captured);
        assertNull(LifeEvents.fresh(finished,buildings,false));
        // The tower the village built itself is celebrated, once, even with an older building after it in the list.
        tower.completed=true;
        assertSame(tower,LifeEvents.fresh(finished,buildings,false));
        assertNull(LifeEvents.fresh(finished,buildings,false));
        var tavern=new TownBuilding("tavern",new BlockPos(20,64,20));tavern.completed=true;buildings.add(tavern);
        assertNull(LifeEvents.fresh(finished,buildings,false));
        // A building gone from the list is forgotten.
        buildings.remove(old);LifeEvents.fresh(finished,buildings,false);
        assertFalse(finished.contains(old));
        assertEquals(3,finished.size());
    }
}
