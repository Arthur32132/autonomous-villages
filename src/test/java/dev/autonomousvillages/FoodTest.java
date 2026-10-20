package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class FoodTest {
    @BeforeAll static void bootstrapRegistries(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}

    @Test void breadApplesAndCookedFishOrMeatAreMealsVegetablesAreQuarters(){
        for(var meal:List.of(Items.BREAD,Items.APPLE,Items.COOKED_COD,Items.COOKED_SALMON,Items.COOKED_PORKCHOP,Items.COOKED_BEEF,Items.COOKED_MUTTON,
                Items.COOKED_CHICKEN,Items.COOKED_RABBIT)){assertTrue(Food.isMeal(meal),meal.toString());assertEquals(4,Food.mealValue(meal));}
        for(var vegetable:List.of(Items.CARROT,Items.POTATO,Items.BAKED_POTATO,Items.BEETROOT)){
            assertFalse(Food.isMeal(vegetable));assertTrue(Food.isVegetable(vegetable));assertEquals(1,Food.mealValue(vegetable));}
        for(var other:List.of(Items.WHEAT,Items.BEEF,Items.COD,Items.WHEAT_SEEDS,Items.COBBLESTONE,Items.POISONOUS_POTATO))assertEquals(0,Food.mealValue(other),other.toString());
    }

    private static Map.Entry<Item,Integer> stack(Item item,int count){return Map.entry(item,count);}

    @Test void fourVegetablesOfAnyKindMakeOneMeal(){
        assertEquals(1,Food.meals(List.of(stack(Items.CARROT,4))));
        assertEquals(0,Food.meals(List.of(stack(Items.CARROT,3))));
        // Kinds are pooled before dividing: two carrots and two potatoes are one meal, not none.
        assertEquals(1,Food.meals(List.of(stack(Items.CARROT,2),stack(Items.POTATO,2))));
        assertEquals(2,Food.meals(List.of(stack(Items.BEETROOT,3),stack(Items.BAKED_POTATO,3),stack(Items.CARROT,2))));
        assertEquals(1,Food.meals(0,7));assertEquals(2,Food.meals(0,8));
    }

    @Test void mixedStacksCountWholeMeals(){
        var store=List.of(stack(Items.BREAD,2),stack(Items.COBBLESTONE,64),stack(Items.APPLE,1),stack(Items.CARROT,3),stack(Items.AIR,0),
                stack(Items.POTATO,5),stack(Items.WHEAT,20),stack(Items.COOKED_BEEF,1),stack(Items.CARROT,1));
        assertEquals(2+1+1+(3+5+1)/4,Food.meals(store));
        // The store's count (meals and vegetables summed apart, as the boss bar and births do) gives the same.
        int meals=0,vegetables=0;
        for(var s:store){if(Food.isMeal(s.getKey()))meals+=s.getValue();if(Food.isVegetable(s.getKey()))vegetables+=s.getValue();}
        assertEquals(Food.meals(meals,vegetables),Food.meals(store));
        assertEquals(0,Food.meals(List.<Map.Entry<Item,Integer>>of()));
    }

    @Test void birthsEatBreadFirstThenOtherMealsThenFourVegetablesPerMissingMeal(){
        assertArrayEquals(new int[]{3,0,0},Food.plan(5,2,10,3));
        assertArrayEquals(new int[]{1,2,0},Food.plan(1,4,0,3));
        assertArrayEquals(new int[]{1,1,4},Food.plan(1,1,4,3));
        assertArrayEquals(new int[]{0,0,12},Food.plan(0,0,12,3));
        assertNull(Food.plan(1,1,3,3));
        assertNull(Food.plan(0,0,11,3));
    }

    @Test void theBossBarCountAndTheBirthRuleNeverDisagree(){
        // Whatever the store holds, "enough for a birth" by the count is exactly "a birth can eat it".
        for(int bread=0;bread<5;bread++)for(int other=0;other<5;other++)for(int vegetables=0;vegetables<14;vegetables++)
            assertEquals(Food.meals(bread+other,vegetables)>=3,Food.plan(bread,other,vegetables,3)!=null,bread+"/"+other+"/"+vegetables);
    }
}
