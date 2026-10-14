package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class VillageRulesTest {
    @Test void circularBoundaryIsInclusiveAndVerticallyBounded() {
        var s=new VillageState("Test",UUID.randomUUID(),new BlockPos(100,64,-100),24);
        assertTrue(s.contains(new BlockPos(124,64,-100)));
        assertFalse(s.contains(new BlockPos(124,64,-99)));
        assertFalse(s.contains(new BlockPos(100,113,-100)));
        assertTrue(s.contains(new BlockPos(100,112,-100)));
    }
    @Test void rejectsOverlapWithSafetyMargin() {
        var a=new VillageState("A",UUID.randomUUID(),BlockPos.ZERO,24);
        assertTrue(a.overlaps(new VillageState("B",UUID.randomUUID(),new BlockPos(56,0,0),24)));
        assertFalse(a.overlaps(new VillageState("B",UUID.randomUUID(),new BlockPos(57,0,0),24)));
    }
    @Test void rejectsMalformedMetadataAndOutsideStorage() {
        var s=new VillageState("A",UUID.randomUUID(),new BlockPos(0,64,0),24);
        s.validate();s.storage.add(new BlockPos(25,64,0).asLong());
        assertThrows(IllegalArgumentException.class,s::validate);
        s.storage.clear();s.radius=65;assertThrows(IllegalArgumentException.class,s::validate);
    }
    @Test void coordinatesDoNotOverflowIntoOtherTerritory() {
        var s=new VillageState("A",UUID.randomUUID(),new BlockPos(29_998_000,64,0),64);
        assertFalse(s.contains(new BlockPos(Integer.MIN_VALUE,64,0)));
    }
    @Test void rejectsInvalidPersistentWorksites() {
        var s=new VillageState("A",UUID.randomUUID(),new BlockPos(0,64,0),24);
        s.fellingTrees.add(null);assertThrows(IllegalArgumentException.class,s::validate);
        s.fellingTrees.clear();s.plantedCrops.add(new BlockPos(30,64,0).asLong());assertThrows(IllegalArgumentException.class,s::validate);
    }
    @Test void jobsAreExclusiveAndReentrantForOwner() {
        var r=new Reservations();UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        assertTrue(r.acquire(42,a,0));assertFalse(r.acquire(42,b,1));
        assertTrue(r.acquire(42,a,2));r.release(42,b);assertFalse(r.available(42,b,3));
        r.release(42,a);assertTrue(r.acquire(42,b,4));
    }
    @Test void abandonedJobsExpireAndLateReleaseCannotStealNewLease() {
        var r=new Reservations();UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        r.acquire(42,a,0);assertTrue(r.acquire(42,b,1200));r.release(a);
        assertFalse(r.available(42,a,1201));
    }
}
