package dev.autonomousvillages;

import java.util.*;
import java.util.function.Predicate;
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
    @Override boolean keeps(ItemStack s){return made<times&&(s.is(ItemTags.PLANKS)||s.is(Items.STICK)||s.is(ItemTags.LOGS_THAT_BURN)||recipe.material().test(s)||s.is(Items.IRON_INGOT));}
    /** Ingredients as raw materials: sticks and planks are expressed in logs when the store has no planks. */
    static List<Need> needs(Village v,VillageCrafting.Recipe recipe,int times){
        var out=new ArrayList<Need>();int planks=0,sticks=0;
        for(var e:recipe.symbols().entrySet()){
            int n=e.getValue()*times;
            switch(e.getKey()){
                case 'P'->planks+=n;
                case 'S'->sticks+=n;
                case 'I'->out.add(new Need(VillageCrafting.IRON,n,"железо"));
                default->{if(recipe.material()==VillageCrafting.PLANK)planks+=n;else out.add(new Need(recipe.material(),n,recipe.label()+": материал"));}
            }
        }
        if(sticks>0){
            int stored=v.stock.count(VillageCrafting.STICK);
            if(stored>=sticks)out.add(new Need(VillageCrafting.STICK,sticks,"палки"));
            else planks+=(sticks+3)/4*2;
        }
        if(planks>0){
            int stored=v.stock.count(VillageCrafting.PLANK);
            if(stored>=planks)out.add(new Need(VillageCrafting.PLANK,planks,"доски"));
            else out.add(new Need(VillageCrafting.LOG,(planks-stored+3)/4,"брёвна"));
            if(stored>0&&stored<planks)out.add(new Need(VillageCrafting.PLANK,stored,"доски"));
        }
        return out;
    }
    /** True when the store holds the raw materials for one craft. */
    static boolean affordable(Village v,VillageCrafting.Recipe recipe,int times,Worker w){
        for(var n:needs(v,recipe,times))if(v.stock.count(n.test())+(w==null?0:w.count(n.test()))<n.amount())return false;
        return true;
    }
    @Override boolean tick(){
        if(fetch==null)fetch=new Fetch(needs(v,recipe,times-made));
        var fetched=fetch.tick();
        if(fetched==Approach.State.FAILED)return fail("нет на складе: "+fetch.missing);
        if(fetched==Approach.State.MOVING)return true;
        if(recipe.needsTable()&&v.index.tables.stream().noneMatch(p->VillageCrafting.nearTable(bot(),v,p))){
            BlockPos nearest=v.index.tables.stream().filter(p->v.level.getBlockState(p).is(Blocks.CRAFTING_TABLE))
                    .min(Comparator.comparingDouble(p->p.distSqr(body().blockPosition()))).orElse(null);
            if(nearest==null)return fail("нет верстака");
            if(table==null||!table.target.equals(nearest))table=new Approach(nearest,3.5,true);
            var state=table.tick();
            if(state==Approach.State.FAILED)return fail("верстак недоступен");
            if(state==Approach.State.MOVING)return true;
        }
        status="изготавливает: "+recipe.label();
        // Intermediate products first, one craft per tick so the hand animation is visible.
        var symbols=recipe.symbols();
        int planksNeeded=symbols.getOrDefault('P',0)+(recipe.material()==VillageCrafting.PLANK?symbols.getOrDefault('M',0):0);
        int sticksNeeded=symbols.getOrDefault('S',0);
        boolean sticksShort=w.count(VillageCrafting.STICK)<sticksNeeded;
        if(w.count(VillageCrafting.PLANK)<planksNeeded+(sticksShort?2:0)&&w.count(VillageCrafting.LOG)>0)
            return VillageCrafting.craft(bot(),v,VillageCrafting.PLANKS)||fail("не вышло: доски");
        if(sticksShort)return VillageCrafting.craft(bot(),v,VillageCrafting.STICKS)||fail("не вышло: палки");
        if(!VillageCrafting.craft(bot(),v,recipe))return fail("не вышло: "+recipe.label());
        made++;v.stock.invalidate();
        return made<times;
    }
    @Override void stop(){if(fetch!=null)fetch.cancel();if(table!=null)table.cancel();}
}
