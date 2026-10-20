package dev.autonomousvillages;

import dev.navigationai.api.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * One unit of work for one worker. {@link #tick()} returns false once the job is over, either
 * finished or abandoned; {@link #stop()} releases every library task the job started.
 */
abstract class Job {
    static final NavigationPolicy TRAVEL=new NavigationPolicy(false,false,true,true,32_000,1024,32,200,2);
    /**
     * A few steps to a stand by the work: when the cell is not reachable around the corner, the library
     * widens its world capture up to the policy radius, and a 32-block capture shared by every resident
     * kept builders standing for a minute or more before each block (soak 8675309: "строит" with the
     * route still computing for 1000-1900 ticks). Another stand is tried instead.
     */
    static final NavigationPolicy NEAR=new NavigationPolicy(false,false,true,true,32_000,1024,16,200,2);
    final Worker w;
    final Village v;
    final long started;
    String status;
    String failure="";
    /** Why the last route ended without reaching its goal, as the library reported it. */
    String navReason="";
    /** Ticks this job waited for the library to plan a route; they do not count against its time limit. */
    long planWait;
    Job(Worker w,String status){this.w=w;this.v=w.v;this.started=v.level.getGameTime();this.status=status;}
    /** A job outside any world, for tests of its route bookkeeping. */
    Job(String status){this.w=null;this.v=null;this.started=0;this.status=status;}

    abstract String kind();
    /** The block or place the job is about; used for reservations and display. */
    BlockPos target(){return null;}
    /** Upper bound in game ticks; a job that runs longer is abandoned. */
    int timeLimit(){return 2400;}
    abstract boolean tick();
    void stop(){}
    /** True while breaking a block or collecting its drops; the schedule waits for that to finish. */
    boolean atomic(){return false;}
    /** Items this job still needs; the worker does not hand them to the store meanwhile. */
    boolean keeps(net.minecraft.world.item.ItemStack stack){return false;}
    /** Policy activity checked by the library before it breaks a block for this job. */
    String activity(){return "none";}
    /** True for a protected or farmland block this job is explicitly about (a crop to harvest, a cell to clear). */
    boolean mayBreak(BlockPos p){return false;}

    BotHandle bot(){return w.bot();}
    Villager body(){return w.body();}
    long now(){return v.level.getGameTime();}
    boolean fail(String reason){failure=navReason.isEmpty()?reason:reason+" ("+navReason+")";status=reason;return false;}
    /** How a finished route ended: the library's reason when it fell short, nothing once it reached its goal. */
    void noteRoute(NavigationTask nav){
        if(nav==null||!nav.completion().isDone())return;
        if(nav.status()==NavigationStatus.SUCCESS){clearRoute();return;}
        try{navReason=Objects.requireNonNullElse(nav.completion().join().reason(),"failed");}catch(RuntimeException ignored){navReason="cancelled";}
    }
    /** A route that starts or arrives leaves no reason behind: an old failure belongs to another stand or target. */
    void clearRoute(){navReason="";}

    /**
     * A block no route reaches is remembered for the whole village (UnreachableMemory), so jobs stop
     * walking there one after another: the reporter skips it for about an hour, everyone does once two
     * residents failed there, and a store block two residents failed at stays out longer still
     * (Village.unreachableFrom). Soak 8675309: builders walked to the same four cells of a house at
     * 136/140 every attempt for a day and a half ("материалы не донесены (unreachable)" 22 times).
     */
    void unreachable(BlockPos p){
        if(UnreachableMemory.blockedRoute(navReason))v.stock.unreachable.remember(p,now(),w.id);
        if(navReason.equals("unreachable")||navReason.equals("no_stand"))v.unreachableFrom(p,w.id);
    }
    /** A cell of a building nobody could stand at: remembered only; the votes and strikes of the store are not for cells. */
    void unreachableCell(BlockPos p){if(UnreachableMemory.blockedRoute(navReason))v.stock.unreachable.remember(p,now(),w.id);}
    /** A store block (chest, workbench) the village uses: not rejected by votes and not remembered as unreachable by enough residents. */
    boolean reachable(BlockPos p){return v.rejected.getOrDefault(p.asLong(),0L)<=now()&&!v.stock.unreachable.isUnreachable(p,now(),v.stock.quorum());}
    /** Chests no route reaches, or this worker failed at lately, are left out of the search as if they had been tried. */
    private void skipUnreachable(Set<BlockPos> tried){for(var c:v.stock.chests())if(!reachable(c.getBlockPos())||v.stock.skips(c.getBlockPos(),now(),w.id))tried.add(c.getBlockPos());}

    boolean lineOfSight(BlockPos p){
        var hit=v.level.clip(new ClipContext(body().getEyePosition(),Vec3.atCenterOf(p),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,body()));
        return hit.getType()==HitResult.Type.MISS||hit.getBlockPos().equals(p);
    }
    boolean within(BlockPos p,double reach){return body().getEyePosition().distanceToSqr(Vec3.atCenterOf(p))<=reach*reach;}

    /**
     * Watchdog for a route: stalled once the body has not come closer to the goal for 300 ticks of
     * walking. Path search runs on a real-time budget, so at a high tick rate it may take many game
     * ticks; that time is bounded separately.
     */
    final class Progress {
        double best=Double.MAX_VALUE;long since=Long.MIN_VALUE;int computing;
        /** Given up because the library never finished planning the route, not because the body got stuck. */
        boolean planning;
        void reset(){best=Double.MAX_VALUE;since=now();computing=0;planning=false;}
        boolean stalled(Vec3 goal,NavigationTask nav){
            if(nav.status()==NavigationStatus.COMPUTING){since=now();return planning=++computing>2000;}
            double d=body().position().distanceToSqr(goal);
            if(d<best-1){best=d;since=now();}
            return now()-since>300;
        }
    }
    /** Walks to a safe standing cell from which a block is in reach; tries other cells after a failed route. */
    final class Approach {
        enum State { ARRIVED, MOVING, FAILED }
        final BlockPos target;
        final double reach;
        final boolean sight;
        final Set<BlockPos> rejected=new HashSet<>();
        NavigationTask nav;
        BlockPos stand;
        final NavHygiene.Attempts attempts=new NavHygiene.Attempts();
        final Progress progress=new Progress();
        /** A new route starts with a clean reason: the old one belonged to another target. */
        Approach(BlockPos target,double reach,boolean sight){this.target=target;this.reach=reach;this.sight=sight;navReason="";}
        boolean arrived(){return within(target,reach)&&(!sight||lineOfSight(target));}
        State tick(){
            if(nav!=null){
                if(!nav.completion().isDone()){
                    if(arrived()&&nav.status()!=NavigationStatus.COMPUTING)return finishEarly();
                    if(!progress.stalled(v.feetPosition(stand),nav))return State.MOVING;
                    nav.cancel(); // no closer for a while: treat this stand as unreachable
                }
                boolean success=nav.status()==NavigationStatus.SUCCESS;noteRoute(nav);nav=null;
                if(progress.planning)navReason="planning_timeout";
                if(arrived())return arrive();
                if(stand!=null)rejected.add(stand);
                if(!success&&attempts.failed())return State.FAILED;
            }
            if(arrived())return arrive();
            if(attempts.exhausted())return State.FAILED;
            stand=v.stand(target,body().position(),reach,sight,rejected);
            if(stand==null){if(navReason.isEmpty())navReason="no_stand";return State.FAILED;}
            attempts.started();progress.reset();clearRoute();
            var feet=v.feetPosition(stand);
            boolean near=Math.max(Math.abs(feet.x-body().getX()),Math.abs(feet.z-body().getZ()))<=10;
            nav=bot().navigator().goTo(new NavigationGoal.PositionGoal(feet,.35),near?NEAR:TRAVEL);
            return State.MOVING;
        }
        private State finishEarly(){nav.cancel();nav=null;return arrive();}
        private State arrive(){clearRoute();return State.ARRIVED;}
        void cancel(){if(nav!=null&&!nav.completion().isDone())nav.cancel();nav=null;}
    }

    /** Walks to a point; used for places rather than blocks (rally points, sheep, beds). */
    final class Walk {
        final Vec3 point;
        final double radius;
        NavigationTask nav;
        int attempts;
        final Progress progress=new Progress();
        final NavigationPolicy policy;
        Walk(Vec3 point,double radius){this(point,radius,TRAVEL);}
        Walk(Vec3 point,double radius,NavigationPolicy policy){this.point=point;this.radius=radius;this.policy=policy;navReason="";}
        Approach.State tick(){
            if(body().position().distanceToSqr(point)<=radius*radius){cancel();clearRoute();return Approach.State.ARRIVED;}
            if(nav!=null&&!nav.completion().isDone()){
                if(!progress.stalled(point,nav))return Approach.State.MOVING;
                nav.cancel();
            }
            noteRoute(nav);
            if(nav!=null&&progress.planning)navReason="planning_timeout";
            if(nav!=null&&nav.status()!=NavigationStatus.SUCCESS&&++attempts>=3)return Approach.State.FAILED;
            progress.reset();clearRoute();
            nav=bot().navigator().goTo(new NavigationGoal.PositionGoal(point,Math.max(.35,radius*.5)),policy);
            return Approach.State.MOVING;
        }
        void cancel(){if(nav!=null&&!nav.completion().isDone())nav.cancel();nav=null;}
    }

    /** Breaks one block with the library miner, then picks up what it dropped. */
    final class Harvest {
        enum State { WORKING, DONE, FAILED }
        final BlockPos target;
        MiningTask mining;
        long brokeAt,collectUntil;
        String error="";
        Harvest(BlockPos target){this.target=target;}
        State tick(){
            if(mining==null&&collectUntil==0){mining=bot().miner().breakBlock(target);return State.WORKING;}
            if(mining!=null){
                if(!mining.completion().isDone())return State.WORKING;
                boolean ok=mining.status()==MiningStatus.SUCCESS;
                if(!ok){error=mining.completion().join().reason();mining=null;stopPickup();return State.FAILED;}
                mining=null;brokeAt=now();collectUntil=now()+60;
            }
            // Fresh drops are on their pickup delay: the harvest waits a little for them even when none shows yet.
            if(Gathering.keepCollecting(collect(target,6),now(),brokeAt,collectUntil))return State.WORKING;
            stopPickup();return State.DONE;
        }
        void cancel(){if(mining!=null&&!mining.completion().isDone())mining.cancel();mining=null;stopPickup();}
    }

    record Need(java.util.function.Predicate<net.minecraft.world.item.ItemStack> test,int amount,String label){}
    /** Walks from chest to chest until the backpack holds every need; {@link #missing} names what the store lacks. */
    final class Fetch {
        final List<Need> needs;
        final Set<BlockPos> tried=new HashSet<>();
        net.minecraft.world.level.block.entity.ChestBlockEntity chest;
        Approach approach;
        String missing="";
        /** The store holds what is missing, but only in a chest this worker skips as unreachable right now. */
        boolean hidden;
        Fetch(List<Need> needs){this.needs=needs;}
        int outstanding(Need n){return Math.max(0,n.amount()-w.count(n.test()));}
        Approach.State tick(){
            Need first=needs.stream().filter(n->outstanding(n)>0).findFirst().orElse(null);
            if(first==null){cancel();return Approach.State.ARRIVED;}
            if(chest==null||!v.stock.chests().contains(chest)){
                skipUnreachable(tried);
                chest=v.stock.nearest(body().blockPosition(),first.test(),tried);
                if(chest==null){missing=first.label();hidden=v.stock.hidden(first.test(),w.id);return Approach.State.FAILED;}
                approach=new Approach(chest.getBlockPos(),4.0,true);
            }
            var state=approach.tick();
            if(state==Approach.State.FAILED){unreachable(chest.getBlockPos());tried.add(chest.getBlockPos());chest=null;return Approach.State.MOVING;}
            if(state==Approach.State.MOVING)return state;
            v.stock.unreachable.forget(chest.getBlockPos());
            for(Need n:needs){int want=outstanding(n);if(want>0)Supplies.transfer(bot(),chest,false,n.test(),want);}
            v.stock.changed(chest);tried.add(chest.getBlockPos());chest=null;approach=null;
            return Approach.State.MOVING;
        }
        void cancel(){if(approach!=null)approach.cancel();}
    }
    /** Hands every item the worker does not keep to the nearest chests with room. */
    final class Deposit {
        final Set<BlockPos> tried=new HashSet<>();
        net.minecraft.world.level.block.entity.ChestBlockEntity chest;
        Approach approach;
        Approach.State tick(){
            if(!w.hasSurplus()){cancel();return Approach.State.ARRIVED;}
            if(chest==null||!v.stock.chests().contains(chest)){
                skipUnreachable(tried);
                chest=v.stock.nearestWithRoom(body().blockPosition(),tried);
                if(chest==null)return Approach.State.FAILED;
                approach=new Approach(chest.getBlockPos(),4.0,true);
            }
            var state=approach.tick();
            if(state==Approach.State.FAILED){unreachable(chest.getBlockPos());tried.add(chest.getBlockPos());chest=null;return Approach.State.MOVING;}
            if(state==Approach.State.MOVING)return state;
            v.stock.unreachable.forget(chest.getBlockPos());
            var bag=w.bag();var kept=w.keptSlots();int moved=0;
            for(int i=0;i<bag.size();i++)if(!bag.get(i).isEmpty()&&!kept.contains(i)){
                var item=bag.get(i);moved+=Supplies.transfer(bot(),chest,true,s->ItemStack.isSameItemSameComponents(s,item),item.getCount());
            }
            if(moved>0){v.stock.changed(chest);v.chronicle.deposited(moved);}
            tried.add(chest.getBlockPos());chest=null;approach=null;
            return Approach.State.MOVING;
        }
        void cancel(){if(approach!=null)approach.cancel();}
    }

    NavigationTask pickupWalk;
    long pickupRouteAt=-NavHygiene.PICKUP_REROUTE;
    /**
     * Picks up nearby drops; returns true while there is still something worth walking to or waiting for.
     * Fresh drops on their pickup delay count, drops the bag has no room for do not.
     */
    boolean collect(BlockPos around,double radius){
        w.pickup();
        var bag=w.bag();
        var drops=v.level.getEntitiesOfClass(ItemEntity.class,new net.minecraft.world.phys.AABB(around).inflate(radius),
                e->e.isAlive()&&NavHygiene.pending(e.hasPickUpDelay(),e.getAge())&&NavHygiene.fits(bag,e.getItem()));
        var nearest=drops.stream().min(Comparator.comparingDouble(e->e.distanceToSqr(body()))).orElse(null);
        if(nearest==null)return false;
        if(w.reach().intersects(nearest.getBoundingBox()))return true; // in reach already: its delay ends without a route
        if((pickupWalk==null||pickupWalk.completion().isDone())&&NavHygiene.mayReroute(now(),pickupRouteAt)){
            pickupRouteAt=now();
            pickupWalk=bot().navigator().goTo(new NavigationGoal.PositionGoal(nearest.position(),.6),TRAVEL);
        }
        return true;
    }
    void stopPickup(){if(pickupWalk!=null&&!pickupWalk.completion().isDone())pickupWalk.cancel();pickupWalk=null;}
}
