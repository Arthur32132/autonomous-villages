package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import dev.navigationai.api.NavigationResult;
import dev.navigationai.api.NavigationStatus;
import dev.navigationai.api.NavigationTask;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class NavHygieneTest {
    @BeforeAll static void bootstrapRegistries(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}

    static Job bare(){return new Job("тест"){@Override String kind(){return "test";}@Override boolean tick(){return false;}};}
    static NavigationTask route(NavigationStatus status,String reason){
        return new NavigationTask(){
            @Override public NavigationStatus status(){return status;}
            @Override public CompletableFuture<NavigationResult> completion(){
                return status==NavigationStatus.COMPUTING||status==NavigationStatus.RUNNING?new CompletableFuture<>():CompletableFuture.completedFuture(new NavigationResult(status,reason,0,0));
            }
            @Override public void cancel(){}
        };
    }

    @Test void anOldRouteFailureDoesNotTaintALaterFailure(){
        var job=bare();
        // The first stand is out of reach: a failure right now names the route's reason.
        job.clearRoute();job.noteRoute(route(NavigationStatus.FAILED,"unreachable"));
        assertEquals("unreachable",job.navReason);
        job.fail("не подойти");assertEquals("не подойти (unreachable)",job.failure);
        // The next stand is reached; the job later runs out of material, which is no route failure.
        job.clearRoute();job.noteRoute(route(NavigationStatus.SUCCESS,"reached_goal"));
        job.fail("материалы не донесены");
        assertEquals("материалы не донесены",job.failure);
        assertFalse(NavHygiene.stuck(job.failure,100,0,2400));
        // A route still under way says nothing yet; a successful one clears a reason left behind.
        job.navReason="unreachable";job.noteRoute(route(NavigationStatus.RUNNING,null));assertEquals("unreachable",job.navReason);
        job.noteRoute(route(NavigationStatus.SUCCESS,"reached_goal"));assertEquals("",job.navReason);
        // A failure without a reason still says it failed.
        job.noteRoute(route(NavigationStatus.FAILED,null));assertEquals("failed",job.navReason);
    }

    @Test void dropsTheBagHasNoRoomForAreNotWalkedTo(){
        var full=new ArrayList<ItemStack>();for(int i=0;i<36;i++)full.add(new ItemStack(Items.COBBLESTONE,64));
        assertFalse(NavHygiene.fits(full,new ItemStack(Items.OAK_LOG)));
        assertFalse(NavHygiene.fits(full,new ItemStack(Items.COBBLESTONE)));
        // An unfilled stack of the same item takes it, a different item still needs a free slot.
        full.set(7,new ItemStack(Items.COBBLESTONE,63));
        assertTrue(NavHygiene.fits(full,new ItemStack(Items.COBBLESTONE,5)));
        assertFalse(NavHygiene.fits(full,new ItemStack(Items.WHEAT_SEEDS)));
        // Unstackable tools fill their slot at once.
        var tools=new ArrayList<ItemStack>();for(int i=0;i<36;i++)tools.add(new ItemStack(Items.IRON_PICKAXE));
        assertFalse(NavHygiene.fits(tools,new ItemStack(Items.IRON_PICKAXE)));
        tools.set(35,ItemStack.EMPTY);
        assertTrue(NavHygiene.fits(tools,new ItemStack(Items.IRON_PICKAXE)));
        assertFalse(NavHygiene.fits(tools,ItemStack.EMPTY));
    }

    @Test void freshDropsOnTheirPickupDelayAreWaitedFor(){
        // A drop from the block just broken is still on its 10-tick delay: it counts, so the harvest does not end before it.
        assertTrue(NavHygiene.pending(true,0));assertTrue(NavHygiene.pending(true,9));
        assertTrue(NavHygiene.pending(false,500));
        // An old drop that still cannot be picked up was thrown or is never collectable: not worth waiting for.
        assertFalse(NavHygiene.pending(true,NavHygiene.FRESH_DROP_AGE));
        // Nothing visible yet right after the break: collecting goes on for a short window, then ends.
        assertTrue(Gathering.keepCollecting(false,1014,1000,1060));
        assertFalse(Gathering.keepCollecting(false,1000+Gathering.COLLECT_WINDOW,1000,1060));
        assertTrue(Gathering.keepCollecting(true,1050,1000,1060));
        assertFalse(Gathering.keepCollecting(true,1060,1000,1060));
    }

    @Test void pickupRoutesAreNotRequestedEveryTick(){
        long last=-NavHygiene.PICKUP_REROUTE;
        assertTrue(NavHygiene.mayReroute(0,last));
        int routes=0;
        // Each pickup stops the route: asked again on every tick for 40 ticks, a route goes out only every 10.
        for(long now=0;now<40;now++)if(NavHygiene.mayReroute(now,last)){routes++;last=now;}
        assertEquals(4,routes);
        assertFalse(NavHygiene.mayReroute(109,100));assertTrue(NavHygiene.mayReroute(110,100));
    }

    @Test void approachCountsEachFailedStandOnce(){
        var a=new NavHygiene.Attempts();
        // Three stands tried and failed: a fourth is still tried.
        for(int i=0;i<3;i++){assertFalse(a.exhausted());a.started();assertFalse(a.failed());}
        assertFalse(a.exhausted());a.started();
        assertTrue(a.failed());
        assertEquals(4,a.routes);
        // Routes that end without bringing the body into reach count only toward the cap of six.
        var b=new NavHygiene.Attempts();
        for(int i=0;i<5;i++){b.started();assertFalse(b.exhausted());}
        b.started();assertTrue(b.exhausted());assertEquals(0,b.failed);
    }

    @Test void planningWaitIsNotTheJobsOwnTime(){
        // A 2400-tick job that waited 1000 ticks for routes is not over time at 3000 ticks.
        assertFalse(NavHygiene.overdue(3000,1000,2400));
        assertTrue(NavHygiene.overdue(3401,1000,2400));
        assertTrue(NavHygiene.overdue(2401,0,2400));assertFalse(NavHygiene.overdue(2400,0,2400));
        // Planning waits are excused up to a second limit, never longer.
        assertFalse(NavHygiene.overdue(4800,4800,2400));assertTrue(NavHygiene.overdue(4801,4801,2400));
        // A timeout made mostly of planning waits does not count the resident as stuck; one of its own time does.
        assertFalse(NavHygiene.stuck("не уложился во время",4801,4000,2400));
        assertTrue(NavHygiene.stuck("не уложился во время",2401,0,2400));
        // Route failures still count, a planner timeout behind them does not.
        assertTrue(NavHygiene.stuck("не подойти (cancelled)",500,0,2400));
        assertTrue(NavHygiene.stuck("нет на складе: палки (unreachable)",500,0,2400));
        assertTrue(NavHygiene.stuck("не дойти (stuck_no_progress)",500,0,2400));
        assertFalse(NavHygiene.stuck("не подойти (planning_timeout)",2100,2001,2400));
        assertFalse(NavHygiene.stuck("не нашёл дорогу домой (search_queue_full)",30,0,2400));
        assertFalse(NavHygiene.stuck("нет ножниц",500,0,2400));
    }

    @Test void restHealingWaitsForTheWalkToEnd(){
        assertFalse(NavHygiene.mayHeal(NavigationStatus.RUNNING,false));
        assertFalse(NavHygiene.mayHeal(NavigationStatus.COMPUTING,false));
        assertTrue(NavHygiene.mayHeal(NavigationStatus.IDLE,false));
        assertTrue(NavHygiene.mayHeal(NavigationStatus.SUCCESS,false));
        assertTrue(NavHygiene.mayHeal(NavigationStatus.FAILED,false));
        // Not in a fight or a flight either.
        assertFalse(NavHygiene.mayHeal(NavigationStatus.IDLE,true));
    }

    @Test void fleeingPlansAgainOnlyAfterAPauseOrWhenTheThreatClosesIn(){
        // Planned at tick 100 with the zombie 8 blocks away: it stays in view at the same distance.
        for(long now=101;now<100+NavHygiene.FLEE_REPLAN;now++)assertFalse(NavHygiene.replanFlight(now,100,8,8));
        assertTrue(NavHygiene.replanFlight(100+NavHygiene.FLEE_REPLAN,100,8,8));
        // It comes a block and more closer: run again at once.
        assertTrue(NavHygiene.replanFlight(102,100,6.9,8));
        assertFalse(NavHygiene.replanFlight(102,100,7.5,8));
    }

    @Test void aLongerRejectionIsKept(){
        var rejected=new HashMap<Long,Long>();
        long cell=42L;
        // An unreachable store block was rejected for a long while; a failed job there must not shorten it to 600 ticks.
        rejected.put(cell,10_000L);
        NavHygiene.reject(rejected,cell,1_600L);
        assertEquals(10_000L,rejected.get(cell));
        NavHygiene.reject(rejected,cell,12_000L);
        assertEquals(12_000L,rejected.get(cell));
        NavHygiene.reject(rejected,7L,600L);
        assertEquals(600L,rejected.get(7L));
    }
}
