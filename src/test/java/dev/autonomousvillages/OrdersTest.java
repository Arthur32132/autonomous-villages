package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

/** Shared orders and available material: metadata only, real items stay in the world. */
class OrdersTest {
    private static VillageState state(){
        var s=new VillageState("A",UUID.randomUUID(),new BlockPos(0,64,0),24);
        s.bounds=new VillageState.Bounds(-24,-24,24,24);
        return s;
    }
    private static Orders.Order order(String purpose,int quantity){
        var order=new Orders.Order();
        order.material=Orders.Material.PLANKS.name();order.purpose=purpose;order.quantity=quantity;
        order.delivery=new BlockPos(0,64,0).asLong();order.work=new BlockPos(2,64,2).asLong();
        return order;
    }

    @Test void ledgerSeparatesFreeStockPersonalKitOrderReserveAndCargoInTransit(){
        List<Orders.Order> orders=new ArrayList<>();
        orders.add(order("bed_planks",6));
        var ledger=Orders.split(orders,Orders.Material.PLANKS,10,new int[]{20,4},new int[]{64,3});
        assertEquals(4,ledger.free());
        assertEquals(1,ledger.backpacks());
        assertEquals(23,ledger.kit());
        assertEquals(6,ledger.reserved());
        assertEquals(0,ledger.transit());
        assertEquals(34,ledger.total());
        orders.getFirst().transit=true;orders.getFirst().inTransit=1;
        var moved=Orders.split(orders,Orders.Material.PLANKS,10,new int[]{20,4},new int[]{64,3});
        assertEquals(5,moved.free());
        assertEquals(1,moved.transit());
        assertEquals(5,moved.reserved());
    }

    @Test void anotherWorkersBackpackIsNeverCountedAsFreeStock(){
        // The exact roadmap case: the village holds planks, but they sit in a builder's backpack.
        var ledger=Orders.split(List.of(),Orders.Material.PLANKS,0,new int[]{20,0},new int[]{64,0});
        assertEquals(0,ledger.free());
        assertEquals(20,ledger.total());
        assertFalse(ledger.strandedInBackpacks()); // personal kit is never transferable
        assertTrue(Orders.split(List.of(),Orders.Material.PLANKS,0,new int[]{20,0},new int[]{8,0}).strandedInBackpacks());
        assertFalse(Orders.split(List.of(),Orders.Material.PLANKS,0,new int[]{0,0},new int[]{64,0}).strandedInBackpacks());
    }

    @Test void aBedRequestRevealsWoolPlanksAndTheMissingTools(){
        var need=Orders.beds(2,false,false);
        assertEquals(4,need.size());
        assertEquals(new Orders.Demand(Orders.Material.PLANKS,"bed_planks",6),need.get(0));
        assertEquals(new Orders.Demand(Orders.Material.WOOL,"bed_wool",6),need.get(1));
        assertEquals(new Orders.Demand(Orders.Material.SHEARS,"bed_shears",1),need.get(2));
        assertEquals(new Orders.Demand(Orders.Material.IRON,"bed_shears_iron",2),need.get(3));
        assertEquals(2,Orders.beds(1,true,true).size());
        assertEquals(2,Orders.beds(1,true,false).size());
        assertEquals(6,Orders.beds(2,true,true).getFirst().quantity());
    }

    @Test void cancellingARequestReleasesItsReserve(){
        List<Orders.Order> orders=new ArrayList<>();
        long work=new BlockPos(2,64,2).asLong();
        String builder=UUID.randomUUID().toString();
        assertTrue(Orders.apply(orders,work,Orders.beds(2,true,true),builder,work,100));
        assertEquals(6,Orders.split(orders,Orders.Material.PLANKS,6,new int[]{0},new int[]{0}).reserved());
        assertEquals(0,Orders.split(orders,Orders.Material.PLANKS,6,new int[]{0},new int[]{0}).free());
        assertFalse(Orders.apply(orders,work,Orders.beds(2,true,true),builder,work,100));
        assertTrue(Orders.cancel(orders,work,"bed_"));
        assertEquals(6,Orders.split(orders,Orders.Material.PLANKS,6,new int[]{0},new int[]{0}).free());
        assertFalse(Orders.cancel(orders,work,"bed_"));
    }

    @Test void aLostWorkerReturnsOnlyRealCargoIncludingAlreadyDeliveredMaterial(){
        List<Orders.Order> orders=new ArrayList<>();
        UUID dead=UUID.randomUUID();long meeting=new BlockPos(1,64,1).asLong();
        Orders.apply(orders,meeting,Orders.beds(2,true,true),dead.toString(),meeting,100);
        orders.getFirst().delivered=6;
        assertTrue(Orders.workerLost(orders,dead,meeting,200,Map.of(Orders.Material.PLANKS,6),Map.of(Orders.Material.PLANKS,10)));
        assertEquals(1,orders.size()); // no wool existed: the unfulfilled request is not lost cargo
        var cargo=orders.getFirst();
        assertTrue(cargo.returnCargo());assertEquals(6,cargo.quantity);assertEquals(200,cargo.created);
        assertEquals(0,Orders.split(orders,Orders.Material.PLANKS,10,new int[]{0},new int[]{0}).reserved());
        assertFalse(Orders.recovered(orders,Orders.Material.PLANKS,10,201));
        assertTrue(Orders.recovered(orders,Orders.Material.PLANKS,12,202));
        assertEquals(2,cargo.delivered);assertEquals(4,cargo.outstanding());
        assertTrue(Orders.recovered(orders,Orders.Material.PLANKS,16,203));
        assertTrue(orders.isEmpty());
    }

