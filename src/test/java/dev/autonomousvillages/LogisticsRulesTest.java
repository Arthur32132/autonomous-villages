package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class LogisticsRulesTest {
    @Test void roleToolsHaveOneWorkingAndOneRelevantSpare(){
        assertEquals(2,PersonalKits.tools(ResidentRoles.Role.MINER,"pick",false));
        assertEquals(1,PersonalKits.tools(ResidentRoles.Role.PORTER,"pick",false));
        assertEquals(2,PersonalKits.tools(ResidentRoles.Role.LOGGER,"axe",false));
        assertEquals(2,PersonalKits.tools(ResidentRoles.Role.FARMER,"hoe",false));
        assertEquals(0,PersonalKits.tools(ResidentRoles.Role.BUILDER,"hoe",false));
        assertEquals(2,PersonalKits.tools(ResidentRoles.Role.FARMER,"shears",true));
        assertEquals(0,PersonalKits.tools(ResidentRoles.Role.MINER,"shears",false));
        assertEquals(2,PersonalKits.tools(ResidentRoles.Role.GUARD,"sword",false));
    }
    @Test void cargoBaselineIsMetadataAndSurvivesSave(){
        var id=java.util.UUID.randomUUID();
        var state=new VillageState("Cargo",id,new net.minecraft.core.BlockPos(0,64,0),24);
        state.residents.add(id);
        state.cargoKits.put(id,new java.util.EnumMap<>(Orders.Material.class));
        state.cargoKits.get(id).put(Orders.Material.PLANKS,0);
        var restored=VillageRuntime.GSON.fromJson(VillageRuntime.GSON.toJson(state),VillageState.class);
        restored.validate();
        assertEquals(0,restored.cargoKits.get(id).get(Orders.Material.PLANKS));
        restored.cargoKits.get(id).put(Orders.Material.PLANKS,-1);
        assertThrows(IllegalArgumentException.class,restored::validate);
    }
    @Test void worksitesKeepBoundedSuppliesWithoutEmptyingOneAnother(){
        assertEquals(8,DeliveryRuns.target("smithy",Orders.Material.FUEL));
        assertEquals(16,DeliveryRuns.target("seeds",Orders.Material.SEEDS));
        assertEquals(16,DeliveryRuns.target("lumber_yard",Orders.Material.LOGS));
        assertEquals(0,DeliveryRuns.target("shared",Orders.Material.FUEL));
        assertEquals(0,DeliveryRuns.target("watch_post",Orders.Material.RAW_IRON));
    }
}
