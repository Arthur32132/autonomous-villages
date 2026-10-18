package dev.autonomousvillages;

import java.util.ArrayList;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;

/** Loads a real furnace (ore, sand or logs for charcoal) and collects whatever it has finished. */
final class SmeltJob extends Job {
    final BlockPos furnace;
    final Predicate<ItemStack> input;
    final int amount;
    private Fetch fetch;
    private Approach approach;
    SmeltJob(Worker w,BlockPos furnace,Predicate<ItemStack> input,int amount,String label){
        super(w,"плавит: "+label);this.furnace=furnace;this.input=input;this.amount=amount;
    }
    @Override String kind(){return "smelt";}
    @Override BlockPos target(){return furnace;}
    @Override boolean keeps(ItemStack s){return input.test(s)||VillageCrafting.FUEL.test(s)||s.is(net.minecraft.tags.ItemTags.PLANKS);}
    @Override boolean tick(){
        if(amount>0&&fetch==null){
            var needs=new ArrayList<Need>();
            needs.add(new Need(input,amount,"сырьё"));
            boolean coal=v.stock.count(VillageCrafting.FUEL)>=2;
            needs.add(new Need(coal?VillageCrafting.FUEL:VillageCrafting.PLANK,coal?2:4,"топливо"));
            fetch=new Fetch(needs);
        }
        if(fetch!=null){
            var state=fetch.tick();
            if(state==Approach.State.MOVING)return true;
            if(state==Approach.State.FAILED&&w.count(input)==0)return fail("нет на складе: "+fetch.missing);
        }
        if(approach==null)approach=new Approach(furnace,3.5,true);
        var state=approach.tick();
        if(state==Approach.State.MOVING)return true;
        if(state==Approach.State.FAILED)return fail("печь недоступна");
        if(v.level.getBlockEntity(furnace) instanceof FurnaceBlockEntity f&&VillageCrafting.serviceFurnace(bot(),v,f,input))v.chronicle.event(status.replace("плавит: ","Загрузили печь: "));
        return false;
    }
    @Override void stop(){if(fetch!=null)fetch.cancel();if(approach!=null)approach.cancel();}
}