    @Test void severalReturnsCannotCountTheSameChestDepositTwice(){
        List<Orders.Order> orders=new ArrayList<>();
        long meeting=new BlockPos(1,64,1).asLong();
        for(int i=0;i<2;i++)Orders.workerLost(orders,UUID.randomUUID(),meeting,100+i,Map.of(Orders.Material.WOOL,6),Map.of(Orders.Material.WOOL,4));
        Orders.recovered(orders,Orders.Material.WOOL,10,200);
        assertEquals(1,orders.size());assertEquals(6,orders.getFirst().outstanding());
        assertFalse(Orders.recovered(orders,Orders.Material.WOOL,10,201));
        Orders.recovered(orders,Orders.Material.WOOL,16,202);assertTrue(orders.isEmpty());
    }

    @Test void shortagesNeverCreatePhantomStockAndTransitIsLimitedToRealSurplus(){
        var request=order("bed_planks",6);request.transit=true;request.inTransit=6;
        var empty=Orders.split(List.of(request),Orders.Material.PLANKS,0,new int[]{0},new int[]{0});
        assertEquals(0,empty.total());assertFalse(empty.strandedInBackpacks());
        var cargo=Orders.split(List.of(request),Orders.Material.PLANKS,0,new int[]{70},new int[]{64});
        assertEquals(70,cargo.total());assertEquals(64,cargo.kit());assertEquals(6,cargo.transit());
        Orders.release(request);assertFalse(request.transit);assertEquals(0,request.inTransit);
    }

    @Test void capacityDoesNotTrimLiveOrdersOrPartiallyAdmitADemandSet(){
        var state=state();
        for(int i=0;i<Orders.MAX_ORDERS-1;i++){var o=order("custom",1);o.work=i;state.orders.add(o);}
        int size=state.orders.size();
        assertThrows(IllegalStateException.class,()->Orders.apply(state.orders,-1,Orders.beds(2,true,true),"",0,1));
        assertEquals(size,state.orders.size());state.compactMetadata();assertEquals(size,state.orders.size());
        state.orders.add(order("custom",1));state.orders.add(order("custom",1));
        state.compactMetadata();assertEquals(Orders.MAX_ORDERS+1,state.orders.size());
        assertThrows(IllegalArgumentException.class,state::validate);
    }

    @Test void removingToolDependenciesAndShrinkingOrdersKeepsMetadataValid(){
        var state=state();long work=new BlockPos(2,64,2).asLong();String recipient=UUID.randomUUID().toString();
        Orders.apply(state.orders,work,Orders.beds(2,false,false),recipient,work,1);
        state.orders.getFirst().delivered=6;
        assertTrue(Orders.apply(state.orders,work,Orders.beds(1,true,true),recipient,work,2));
        assertEquals(2,state.orders.size());assertEquals(3,state.orders.getFirst().delivered);state.validate();
    }

    @Test void coldReturnCargoStopsReservingMaterial(){
        List<Orders.Order> orders=new ArrayList<>();
        var order=order("return_cargo",6);order.created=0;orders.add(order);
        assertFalse(Orders.recovered(orders,Orders.Material.PLANKS,0,Orders.RETURN_CARGO_TICKS));
        assertTrue(Orders.recovered(orders,Orders.Material.PLANKS,0,Orders.RETURN_CARGO_TICKS+1));
    }

    @Test void ordersSurviveARoundTripAndMalformedMetadataIsRejected(){
        var s=state();
        assertTrue(Orders.apply(s.orders,new BlockPos(2,64,2).asLong(),Orders.beds(2,false,false),UUID.randomUUID().toString(),new BlockPos(1,64,1).asLong(),7));
        s.compactMetadata();s.validate();
        var restored=VillageRuntime.GSON.fromJson(VillageRuntime.GSON.toJson(s),VillageState.class);
        restored.compactMetadata();restored.validate();
        assertEquals(4,restored.orders.size());
        assertEquals(s.orders.getFirst().material,restored.orders.getFirst().material);
        assertEquals(6,restored.orders.getFirst().quantity);
        restored.orders.getFirst().quantity=0;
        assertThrows(IllegalArgumentException.class,restored::validate);
        restored.orders.getFirst().quantity=6;restored.orders.getFirst().material="NOPE";
        assertThrows(IllegalArgumentException.class,restored::validate);
        restored.orders.getFirst().material=Orders.Material.PLANKS.name();
        restored.orders.getFirst().recipient="not-a-uuid";
        assertThrows(IllegalArgumentException.class,restored::validate);
        restored.orders.getFirst().recipient="";
        restored.orders.getFirst().delivered=99;
        assertThrows(IllegalArgumentException.class,restored::validate);
        restored.orders.getFirst().delivered=0;
        restored.orders.getFirst().delivery=new BlockPos(30_000_000,64,0).asLong();
        assertThrows(IllegalArgumentException.class,restored::validate);
    }

    @Test void legacySavesWithoutOrdersLoadAsAnEmptyLedger(){
        var legacy=VillageRuntime.GSON.fromJson("{\"name\":\"A\",\"owner\":\""+UUID.randomUUID()+"\",\"radius\":24}",VillageState.class);
        assertTrue(legacy.orders.isEmpty());
        legacy.validate();
        legacy.compactMetadata();
        assertNotNull(legacy.orders);
    }
}
