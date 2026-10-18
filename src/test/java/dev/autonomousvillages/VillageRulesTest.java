package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class VillageRulesTest {
    @Test void localMineColumnsUseTheRectangleInsteadOfItsCircumscribedRadius(){
        var s=new VillageState("A",UUID.randomUUID(),new BlockPos(-321,73,-319),108);
        s.bounds=new VillageState.Bounds(-401,-392,-242,-246);
        for(var d:net.minecraft.core.Direction.Plane.HORIZONTAL){var p=s.localMineColumn(d);assertTrue(s.contains(p));assertTrue(s.contains(p.relative(d.getOpposite(),8)));}
        assertEquals(-250,s.localMineColumn(net.minecraft.core.Direction.EAST).getX());
    }
    @Test void structureFootprintIncludesFarCornersAndAllHousingElevations() {
        var s=new VillageState("Hill",UUID.randomUUID(),new BlockPos(-179,103,632),135);
        s.bounds=new VillageState.Bounds(-280,543,-78,722);
        assertTrue(s.contains(new BlockPos(-280,60,543)));
        assertTrue(s.contains(new BlockPos(-78,250,722)));
        assertFalse(s.contains(new BlockPos(-281,103,632)));
        assertFalse(s.contains(new BlockPos(-179,320,632)));
        s.validate();
    }
    @Test void rectangularTerritoriesCheckActualExtentsAndPersistGrowth() {
        var a=new VillageState("A",UUID.randomUUID(),BlockPos.ZERO,120);
        a.bounds=new VillageState.Bounds(-100,-20,100,20);
        var b=new VillageState("B",UUID.randomUUID(),new BlockPos(0,0,200),120);
        b.bounds=new VillageState.Bounds(-10,29,10,45);
        assertFalse(a.overlaps(b));b.bounds.minZ=28;assertTrue(a.overlaps(b));
        a.bounds.include(new BlockPos(140,64,0),24);
        assertTrue(a.contains(new BlockPos(164,64,0)));a.validate();
    }
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
    @Test void coordinatesDoNotOverflowIntoOtherTerritory() {
        var s=new VillageState("A",UUID.randomUUID(),new BlockPos(29_998_000,64,0),64);
        assertFalse(s.contains(new BlockPos(Integer.MIN_VALUE,64,0)));
    }
    @Test void staleMetadataIsDroppedInsteadOfRejectingTheWholeSave() {
        var s=new VillageState("A",UUID.randomUUID(),new BlockPos(0,64,0),24);
        long inside=new BlockPos(3,64,0).asLong(),outside=new BlockPos(30,64,0).asLong();
        s.storage.add(inside);s.storage.add(outside);s.furnaces.add(outside);s.lights.add(outside);s.lights.add(inside);
        s.plantedCrops.add(outside);s.fellingTrees.add(null);s.mineEntrance=outside;s.mineLength=999;
        s.compactMetadata();s.validate();
        assertEquals(java.util.Set.of(inside),s.storage);assertTrue(s.furnaces.isEmpty());assertEquals(java.util.Set.of(inside),s.lights);
        assertTrue(s.plantedCrops.isEmpty());assertTrue(s.fellingTrees.isEmpty());assertNull(s.mineEntrance);assertEquals(64,s.mineLength);
    }
    @Test void identityAndBudgetsStillRejectACorruptSave() {
        var s=new VillageState("A",UUID.randomUUID(),new BlockPos(0,64,0),24);
        s.validate();
        s.name="bad name!";assertThrows(IllegalArgumentException.class,s::validate);
        s.name="A";s.owner="not-a-uuid";assertThrows(IllegalArgumentException.class,s::validate);
        s.owner=UUID.randomUUID().toString();s.radius=5000;assertThrows(IllegalArgumentException.class,s::validate);
        s.radius=24;s.lastBirth=-1;assertThrows(IllegalArgumentException.class,s::validate);
    }
    @Test void missingCollectionsFromAHandEditedSaveAreRebuilt() {
        var s=VillageRuntime.GSON.fromJson("{\"name\":\"Old\",\"owner\":\""+UUID.randomUUID()+"\",\"x\":0,\"y\":64,\"z\":0,\"radius\":24,\"lights\":null,\"chronicle\":null,\"policy\":null}",VillageState.class);
        s.compactMetadata();s.validate();
        assertNotNull(s.lights);assertNotNull(s.chronicle.events);assertNotNull(s.policy.zones);
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
    @Test void restWindowCoversTwilightAndMorningWithoutRemovingDaytimeWork(){
        for(long clock:new long[]{0,999,11500,13000,23000,23999,24000})assertTrue(Worker.nightRestWindow(clock,false));
        for(long clock:new long[]{1000,3000,9000,11499,25000})assertFalse(Worker.nightRestWindow(clock,false));
        assertTrue(Worker.nightRestWindow(6000,true));
    }
    @Test void policyZonesBlockOnlyTheirActivity(){
        var p=new VillagePolicy();var z=new VillagePolicy.Zone();z.activity="wood";z.minX=0;z.maxX=10;z.minZ=0;z.maxZ=10;p.zones.add(z);
        assertFalse(p.allows("wood",new BlockPos(5,64,5)));assertTrue(p.allows("mine",new BlockPos(5,64,5)));
        assertTrue(p.allows("wood",new BlockPos(11,64,5)));assertTrue(p.allows("none",new BlockPos(5,64,5)));
        p.validate();z.activity="explode";assertThrows(IllegalArgumentException.class,p::validate);
    }
    @Test void chronicleKeepsTotalsAndOnlyTheLatestEvents(){
        var d=new Chronicle.Data();
        for(int i=0;i<100;i++)d.events.add("e"+i);
        d.totals.put("trees",3L);d.sanitize();
        assertEquals(60,d.events.size());assertEquals("e40",d.events.getFirst());assertEquals(3L,d.totals.get("trees"));
    }
    @Test void recipesKnowTheirGridAndIngredients(){
        assertFalse(VillageCrafting.PLANKS.needsTable());assertFalse(VillageCrafting.TABLE.needsTable());assertFalse(VillageCrafting.TORCH.needsTable());
        assertTrue(VillageCrafting.CHEST.needsTable());assertTrue(VillageCrafting.DOOR.needsTable());assertTrue(VillageCrafting.BREAD.needsTable());
        assertEquals(java.util.Map.of('P',8),VillageCrafting.CHEST.symbols());
        assertEquals(java.util.Map.of('M',3,'S',2),VillageCrafting.tool("pick","stone").symbols());
        assertEquals(java.util.Map.of('I',2),VillageCrafting.SHEARS.symbols());
        assertEquals(java.util.Map.of('M',3,'P',3),VillageCrafting.bed(null).symbols());
    }
    @Test void plannerToolFollowsTheRole(){
        assertEquals("pick",VillagePlanner.toolFamily(ResidentRoles.Role.MINER));
        assertEquals("hoe",VillagePlanner.toolFamily(ResidentRoles.Role.FARMER));
        assertEquals("sword",VillagePlanner.toolFamily(ResidentRoles.Role.GUARD));
        assertEquals("axe",VillagePlanner.toolFamily(ResidentRoles.Role.LOGGER));
        assertEquals("axe",VillagePlanner.toolFamily(null));
    }
    @Test void saveRoundTripKeepsLightsChronicleAndBirths(){
        var s=new VillageState("Rt",UUID.randomUUID(),new BlockPos(0,64,0),24);
        s.lights.add(new BlockPos(1,64,1).asLong());s.chronicle.totals.put("births",2L);s.chronicle.events.add("день 1: основано");s.lastBirth=4800;
        var save=new VillageRuntime.Save();save.villages=List.of(s);
        String json=VillageRuntime.GSON.toJson(save);
        var restored=VillageRuntime.GSON.fromJson(json,VillageRuntime.Save.class);
        assertEquals(VillageRuntime.Save.VERSION,restored.version);
        var r=restored.villages.getFirst();r.compactMetadata();r.validate();
        assertEquals(s.lights,r.lights);assertEquals(2L,r.chronicle.totals.get("births"));assertEquals(4800,r.lastBirth);
    }
}
