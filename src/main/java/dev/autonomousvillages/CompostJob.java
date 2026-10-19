package dev.autonomousvillages;

import java.util.*;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.CropBlock;

/**
 * Spare saplings go into the village composter; the bone meal it gives makes the young crops grow
 * at once, with the vanilla green sparkles. Wheat ripens in a day or two otherwise, and bread for a
 * new child is the slowest thing a village makes. Seeds for sowing are never composted.
 */
final class CompostJob extends Job {
    /** Saplings for replanting stay in the store; the rest is compost. */
    static final int SAPLING_RESERVE=16;
    static final Predicate<ItemStack> COMPOST=s->s.is(ItemTags.SAPLINGS)||s.is(Items.PUMPKIN_SEEDS)||s.is(Items.MELON_SEEDS);
    static final Predicate<ItemStack> BONE_MEAL=s->s.is(Items.BONE_MEAL);
    private final BlockPos composter;
    private final int toFetch,mealToFetch;
    private Fetch fetch;
    private boolean fetched;
    private Approach toComposter,toCrop;
    private final ArrayDeque<BlockPos> crops=new ArrayDeque<>();
    private int fed,grown;
    private long nextAction;
    CompostJob(Worker w,BlockPos composter,int toFetch,int mealToFetch){
        super(w,"делает компост");this.composter=composter;this.toFetch=toFetch;this.mealToFetch=mealToFetch;
    }
    @Override String kind(){return "compost";}
    @Override int timeLimit(){return 3600;}
    @Override BlockPos target(){return composter;}
    @Override boolean keeps(ItemStack s){return COMPOST.test(s)||BONE_MEAL.test(s);}

    /** Spare compost in the store: saplings beyond the replanting reserve, and melon or pumpkin seeds nobody sows. */
    static int spare(Village v){
        return Math.max(0,v.stock.count(s->s.is(ItemTags.SAPLINGS))-SAPLING_RESERVE)+v.stock.count(s->s.is(Items.PUMPKIN_SEEDS)||s.is(Items.MELON_SEEDS));
    }
    static boolean young(Village v,BlockPos p){
        var s=v.level.getBlockState(p);
        return s.getBlock() instanceof CropBlock crop&&!crop.isMaxAge(s);
    }

    @Override boolean tick(){
        if(now()<nextAction)return true;
        // 1. Spare compost and any bone meal from the store, once.
        if(!fetched){
            if(fetch==null){
                var needs=new ArrayList<Need>();
                if(toFetch>0)needs.add(new Need(COMPOST,toFetch,"компост"));
                if(mealToFetch>0)needs.add(new Need(BONE_MEAL,mealToFetch,"костная мука"));
                fetch=new Fetch(needs);
            }
            if(fetch.tick()==Approach.State.MOVING)return true;
            fetched=true;
        }
        // 2. Feed the composter and empty it whenever it is ready.
        var state=v.level.getBlockState(composter);
        if(!(state.getBlock() instanceof ComposterBlock))return fail("компостера нет");
        int level=state.getValue(ComposterBlock.LEVEL);boolean ready=level==ComposterBlock.READY;
        if(w.count(BONE_MEAL)==0&&(ready||level==7||w.count(COMPOST)>0&&level<7)){
            if(toComposter==null)toComposter=new Approach(composter,3.5,true);
            var reach=toComposter.tick();
            if(reach==Approach.State.MOVING)return true;
            if(reach==Approach.State.FAILED)return fail("не подойти к компостеру");
            if(level==7){nextAction=now()+10;return true;} // the compost settles for a second
            if(ready){
                // The bone meal goes into the bag first; the composter is emptied only once it fits.
                if(!bot().inventory().insert(new ItemStack(Items.BONE_MEAL)).isEmpty())return finish();
                v.level.setBlock(composter,state.setValue(ComposterBlock.LEVEL,0),3);
                v.level.playSound(null,composter,SoundEvents.COMPOSTER_EMPTY,SoundSource.BLOCKS,1f,1f);
            }else{
                int slot=Supplies.find(bot(),COMPOST);
                var item=w.bag().get(slot).getItem();
                if(!Supplies.consume(bot(),item,1))return finish();
                ComposterBlock.insertItem(body(),state,v.level,new ItemStack(item),composter);fed++;
            }
            body().swing(InteractionHand.MAIN_HAND);nextAction=now()+6;
            return true;
        }
        // 3. Bone meal on the young crops.
        if(w.count(BONE_MEAL)>0){
            if(crops.isEmpty()){
                BlockPos from=body().blockPosition();
                v.index.crops.stream().filter(p->young(v,p)&&v.canWork(p)).sorted(Comparator.comparingDouble(p->p.distSqr(from))).limit(8).forEach(crops::add);
                if(crops.isEmpty())return finish();
            }
            BlockPos crop=crops.peek();
            if(!young(v,crop)){crops.poll();toCrop=null;return true;}
            if(toCrop==null||!toCrop.target.equals(crop))toCrop=new Approach(crop,4.0,false);
            var reach=toCrop.tick();
            if(reach==Approach.State.MOVING)return true;
            if(reach==Approach.State.FAILED){crops.poll();toCrop=null;return true;}
            // A scoop of good compost is spread over the bed around the crop, not a single plant.
            if(!Supplies.consume(bot(),Items.BONE_MEAL,1))return finish();
            for(BlockPos p:BlockPos.betweenClosed(crop.offset(-1,0,-1),crop.offset(1,0,1))){
                if(!young(v,p)||!v.canWork(p))continue;
                if(BoneMealItem.growCrop(new ItemStack(Items.BONE_MEAL),v.level,p)){grown++;v.level.levelEvent(1505,p,15);}
            }
            body().swing(InteractionHand.MAIN_HAND);
            crops.poll();toCrop=null;nextAction=now()+10;
            return true;
        }
        return finish();
    }
    private boolean finish(){
        if(fed>0)v.chronicle.add("composted",fed);
        if(grown>0)v.chronicle.add("fertilized",grown);
        return false;
    }
    @Override void stop(){if(fetch!=null)fetch.cancel();if(toComposter!=null)toComposter.cancel();if(toCrop!=null)toCrop.cancel();}
}
