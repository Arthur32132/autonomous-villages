package dev.autonomousvillages;

import java.util.*;
import java.util.function.ToDoubleBiFunction;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * A farmer feeds wheat to two adult sheep near the village; vanilla does the rest (hearts, a lamb).
 * More sheep means more wool for beds once the village has shears.
 */
final class HerdJob extends Job {
    /** Vanilla animals in love look for a mate within 8 blocks. */
    static final double BREEDING_RANGE=8;
    /** New routes after a sheep that walked off, for the whole job: a flock on the move is not chased all day. */
    static final int MAX_RETARGETS=12;
    private Fetch fetch;
    private Sheep first,sheep;
    private Walk walk;
    private int fed,retargets;
    HerdJob(Worker w){super(w,"кормит овец");}
    @Override String kind(){return "herd";}
    @Override int timeLimit(){return 2400;}
    @Override boolean keeps(ItemStack s){return s.is(Items.WHEAT);}
    static List<Sheep> flock(Village v,net.minecraft.world.entity.Entity from,double range){
        return v.level.getEntitiesOfClass(Sheep.class,from.getBoundingBox().inflate(range),s->s.isAlive()&&v.canWork(s.blockPosition()));
    }
    /** The mate for the sheep fed first: the nearest other candidate within breeding range of it, or null. */
    static <T> T partner(T first,Collection<T> candidates,ToDoubleBiFunction<T,T> distSqr){
        T best=null;double nearest=BREEDING_RANGE*BREEDING_RANGE;
        for(T c:candidates){if(c.equals(first))continue;double d=distSqr.applyAsDouble(first,c);if(d<=nearest){best=c;nearest=d;}}
        return best;
    }
    /** The sheep to feed first: the nearest one (candidates come nearest first) that has a mate within breeding range. */
    static <T> T pairStart(List<T> candidates,ToDoubleBiFunction<T,T> distSqr){
        for(T c:candidates)if(partner(c,candidates,distSqr)!=null)return c;
        return null;
    }
    @Override boolean tick(){
        if(w.count(s->s.is(Items.WHEAT))<1){
            if(fed>=2)return false;
            if(fetch==null)fetch=new Fetch(List.of(new Need(s->s.is(Items.WHEAT),2-fed,"пшеница")));
            var state=fetch.tick();
            if(state==Approach.State.FAILED)return fed>0?false:fail("нет пшеницы");
            if(state==Approach.State.MOVING)return true;
        }
        if(sheep==null||!sheep.isAlive()||!sheep.canFallInLove()||sheep.isBaby()){
            var candidates=flock(v,body(),48).stream().filter(s->!s.isBaby()&&s.canFallInLove()&&s.getAge()==0)
                    .sorted(Comparator.comparingDouble(s->s.distanceToSqr(body()))).toList();
            ToDoubleBiFunction<Sheep,Sheep> apart=(a,b)->a.distanceToSqr(b);
            sheep=first==null?pairStart(candidates,apart):first.isAlive()?partner(first,candidates,apart):null;
            walk=null;
            if(sheep==null)return fed>0?false:fail("рядом нет взрослых овец");
        }
        if(body().distanceToSqr(sheep)>6.25){
            if(walk==null||walk.point.distanceToSqr(sheep.position())>9){
                if(walk!=null&&++retargets>MAX_RETARGETS)return fed>0?false:fail("не подойти к овце");
                walk=new Walk(sheep.position(),1.8);
            }
            if(walk.tick()==Approach.State.FAILED){sheep=null;return fed>0?false:fail("не подойти к овце");}
            return true;
        }
        if(!Supplies.consume(bot(),Items.WHEAT,1))return false;
        sheep.setInLove(null);body().swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        fed++;if(first==null)first=sheep;sheep=null;walk=null;
        if(fed>=2){v.chronicle.add("herd",1);return false;}
        return true;
    }
    @Override void stop(){if(fetch!=null)fetch.cancel();if(walk!=null)walk.cancel();}
}
