package dev.autonomousvillages;

import java.util.*;
import java.util.function.ToDoubleBiFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/**
 * Livestock work of one resident: feeding a pair of cows (wheat) or chickens (seeds) from the store, the daily egg round
 * through the yard, or culling one adult of a surplus herd with a sword or an axe. Everything picked up goes to the store.
 */
final class LivestockJob extends Job {
    static final String KIND="livestock";
    enum Mode { BREED, EGGS, CULL }
    /** New routes after an animal that walked or ran off: for the whole job, or until the next blow when culling. */
    static final int MAX_RETARGETS=16;
    /** Ticks between two blows, about a sword's recharge. */
    static final int HIT_EVERY=15;
    static final int MAX_EGGS=16;
    final Mode mode;
    final LivestockFeature.Kind kind;
    private final LivestockFeature feature;
    private Fetch fetch;
    private Deposit deposit;
    private Walk walk;
    private Animal first,animal;
    private ItemEntity egg;
    private int fed,retargets,hits,eggsBefore=-1;
    private long nextHit,collectUntil,killedTime;
    private BlockPos killedAt;
    /** Eggs no route reached or the bag could not take: passed over for the rest of the round. */
    private final Set<UUID> skipped=new HashSet<>();
    LivestockJob(Worker w,LivestockFeature feature,Mode mode,LivestockFeature.Kind kind){
        super(w,switch(mode){case BREED->"кормит "+kind.plural;case EGGS->"собирает яйца";case CULL->"забивает "+kind.one+" на мясо";});
        this.feature=feature;this.mode=mode;this.kind=kind;
    }
    @Override String kind(){return KIND;}
    @Override int timeLimit(){return mode==Mode.EGGS?1800:2400;}
    @Override boolean keeps(ItemStack s){return mode==Mode.BREED&&deposit==null&&kind.feed.test(s);}

    /** Attack damage of a weapon as a player deals it: the base 1 plus the item's own modifiers. */
    static float damage(ItemStack s){
        var modifiers=s.get(DataComponents.ATTRIBUTE_MODIFIERS);
        return (float)(modifiers==null?1:modifiers.compute(Attributes.ATTACK_DAMAGE,1,EquipmentSlot.MAINHAND));
    }
    static boolean weapon(ItemStack s){return (s.is(ItemTags.SWORDS)||s.is(ItemTags.AXES))&&Supplies.usable(s);}
    /** The bag slot of the hardest-hitting usable sword or axe, or -1. */
    static int weapon(Worker w){
        var bag=w.bag();int best=-1;float most=0;
        for(int i=0;i<bag.size();i++){var s=bag.get(i);if(!s.isEmpty()&&weapon(s)&&damage(s)>most){best=i;most=damage(s);}}
        return best;
    }
    /** The pair to feed, nearest first: the same choice {@link HerdJob} makes, first animal then its mate; empty when none. */
    static <T> List<T> pair(List<T> nearestFirst,ToDoubleBiFunction<T,T> distSqr){
        T start=HerdJob.pairStart(nearestFirst,distSqr);
        return start==null?List.of():List.of(start,HerdJob.partner(start,nearestFirst,distSqr));
    }

    @Override boolean tick(){
        if(deposit!=null){
            if(deposit.tick()==Approach.State.MOVING)return true;
            v.stock.invalidate();return false;
        }
        return switch(mode){case BREED->breed();case EGGS->eggs();case CULL->cull();};
    }

    /** Walks after a moving animal; true while still on the way, false once it is within reach. A route that keeps failing ends the job. */
    private Boolean chase(net.minecraft.world.entity.Entity target,double reach){
        if(body().distanceToSqr(target)<=reach*reach&&body().hasLineOfSight(target))return false;
        if(walk!=null&&walk.point.distanceToSqr(target.position())>9){walk.cancel();walk=null;retargets++;}
        if(retargets>MAX_RETARGETS)return null;
        if(walk==null)walk=new Walk(target.position(),Math.max(.8,reach-.7));
        var route=walk.tick();
        if(route==Approach.State.FAILED){walk=null;return null;}
        if(route==Approach.State.ARRIVED){walk=null;retargets++;}
        return true;
    }

