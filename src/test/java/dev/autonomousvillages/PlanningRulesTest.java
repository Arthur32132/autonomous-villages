package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class PlanningRulesTest {
    @Test void competingFoodNeedsIncreaseTheBudgetWithoutConsumingSeedOrTimberReserves(){
        var normal=SettlementEconomy.budget(6,0,0,0,2);
        var busy=SettlementEconomy.budget(6,2,1,2,2);
        assertEquals(48,normal.food());assertEquals(78,busy.food());
        assertEquals(24,busy.seed());assertEquals(4,busy.breedingWheat());assertEquals(72,busy.timber());
        assertEquals(0,SettlementEconomy.budget(6,0,0,0,4).breedingWheat());
        assertEquals(24,SettlementEconomy.budget(1,0,0,0,0).food());
    }
    @Test void protectedColumnsApplyAtEveryDepthButDoNotForbidWalkingHome(){
        var policy=new VillagePolicy();var z=new VillagePolicy.Zone();z.activity="wood";z.minX=-2;z.maxX=2;z.minZ=-3;z.maxZ=3;policy.zones.add(z);policy.validate();
        assertFalse(policy.allows("wood",new BlockPos(2,200,3)));
        assertTrue(policy.allows("mine",new BlockPos(2,-30,3)));
        assertTrue(policy.allows("wood",new BlockPos(3,64,3)));
        z.activity="all";
        assertFalse(policy.allows("farm",new BlockPos(0,64,0)));
        assertTrue(policy.allows(Resident.activity("home"),new BlockPos(0,64,0)));
    }
    @Test void permissionCategoriesCoverBothNewDirectMutationsAndLibraryMining(){
        for(String job:List.of("harvest","hoe","plant","seeds","fill_bucket","irrigate","dig_irrigation"))assertEquals("farm",Resident.activity(job),job);
        for(String job:List.of("grade","build","renovate"))assertEquals("build",Resident.activity(job),job);
        for(String job:List.of("stone","ore","mine","sand"))assertEquals("mine",Resident.activity(job),job);
        assertEquals("wood",Resident.activity("wood"));
    }
    @Test void expansionPermissionRejectsAForbiddenSideEvenWhenAnotherSideIsAllowed(){
        var p=new VillagePolicy();p.expansion.clear();p.expansion.add("east");
        var old=new VillageState.Bounds(-24,-24,24,24);
        assertTrue(p.expansionAllowed(old,new VillageState.Bounds(-24,-24,48,24)));
        assertFalse(p.expansionAllowed(old,new VillageState.Bounds(-25,-24,48,24)));
        assertFalse(p.expansionAllowed(old,new VillageState.Bounds(-24,-25,24,24)));
        assertTrue(p.expansionAllowed(old,old));
    }
    @Test void fieldRotationVacanciesAndHumanControlsSurviveSavingWithoutCreatingItems(){
        var v=new VillageState("Plan",UUID.randomUUID(),new BlockPos(0,64,0),24);
        var f=new ManagedLand.Field();f.water=new BlockPos(0,63,0).asLong();f.entry=new BlockPos(0,64,-4).asLong();
        f.seedChest=new BlockPos(-2,64,-3).asLong();f.produceChest=new BlockPos(2,64,-3).asLong();f.source=new BlockPos(8,63,8).asLong();
        f.cells.add(new BlockPos(1,64,1).asLong());f.nextSurvey=99;v.land.fields.add(f);
        var lot=new ManagedLand.Forest();lot.center=new BlockPos(10,64,10).asLong();lot.restUntil=24000;lot.harvested=2;lot.replanted=2;v.land.forests.add(lot);v.land.rotation=1;
        v.policy.privateChests.add(f.seedChest);v.policy.expansion.clear();v.policy.expansion.add("east");v.vacantRoles.add(ResidentRoles.Role.FARMER);v.roadFailures.put(f.entry,2);
        var copy=VillageRuntime.GSON.fromJson(VillageRuntime.GSON.toJson(v),VillageState.class);copy.validate();
        assertEquals(f.cells,copy.land.fields.getFirst().cells);assertEquals(f.source,copy.land.fields.getFirst().source);assertEquals(0,copy.land.fields.getFirst().nextSurvey);
        assertEquals(24000,copy.land.forests.getFirst().restUntil);assertEquals(1,copy.land.rotation);
        assertEquals(Set.of("east"),copy.policy.expansion);assertEquals(v.policy.privateChests,copy.policy.privateChests);
        assertEquals(v.vacantRoles,copy.vacantRoles);assertEquals(2,copy.roadFailures.get(f.entry));assertTrue(copy.storage.isEmpty());
    }
    @Test void oldMetadataReceivesSafeDefaultsAndMalformedLandIsRejected(){
        var v=new VillageState("Old",UUID.randomUUID(),new BlockPos(0,64,0),24);
        v.policy=null;v.land=null;v.roadFailures=null;v.vacantRoles=null;v.compactMetadata();v.validate();
        assertEquals(4,v.policy.expansion.size());assertTrue(v.land.fields.isEmpty());
        var f=new ManagedLand.Field();f.water=new BlockPos(1000,64,0).asLong();v.land.fields.add(f);
        assertThrows(IllegalArgumentException.class,v::validate);
    }
    @Test void corruptUnboundedPoliciesAndRoadCountsCannotLoad(){
        var p=new VillagePolicy();p.expansion.add("diagonal");assertThrows(IllegalArgumentException.class,p::validate);
        var v=new VillageState("Bad",UUID.randomUUID(),new BlockPos(0,64,0),24);v.roadFailures.put(0L,1000001);
        assertThrows(IllegalArgumentException.class,v::validate);
        p=new VillagePolicy();var zone=new VillagePolicy.Zone();zone.minX=5;zone.maxX=4;p.zones.add(zone);assertThrows(IllegalArgumentException.class,p::validate);
    }
    @Test void emergencySuccessorCannotStealTheOnlyFoodOrGuardWorker(){
        assertTrue(PopulationPlanning.mayReplace(ResidentRoles.Role.FARMER,ResidentRoles.Role.MINER,1));
        assertFalse(PopulationPlanning.mayReplace(ResidentRoles.Role.MINER,ResidentRoles.Role.FARMER,1));
        assertFalse(PopulationPlanning.mayReplace(ResidentRoles.Role.LOGGER,ResidentRoles.Role.GUARD,1));
        assertTrue(PopulationPlanning.mayReplace(ResidentRoles.Role.MINER,ResidentRoles.Role.LOGGER,2));
        assertFalse(PopulationPlanning.mayReplace(ResidentRoles.Role.FARMER,ResidentRoles.Role.FARMER,2));
        assertEquals(SettlementEconomy.foodUnits(3,0),SettlementEconomy.foodUnits(0,12));
        assertEquals(12,SettlementEconomy.foodUnits(2,4));
    }

    @Test void auxiliaryMiningRespectsTheSameZoneAsAnExplicitMineJob(){
        var policy=new VillagePolicy();var zone=new VillagePolicy.Zone();zone.activity="mine";zone.minX=-1;zone.maxX=1;zone.minZ=-1;zone.maxZ=1;policy.zones.add(zone);
        var p=new BlockPos(0,64,0);
        for(String job:List.of("escape","storage","home","pickup")){
            assertTrue(policy.allows(Resident.activity(job),p));
            assertFalse(policy.allows(Resident.miningActivity(job),p),job);
        }
    }
    @Test void exhaustedAndRestingForestLotsDoNotHoldTheHarvestRotation(){
        var empty=new ManagedLand.Forest();var resting=new ManagedLand.Forest();resting.restUntil=100;
        var ready=new ManagedLand.Forest();
        assertEquals(List.of(ready),ManagedLand.eligible(List.of(empty,resting,ready),50,f->f!=empty));
        assertEquals(List.of(resting,ready),ManagedLand.eligible(List.of(empty,resting,ready),100,f->f!=empty));
        assertTrue(ManagedLand.eligible(List.of(empty),200,f->false).isEmpty());
    }

}
