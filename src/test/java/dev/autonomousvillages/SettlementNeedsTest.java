package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Pure graph contracts. Adapted to concrete work keys; execution is separately authorized. */
class SettlementNeedsTest {
    private static SettlementNeeds.Need need(long work,String purpose,boolean observed,List<SettlementNeeds.Key> dependencies){
        return new SettlementNeeds.Need(new SettlementNeeds.Key(work,purpose),SettlementNeeds.Stage.TOOL,
                "reason",10,"worker","physical completion",dependencies,observed,SettlementNeeds.Action.CRAFT,null,0,"","");
    }
    @Test void dependenciesReferToConcreteWorks(){
        var first=need(10,"iron",false,List.of());var second=need(20,"iron",true,List.of());
        var tool=need(30,"tool",false,List.of(first.key()));
        var plan=new SettlementNeeds.Plan(List.of(first,second,tool));
        assertEquals(SettlementNeeds.State.WAITING,plan.state(tool).state());
        assertEquals(SettlementNeeds.State.DONE,plan.state(second).state());
    }
    @Test void completedDependencyReleasesTheNextWork(){
        var iron=need(10,"iron",true,List.of());var tool=need(20,"tool",false,List.of(iron.key()));
        var plan=new SettlementNeeds.Plan(List.of(iron,tool));
        assertEquals(SettlementNeeds.State.READY,plan.state(tool).state());
    }
    @Test void missingDependenciesAreRejected(){
        assertThrows(IllegalArgumentException.class,()->new SettlementNeeds.Plan(List.of(need(10,"tool",false,
                List.of(new SettlementNeeds.Key(20,"missing"))))));
    }
    @Test void cyclicDependenciesAreRejected(){
        var a=need(10,"a",false,List.of(new SettlementNeeds.Key(20,"b")));
        var b=need(20,"b",false,List.of(a.key()));
        assertThrows(IllegalArgumentException.class,()->new SettlementNeeds.Plan(List.of(a,b)));
    }
    @Test void woodAlternativesPreserveCarrierAndUseMatchingUnits(){
        assertEquals(SettlementNeeds.Route.LOGS,SettlementNeeds.chooseRoute(16,0,16,8,null));
        assertEquals(SettlementNeeds.Route.LOGS,SettlementNeeds.chooseRoute(0,8,0,8,null));
        assertEquals(SettlementNeeds.Route.PLANKS,SettlementNeeds.chooseRoute(0,0,64,64,null));
        assertEquals(SettlementNeeds.Route.LOGS,SettlementNeeds.chooseRoute(0,0,64,0,true));
        assertEquals(SettlementNeeds.Route.PLANKS,SettlementNeeds.chooseRoute(0,0,0,64,false));
    }
    @Test void waitingReasonsSurviveWithoutAnAssignedResident(){
        var input=need(10,"iron",false,List.of());var tool=need(20,"shears",false,List.of(input.key()));
        var plan=new SettlementNeeds.Plan(List.of(input,tool));
        assertTrue(plan.state(tool).reason().contains("iron"));
        assertTrue(SettlementNeeds.lines(plan).stream().anyMatch(s->s.contains("shears")&&s.contains("ЖДЁТ")));
        assertEquals("нет открытых потребностей",SettlementNeeds.Plan.empty().summary());
    }
}
