package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class ProductionRulesTest {
    @Test void mineRequiresSpareFoodAndExitBlocksRatherThanJustAWorkingPick(){
        assertFalse(new MineSupplies.Kit(200,0,12,16).ready());
        assertFalse(new MineSupplies.Kit(200,100,2,16).ready());
        assertFalse(new MineSupplies.Kit(200,100,12,7).ready());
        assertFalse(new MineSupplies.Kit(31,100,12,16).ready());
        assertEquals(0,new MineSupplies.Kit(200,100,0,16).segments());
        assertEquals(5,new MineSupplies.Kit(32,24,3,8).segments());
        assertEquals(8,new MineSupplies.Kit(131,131,20,8).segments());
        assertEquals(24,new MineSupplies.Kit(500,100,20,64).segments());
    }
    @Test void resourceSpecificKnowledgeWinsAndHazardOrDryDirectionIsSkipped(){
        var iron=new ExpeditionState.Mine();iron.entrance=1;iron.iron.add(10L);
        var coal=new ExpeditionState.Mine();coal.entrance=2;coal.coal.add(20L);
        var blocked=new ExpeditionState.Mine();blocked.entrance=0;blocked.iron.add(30L);blocked.blocked=true;blocked.hazard="lava";
        var dry=new ExpeditionState.Mine();dry.entrance=3;dry.exhausted=true;dry.drySegments=24;
        assertSame(iron,MineKnowledge.choose(List.of(coal,blocked,dry,iron),"iron"));
        assertSame(coal,MineKnowledge.choose(List.of(iron,blocked,dry,coal),"coal"));
        assertNull(MineKnowledge.choose(List.of(blocked,dry),"iron"));
    }
    @Test void ironRequestUpdatesDoNotCancelBedRequestsAtTheSameWorksite(){
        var orders=new ArrayList<Orders.Order>();
        Orders.apply(orders,10,Orders.beds(1,true,true),"builder",20,0);
        Orders.applyScoped(orders,10,List.of(new Orders.Demand(Orders.Material.IRON,"iron_shears",2)),"farmer",20,1,"iron_");
        Orders.applyScoped(orders,10,List.of(new Orders.Demand(Orders.Material.IRON,"iron_pickaxe",3)),"miner",20,2,"iron_");
        assertTrue(orders.stream().anyMatch(o->o.purpose.equals("bed_wool")));
        assertFalse(orders.stream().anyMatch(o->o.purpose.equals("iron_shears")));
        assertEquals(3,orders.stream().filter(o->o.purpose.equals("iron_pickaxe")).findFirst().orElseThrow().quantity);
    }
    @Test void flockAndExplorationFactsSurviveSaveWithoutInventories(){
        var v=new VillageState("Farm",UUID.randomUUID(),new BlockPos(0,64,0),24);
        v.sheepFarm.base=new BlockPos(1,64,1).asLong();v.sheepFarm.flock.add(UUID.randomUUID());
        var m=new ExpeditionState.Mine();m.entrance=new BlockPos(8,64,8).asLong();m.iron.add(new BlockPos(8,60,8).asLong());m.hazard="water";m.blocked=true;
        v.mines.add(m);
        var restored=VillageRuntime.GSON.fromJson(VillageRuntime.GSON.toJson(v),VillageState.class);restored.validate();
        assertEquals(v.sheepFarm.flock,restored.sheepFarm.flock);assertEquals(m.iron,restored.mines.getFirst().iron);
        assertEquals("water",restored.mines.getFirst().hazard);
    }
}
