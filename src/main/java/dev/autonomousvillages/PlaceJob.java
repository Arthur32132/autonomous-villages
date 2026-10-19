package dev.autonomousvillages;

import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** Places one utility block (chest, workbench, furnace) at a chosen spot near the village store. */
final class PlaceJob extends Job {
    final BlockPos spot;
    final BlockState state;
    final Predicate<ItemStack> item;
    private Fetch fetch;
    private Approach approach;
    PlaceJob(Worker w,BlockPos spot,BlockState state,String label){
        super(w,"ставит: "+label);this.spot=spot.immutable();this.state=state;
        var wanted=state.getBlock().asItem();this.item=s->s.is(wanted);
    }
    @Override String kind(){return "place";}
    @Override BlockPos target(){return spot;}
    @Override boolean keeps(ItemStack s){return item.test(s);}
    @Override boolean tick(){
        if(!w.has(item)){
            if(fetch==null)fetch=new Fetch(List.of(new Need(item,1,status)));
            var fetched=fetch.tick();
            if(fetched==Approach.State.FAILED)return fail("нет на складе: "+fetch.missing);
            if(fetched==Approach.State.MOVING)return true;
        }
        if(approach==null)approach=new Approach(spot,4.0,false);
        var s=approach.tick();
        if(s==Approach.State.MOVING)return true;
        if(s==Approach.State.FAILED)return fail("не подойти к месту установки");
        if(!(state.getBlock().asItem() instanceof BlockItem blockItem))return false;
        if(!Construction.place(v,bot(),new Construction.Step(spot,state,blockItem)))return fail("не удалось поставить");
        v.index.tables.removeIf(p->!v.level.getBlockState(p).is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE));
        v.chronicle.event(status.replace("ставит: ","Поставили: "));
        if(state.getBlock() instanceof net.minecraft.world.level.block.BedBlock)v.chronicle.add("spareBeds",1);
        return false;
    }
    @Override void stop(){if(fetch!=null)fetch.cancel();if(approach!=null)approach.cancel();}
}
