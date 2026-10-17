package dev.autonomousvillages;

import java.util.*;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;

/** Conservative paid excavation budget. Spare tools and exit blocks are not mining credit. */
final class MineSupplies {
    record Kit(int primary,int spare,int food,int exitBlocks){
        boolean ready(){return primary>=32&&spare>=24&&food>=3&&exitBlocks>=8;}
        int segments(){return ready()?Math.min(24,Math.min((primary-16)/3,Math.min(food*4,exitBlocks))):0;}
    }
    static Kit inspect(Resident r){
        var picks=new ArrayList<Integer>();int food=0,exit=0;
        for(var stack:r.bot().inventory().snapshot().slots()){
            if(stack.is(ItemTags.PICKAXES)&&stack.isCorrectToolForDrops(Blocks.IRON_ORE.defaultBlockState())&&Supplies.usable(stack))
                for(int n=0;n<Math.min(2,stack.getCount());n++)picks.add(stack.getMaxDamage()-stack.getDamageValue());
            if(Supplies.food(stack))food+=stack.getCount();
            if(stack.is(ItemTags.PLANKS)||stack.is(Items.COBBLESTONE)||stack.is(Items.DIRT))exit+=stack.getCount();
        }
        // Native food is physically carried too, and is actually eaten by the villager.
        food+=Supplies.count(Supplies.copy(r.body().getInventory()),Supplies::food);
        picks.sort(Comparator.reverseOrder());
        return new Kit(picks.isEmpty()?0:picks.getFirst(),picks.size()<2?0:picks.get(1),food,exit);
    }
}
