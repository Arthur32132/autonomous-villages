package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class UnreachableMemoryTest {
    static final BlockPos CHEST=new BlockPos(10,64,10),FAR=new BlockPos(30,64,10),HIGH=new BlockPos(12,70,10);
    static final UUID ANNA=new UUID(0,1),BORIS=new UUID(0,2);
    static final int HOUR=UnreachableMemory.TICKS,DAY=UnreachableMemory.STREAK;

    @Test void aFailedTargetIsSkippedForAnHourAndThenTriedAgain(){
        var m=new UnreachableMemory();
        assertFalse(m.isUnreachable(CHEST,100));
        m.remember(CHEST,100,ANNA);
        assertTrue(m.isUnreachable(CHEST,100));assertTrue(m.isUnreachable(CHEST,100+HOUR-1));
        assertFalse(m.isUnreachable(CHEST,100+HOUR));
        assertEquals(1,m.count(500));assertEquals(0,m.count(5000));
        assertFalse(m.isUnreachable(FAR,100));
    }
    @Test void failingAgainSoonAfterStaysOutTwiceAsLongUpToADay(){
        var m=new UnreachableMemory();
        m.remember(CHEST,0,ANNA);
        m.remember(CHEST,1200,BORIS); // second failure right after the first expired
        assertTrue(m.isUnreachable(CHEST,1200+2*HOUR-1));assertFalse(m.isUnreachable(CHEST,1200+2*HOUR));
        long now=1200;int strikes=2;
        // Tried again the moment each mark expires: every failure doubles the span up to the cap.
        for(int i=0;i<10;i++){now+=m.span(strikes);strikes=Math.min(strikes+1,UnreachableMemory.MAX_STRIKES);m.remember(CHEST,now,ANNA);}
        assertEquals(m.span(UnreachableMemory.MAX_STRIKES),(long)HOUR<<(UnreachableMemory.MAX_STRIKES-1));
        assertTrue(m.isUnreachable(CHEST,now+m.span(UnreachableMemory.MAX_STRIKES)-1));
        assertFalse(m.isUnreachable(CHEST,now+m.span(UnreachableMemory.MAX_STRIKES)));
        // A failure more than a day after the last one starts over with one hour.
        var fresh=new UnreachableMemory();fresh.remember(CHEST,0,ANNA);fresh.remember(CHEST,DAY+1,ANNA);
        assertEquals(1,fresh.strikes(CHEST));assertFalse(fresh.isUnreachable(CHEST,DAY+1+HOUR));
    }
    @Test void aNightBetweenTwoFailuresStillCountsAsARow(){
        // Soak 8675309: the cells failed, the mark expired, everyone slept, and the next try came 13000 ticks later.
        var m=new UnreachableMemory();
        m.remember(CHEST,0,ANNA);assertFalse(m.isUnreachable(CHEST,5000));
        m.remember(CHEST,13000,BORIS);
        assertEquals(2,m.strikes(CHEST));assertTrue(m.isUnreachable(CHEST,13000+2*HOUR-1));assertFalse(m.isUnreachable(CHEST,13000+2*HOUR));
        // Later strikes keep counting across a day as well, and across twice the span once that is longer.
        m.remember(CHEST,13000+DAY-1,ANNA);assertEquals(3,m.strikes(CHEST));
        long last=13000+DAY-1;for(int s=4;s<=UnreachableMemory.MAX_STRIKES;s++){last+=DAY-1;m.remember(CHEST,last,ANNA);assertEquals(s,m.strikes(CHEST));}
        long span=m.span(UnreachableMemory.MAX_STRIKES);
        m.remember(CHEST,last+2*span-1,BORIS);assertEquals(UnreachableMemory.MAX_STRIKES,m.strikes(CHEST));
        m.remember(CHEST,last+2*span-1+2*span,BORIS);assertEquals(1,m.strikes(CHEST));
    }
    @Test void reachingOrAStuckReporterClearsMarks(){
        var m=new UnreachableMemory();
        m.remember(CHEST,0,ANNA);m.remember(FAR,0,BORIS);m.remember(HIGH,0,ANNA);
        m.forget(CHEST);
        assertFalse(m.isUnreachable(CHEST,1));assertTrue(m.isUnreachable(FAR,1));
        m.forgetBy(ANNA);
        assertFalse(m.isUnreachable(HIGH,1));assertTrue(m.isUnreachable(FAR,1));
        m.forgetBy(null);assertTrue(m.isUnreachable(FAR,1));
        m.remember(CHEST,0,null);m.forgetBy(ANNA);assertTrue(m.isUnreachable(CHEST,1));
        // A mark two residents reported outlives one of them turning out to be stuck, but no longer hides the chest from the village.
        m.remember(FAR,10,ANNA);assertTrue(m.isUnreachable(FAR,11,2));
        m.forgetBy(ANNA);assertTrue(m.isUnreachable(FAR,11));assertFalse(m.isUnreachable(FAR,11,2));assertTrue(m.reported(FAR,11,BORIS));
    }
    @Test void oneResidentsFailureHidesAChestOnlyFromItself(){
        var m=new UnreachableMemory();
        m.remember(CHEST,0,ANNA);
        assertTrue(m.reported(CHEST,1,ANNA));assertFalse(m.reported(CHEST,1,BORIS));assertFalse(m.reported(CHEST,1,null));
        assertFalse(m.isUnreachable(CHEST,1,2)); // the village still uses the chest
        assertTrue(m.isUnreachable(CHEST,1,1));  // a lone resident is the whole village
        m.remember(CHEST,500,ANNA);assertFalse(m.isUnreachable(CHEST,501,2)); // the same resident again is still one report
        m.remember(CHEST,600,BORIS);assertTrue(m.isUnreachable(CHEST,601,2));
        assertFalse(m.reported(CHEST,m.span(3)+600,ANNA));
    }
    @Test void memoryStaysSmall(){
        var m=new UnreachableMemory(1000,4);
        for(int i=0;i<10;i++)m.remember(new BlockPos(i,64,0),i,ANNA);
        assertEquals(4,m.size());
        assertTrue(m.isUnreachable(new BlockPos(9,64,0),10));assertFalse(m.isUnreachable(new BlockPos(0,64,0),10));
        // Expired marks go before live ones.
        var e=new UnreachableMemory(100,2);
        e.remember(new BlockPos(1,0,0),0,ANNA);e.remember(new BlockPos(2,0,0),500,ANNA);e.remember(new BlockPos(3,0,0),510,ANNA);
        assertTrue(e.isUnreachable(new BlockPos(2,0,0),520));assertTrue(e.isUnreachable(new BlockPos(3,0,0),520));assertEquals(2,e.size());
    }
    @Test void onlyRouteEndingsAboutTheTargetCount(){
        for(String reason:List.of("unreachable","no_stand","stuck","stuck_no_progress","path_too_long","search_budget_exhausted","obstacle_requires_breaking",""))
            assertTrue(UnreachableMemory.blockedRoute(reason),reason);
        // The watchdog's cancel (crowding, a closed door, a slow server) says nothing about the target.
        for(String reason:List.of("planning_timeout","chunk_loading_timeout","search_queue_full","unloaded_frontier","dimension_unavailable","cancelled"))
            assertFalse(UnreachableMemory.blockedRoute(reason),reason);
    }
    @Test void chestChoiceSkipsUnreachableChestsAndFallsBackToTheNextOne(){
        var m=new UnreachableMemory();var from=new BlockPos(0,64,0);
        List<BlockPos> chests=List.of(FAR,CHEST,HIGH);Function<BlockPos,BlockPos> at=Function.identity();
        assertEquals(CHEST,VillageStock.pick(chests,at,from,Set.of(),m,0,2));
        m.remember(CHEST,0,ANNA);
        assertEquals(CHEST,VillageStock.pick(chests,at,from,Set.of(),m,0,2)); // one report: the village keeps the chest
        assertEquals(HIGH,VillageStock.pick(chests,at,from,Set.of(),m,0,1));  // the only resident: skipped at once
        m.remember(CHEST,0,BORIS);
        assertEquals(HIGH,VillageStock.pick(chests,at,from,Set.of(),m,0,2)); // height weighs more, still before the far one
        assertEquals(FAR,VillageStock.pick(chests,at,from,Set.of(HIGH),m,0,2));
        m.remember(FAR,0,BORIS);m.remember(FAR,0,ANNA);m.remember(HIGH,0,BORIS);m.remember(HIGH,0,ANNA);
        assertNull(VillageStock.pick(chests,at,from,Set.of(),m,0,2)); // the job ends instead of walking to a chest nobody reaches
        assertEquals(CHEST,VillageStock.pick(chests,at,from,Set.of(),m,2*HOUR,2));
    }
    @Test void constructionDefersUnreachableBlocksAndKeepsTheOthersInOrder(){
        var m=new UnreachableMemory();
        var a=new BlockPos(0,64,0);var b=new BlockPos(1,64,0);var c=new BlockPos(0,65,0);var d=new BlockPos(1,65,0);
        List<BlockPos> plan=List.of(a,b,c,d);Function<BlockPos,BlockPos> at=Function.identity();
        assertEquals(plan,Construction.deferUnreachable(plan,at,m,0));
        m.remember(b,0,ANNA);m.remember(c,0,BORIS);
        assertEquals(List.of(a,d),Construction.deferUnreachable(plan,at,m,0));
        m.remember(a,0,ANNA);m.remember(d,0,ANNA);
        assertTrue(Construction.deferUnreachable(plan,at,m,0).isEmpty()); // nothing affordable: the planner marks the project unpaid
        assertEquals(plan,Construction.deferUnreachable(plan,at,m,HOUR));
    }
    @Test void aSiteWhoseEveryCellFailedTwiceInARowCountsAsBlocked(){
        var m=new UnreachableMemory();
        var a=new BlockPos(0,64,0);var b=new BlockPos(1,64,0);var c=new BlockPos(0,65,0);var d=new BlockPos(1,65,0);
        List<BlockPos> cells=List.of(a,b,c,d);
        assertFalse(m.allBlocked(List.of(),0));
        for(var p:cells)m.remember(p,0,ANNA);
        assertFalse(m.allBlocked(cells,10)); // one failure each: the house is still being built
        // The marks expire, the night passes, the builders try again and fail at three of the four cells.
        for(var p:List.of(a,b,c))m.remember(p,13000,BORIS);
        assertFalse(m.allBlocked(cells,13001)); // d is open again: not blocked, it is tried
        m.remember(d,13500,BORIS);
        assertTrue(m.allBlocked(cells,13501)); // every cell failed twice in a row: the project waits, the next house may start
        assertTrue(m.allBlocked(List.of(a,d),13501));
        assertFalse(m.allBlocked(cells,13500+2*HOUR)); // expired: the builders try once more, then it stays out twice as long
        m.forget(d);assertFalse(m.allBlocked(cells,13501)); // someone reached a cell after all
    }
}
