package dev.autonomousvillages;

import java.util.*;
import java.util.function.Predicate;
import net.minecraft.world.item.Item;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Fetches ingredients from the store, walks to a workbench when the recipe is 3x3 and crafts it a
 * number of times. Missing sticks and planks are made on the spot from planks and logs.
 */
final class CraftJob extends Job {
    final VillageCrafting.Recipe recipe;
    final int times;
    private Fetch fetch;
    private Approach table;
    private int made;
    CraftJob(Worker w,VillageCrafting.Recipe recipe,int times){super(w,"изготавливает: "+recipe.label());this.recipe=recipe;this.times=Math.max(1,times);}
    @Override String kind(){return "craft";}
    /** Fetching from the store and walking to a workbench can cross a whole hillside village. */
    @Override int timeLimit(){return 4800;}
    @Override boolean keeps(ItemStack s){return made<times&&(s.is(ItemTags.PLANKS)||s.is(Items.STICK)||s.is(ItemTags.LOGS_THAT_BURN)||recipe.material().test(s)||s.is(Items.IRON_INGOT)
            ||recipe.symbols().containsKey('T')&&s.is(Items.TORCH));}
    /**
     * Ingredients as raw materials, counting what the worker already carries. Sticks and planks are
     * expressed in logs when there are too few planks. Doors, fences, stairs, slabs and signs need
     * planks of one wood, so a single plank or log type is chosen for them.
     */
    static List<Need> needs(Village v,VillageCrafting.Recipe recipe,int times,Worker w){return wants(v,recipe,times,w).all();}
    /** What the crafts take: the needs other than wood, the wood, and the planks and logs on hand per item it was chosen from. */
    record Wants(List<Need> other,Wood wood,Map<Item,Integer> planks,Map<Item,Integer> logs){
        List<Need> all(){var out=new ArrayList<>(other);if(wood!=null)out.addAll(wood.needs());return out;}
    }
    static Wants wants(Village v,VillageCrafting.Recipe recipe,int times,Worker w){
        var out=new ArrayList<Need>();int planks=0,sticks=0;
        for(var e:recipe.symbols().entrySet()){
            int n=e.getValue()*times;
            switch(e.getKey()){
                case 'P'->planks+=n;
                case 'S'->sticks+=n;
                case 'I'->out.add(new Need(VillageCrafting.IRON,n,"железо"));
                case 'T'->out.add(new Need(VillageCrafting.TORCH_ITEM,n,"факелы"));
                default->{if(recipe.material()==VillageCrafting.PLANK)planks+=n;else out.add(new Need(recipe.material(),n,recipe.label()+": материал"));}
            }
        }
        if(sticks>0){
            int have=v.stock.count(VillageCrafting.STICK)+(w==null?0:w.count(VillageCrafting.STICK));
            if(have>=sticks)out.add(new Need(VillageCrafting.STICK,sticks,"палки"));
            else planks+=(sticks+3)/4*2;
        }
        if(planks==0)return new Wants(out,null,Map.of(),Map.of());
        var plankCounts=counts(v,w,VillageCrafting.PLANK);var logCounts=counts(v,w,VillageCrafting.LOG);
        var wood=recipe.coherent()?wood(planks,plankCounts,logCounts):mixed(planks,Wood.count(plankCounts,null));
        return new Wants(out,wood,plankCounts,logCounts);
    }
    /** Planks of {@code plank} and logs of {@code log} to cut the rest from; a null kind is any kind. */
    record Wood(Item plank,int planks,Item log,int logs){
        Predicate<ItemStack> plankTest(){return plank==null?VillageCrafting.PLANK:s->s.is(plank);}
        Predicate<ItemStack> logTest(){return log==null?VillageCrafting.LOG:s->s.is(log);}
        /** Logs, then planks, to fetch. */
        List<Need> needs(){
            var out=new ArrayList<Need>();
            if(logs>0)out.add(new Need(logTest(),logs,"брёвна"));
            if(planks>0)out.add(new Need(plankTest(),planks,"доски"));
            return out;
        }
        /** True when the planks and logs on hand, per item, cover it: of its one kind, or of any kind when that is null. */
        boolean covered(Map<Item,Integer> plankCounts,Map<Item,Integer> logCounts){return count(plankCounts,plank)>=planks&&count(logCounts,log)>=logs;}
        static int count(Map<Item,Integer> counts,Item kind){return kind==null?counts.values().stream().mapToInt(Integer::intValue).sum():counts.getOrDefault(kind,0);}
    }
    /** Tools, chests and tables take any planks: what there is, and logs of any kind for the rest. */
    static Wood mixed(int planks,int have){return have>=planks?new Wood(null,planks,null,0):new Wood(null,have,null,(planks-have+3)/4);}
    /** A coherent recipe: planks of the one kind there is enough of, else logs of the kind there is most of; null kinds match nothing on hand. */
    static Wood wood(int planks,Map<Item,Integer> plankCounts,Map<Item,Integer> logCounts){
        Item kind=mostCommon(plankCounts,planks);
        return kind!=null?new Wood(kind,planks,null,0):new Wood(null,0,mostCommon(logCounts,1),(planks+3)/4);
    }
    /** Items of a kind in the store and the bag, per item. */
    static Map<Item,Integer> counts(Worker w,Predicate<ItemStack> kind){var counts=new HashMap<Item,Integer>();for(var s:w.bag())add(counts,s,kind);return counts;}
    static Map<Item,Integer> counts(Village v,Worker w,Predicate<ItemStack> kind){
        var counts=new HashMap<Item,Integer>();
        for(var c:v.stock.chests())for(int i=0;i<c.getContainerSize();i++)add(counts,c.getItem(i),kind);
        if(w!=null)for(var s:w.bag())add(counts,s,kind);
        return counts;
    }
    private static void add(Map<Item,Integer> counts,ItemStack s,Predicate<ItemStack> kind){if(!s.isEmpty()&&kind.test(s))counts.merge(s.getItem(),s.getCount(),Integer::sum);}
    /** Planks of the one kind there is most of, from per-item counts. */
    static int sameWood(Map<Item,Integer> planks){return planks.values().stream().mapToInt(Integer::intValue).max().orElse(0);}
    /** The item there is most of, if at least {@code min}. */
    static Item mostCommon(Map<Item,Integer> counts,int min){
        return counts.entrySet().stream().filter(e->e.getValue()>=min).max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }
    /** True when the store and the bag hold the raw materials for the crafts. */
    static boolean affordable(Village v,VillageCrafting.Recipe recipe,int times,Worker w){
        var wants=wants(v,recipe,times,w);
        for(var n:wants.other())if(v.stock.count(n.test())+(w==null?0:w.count(n.test()))<n.amount())return false;
        // The wood counts per item, as it was chosen: one kind for a coherent recipe, exactly what the craft will take.
        return wants.wood()==null||wants.wood().covered(wants.planks(),wants.logs());
    }
    private boolean fetched,recounted;
    @Override boolean tick(){
        // Fetch once: crafting uses up what was fetched, and that must not send the worker back out.
        if(!fetched){
            if(fetch==null)fetch=new Fetch(needs(v,recipe,times-made,w));
            var state=fetch.tick();
            if(state==Approach.State.FAILED){
                // Someone took the planks or sticks while this worker walked over (a builder, most often):
                // count again from what the store holds now, logs included, once.
                if(!recounted&&affordable(v,recipe,times-made,w)){recounted=true;fetch.cancel();fetch=new Fetch(needs(v,recipe,times-made,w));return true;}
                return fail("нет на складе: "+fetch.missing);
            }
            if(state==Approach.State.MOVING)return true;
            fetched=true;
        }
        if(recipe.needsTable()&&v.index.tables.stream().noneMatch(p->VillageCrafting.nearTable(bot(),v,p))){
            BlockPos nearest=v.index.tables.stream().filter(p->v.level.getBlockState(p).is(Blocks.CRAFTING_TABLE)&&reachable(p))
                    .min(Comparator.comparingDouble(p->p.distSqr(body().blockPosition()))).orElse(null);
            if(nearest==null)return fail("нет верстака");
            if(table==null||!table.target.equals(nearest))table=new Approach(nearest,3.5,true);
            var state=table.tick();
            if(state==Approach.State.FAILED){unreachable(table.target);return fail("верстак недоступен");}
            if(state==Approach.State.MOVING)return true;
        }
        status="изготавливает: "+recipe.label();
        // Intermediate products first, one craft per tick so the hand animation is visible.
        var symbols=recipe.symbols();
        int planksNeeded=symbols.getOrDefault('P',0)+(recipe.material()==VillageCrafting.PLANK?symbols.getOrDefault('M',0):0);
        int sticksNeeded=symbols.getOrDefault('S',0);
        boolean sticksShort=w.count(VillageCrafting.STICK)<sticksNeeded;
        // A coherent recipe counts planks of one wood only, exactly as needs() did when it was found affordable.
        int planksHave=recipe.coherent()?sameWood(counts(w,VillageCrafting.PLANK)):w.count(VillageCrafting.PLANK);
        if(planksHave<planksNeeded+(sticksShort?2:0)&&w.count(VillageCrafting.LOG)>0)
            return VillageCrafting.craft(bot(),v,VillageCrafting.PLANKS)||fail("не вышло: доски");
        if(sticksShort)return VillageCrafting.craft(bot(),v,VillageCrafting.STICKS)||fail("не вышло: палки");
        if(!VillageCrafting.craft(bot(),v,recipe))return fail("не вышло: "+recipe.label());
        made++;v.stock.invalidate();
        return made<times;
    }
    @Override void stop(){if(fetch!=null)fetch.cancel();if(table!=null)table.cancel();}
}
