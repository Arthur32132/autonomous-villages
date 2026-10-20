package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonElement;
import java.util.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class LifecycleSafetyTest {
    @Test void throwingPhaseDoesNotStopLaterPhasesAndLogsOncePerInterval(){
        var logged=new ArrayList<String>();long[] clock={0};var guard=new TickGuard(200,()->clock[0],(phase,error)->logged.add(phase+":"+error.getMessage()));
        var ran=new ArrayList<String>();
        for(clock[0]=0;clock[0]<500;clock[0]++){
            assertFalse(guard.run("survey",()->{throw new IllegalStateException("boom");}));
            assertTrue(guard.run("doors",()->ran.add("doors")));
        }
        assertEquals(500,ran.size());
        assertEquals(List.of("survey:boom","survey:boom","survey:boom"),logged); // ticks 0, 200, 400
    }
    @Test void phasesAreRateLimitedIndependentlyAndSupplierFallsBack(){
        var logged=new ArrayList<String>();long[] clock={0};var guard=new TickGuard(100,()->clock[0],(phase,error)->logged.add(phase));
        guard.run("a",()->{throw new RuntimeException();});guard.run("b",()->{throw new RuntimeException();});
        clock[0]=50;guard.run("a",()->{throw new RuntimeException();});clock[0]=100;guard.run("b",()->{throw new RuntimeException();});
        assertEquals(List.of("a","b","b"),logged);
        clock[0]=0;
        assertEquals("fallback",guard.run("c",()->{if(true)throw new IllegalArgumentException();return "value";},"fallback"));
        assertEquals("value",guard.run("c",()->"value","fallback"));
        assertEquals(List.of("a","b","b","c"),logged);
    }
    @Test void clockIsReadOnlyWhenSomethingFailedAndExplicitFailuresShareTheLimit(){
        var logged=new ArrayList<String>();int[] reads={0};var guard=new TickGuard(200,()->{reads[0]++;return 0;},(phase,error)->logged.add(phase));
        for(int i=0;i<1000;i++)assertTrue(guard.run("mayBreak",()->{}));
        assertEquals(0,reads[0],"a successful hot path never touches the clock");
        guard.failed("mayBreak",new IllegalStateException());guard.failed("mayBreak",new IllegalStateException()); // the caller's own catch, as the block-break callback does
        assertEquals(List.of("mayBreak"),logged);assertEquals(2,reads[0]);
    }
    @Test void dirtyFlagCoalescesIntoOneSnapshotPerInterval(){
        var saves=new SaveCoalescer(100);
        assertFalse(saves.take(10),"nothing dirty, nothing to write");
        for(int i=0;i<25;i++)saves.dirty(); // a gift, a death, a chest and a shaft segment in one tick
        assertTrue(saves.take(10));assertFalse(saves.isDirty());
        saves.dirty();
        for(long tick=11;tick<110;tick++)assertFalse(saves.take(tick),"still inside the interval at "+tick);
        assertTrue(saves.take(110));
        assertFalse(saves.take(300),"nothing changed since");
    }
    @Test void synchronousSaveClearsThePendingSnapshot(){
        var saves=new SaveCoalescer(100);
        saves.dirty();saves.clear(); // saveNow() wrote everything
        assertFalse(saves.take(1000));
        saves.dirty();assertTrue(saves.take(1000));
    }
    @Test void failedAttachIsRetriedAfterThePauseAndWarnedOnce(){
        var id=UUID.randomUUID();var retries=new AttachRetries(1200);
        assertFalse(retries.waiting(id,0));
        assertTrue(retries.failed(id,0),"first failure warns");
        assertTrue(retries.waiting(id,1199));assertFalse(retries.waiting(id,1200));
        assertFalse(retries.failed(id,1200),"the same streak stays quiet");
        assertTrue(retries.waiting(id,2399));
        retries.succeeded(id);
        assertFalse(retries.waiting(id,2400));assertTrue(retries.failed(id,5000),"a new streak warns again");
    }
    @Test void retryMapForgetsResidentsWhoLeft(){
        var gone=UUID.randomUUID();var stays=UUID.randomUUID();var retries=new AttachRetries(1200);
        retries.failed(gone,0);retries.failed(stays,0);
        retries.retain(Set.of(stays));
        assertEquals(1,retries.size());assertTrue(retries.waiting(stays,10));assertFalse(retries.waiting(gone,10));
        assertTrue(retries.failed(gone,10),"forgotten residents warn again when they return");
    }
    @Test void invalidVillageIsSkippedWhileTheOthersAreSaved(){
        var partition=new SaveCoalescer.Partition();var warned=new ArrayList<String>();
        var good=state("Good");var broken=state("Broken");broken.radius=0;
        var out=encode(partition,List.of(good,broken),warned);
        assertEquals(1,out.size());assertEquals("Good",out.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals(List.of("Broken omitted"),warned,"the log says the village was dropped, not kept");
        encode(partition,List.of(good,broken),warned);
        assertEquals(List.of("Broken omitted"),warned,"logged once per streak");
    }
    @Test void invalidVillageKeepsItsLastValidSnapshot(){
        var partition=new SaveCoalescer.Partition();var warned=new ArrayList<String>();
        var a=state("A");var b=state("B");
        var first=encode(partition,List.of(a,b),warned);
        assertEquals(2,first.size());
        b.radius=0; // corrupted in memory after a valid save
        var second=encode(partition,List.of(a,b),warned);
        assertEquals(2,second.size());assertEquals(24,second.get(1).getAsJsonObject().get("radius").getAsInt());
        assertEquals(List.of("B kept"),warned);
        b.radius=32; // repaired: the fresh state is written and a later failure warns again
        var third=encode(partition,List.of(a,b),warned);
        assertEquals(32,third.get(1).getAsJsonObject().get("radius").getAsInt());
        b.radius=0;encode(partition,List.of(a,b),warned);
        assertEquals(List.of("B kept","B kept"),warned);
    }
    @Test void loadedSnapshotStandsInBeforeTheFirstEncodeAfterARestart(){
        var partition=new SaveCoalescer.Partition();var warned=new ArrayList<String>();
        var a=state("A");var b=state("B");
        JsonElement loadedB=VillageRuntime.GSON.toJsonTree(b);partition.seed("B",loadedB); // what load() read from disk
        b.radius=0; // overflows right after the restart, before any successful save
        var out=encode(partition,List.of(a,b),warned);
        assertEquals(2,out.size(),"the village is not dropped from the file");assertEquals(loadedB,out.get(1));
        assertEquals(List.of("B kept"),warned);
    }
    @Test void releasedVillageDoesNotLingerInTheLastGoodSnapshots(){
        var partition=new SaveCoalescer.Partition();var warned=new ArrayList<String>();
        var a=state("A");var b=state("B");
        encode(partition,List.of(a,b),warned);
        JsonElement onlyA=encode(partition,List.of(a),warned);
        assertEquals(1,onlyA.getAsJsonArray().size());
        b.radius=0;
        var again=encode(partition,List.of(a,b),warned);
        assertEquals(1,again.size(),"a snapshot of a released village is not reused for a new one of the same name");
        assertEquals(List.of("B omitted"),warned);
    }
    private static com.google.gson.JsonArray encode(SaveCoalescer.Partition partition,List<VillageState> states,List<String> warned){
        return partition.encode(states,s->s.name,s->{s.validate();return VillageRuntime.GSON.toJsonTree(s);},(name,error,kept)->warned.add(name+(kept?" kept":" omitted")));
    }
    private static VillageState state(String name){return new VillageState(name,UUID.randomUUID(),new BlockPos(0,64,0),24);}
}
