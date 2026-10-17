package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class VillageRulesTest {
    @Test void exactAtomicObservationIncludesCompletionAtTheScheduleBoundary(){
        assertTrue(Resident.crossedScheduleBoundary(11499,11500,0,false));
        assertFalse(Resident.crossedScheduleBoundary(11500,11501,0,false));
        assertTrue(Resident.crossedScheduleBoundary(8999,9001,0,false));
        assertFalse(Resident.crossedScheduleBoundary(8999,9001,1,false));
        assertTrue(Resident.crossedScheduleBoundary(25999,26000,0,true));
        assertFalse(Resident.crossedScheduleBoundary(25999,26000,0,false));
        assertFalse(Resident.crossedScheduleBoundary(11500,1000,0,true));
        assertFalse(Resident.crossedScheduleBoundary(1000,14000,0,true));
    }
    @Test void commonDestinationsNeverCompeteWithExclusiveResourceJobs(){
        for(String job:java.util.List.of("home","native_work","storage","table","air","escape","farm_watch","supply","pickup","deliver"))assertFalse(Resident.exclusiveJob(job),job);
        for(String job:java.util.List.of("wood","stone","ore","harvest","build","renovate","shear"))assertTrue(Resident.exclusiveJob(job),job);
    }
    @Test void treeAtVillageBorderKeepsItsExteriorBranchesRegistered(){
        var s=new VillageState("A",UUID.randomUUID(),new BlockPos(0,64,0),24);s.bounds=new VillageState.Bounds(-24,-24,24,24);
        long base=new BlockPos(24,64,0).asLong(),branch=new BlockPos(25,70,0).asLong();
        assertTrue(s.rememberWorksite(base));s.fellingTrees.add(base);s.treeWork.put(base,java.util.List.of(base,branch));
        for(int i=0;i<600;i++)assertTrue(s.rememberWorksite(new BlockPos(1000+i*100,70,0).asLong()));
        s.compactMetadata();s.validate();assertTrue(s.worksites.contains(base));assertTrue(s.recordedWork(BlockPos.of(branch)));
    }
    @Test void worksiteHistoryStaysBoundedAndRetainsAnActiveRemoteTree(){
        var s=new VillageState("A",UUID.randomUUID(),new BlockPos(0,64,0),24);
        s.bounds=new VillageState.Bounds(-24,-24,24,24);
        long active=new BlockPos(1000,70,0).asLong();assertTrue(s.rememberWorksite(active));s.fellingTrees.add(active);
        for(int i=0;i<5000;i++)assertTrue(s.rememberWorksite(new BlockPos(2000+i*100,70,0).asLong()));
        assertEquals(256,s.worksites.size());assertTrue(s.worksites.contains(active));
        for(int i=0;i<3000;i++){s.plantedCrops.add(new BlockPos(i%20,64,i/20%20).asLong());s.reforestation.add((long)i);}
        s.compactMetadata();s.validate();assertTrue(s.reforestation.size()<=2048);
        var restored=VillageRuntime.GSON.fromJson(VillageRuntime.GSON.toJson(s),VillageState.class);restored.validate();assertEquals(s.worksites,restored.worksites);
    }
    @Test void fullActiveWorksiteBudgetRefusesNewWorkWithoutLosingIntent(){
        var s=new VillageState("A",UUID.randomUUID(),new BlockPos(0,64,0),24);
        for(int i=0;i<256;i++){long p=new BlockPos(1000+i*200,70,0).asLong();assertTrue(s.rememberWorksite(p));var m=new ExpeditionState.Mine();m.entrance=p;s.mines.add(m);}
        assertFalse(s.rememberWorksite(new BlockPos(100000,70,0).asLong()));s.validate();
    }
    @Test void completeSiteIncludesTheNegativePorchAndRoofMargins(){
        var s=new VillageState("A",UUID.randomUUID(),new BlockPos(0,64,0),24);s.bounds=new VillageState.Bounds(-24,-24,24,24);
        assertFalse(TownPlanner.siteWithinBounds(s,new BlockPos(-24,64,-24),7,7));
        assertTrue(TownPlanner.siteWithinBounds(s,new BlockPos(-22,64,-20),7,7));
        assertFalse(TownPlanner.siteWithinBounds(s,new BlockPos(20,64,20),7,7));
        assertEquals(257L*257,VillageState.footprintChunks(new VillageState.Bounds(0,0,4096,4096)));
        assertTrue(VillageState.footprintChunks(s.bounds)<=VillageState.MAX_TICKET_CHUNKS);
    }
    @Test void localMineColumnsUseTheRectangleInsteadOfItsCircumscribedRadius(){
        var s=new VillageState("A",UUID.randomUUID(),new BlockPos(-321,73,-319),108);
        s.bounds=new VillageState.Bounds(-401,-392,-242,-246);
        for(var d:net.minecraft.core.Direction.Plane.HORIZONTAL){var p=s.localMineColumn(d);assertTrue(s.contains(p));assertTrue(s.contains(p.relative(d.getOpposite(),8)));}
        assertEquals(-250,s.localMineColumn(net.minecraft.core.Direction.EAST).getX());
    }
    @Test void knownLegacyMineReferenceRecoversWithoutLosingExcavatedWorksite(){
        var s=new VillageState("A",UUID.randomUUID(),new BlockPos(-321,73,-319),108);s.bounds=new VillageState.Bounds(-401,-392,-242,-246);
        long entry=new BlockPos(-221,81,-319).asLong();s.mineEntrance=entry;s.mineDirection=net.minecraft.core.Direction.WEST.get2DDataValue();s.mineLength=8;
        var id=UUID.randomUUID();s.residents.add(id);s.residentPositions.put(id,new BlockPos(-540,67,-270).asLong());
        var gson=new com.google.gson.Gson();var restored=gson.fromJson(gson.toJson(s),VillageState.class);
        assertTrue(restored.migrateLegacyMineReference());restored.validate();assertNull(restored.mineEntrance);assertEquals(0,restored.mineLength);
        assertEquals(1,restored.mines.size());assertEquals(entry,restored.mines.getFirst().entrance);assertEquals(8,restored.mines.getFirst().length);
        assertTrue(restored.worksites.contains(entry));assertEquals(s.residentPositions,restored.residentPositions);assertFalse(restored.migrateLegacyMineReference());
    }
    @Test void unfinishedLegacySurveyRecoversButUnrelatedInvalidMineIsRejected(){
        var s=new VillageState("A",UUID.randomUUID(),new BlockPos(-321,73,-319),108);s.bounds=new VillageState.Bounds(-401,-392,-242,-246);
        s.mineEntrance=new BlockPos(-221,81,-319).asLong();assertTrue(s.migrateLegacyMineReference());s.validate();assertTrue(s.mines.isEmpty());
        s.mineEntrance=new BlockPos(9000,70,9000).asLong();assertFalse(s.migrateLegacyMineReference());assertThrows(IllegalArgumentException.class,s::validate);
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
    @Test void recordedForestWorkSurvivesOutsideResidentialFootprint() {
        var s=new VillageState("A",UUID.randomUUID(),new BlockPos(0,64,0),24);
        s.bounds=new VillageState.Bounds(-24,-24,24,24);
        long base=new BlockPos(200,70,0).asLong();
        s.fellingTrees.add(base);assertThrows(IllegalArgumentException.class,s::validate);
        s.worksites.add(base);s.treeWork.put(base,java.util.List.of(base,new BlockPos(204,75,0).asLong()));s.validate();
        assertTrue(s.recordedWork(new BlockPos(204,75,0)));
        assertFalse(s.contains(new BlockPos(204,75,0)));
        assertFalse(s.recordedWork(new BlockPos(281,75,0)));
    }
    @Test void invalidExpeditionCannotDuplicatePartyMembersOrInventNegativeCargoCounters() {
        var e=new ExpeditionState();UUID id=UUID.randomUUID();e.team.add(id);e.validate();
        e.team.add(id);assertThrows(IllegalArgumentException.class,e::validate);
        e.team.removeLast();e.brokenBlocks=-1;assertThrows(IllegalArgumentException.class,e::validate);
        e.brokenBlocks=0;e.phase=null;assertThrows(IllegalArgumentException.class,e::validate);
    }
    @Test void returnTrailsAreBoundedAndRemainAttachedToTheirActualParty(){
        var e=new ExpeditionState();UUID id=UUID.randomUUID();e.team.add(id);
        e.returnTrails.put(id,new java.util.ArrayList<>(java.util.List.of(new BlockPos(10,64,12).asLong())));e.validate();
        String json=VillageRuntime.GSON.toJson(e);var restored=VillageRuntime.GSON.fromJson(json,ExpeditionState.class);restored.validate();
        assertEquals(e.returnTrails,restored.returnTrails);
        restored.returnTrails.put(UUID.randomUUID(),new java.util.ArrayList<>());assertThrows(IllegalArgumentException.class,restored::validate);
        restored=VillageRuntime.GSON.fromJson(json,ExpeditionState.class);
        while(restored.returnTrails.get(id).size()<=256)restored.returnTrails.get(id).add(0L);
        assertThrows(IllegalArgumentException.class,restored::validate);
        var legacy=VillageRuntime.GSON.fromJson("{\"purpose\":\"mine\",\"phase\":\"return\",\"team\":[]}",ExpeditionState.class);legacy.validate();
        assertTrue(legacy.returnTrails.isEmpty());
    }
    @Test void observedReturnTrailErasesLoopsAndKeepsItsBound(){
        var e=new ExpeditionState();UUID id=UUID.randomUUID();e.team.add(id);
        for(int x:new int[]{0,4,8,12,8})e.recordReturnPoint(id,new BlockPos(x,64,0));
        assertEquals(java.util.List.of(new BlockPos(0,64,0).asLong(),new BlockPos(4,64,0).asLong(),new BlockPos(8,64,0).asLong()),e.returnTrails.get(id));
        for(int x=12;x<1200;x+=4)e.recordReturnPoint(id,new BlockPos(x,64,0));
        assertEquals(256,e.returnTrails.get(id).size());e.validate();
    }
    @Test void departureWindowDoesNotSendAWaitingPartyOutAtNight(){
        assertTrue(ExpeditionManager.departureWindow(1000,false));assertTrue(ExpeditionManager.departureWindow(8000,false));
        assertFalse(ExpeditionManager.departureWindow(8001,false));assertFalse(ExpeditionManager.departureWindow(14000,true));
        assertFalse(ExpeditionManager.departureWindow(1000,true));assertFalse(ExpeditionManager.departureWindow(24001,false));
        assertFalse(ExpeditionManager.departureWindow(23500,false));assertTrue(ExpeditionManager.departureWindow(25000,false));
        var state=new ExpeditionState();state.phase="shelter";state.validate();
    }
    @Test void invalidDeliveryMetadataCannotPretendToVerifyFutureMiningCargo(){
        var s=new VillageState("A",UUID.randomUUID(),new BlockPos(0,64,0),24);
        s.mineralDeposits.put(UUID.randomUUID(),-1L);assertThrows(IllegalArgumentException.class,s::validate);
        s.mineralDeposits.clear();var e=new ExpeditionState();var id=UUID.randomUUID();e.team.add(id);e.unloadedMembers.add(UUID.randomUUID());
        assertThrows(IllegalArgumentException.class,e::validate);e.unloadedMembers.clear();e.unloadedMembers.add(id);e.validate();
    }
    @Test void oreWorkResumesWithItsRealTargetsAndRejectsMalformedProgress(){
        var gson=new com.google.gson.Gson();var old=gson.fromJson("{\"purpose\":\"mine\",\"phase\":\"prepare\"}",ExpeditionState.class);
        old.validate();assertNotNull(old.oreTargets);assertFalse(old.dug);
        old.length=64;old.dug=true;old.workedSegments=52;old.oreTargets.add(new BlockPos(20,16,30).asLong());
        var restored=gson.fromJson(gson.toJson(old),ExpeditionState.class);restored.validate();assertEquals(old.oreTargets,restored.oreTargets);assertEquals(52,restored.workedSegments);
        restored.oreTargets.add(null);assertThrows(IllegalArgumentException.class,restored::validate);
        restored.oreTargets.clear();restored.workedSegments=65;assertThrows(IllegalArgumentException.class,restored::validate);
    }
    @Test void travellerPositionSurvivesOutsideFootprintAndLegacySavesDefaultToEmpty(){
        var gson=new com.google.gson.Gson();var state=new VillageState("A",UUID.randomUUID(),new BlockPos(0,64,0),24);
        var id=UUID.randomUUID();state.residents.add(id);state.residentPositions.put(id,new BlockPos(400,70,-220).asLong());
        var restored=gson.fromJson(gson.toJson(state),VillageState.class);restored.validate();assertEquals(state.residentPositions,restored.residentPositions);
        var legacy=gson.fromJson("{\"name\":\"A\",\"owner\":\""+id+"\",\"radius\":24}",VillageState.class);legacy.validate();assertTrue(legacy.residentPositions.isEmpty());
        restored.residentPositions.put(id,null);assertThrows(IllegalArgumentException.class,restored::validate);
    }
    @Test void knownPasturesSurviveRestartWithoutRequiringVillageTerritory(){
        var gson=new com.google.gson.Gson();var s=new VillageState("A",UUID.randomUUID(),new BlockPos(0,64,0),24);
        s.sheepPastures.add(new BlockPos(300,70,0).asLong());s.validate();
        var restored=gson.fromJson(gson.toJson(s),VillageState.class);restored.validate();assertEquals(s.sheepPastures,restored.sheepPastures);
        assertEquals(0,restored.woolScouts);restored.woolScouts=-1;assertThrows(IllegalArgumentException.class,restored::validate);restored.woolScouts=0;
        restored.sheepPastures.add(null);assertThrows(IllegalArgumentException.class,restored::validate);
        restored.sheepPastures.clear();for(int i=0;i<9;i++)restored.sheepPastures.add(new BlockPos(i*32,70,0).asLong());assertThrows(IllegalArgumentException.class,restored::validate);
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
    @Test void ironSurveyUsesEquippedEarlyTripsWithinBlockAndDurabilityBudgets(){
        assertEquals(32,ExpeditionManager.plannedMineLength(80,8,131,true,1000));
        assertEquals(16,ExpeditionManager.plannedMineLength(80,8,79,true,1000));
        assertEquals(16,ExpeditionManager.plannedMineLength(80,8,131,true,7000));
        assertEquals(16,ExpeditionManager.plannedMineLength(80,8,131,false,1000));
        assertEquals(32,ExpeditionManager.plannedMineLength(50,8,131,true,1000));
        assertEquals(64,ExpeditionManager.plannedMineLength(80,48,131,true,1000));
        for(int completed=0;completed<=64;completed++){
            int next=ExpeditionManager.plannedMineLength(100,completed,80,true,1000);
            assertTrue(next>=completed&&next<=64&&next-completed<=24);
            assertTrue((next-completed)*3<=80-8);
        }
    }
    @Test void shelterWindowCoversTwilightAndMorningWithoutRemovingDaytimeWork(){
        for(long clock:new long[]{0,999,11500,13000,23000,23999,24000})assertTrue(Resident.nightRestWindow(clock,false));
        for(long clock:new long[]{1000,3000,9000,11499,25000})assertFalse(Resident.nightRestWindow(clock,false));
        assertTrue(Resident.nightRestWindow(6000,true));
    }
    @Test void retreatCorridorRejectsOnlyAnEnemyAcrossTheStraightApproach(){
        Vec3 from=new Vec3(0,64,0),to=new Vec3(30,64,0);
        assertTrue(Resident.approachExposed(from,to,new Vec3(10,64,0)));
        assertTrue(Resident.approachExposed(from,to,new Vec3(10,64,3)));
        assertFalse(Resident.approachExposed(from,to,new Vec3(10,64,10)));
        assertFalse(Resident.approachExposed(from,to,new Vec3(-5,64,0)));
        assertFalse(Resident.approachExposed(from,to,new Vec3(36,64,0)));
        assertTrue(Resident.approachExposed(new Vec3(0,64,0),new Vec3(10,64,10),new Vec3(5,64,5)));
        assertFalse(Resident.approachExposed(new Vec3(0,64,0),new Vec3(10,64,10),new Vec3(5,64,-2)));
    }
    @Test void standExposureRejectsAnEnemyAcrossTheApproachOrACloserStand(){
        Vec3 from=new Vec3(0,64,0),enemy=new Vec3(8,64,3);
        assertTrue(Resident.standExposed(from,new BlockPos(16,64,2),enemy));
        assertFalse(Resident.standExposed(from,new BlockPos(-6,64,-4),enemy));
    }
    @Test void shelterOrderFiltersDistantUnknownAndEnemyAdjacentBeds(){
        Vec3 from=new Vec3(0,64,0),enemy=new Vec3(8,64,3);
        BlockPos near=new BlockPos(6,64,-3),far=new BlockPos(-4,64,6);
        assertEquals(List.of(far),Resident.orderedShelters(from,enemy,List.of(near,far),far::equals));
        assertEquals(List.of(near,far),Resident.orderedShelters(from,enemy,List.of(far,near),bed->true));
        assertTrue(Resident.orderedShelters(from,new Vec3(6,64,0),List.of(new BlockPos(3,64,0)),bed->true).isEmpty());
        assertTrue(Resident.orderedShelters(from,new Vec3(0,64,-14),List.of(new BlockPos(6,64,-3)),bed->false).isEmpty());
    }
    @Test void shelteredStandSkipsAnUnusableFirstHouseAndKeepsTheSecond(){
        Vec3 from=new Vec3(0,64,0),enemy=new Vec3(8,64,3);
        BlockPos near=new BlockPos(6,64,-3),far=new BlockPos(-4,64,6);
        List<BlockPos> beds=List.of(near,far);
        var chosen=Resident.shelteredStand(from,enemy,beds,bed->true,bed->bed.equals(near)?null:new BlockPos(-4,64,4));
        assertNotNull(chosen);
        assertEquals(far,chosen.bed());
        assertEquals(new BlockPos(-4,64,4),chosen.stand());
        var blocked=Resident.shelteredStand(from,enemy,beds,bed->true,bed->bed.equals(near)?new BlockPos(10,64,1):new BlockPos(-4,64,4));
        assertNotNull(blocked);
        assertEquals(far,blocked.bed());
        assertNull(Resident.shelteredStand(from,enemy,beds,bed->true,bed->null));
    }
    @Test void shelteredStandRejectsANearlyCurrentStandAndKeepsAFartherAlternative(){
        Vec3 from=new Vec3(0,64,0),enemy=new Vec3(0,64,-10);
        BlockPos near=new BlockPos(2,64,0),far=new BlockPos(12,64,0);
        assertNull(Resident.shelteredStand(from,enemy,List.of(near),bed->true,bed->bed));
        var chosen=Resident.shelteredStand(from,enemy,List.of(near,far),bed->true,bed->bed);
        assertNotNull(chosen);
        assertEquals(far,chosen.bed());
        assertEquals(far,chosen.stand());
    }
}
