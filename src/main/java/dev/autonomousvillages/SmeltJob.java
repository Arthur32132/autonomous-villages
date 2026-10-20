package dev.autonomousvillages;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.phys.Vec3;

/** Loads a real furnace (ore, sand or logs for charcoal) with fuel for the whole batch, collects what is done, returns what the fuel does not cover. */
final class SmeltJob extends Job {
    /** Vanilla burn times: a furnace smelts one item per 200 ticks, coal burns 1600 (8 items), a plank or log 300 (1.5). */
    static final int SMELT_TIME=200,COAL_BURN=1600,WOOD_BURN=300;
    final BlockPos furnace;
    final Predicate<ItemStack> input;
    final int amount;
    private Fetch fetch,fuelFetch;
    private Approach approach;
    private Deposit deposit;
    /** The furnace is served: leftovers are no longer kept and go back to the store. */
    private boolean served;
    SmeltJob(Worker w,BlockPos furnace,Predicate<ItemStack> input,int amount,String label){
        super(w,"плавит: "+label);this.furnace=furnace;this.input=input;this.amount=amount;
    }
    @Override String kind(){return "smelt";}
    @Override BlockPos target(){return furnace;}
    @Override boolean keeps(ItemStack s){return !served&&(input.test(s)||VillageCrafting.FUEL.test(s)||s.is(ItemTags.PLANKS));}

    /** Fuel items that smelt {@code items} when each burns {@code burn} ticks. */
    static int fuelFor(int items,int burn){return items<=0||burn<=0?0:(items*SMELT_TIME+burn-1)/burn;}
    /** Items that {@code fuel} pieces burning {@code burn} ticks each smelt. */
    static int smeltable(int fuel,int burn){return burn<=0||fuel<=0?0:fuel*burn/SMELT_TIME;}
    record Batch(int fuel,int input){}
    /** Fuel for all queued and carried input, then only as much input as the fuel in the slot covers; rooms are what the slots still take. */
    static Batch batch(int queued,int carried,int inputRoom,int slotFuel,int carriedFuel,int fuelRoom,int burn){
        int fuel=Math.max(0,Math.min(Math.min(carriedFuel,fuelRoom),fuelFor(queued+Math.min(carried,inputRoom),burn)-slotFuel));
        int load=Math.max(0,Math.min(Math.min(carried,inputRoom),smeltable(slotFuel+fuel,burn)-queued));
        return new Batch(fuel,load);
    }

