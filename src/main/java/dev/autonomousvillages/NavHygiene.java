package dev.autonomousvillages;

import dev.navigationai.api.NavigationStatus;
import java.util.*;
import net.minecraft.world.item.ItemStack;

/** Route bookkeeping rules shared by jobs and workers, kept free of the world so tests drive them. */
final class NavHygiene {
    private NavHygiene(){}
    /** Library reasons that say the planner was busy, not that the resident or its target is walled in. */
    static final Set<String> PLANNING=Set.of("planning_timeout","search_queue_full","chunk_loading_timeout");

    /** Routes of one Approach: each failed stand counts once, four failed stands or six routes in all end it. */
    static final class Attempts {
        static final int MAX_FAILED=4,MAX_ROUTES=6;
        int routes,failed;
        void started(){routes++;}
        /** Counts one failed route; true once the target is given up. */
        boolean failed(){return ++failed>=MAX_FAILED;}
        boolean exhausted(){return routes>=MAX_ROUTES;}
    }

    /** A drop on its pickup delay is about to become collectable only while it is fresh; an old one was thrown or is never picked up. */
    static final int FRESH_DROP_AGE=20;
    /** Not more than one pickup route per this many ticks: picking an item up stops the bot's route, and a new one each tick floods the planner. */
    static final int PICKUP_REROUTE=10;
    static boolean pending(boolean delayed,int age){return !delayed||age<FRESH_DROP_AGE;}
    /** True when the bag takes at least one of the item: a free slot or an unfilled stack of the same item. */
    static boolean fits(List<ItemStack> bag,ItemStack item){
        if(item.isEmpty())return false;
        for(var s:bag)if(s.isEmpty()||ItemStack.isSameItemSameComponents(s,item)&&s.getCount()<s.getMaxStackSize())return true;
        return false;
    }
    static boolean mayReroute(long now,long last){return now-last>=PICKUP_REROUTE;}

    /** Over time once the job's own ticks pass its limit; time waiting for route planning is not its own, up to a second limit. */
    static boolean overdue(long elapsed,long planWait,int limit){return elapsed-Math.min(planWait,limit)>limit;}
    /** A failure that says the resident itself may be stuck; planner waits and timeouts made of them do not. */
    static boolean stuck(String failure,long elapsed,long planWait,int limit){
        for(String r:PLANNING)if(failure.endsWith("("+r+")"))return false;
        if(failure.contains("уложился"))return elapsed-planWait>limit;
        return failure.startsWith("не подойти")||failure.startsWith("не нашёл дорогу")||failure.startsWith("не дойти")
                ||failure.endsWith("(unreachable)")||failure.contains("(stuck");
    }

    /** Eating from the bag stops every task of the bot: rest healing waits until no route or fight is under way. */
    static boolean mayHeal(NavigationStatus route,boolean busy){return !busy&&route!=NavigationStatus.RUNNING&&route!=NavigationStatus.COMPUTING;}

    /** Ticks between two flight plans while the threat stays in view, unless it closed in by a block since. */
    static final int FLEE_REPLAN=10;
    static boolean replanFlight(long now,long plannedAt,double distance,double distanceAtPlan){return now-plannedAt>=FLEE_REPLAN||distance<distanceAtPlan-1;}

    /** A target rejected for longer already keeps its longer rejection. */
    static void reject(Map<Long,Long> rejected,long key,long until){rejected.merge(key,until,Math::max);}
}
