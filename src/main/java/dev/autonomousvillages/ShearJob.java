package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Walks to woolly sheep near the village, shears them with real shears and gathers the wool. */
final class ShearJob extends Job {
    /** A sheep reached yet out of reach (a fence, a sheep on the move) this often counts as a failed route. */
    static final int MAX_RETARGETS=8;
    private Sheep sheep;
    private Walk walk;
    private int sheared,retargets;
    private long collectUntil,lastShear;
    private BlockPos shearedAt;
    private Fetch fetch;
    /** Two failed routes to one sheep and it is left alone for the rest of the job. */
    private final Gathering.Strikes<UUID> strikes=new Gathering.Strikes<>(2);
    ShearJob(Worker w){super(w,"стрижёт овец");}
    @Override String kind(){return "shear";}
    @Override int timeLimit(){return 2400;}
    @Override boolean keeps(ItemStack s){return s.is(Items.SHEARS);}
    static Sheep nearestSheep(Village v,net.minecraft.world.entity.Entity from,double range){return nearestSheep(v,from,range,id->false);}
    static Sheep nearestSheep(Village v,net.minecraft.world.entity.Entity from,double range,java.util.function.Predicate<UUID> skip){
        return v.level.getEntitiesOfClass(Sheep.class,from.getBoundingBox().inflate(range),s->s.isAlive()&&s.readyForShearing()&&!s.hasCustomName()&&!s.isLeashed()&&v.canWork(s.blockPosition())&&!skip.test(s.getUUID()))
                .stream().min(Comparator.comparingDouble(from::distanceToSqr)).orElse(null);
    }
    @Override boolean tick(){
        if(collectUntil>0){
            if(Gathering.keepCollecting(collect(shearedAt,6),now(),lastShear,collectUntil))return true;
            stopPickup();collectUntil=0;if(sheared>=4)return false;
        }
        int shears=Supplies.find(bot(),s->s.is(Items.SHEARS)&&Supplies.usable(s));
        if(shears<0){
            if(fetch==null)fetch=new Fetch(java.util.List.of(new Need(s->s.is(Items.SHEARS)&&Supplies.usable(s),1,"ножницы")));
            var fetched=fetch.tick();
            if(fetched==Approach.State.FAILED)return fail("нет ножниц");
            return true;
        }
        if(sheep==null||!sheep.isAlive()||!sheep.readyForShearing()){
            sheep=nearestSheep(v,body(),48,strikes::out);walk=null;retargets=0;
            if(sheep==null)return sheared>0?false:fail("рядом нет овец с шерстью");
        }
        if(body().distanceToSqr(sheep)>6.25||!body().hasLineOfSight(sheep)){
            if(walk==null||walk.point.distanceToSqr(sheep.position())>9){if(walk!=null)retargets++;walk=new Walk(sheep.position(),1.8);}
            var route=walk.tick();
            if(route==Approach.State.ARRIVED){retargets++;walk=null;}
            if(route==Approach.State.FAILED||retargets>MAX_RETARGETS){
                if(walk!=null)walk.cancel();
                walk=null;retargets=0;
                if(strikes.strike(sheep.getUUID()))sheep=null;
            }
            return true;
        }
        bot().inventory().select(shears);
        sheep.shear(v.level,SoundSource.NEUTRAL,body().getMainHandItem());
        body().getMainHandItem().hurtAndBreak(1,body(),EquipmentSlot.MAINHAND);
        sheared++;shearedAt=sheep.blockPosition();sheep=null;walk=null;retargets=0;lastShear=now();collectUntil=now()+60;v.chronicle.add("sheared",1);
        return true;
    }
    @Override void stop(){if(walk!=null)walk.cancel();stopPickup();}
}
