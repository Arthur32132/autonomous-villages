package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ResidentRolesTest {
    private static UUID id(int n){return new UUID(0,n);}
    @Test void professionIsPreferredAndExistingAppointmentsNeverShuffle(){
        var assignments=new LinkedHashMap<UUID,ResidentRoles.Assignment>();
        var adults=new LinkedHashMap<UUID,String>();
        adults.put(id(3),"farmer");adults.put(id(4),"mason");adults.put(id(5),"armorer");
        ResidentRoles.assign(assignments,adults);
        assertEquals(ResidentRoles.Role.FARMER,assignments.get(id(3)).role);
        assertEquals(ResidentRoles.Role.BUILDER,assignments.get(id(4)).role);
        assertEquals(ResidentRoles.Role.GUARD,assignments.get(id(5)).role);
        String before=VillageRuntime.GSON.toJson(assignments);
        assertFalse(ResidentRoles.assign(assignments,Map.of(id(3),"none"))); // neighbours unloaded
        assertEquals(before,VillageRuntime.GSON.toJson(assignments));
        adults.put(id(1),"none");ResidentRoles.assign(assignments,adults); // earlier UUID joins
        assertEquals(ResidentRoles.Role.FARMER,assignments.get(id(3)).role);
        assertEquals("farmer",assignments.get(id(3)).profession);
    }
    @Test void missingSpecialtiesAreFilledWithoutReassigningSurvivors(){
        var assignments=new LinkedHashMap<UUID,ResidentRoles.Assignment>();
        var adults=new LinkedHashMap<UUID,String>();
        for(int n=1;n<=7;n++)adults.put(id(n),"none");
        ResidentRoles.assign(assignments,adults);
        assertEquals(EnumSet.allOf(ResidentRoles.Role.class),EnumSet.copyOf(assignments.values().stream().map(a->a.role).toList()));
        var survivor=assignments.get(id(4));int schedule=survivor.schedule;
        UUID farmer=assignments.entrySet().stream().filter(e->e.getValue().role==ResidentRoles.Role.FARMER).findFirst().orElseThrow().getKey();
        assignments.remove(farmer);adults.remove(farmer);adults.put(id(8),"none");
        ResidentRoles.assign(assignments,adults);
        assertSame(survivor,assignments.get(id(4)));assertEquals(schedule,survivor.schedule);
        assertEquals(ResidentRoles.Role.FARMER,assignments.get(id(8)).role);
    }
    @Test void sevenNativeFarmersStillCoverEveryVillageService(){
        var appointments=new LinkedHashMap<UUID,ResidentRoles.Assignment>();var adults=new LinkedHashMap<UUID,String>();
        for(int n=1;n<=7;n++)adults.put(id(n),"farmer");
        ResidentRoles.assign(appointments,adults);
        assertEquals(ResidentRoles.Role.values().length,appointments.values().stream().map(a->a.role).distinct().count());
        assertTrue(appointments.values().stream().anyMatch(a->a.reason.startsWith("нужен специалист:")));
    }
    @Test void appointmentsAndScheduleSurviveSaveAndDeathCompaction(){
        var state=new VillageState("Roles",id(99),new net.minecraft.core.BlockPos(0,64,0),24);
        state.residents.addAll(List.of(id(1),id(2)));
        ResidentRoles.assign(state.residentRoles,Map.of(id(1),"farmer",id(2),"armorer"));
        String json=VillageRuntime.GSON.toJson(state);
        var restored=VillageRuntime.GSON.fromJson(json,VillageState.class);restored.validate();
        assertEquals(json,VillageRuntime.GSON.toJson(restored));
        restored.residents.remove(id(2));restored.compactMetadata();restored.validate();
        assertEquals(ResidentRoles.Role.FARMER,restored.residentRoles.get(id(1)).role);
        assertFalse(restored.residentRoles.containsKey(id(2)));
    }
    @Test void oldSavesStartWithEmptyAppointmentsAndCorruptAppointmentsAreRejected(){
        var state=VillageRuntime.GSON.fromJson("{\"name\":\"Old\",\"owner\":\""+id(9)+"\",\"x\":0,\"y\":64,\"z\":0,\"radius\":24}",VillageState.class);
        state.compactMetadata();state.validate();assertTrue(state.residentRoles.isEmpty());
        state.residents.add(id(1));ResidentRoles.assign(state.residentRoles,Map.of(id(1),"none"));
        state.residentRoles.get(id(1)).schedule=-1;assertThrows(IllegalArgumentException.class,state::validate);
    }
    @Test void expeditionLeavesThreeDifferentSpecialistsAtHome(){
        var crew=Map.of(id(1),ResidentRoles.Role.FARMER,id(2),ResidentRoles.Role.BUILDER,
                id(3),ResidentRoles.Role.GUARD,id(4),ResidentRoles.Role.MINER,id(5),ResidentRoles.Role.STEWARD);
        assertTrue(ResidentRoles.homeCoverage(crew,Set.of(id(4),id(2)))); // steward covers repair
        assertFalse(ResidentRoles.homeCoverage(crew,Set.of(id(1))));
        assertFalse(ResidentRoles.homeCoverage(crew,Set.of(id(3))));
        assertFalse(ResidentRoles.homeCoverage(crew,Set.of(id(2),id(5))));
        assertFalse(ResidentRoles.homeCoverage(Map.of(id(1),ResidentRoles.Role.STEWARD),Set.of()));
    }
}
