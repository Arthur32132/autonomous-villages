package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonElement;
import java.util.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class LifecycleSafetyTest {
    @Test void throwingPhaseDoesNotStopLaterPhasesAndLogsOncePerInterval(){
        var logged=new ArrayList<String>();var guard=new TickGuard(200,(phase,error)->logged.add(phase+":"+error.getMessage()));
        var ran=new ArrayList<String>();
        for(long tick=0;tick<500;tick++){
            assertFalse(guard.run("survey",tick,()->{throw new IllegalStateException("boom");}));
            assertTrue(guard.run("doors",tick,()->ran.add("doors")));
        }
        assertEquals(500,ran.size());
        assertEquals(List.of("survey:boom","survey:boom","survey:boom"),logged); // ticks 0, 200, 400
    }
    @Test void phasesAreRateLimitedIndependentlyAndSupplierFallsBack(){
        var logged=new ArrayList<String>();var guard=new TickGuard(100,(phase,error)->logged.add(phase));
        guard.run("a",0,()->{throw new RuntimeException();});guard.run("b",0,()->{throw new RuntimeException();});
        guard.run("a",50,()->{throw new RuntimeException();});guard.run("b",100,()->{throw new RuntimeException();});
        assertEquals(List.of("a","b","b"),logged);
        assertEquals("fallback",guard.run("c",0,()->{if(true)throw new IllegalArgumentException();return "value";},"fallback"));
        assertEquals("value",guard.run("c",0,()->"value","fallback"));
        assertEquals(List.of("a","b","b","c"),logged);
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
        var out=partition.encode(List.of(good,broken),s->s.name,s->{s.validate();return VillageRuntime.GSON.toJsonTree(s);},(name,error)->warned.add(name));
        assertEquals(1,out.size());assertEquals("Good",out.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals(List.of("Broken"),warned);
        partition.encode(List.of(good,broken),s->s.name,s->{s.validate();return VillageRuntime.GSON.toJsonTree(s);},(name,error)->warned.add(name));
        assertEquals(List.of("Broken"),warned,"logged once per streak");
    }
    @Test void invalidVillageKeepsItsLastValidSnapshot(){
        var partition=new SaveCoalescer.Partition();var warned=new ArrayList<String>();
        var a=state("A");var b=state("B");
        var first=partition.encode(List.of(a,b),s->s.name,s->{s.validate();return VillageRuntime.GSON.toJsonTree(s);},(name,error)->warned.add(name));
        assertEquals(2,first.size());
        b.radius=0; // corrupted in memory after a valid save
        var second=partition.encode(List.of(a,b),s->s.name,s->{s.validate();return VillageRuntime.GSON.toJsonTree(s);},(name,error)->warned.add(name));
        assertEquals(2,second.size());assertEquals(24,second.get(1).getAsJsonObject().get("radius").getAsInt());
        assertEquals(List.of("B"),warned);
        b.radius=32; // repaired: the fresh state is written and a later failure warns again
        var third=partition.encode(List.of(a,b),s->s.name,s->{s.validate();return VillageRuntime.GSON.toJsonTree(s);},(name,error)->warned.add(name));
        assertEquals(32,third.get(1).getAsJsonObject().get("radius").getAsInt());
        b.radius=0;partition.encode(List.of(a,b),s->s.name,s->{s.validate();return VillageRuntime.GSON.toJsonTree(s);},(name,error)->warned.add(name));
        assertEquals(List.of("B","B"),warned);
    }
    @Test void releasedVillageDoesNotLingerInTheLastGoodSnapshots(){
        var partition=new SaveCoalescer.Partition();
        var a=state("A");var b=state("B");
        partition.encode(List.of(a,b),s->s.name,s->{s.validate();return VillageRuntime.GSON.toJsonTree(s);},(name,error)->{});
        JsonElement onlyA=partition.encode(List.of(a),s->s.name,s->{s.validate();return VillageRuntime.GSON.toJsonTree(s);},(name,error)->{});
        assertEquals(1,onlyA.getAsJsonArray().size());
        b.radius=0;
        var again=partition.encode(List.of(a,b),s->s.name,s->{s.validate();return VillageRuntime.GSON.toJsonTree(s);},(name,error)->{});
        assertEquals(1,again.size(),"a snapshot of a released village is not reused for a new one of the same name");
    }
    private static VillageState state(String name){return new VillageState(name,UUID.randomUUID(),new BlockPos(0,64,0),24);}
}
