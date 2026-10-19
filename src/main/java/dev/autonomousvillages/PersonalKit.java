package dev.autonomousvillages;

import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Tool families a worker keeps for himself; everything else belongs in the village store. */
final class PersonalKit {
    private PersonalKit(){}
    static String family(ItemStack s){
        if(s.is(ItemTags.PICKAXES))return "pick";
        if(s.is(ItemTags.AXES))return "axe";
        if(s.is(ItemTags.HOES))return "hoe";
        if(s.is(ItemTags.SWORDS))return "sword";
        if(s.is(ItemTags.SHOVELS))return "shovel";
        if(s.is(Items.SHEARS))return "shears";
        if(s.is(Items.SHIELD))return "shield";
        if(s.is(Items.BUCKET)||s.is(Items.WATER_BUCKET))return "bucket";
        if(s.has(DataComponents.EQUIPPABLE))return "equipment:"+s.getItem();
        return "";
    }
    /** Higher is better: tier first (via max damage), then remaining durability. */
    static int rank(ItemStack s){return s.isDamageableItem()?s.getMaxDamage()*4+(s.getMaxDamage()-s.getDamageValue()):Integer.MAX_VALUE/2;}
}
