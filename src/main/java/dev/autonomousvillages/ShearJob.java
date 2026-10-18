package dev.autonomousvillages;

import java.util.*;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Walks to woolly sheep near the village, shears them with real shears and gathers the wool. */
final class ShearJob extends Job {
    private Sheep sheep;
    private Walk walk;
    private int sheared;
    private long collectUntil;
    private Fetch fetch;
    ShearJob(Worker w){super(w,"стрижёт овец");}
    @Override String kind(){return "shear";}
    @Override int timeLimit(){return 2400;}
    @Override boolean keeps(ItemStack s){return s.is(Items.SHEARS);}
    static Sheep nearestSheep(Village v,net.minecraft.world.entity.Entity from,double range){
        return v.level.getEntitiesOfClass(Sheep.class,from.getBoundingBox().inflate(range),s->s.isAlive()&&s.readyForShearing()&&!s.hasCustomName()&&!s.isLeashed()&&v.canWork(s.blockPosition()))
                .stream().min(Comparator.comparingDouble(from::distanceToSqr)).orElse(null);
    }
    @Override boolean tick(){
        if(collectUntil>0){
            if(collect(body().blockPosition(),6)&&now()<collectUntil)return true;
            stopPickup();collectUntil=0;if(sheared>=4)return false;
        }
        int shears=Supplies.find(bot(),s->s.is(Items.SHEARS)&&Supplies.usable(s));
        if(shears<0){
            if(fetch==null)fetch=new Fetch(java.util.List.of(new Need(s->s.is(Items.SHEARS)&&Supplies.usable(s),1,"ножницы")));
            var fetched=fetch.tick();
            if(fetched==Approach.State.FAILED)return fail("нет ножниц");
            return true;
        }
        if(sheep==null||!sheep.isAlive()||!sheep.readyForShearing()){sheep=nearestSheep(v,body(),48);walk=null;if(sheep==null)return sheared>0||fail("рядом нет овец с шерстью");}
        if(body().distanceToSqr(sheep)>6.25||!body().hasLineOfSight(sheep)){
            if(walk==null||walk.point.distanceToSqr(sheep.position())>9)walk=new Walk(sheep.position(),1.8);
            if(walk.tick()==Approach.State.FAILED)sheep=null;
            return true;
        }
        bot().inventory().select(shears);
        sheep.shear(v.level,SoundSource.NEUTRAL,body().getMainHandItem());
        body().getMainHandItem().hurtAndBreak(1,body(),EquipmentSlot.MAINHAND);
        sheared++;sheep=null;walk=null;collectUntil=now()+60;v.chronicle.add("sheared",1);
        return true;
    }
    @Override void stop(){if(walk!=null)walk.cancel();stopPickup();}
}