    @Override boolean tick(){
        if(deposit!=null){
            if(deposit.tick()==Approach.State.MOVING)return true;
            v.stock.invalidate();return false;
        }
        if(amount>0&&fetch==null)fetch=new Fetch(List.of(new Need(input,amount,"сырьё")));
        if(fetch!=null&&fuelFetch==null){
            var state=fetch.tick();
            if(state==Approach.State.MOVING)return true;
            int carried=w.count(input);
            if(state==Approach.State.FAILED&&carried==0)return fail("нет на складе: "+fetch.missing);
            // Coal when the store has enough for the batch, planks otherwise.
            boolean coal=v.stock.count(VillageCrafting.FUEL)+w.count(VillageCrafting.FUEL)>=fuelFor(carried,COAL_BURN);
            var needs=new ArrayList<Need>();
            needs.add(new Need(coal?VillageCrafting.FUEL:VillageCrafting.PLANK,fuelFor(carried,coal?COAL_BURN:WOOD_BURN),"топливо"));
            fuelFetch=new Fetch(needs);
        }
        // Too little fuel in the store is no reason to stop: the furnace takes what the fuel covers.
        if(fuelFetch!=null&&fuelFetch.tick()==Approach.State.MOVING)return true;
        if(approach==null)approach=new Approach(furnace,3.5,true);
        var state=approach.tick();
        if(state==Approach.State.MOVING)return true;
        if(state==Approach.State.FAILED)fail("печь недоступна");
        else if(v.level.getBlockEntity(furnace) instanceof FurnaceBlockEntity f&&load(f))v.chronicle.event(status.replace("плавит: ","Загрузили печь: "));
        served=true;deposit=new Deposit();
        return true;
    }
    /** Takes the finished output, then loads fuel for the batch and only as much input as that fuel smelts. */
    private boolean load(FurnaceBlockEntity f){
        if(f.isLocked()||body().position().distanceToSqr(Vec3.atCenterOf(furnace))>16)return false;
        var before=Supplies.copy(bot().inventory().snapshot().slots());var next=Supplies.copy(before);
        var prior=Supplies.copy(f);var slots=Supplies.copy(prior);boolean changed=false;
        ItemStack out=slots.get(2);var withOutput=Supplies.copy(next);
        if(!out.isEmpty()&&Supplies.insert(withOutput,out)){next=withOutput;slots.set(2,ItemStack.EMPTY);changed=true;}
        ItemStack in=slots.get(0),fuel=slots.get(1),ore=null,wood=null;
        var fuels=v.level.fuelValues();
        for(ItemStack s:next)if(!s.isEmpty()&&input.test(s)&&(in.isEmpty()?v.level.recipeAccess().getRecipeFor(RecipeType.SMELTING,new SingleRecipeInput(s),v.level).isPresent()
                :ItemStack.isSameItemSameComponents(in,s))){ore=s.copyWithCount(1);break;}
        int woodCount=0;
        for(ItemStack s:next){
            if(s.isEmpty()||!(VillageCrafting.FUEL.test(s)||s.is(ItemTags.PLANKS)||s.is(ItemTags.LOGS_THAT_BURN)&&!input.test(s))||!fuels.isFuel(s)
                    ||!fuel.isEmpty()&&!ItemStack.isSameItemSameComponents(fuel,s))continue;
            ItemStack kind=s;int n=Supplies.count(next,x->ItemStack.isSameItemSameComponents(x,kind));
            if(n>woodCount){wood=s.copyWithCount(1);woodCount=n;}
        }
        int burn=!fuel.isEmpty()?fuels.burnDuration(fuel):wood!=null?fuels.burnDuration(wood):0;
        ItemStack oreKind=ore,woodKind=wood;
        int carried=ore==null?0:Supplies.count(next,x->ItemStack.isSameItemSameComponents(x,oreKind));
        int inputRoom=ore==null?0:in.isEmpty()?ore.getMaxStackSize():in.getMaxStackSize()-in.getCount();
        int fuelRoom=wood==null?0:fuel.isEmpty()?wood.getMaxStackSize():fuel.getMaxStackSize()-fuel.getCount();
        var batch=batch(in.getCount(),carried,inputRoom,fuel.getCount(),woodCount,fuelRoom,burn);
        if(batch.fuel()>0){take(next,woodKind,batch.fuel());slots.set(1,(fuel.isEmpty()?woodKind:fuel).copyWithCount(fuel.getCount()+batch.fuel()));changed=true;}
        if(batch.input()>0){take(next,oreKind,batch.input());slots.set(0,(in.isEmpty()?oreKind:in).copyWithCount(in.getCount()+batch.input()));changed=true;}
        if(!changed||!Supplies.matches(prior,Supplies.copy(f))||!Supplies.replace(bot(),before,next))return false;
        for(int i=0;i<3;i++)f.setItem(i,slots.get(i));
        f.setChanged();
        return true;
    }
    /** Removes {@code n} items like {@code kind} from the slots. */
    private static void take(List<ItemStack> slots,ItemStack kind,int n){
        for(ItemStack s:slots){if(n<=0)return;if(ItemStack.isSameItemSameComponents(s,kind)){int k=Math.min(n,s.getCount());s.shrink(k);n-=k;}}
    }
    @Override void stop(){if(fetch!=null)fetch.cancel();if(fuelFetch!=null)fuelFetch.cancel();if(approach!=null)approach.cancel();if(deposit!=null)deposit.cancel();}
}
