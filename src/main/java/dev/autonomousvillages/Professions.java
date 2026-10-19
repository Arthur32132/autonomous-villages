package dev.autonomousvillages;

import net.minecraft.core.Holder;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * What a vanilla profession brings home from its workstation once a day: the shepherd wool from the
 * loom, the farmer a loaf from the composter's fields, the smiths now and then an ingot. Professions
 * matter to the village, not only to traders, and the shepherd finally answers the call for beds.
 */
final class Professions {
    private Professions(){}
    static ItemStack product(Holder<VillagerProfession> profession,long day){
        if(profession.is(VillagerProfession.SHEPHERD))return new ItemStack(Items.WOOL.white(),2);
        if(profession.is(VillagerProfession.FARMER))return new ItemStack(Items.BREAD);
        if(profession.is(VillagerProfession.FISHERMAN))return new ItemStack(Items.COOKED_COD,2);
        if(profession.is(VillagerProfession.BUTCHER))return new ItemStack(Items.COOKED_PORKCHOP);
        if(profession.is(VillagerProfession.MASON))return new ItemStack(Items.COBBLESTONE,4);
        if(profession.is(VillagerProfession.FLETCHER))return new ItemStack(Items.STICK,4);
        // An ingot every other day from each smithy: shears for the shepherd's wool, iron tools later.
        if((profession.is(VillagerProfession.ARMORER)||profession.is(VillagerProfession.TOOLSMITH)||profession.is(VillagerProfession.WEAPONSMITH))&&day%2==0)
            return new ItemStack(Items.IRON_INGOT);
        return ItemStack.EMPTY;
    }
    /** Russian name of the product for the chronicle. */
    static String label(ItemStack s){
        return s.is(net.minecraft.tags.ItemTags.WOOL)?"шерсть":s.is(Items.BREAD)?"хлеб":s.is(Items.COOKED_COD)?"рыбу":s.is(Items.COOKED_PORKCHOP)?"мясо"
                :s.is(Items.COBBLESTONE)?"камень":s.is(Items.STICK)?"древки":s.is(Items.IRON_INGOT)?"железо":"товар";
    }
}
