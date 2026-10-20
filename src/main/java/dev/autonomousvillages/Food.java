package dev.autonomousvillages;

import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The one definition of a meal for births and the boss bar: bread, apples, cooked fish or meat count one
 * meal each; four vegetables (carrots, potatoes, baked potatoes, beetroots) make one meal between them.
 */
final class Food {
    private Food(){}
    static final int VEGETABLES_PER_MEAL=4;
    static boolean isMeal(Item item){
        return item==Items.BREAD||item==Items.APPLE||item==Items.COOKED_COD||item==Items.COOKED_SALMON||item==Items.COOKED_PORKCHOP
                ||item==Items.COOKED_BEEF||item==Items.COOKED_MUTTON||item==Items.COOKED_CHICKEN||item==Items.COOKED_RABBIT;
    }
    static boolean isVegetable(Item item){return item==Items.CARROT||item==Items.POTATO||item==Items.BAKED_POTATO||item==Items.BEETROOT;}
    /** Quarters of a meal one item is worth: 4 for a meal, 1 for a vegetable, 0 for anything else. */
    static int mealValue(Item item){return isMeal(item)?VEGETABLES_PER_MEAL:isVegetable(item)?1:0;}
    // Shared instances: VillageStock caches its counts per predicate instance.
    static final Predicate<ItemStack> BREAD=s->s.is(Items.BREAD);
    static final Predicate<ItemStack> OTHER_MEAL=s->!s.is(Items.BREAD)&&isMeal(s.getItem());
    static final Predicate<ItemStack> VEGETABLE=s->isVegetable(s.getItem());
    /** Whole meals in a pile of stacks (item and count); vegetables of every kind are pooled before dividing by four. */
    static int meals(Iterable<? extends Map.Entry<Item,Integer>> stacks){
        long quarters=0;
        for(var s:stacks)if(s!=null&&s.getKey()!=null&&s.getValue()!=null&&s.getValue()>0)quarters+=(long)mealValue(s.getKey())*s.getValue();
        return (int)Math.min(Integer.MAX_VALUE,quarters/VEGETABLES_PER_MEAL);
    }
    static int meals(int meals,int vegetables){return meals+vegetables/VEGETABLES_PER_MEAL;}
    static int meals(VillageStock stock){return meals(stock.count(BREAD)+stock.count(OTHER_MEAL),stock.count(VEGETABLE));}
    /** What n meals take from the store: bread first, then other meals, then four vegetables for each one still missing; null if it falls short. */
    static int[] plan(int bread,int otherMeals,int vegetables,int n){
        int b=Math.clamp(bread,0,n),o=Math.clamp(otherMeals,0,n-b),veg=(n-b-o)*VEGETABLES_PER_MEAL;
        return veg>vegetables?null:new int[]{b,o,veg};
    }
    static int[] plan(VillageStock stock,int n){return plan(stock.count(BREAD),stock.count(OTHER_MEAL),stock.count(VEGETABLE),n);}
    /** Takes a plan's food out of the store; true when all of it was there. */
    static boolean eat(VillageStock stock,int[] plan){
        return stock.consume(BREAD,plan[0])+stock.consume(OTHER_MEAL,plan[1])+stock.consume(VEGETABLE,plan[2])==plan[0]+plan[1]+plan[2];
    }
}
