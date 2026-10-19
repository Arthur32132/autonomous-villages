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
 * Spare saplings and seeds go into the village composter; the bone meal it gives makes the young
 * crops grow at once, with the vanilla green sparkles. Wheat ripens in a day or two otherwise, and
 * bread for a new child is the slowest thing a village makes.
 */
final class CompostJob extends Job {
    /** Saplings for replanting and seeds for the fields stay; the rest is compost. */
    static final int SAPLING_RESERVE=16,SEED_RESERVE=32;
    static final Predicate<ItemStack> COMPOST=s->s.is(ItemTags.SAPLINGS)||s.is(Items.WHEAT_SEEDS)||s.is(Items.BEETROOT_SEEDS)||s.is(Items.PUMPKIN_SEEDS)||s.is(Items.MELON_SEEDS);
    private final BlockPos composter;
    private final int toFetch;
    private Fetch fetch;
    private Approach approach;
    private final ArrayDeque<BlockPos> crops=new ArrayDeque<>();
    private int fed,grown;
    private long nextAction;
    CompostJob(Worker w,BlockPos composter,int toFetch){super(w,"делает компост");this.composter=composter;this.toFetch=toFetch;}
    @Override String kind(){return "compost";}
    @Override int timeLimit(){return 3600;}
    @Override BlockPos target(){return composter;}
    @Override boolean keeps(ItemStack s){return COMPOST.test(s)||s.is(Items.BONE_MEAL);}

    /** Spare compost in the store: saplings beyond the replanting reserve, seeds beyond the sowing reserve. */
    static int spare(Village v){
        int saplings=v.stock.count(s->s.is(ItemTags.SAPLINGS)),seeds=v.stock.count(COMPOST.and(s->!s.is(ItemTags.SAPLINGS)));
        return Math.max(0,saplings-SAPLING_RESERVE)+Math.max(0,seeds-SEED_RESERVE);
    }
    static boolean young(Village v,BlockPos p){
        var s=v.level.getBlockState(p);
        return s.getBlock() instanceof CropBlock crop&&!crop.isMaxAge(s);
    }

    @Override boolean tick(){
        if(now()<nextAction)return true;
        // 1. Spare compost from the store.
        if(toFetch>0&&fetch==null)fetch=new Fetch(List.of(new Need(COMPOST,toFetch,"компост")));
        if(fetch!=null&&w.count(COMPOST)<toFetch&&w.count(s->s.is(Items.BONE_MEAL))==0){
            var state=fetch.tick();
            if(state==Approach.State.MOVING)return true;
        }
        // 2. Feed the composter and empty it whenever it is ready.
        var state=v.level.getBlockState(composter);
        if(!(state.getBlock() instanceof ComposterBlock))return fail("компостера нет");
        int level=state.getValue(ComposterBlock.LEVEL);boolean ready=level==ComposterBlock.READY;
        if(w.count(s->s.is(Items.BONE_MEAL))==0&&(ready||level==7||w.count(COMPOST)>0&&level<7)){
            if(approach==null)approach=new Approach(composter,3.5,true);
            var reach=approach.tick();
            if(reach==Approach.State.MOVING)return true;
            if(reach==Approach.State.FAILED)return fail("не подойти к компостеру");
            if(level==7){nextAction=now()+10;return true;} // the compost settles for a second
            if(ready){
                v.level.setBlock(composter,state.setValue(ComposterBlock.LEVEL,0),3);
                v.level.playSound(null,composter,SoundEvents.COMPOSTER_EMPTY,SoundSource.BLOCKS,1f,1f);
                var rest=bot().inventory().insert(new ItemStack(Items.BONE_MEAL));
                if(!rest.isEmpty())return false;
            }else{
                int slot=Supplies.find(bot(),COMPOST);
                var one=w.bag().get(slot).copyWithCount(1);
                ComposterBlock.insertItem(body(),state,v.level,one,composter);
                Supplies.consume(bot(),w.bag().get(slot).getItem(),1);fed++;
            }
            body().swing(InteractionHand.MAIN_HAND);nextAction=now()+6;
            return true;
        }
        // 3. Bone meal on the young crops.
        if(w.count(s->s.is(Items.BONE_MEAL))>0){
            if(crops.isEmpty()){
                BlockPos from=body().blockPosition();
                v.index.crops.stream().filter(p->young(v,p)&&v.canWork(p)).sorted(Comparator.comparingDouble(p->p.distSqr(from))).limit(8).forEach(crops::add);
                if(crops.isEmpty())return finish();
                approach=null;
            }
            BlockPos crop=crops.peek();
            if(!young(v,crop)){crops.poll();approach=null;return true;}
            if(approach==null||!approach.target.equals(crop))approach=new Approach(crop,4.0,false);
            var reach=approach.tick();
            if(reach==Approach.State.MOVING)return true;
            if(reach==Approach.State.FAILED){crops.poll();approach=null;return true;}
            // A scoop of good compost is spread over the bed around the crop, not a single plant.
            int boosted=0;
            for(BlockPos p:BlockPos.betweenClosed(crop.offset(-1,0,-1),crop.offset(1,0,1))){
                if(!young(v,p)||!v.canWork(p))continue;
                if(BoneMealItem.growCrop(new ItemStack(Items.BONE_MEAL),v.level,p)){boosted++;v.level.levelEvent(1505,p,15);}
            }
            if(boosted>0){Supplies.consume(bot(),Items.BONE_MEAL,1);grown+=boosted;body().swing(InteractionHand.MAIN_HAND);}
            crops.poll();
            nextAction=now()+10;
            return true;
        }
        return finish();
    }
    private boolean finish(){
        if(fed+grown>0)v.chronicle.add("composted",fed);
        if(grown>0)v.chronicle.add("fertilized",grown);
        return false;
    }
    @Override void stop(){if(fetch!=null)fetch.cancel();if(approach!=null)approach.cancel();}
}