    // --- breeding: like HerdJob, for cows and chickens -------------------------------------------
    private boolean breed(){
        if(w.count(kind.feed)<1){
            if(fed>=2)return false;
            if(fetch==null)fetch=new Fetch(List.of(new Need(kind.feed,2-fed,kind.feedLabel)));
            var state=fetch.tick();
            if(state==Approach.State.FAILED)return fed>0?false:fail("нет корма: "+kind.feedLabel);
            if(state==Approach.State.MOVING)return true;
        }
        if(animal==null||!LivestockFeature.touchable(v,animal)||!animal.canFallInLove()||animal.isBaby()||animal.getAge()!=0){
            var candidates=LivestockFeature.animals(v,kind,body().getBoundingBox().inflate(48),a->!a.isBaby()&&a.canFallInLove()&&a.getAge()==0&&LivestockFeature.touchable(v,a))
                    .stream().sorted(Comparator.comparingDouble(a->a.distanceToSqr(body()))).toList();
            ToDoubleBiFunction<Animal,Animal> apart=(a,b)->a.distanceToSqr(b);
            if(first==null){var pair=pair(candidates,apart);animal=pair.isEmpty()?null:pair.getFirst();}
            else animal=first.isAlive()?HerdJob.partner(first,candidates,apart):null;
            if(walk!=null)walk.cancel();
            walk=null;
            if(animal==null)return fed>0?false:fail("рядом нет пары "+kind.plural);
        }
        Boolean away=chase(animal,2.5);
        if(away==null){animal=null;return fed>0?false:fail("не подойти к животным");}
        if(away)return true;
        ItemStack food=w.bag().stream().filter(s->!s.isEmpty()&&animal.isFood(s)).findFirst().orElse(ItemStack.EMPTY);
        if(food.isEmpty()||!Supplies.consume(bot(),food.getItem(),1))return fed>0?false:fail("нет корма: "+kind.feedLabel);
        animal.setInLove(null);body().swing(InteractionHand.MAIN_HAND);
        fed++;if(first==null)first=animal;animal=null;walk=null;
        if(fed>=2){feature.bred(v,kind,first.blockPosition(),now());v.chronicle.add("livestock:fed:"+kind.name().toLowerCase(Locale.ROOT),1);return false;}
        return true;
    }

    // --- eggs: once a day through the yard -------------------------------------------------------------
    private boolean eggs(){
        w.pickup();
        if(eggsBefore<0)eggsBefore=w.count(LivestockFeature.EGG);
        int picked=Math.max(0,w.count(LivestockFeature.EGG)-eggsBefore);
        if(egg==null||!egg.isAlive()){
            var left=picked<MAX_EGGS&&w.freeSlots()>0?LivestockFeature.eggs(v,LivestockFeature.yardBox(v.state)).stream()
                    .filter(e->!e.hasPickUpDelay()&&!skipped.contains(e.getUUID())).min(Comparator.comparingDouble(e->e.distanceToSqr(body()))).orElse(null):null;
            if(walk!=null)walk.cancel();
            egg=left;walk=null;retargets=0;
            if(egg==null){
                if(picked==0&&eggsBefore==0)return fail("яиц не нашлось");
                if(picked>0)v.chronicle.add("livestock:eggs",picked);
                deposit=new Deposit();status="несёт яйца на склад";
                return true;
            }
        }
        Boolean away=chase(egg,1.2);
        if(away==null){skipped.add(egg.getUUID());egg=null;return true;}
        if(away)return true;
        w.pickup();
        // Still lying there within reach: the bag has no room for it; it is left for another day.
        if(egg.isAlive()){skipped.add(egg.getUUID());egg=null;}
        return true;
    }

    // --- culling: one adult of a surplus herd ---------------------------------------------------------
    private boolean cull(){
        if(collectUntil>0){
            if(Gathering.keepCollecting(collect(killedAt,6),now(),killedTime,collectUntil))return true;
            stopPickup();collectUntil=0;deposit=new Deposit();status="несёт мясо на склад";
            return true;
        }
        if(animal!=null&&!animal.isAlive()){
            // Down: its drops fall where it stood, and they are picked up before the store gets them.
            if(hits>0){v.chronicle.add("livestock:culled:"+kind.name().toLowerCase(Locale.ROOT),1);}
            animal=null;killedTime=now();collectUntil=now()+100;
            return true;
        }
        int slot=weapon(w);
        if(slot<0)return fail("нечем забить "+kind.one);
        if(animal==null||!LivestockFeature.touchable(v,animal)){
            if(hits>0)return fail("животное ушло со двора");
            // The decision is rechecked on the spot: the herd may have shrunk since the census.
            var herd=LivestockFeature.census(v,kind);
            if(!LivestockFeature.surplus(herd.herd(),herd.adults(),herd.cullable(),kind.cap))return false;
            animal=LivestockFeature.animals(v,kind,body().getBoundingBox().inflate(48),a->!a.isBaby()&&!a.isInLove()&&LivestockFeature.touchable(v,a))
                    .stream().min(Comparator.comparingDouble(a->a.distanceToSqr(body()))).orElse(null);
            if(walk!=null)walk.cancel();
            walk=null;
            if(animal==null)return fail("рядом нет лишних "+kind.plural);
        }
        killedAt=animal.blockPosition();
        Boolean away=chase(animal,2.5);
        if(away==null)return fail("не догнать "+kind.one);
        if(away||now()<nextHit)return true;
        ItemStack held=bot().inventory().select(slot)?body().getMainHandItem():ItemStack.EMPTY;
        if(!weapon(held))return fail("нечем забить "+kind.one);
        body().swing(InteractionHand.MAIN_HAND);
        animal.hurtServer(v.level,v.level.damageSources().mobAttack(body()),damage(held));
        held.hurtAndBreak(1,body(),EquipmentSlot.MAINHAND);
        hits++;nextHit=now()+HIT_EVERY;retargets=0;
        return true;
    }

    @Override void stop(){if(fetch!=null)fetch.cancel();if(walk!=null)walk.cancel();if(deposit!=null)deposit.cancel();stopPickup();}
}
