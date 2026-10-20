package dev.autonomousvillages;

import java.util.*;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
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
    private boolean served,fuelled,starved;
    /** The one fuel item fetched for the batch (the furnace's fuel slot holds a single kind). */
    private Item fuelKind;
    SmeltJob(Worker w,BlockPos furnace,Predicate<ItemStack> input,int amount,String label){
        super(w,"плавит: "+label);this.furnace=furnace;this.input=input;this.amount=amount;
    }
    @Override String kind(){return "smelt";}
    @Override BlockPos target(){return furnace;}
    @Override boolean keeps(ItemStack s){return !served&&(input.test(s)||VillageCrafting.FUEL.test(s)||s.is(ItemTags.PLANKS)||fuelKind!=null&&s.is(fuelKind));}

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
    /** The one fuel item to fetch for {@code items}: the furnace's own when its slot holds fuel, else the best-burning kind there is enough of, else the kind that smelts the most; null when nothing burns. */
    static Item fuelKind(Map<Item,Integer> counts,Item slot,int items,ToIntFunction<Item> burn){
        if(slot!=null)return slot;
        ToIntFunction<Map.Entry<Item,Integer>> smelts=e->smeltable(e.getValue(),burn.applyAsInt(e.getKey()));
        return counts.entrySet().stream().filter(e->smelts.applyAsInt(e)>0)
                .max(Comparator.comparing((Map.Entry<Item,Integer> e)->smelts.applyAsInt(e)>=items)
                        .thenComparingInt(e->smelts.applyAsInt(e)>=items?burn.applyAsInt(e.getKey()):smelts.applyAsInt(e)).thenComparingInt(Map.Entry::getValue))
                .map(Map.Entry::getKey).orElse(null);
    }
    /** The fuel to load from the bag: the fetched kind when it covers {@code items}, else the kind that smelts the most (the fetched one on a tie); logs only when nothing else burns. */
    static Item loadKind(Map<Item,Integer> bag,Item fetched,int items,ToIntFunction<Item> burn,Predicate<Item> log){
        int have=fetched==null?0:bag.getOrDefault(fetched,0);
        if(have>0&&smeltable(have,burn.applyAsInt(fetched))>=items)return fetched;
        boolean other=bag.entrySet().stream().anyMatch(e->e.getValue()>0&&burn.applyAsInt(e.getKey())>0&&!log.test(e.getKey()));
        Item best=null;long most=0;
        for(var e:bag.entrySet()){
            if(e.getValue()<=0||other&&log.test(e.getKey()))continue;
            long smelts=(long)e.getValue()*burn.applyAsInt(e.getKey());
            if(smelts>most||smelts==most&&smelts>0&&e.getKey()==fetched){best=e.getKey();most=smelts;}
        }
        return best;
    }

    @Override boolean tick(){
        if(deposit!=null){
            if(deposit.tick()==Approach.State.MOVING)return true;
            v.stock.invalidate();return false;
        }
        if(amount>0&&fetch==null){
            // Nothing to burn anywhere: no trip with the input that would only carry it back.
            if(fuelNeed(amount)==null){w.cooldown("smelt",1200);return fail("нет топлива");}
            fetch=new Fetch(List.of(new Need(input,amount,"сырьё")));
        }
        if(fetch!=null&&fuelFetch==null){
            var state=fetch.tick();
            if(state==Approach.State.MOVING)return true;
            int carried=w.count(input);
            if(state==Approach.State.FAILED&&carried==0)return fail("нет на складе: "+fetch.missing);
            var need=fuelNeed(carried);
            fuelFetch=new Fetch(need==null?List.of():List.of(need));
        }
        // Too little fuel in the store is no reason to stop: the furnace takes what the fuel covers.
        if(fuelFetch!=null&&!fuelled){if(fuelFetch.tick()==Approach.State.MOVING)return true;fuelled=true;}
        if(approach==null)approach=new Approach(furnace,3.5,true);
        var state=approach.tick();
        if(state==Approach.State.MOVING)return true;
        if(state==Approach.State.FAILED)fail("печь недоступна");
        else if(v.level.getBlockEntity(furnace) instanceof FurnaceBlockEntity f&&load(f))v.chronicle.event(status.replace("плавит: ","Загрузили печь: "));
        // The input goes back to the store, and this worker leaves the furnace alone for a minute.
        if(starved){w.cooldown("smelt",1200);fail("нет топлива");}
        served=true;deposit=new Deposit();
        return true;
    }
    /** Fuel of one kind for {@code carried} input on top of what the furnace already holds; null when the store and the bag have nothing to burn. */
    private Need fuelNeed(int carried){
        ItemStack in=ItemStack.EMPTY,slot=ItemStack.EMPTY;
        if(v.level.hasChunkAt(furnace)&&v.level.getBlockEntity(furnace) instanceof FurnaceBlockEntity f){in=f.getItem(0);slot=f.getItem(1);}
        var fuels=v.level.fuelValues();
        Item kind=fuelKind(CraftJob.counts(v,w,s->VillageCrafting.FUEL.test(s)||s.is(ItemTags.PLANKS)),slot.isEmpty()?null:slot.getItem(),in.getCount()+carried,
                i->fuels.burnDuration(new ItemStack(i)));
        if(kind==null)return null;
        fuelKind=kind;
        return new Need(s->s.is(kind),fuelFor(in.getCount()+carried,fuels.burnDuration(new ItemStack(kind)))-slot.getCount(),"топливо");
    }
    /** Takes the finished output, then loads fuel for the batch and only as much input as that fuel smelts. */
    private boolean load(FurnaceBlockEntity f){
        if(f.isLocked()||body().position().distanceToSqr(Vec3.atCenterOf(furnace))>16)return false;
        var before=Supplies.copy(bot().inventory().snapshot().slots());var next=Supplies.copy(before);
        var prior=Supplies.copy(f);var slots=Supplies.copy(prior);boolean changed=false;
        ItemStack out=slots.get(2);var withOutput=Supplies.copy(next);
        if(!out.isEmpty()&&Supplies.insert(withOutput,out)){next=withOutput;slots.set(2,ItemStack.EMPTY);changed=true;}
        ItemStack in=slots.get(0),fuel=slots.get(1),ore=inputKind(next,in),wood=null;
        var fuels=v.level.fuelValues();
        var burnable=new HashMap<Item,Integer>();
        for(ItemStack s:next)if(!s.isEmpty()&&(VillageCrafting.FUEL.test(s)||s.is(ItemTags.PLANKS)||s.is(ItemTags.LOGS_THAT_BURN)&&!input.test(s))&&fuels.isFuel(s)
                &&(fuel.isEmpty()||ItemStack.isSameItemSameComponents(fuel,s)))burnable.merge(s.getItem(),s.getCount(),Integer::sum);
        ItemStack oreKind=ore;
        int carried=ore==null?0:Supplies.count(next,x->ItemStack.isSameItemSameComponents(x,oreKind));
        int inputRoom=ore==null?0:in.isEmpty()?ore.getMaxStackSize():in.getMaxStackSize()-in.getCount();
        Item kind=!fuel.isEmpty()?fuel.getItem():loadKind(burnable,fuelKind,in.getCount()+Math.min(carried,inputRoom),i->fuels.burnDuration(new ItemStack(i)),i->new ItemStack(i).is(ItemTags.LOGS_THAT_BURN));
        for(ItemStack s:next)if(kind!=null&&s.is(kind)&&(fuel.isEmpty()||ItemStack.isSameItemSameComponents(fuel,s))){wood=s.copyWithCount(1);break;}
        ItemStack woodKind=wood;
        int woodCount=wood==null?0:Supplies.count(next,x->ItemStack.isSameItemSameComponents(x,woodKind));
        int burn=!fuel.isEmpty()?fuels.burnDuration(fuel):wood!=null?fuels.burnDuration(wood):0;
        int fuelRoom=wood==null?0:fuel.isEmpty()?wood.getMaxStackSize():fuel.getMaxStackSize()-fuel.getCount();
        var batch=batch(in.getCount(),carried,inputRoom,fuel.getCount(),woodCount,fuelRoom,burn);
        starved=carried>0&&inputRoom>0&&batch.input()==0;
        if(batch.fuel()>0){take(next,woodKind,batch.fuel());slots.set(1,(fuel.isEmpty()?woodKind:fuel).copyWithCount(fuel.getCount()+batch.fuel()));changed=true;}
        if(batch.input()>0){take(next,oreKind,batch.input());slots.set(0,(in.isEmpty()?oreKind:in).copyWithCount(in.getCount()+batch.input()));changed=true;}
        if(!changed||!Supplies.matches(prior,Supplies.copy(f))||!Supplies.replace(bot(),before,next))return false;
        for(int i=0;i<3;i++)f.setItem(i,slots.get(i));
        f.setChanged();
        return true;
    }
    /** The input kind carried most that the furnace takes: the kind already in its input slot, else one with a smelting recipe. */
    private ItemStack inputKind(List<ItemStack> bag,ItemStack in){
        ItemStack best=null;int most=0;
        for(ItemStack s:bag){
            if(s.isEmpty()||!input.test(s)||(in.isEmpty()?v.level.recipeAccess().getRecipeFor(RecipeType.SMELTING,new SingleRecipeInput(s),v.level).isEmpty():!ItemStack.isSameItemSameComponents(in,s)))continue;
            int n=Supplies.count(bag,x->ItemStack.isSameItemSameComponents(x,s));
            if(n>most){best=s.copyWithCount(1);most=n;}
        }
        return best;
    }
    /** Removes {@code n} items like {@code kind} from the slots. */
    private static void take(List<ItemStack> slots,ItemStack kind,int n){
        for(ItemStack s:slots){if(n<=0)return;if(ItemStack.isSameItemSameComponents(s,kind)){int k=Math.min(n,s.getCount());s.shrink(k);n-=k;}}
    }
    @Override void stop(){if(fetch!=null)fetch.cancel();if(fuelFetch!=null)fuelFetch.cancel();if(approach!=null)approach.cancel();if(deposit!=null)deposit.cancel();}
}
