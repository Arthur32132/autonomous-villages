package dev.autonomousvillages;

import dev.navigationai.api.BotHandle;
import java.util.*;
import java.util.function.Predicate;
import net.minecraft.core.*;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

/** Villager crafts use the loaded vanilla recipes, including datapack replacements and remaining items. */
final class VillageWorkshop {
    static final Predicate<ItemStack> PLANK = s -> s.is(ItemTags.PLANKS), STONE = s -> s.is(ItemTags.STONE_TOOL_MATERIALS);
    static boolean craft(BotHandle b, Village v, String[] pattern, Predicate<ItemStack> material, Predicate<ItemStack> accept) {
        int width = pattern[0].length(), height = pattern.length;
        if (v.level.getGameRules().get(GameRules.LIMITED_CRAFTING)) return false; // villagers have no recipe book
        if ((width > 2 || height > 2) && v.index.tables.stream().noneMatch(p -> b.entity().position().distanceToSqr(Vec3.atCenterOf(p)) <= 16
                && v.level.getBlockState(p).is(Blocks.CRAFTING_TABLE))) return false;
        var before = Supplies.copy(b.inventory().snapshot().slots()); var next = Supplies.copy(before);
        List<ItemStack> grid = new ArrayList<>();
        for (String row : pattern) for (int x = 0; x < width; x++) {
            char symbol = row.charAt(x);
            if (symbol == ' ') { grid.add(ItemStack.EMPTY); continue; }
            Predicate<ItemStack> ingredient = switch (symbol) {
                case 'P' -> PLANK; case 'S' -> s -> s.is(Items.STICK); case 'I' -> s -> s.is(Items.IRON_INGOT); default -> material;
            };
            ItemStack chosen = next.stream().filter(s -> !s.isEmpty() && Inventory.isUsableForCrafting(s) && ingredient.test(s)).findFirst().orElse(null);
            if (chosen == null) return false;
            grid.add(chosen.copyWithCount(1)); chosen.shrink(1);
        }
        CraftingInput input = CraftingInput.of(width,height,grid);
        var recipe = v.level.recipeAccess().getRecipeFor(RecipeType.CRAFTING, input, v.level).orElse(null);
        if (recipe == null) return false;
        ItemStack output = recipe.value().assemble(input);
        if (output.isEmpty() || !accept.test(output) || !output.isItemEnabled(v.level.enabledFeatures())) return false;
        for (ItemStack rest : recipe.value().getRemainingItems(input)) if (!rest.isEmpty() && !Supplies.insert(next,rest)) return false;
        return Supplies.insert(next, output) && Supplies.replace(b, before, next);
    }
    static boolean craft(BotHandle b, Village v, Item out, String[] pattern, Predicate<ItemStack> material) {
        return craft(b, v, pattern, material, s -> s.is(out));
    }
    static boolean make(BotHandle b, Village v, boolean steward) {
        var slots = b.inventory().snapshot().slots();
        int planks = Supplies.count(slots, PLANK);
        if (planks < 16 && craft(b,v,new String[]{"M"},s -> s.is(ItemTags.LOGS_THAT_BURN),PLANK)) return true;
        if (b.inventory().count(Items.STICK) < 6 && craft(b,v,Items.STICK,new String[]{"P","P"},PLANK)) return true;
        if (v.index.tables.stream().noneMatch(p -> v.level.getBlockState(p).is(Blocks.CRAFTING_TABLE)) && b.inventory().count(Items.CRAFTING_TABLE) == 0
                && craft(b,v,Items.CRAFTING_TABLE,new String[]{"PP","PP"},PLANK)) return true;
        if (Supplies.find(b,s -> s.is(ItemTags.PICKAXES) && Supplies.usable(s)) < 0) {
            if (craft(b,v,Items.STONE_PICKAXE,new String[]{"MMM"," S "," S "},STONE)) return true;
            if (craft(b,v,Items.WOODEN_PICKAXE,new String[]{"MMM"," S "," S "},PLANK)) return true;
        }
        if (b.inventory().count(Items.IRON_PICKAXE) == 0 && craft(b,v,Items.IRON_PICKAXE,new String[]{"III"," S "," S "},s -> s.is(Items.IRON_INGOT))) return true;
        if (b.inventory().count(Items.STONE_PICKAXE) == 0 && b.inventory().count(Items.IRON_PICKAXE) == 0
                && craft(b,v,Items.STONE_PICKAXE,new String[]{"MMM"," S "," S "},STONE)) return true;
        if (Supplies.find(b,s -> s.is(ItemTags.AXES) && Supplies.usable(s)) < 0 && craft(b,v,Items.STONE_AXE,new String[]{"MM","MS"," S"},STONE)) return true;
        if (Supplies.find(b,s -> s.is(ItemTags.HOES) && Supplies.usable(s)) < 0 && craft(b,v,Items.WOODEN_HOE,new String[]{"MM"," S"," S"},PLANK)) return true;
        if (Supplies.find(b,s -> s.is(ItemTags.SWORDS) && Supplies.usable(s)) < 0) {
            if (craft(b,v,Items.STONE_SWORD,new String[]{"M","M","S"},STONE)) return true;
            if (craft(b,v,Items.WOODEN_SWORD,new String[]{"M","M","S"},PLANK)) return true;
        }
        if (b.entity().getOffhandItem().isEmpty() && b.inventory().count(Items.SHIELD) == 0
                && craft(b,v,Items.SHIELD,new String[]{"PIP","PPP"," P "},PLANK)) return true;
        if (steward) {
            if (v.needsChest() && b.inventory().count(Items.CHEST) == 0 && craft(b,v,Items.CHEST,new String[]{"PPP","P P","PPP"},PLANK)) return true;
            if (v.state.furnaces.isEmpty() && b.inventory().count(Items.FURNACE) == 0 && craft(b,v,Items.FURNACE,new String[]{"MMM","M M","MMM"},STONE)) return true;
            if (craft(b,v,Items.BREAD,new String[]{"MMM"},s -> s.is(Items.WHEAT))) return true;
            if (b.inventory().count(Items.TORCH) < 8 && craft(b,v,Items.TORCH,new String[]{"M","S"},s -> s.is(Items.COAL) || s.is(Items.CHARCOAL))) return true;
            if (Supplies.find(b,s->s.is(ItemTags.BEDS)) < 0 && v.index.beds.size() < Math.min(24,v.state.residents.size()+2)) {
                for(ItemStack wool:b.inventory().snapshot().slots()) if(wool.is(ItemTags.WOOL)&&b.inventory().count(wool.getItem())>=3
                        &&craft(b,v,new String[]{"MMM","PPP"},s->s.is(wool.getItem()),s->s.is(ItemTags.BEDS)))return true;
            }
            if (b.inventory().count(Items.SHEARS) == 0 && craft(b,v,Items.SHEARS,new String[]{" I","I "},s -> s.is(Items.IRON_INGOT))) return true;
        }
        int shield = Supplies.find(b,s -> s.is(Items.SHIELD) && Supplies.usable(s));
        if (shield >= 0 && b.entity().getOffhandItem().isEmpty()) return b.inventory().equip(shield, EquipmentSlot.OFFHAND);
        EquipmentSlot[] armor={EquipmentSlot.CHEST,EquipmentSlot.HEAD,EquipmentSlot.LEGS,EquipmentSlot.FEET};
        Item[] items={Items.IRON_CHESTPLATE,Items.IRON_HELMET,Items.IRON_LEGGINGS,Items.IRON_BOOTS};
        String[][] patterns={{"III","I I"},{"III","I I"},{"III","I I","I I"},{"I I","I I"}};
        patterns[0]=new String[]{"I I","III","III"};
        for(int i=0;i<armor.length;i++)if(!Supplies.usable(b.entity().getItemBySlot(armor[i]))){
            Item item=items[i];int slot=Supplies.find(b,s->s.is(item)&&Supplies.usable(s));
            if(slot>=0)return b.inventory().equip(slot,armor[i]);
            if(craft(b,v,item,patterns[i],s->s.is(Items.IRON_INGOT)))return true;
        }
        return false;
    }
    static boolean serviceFurnace(BotHandle b, Village v, FurnaceBlockEntity furnace) {
        if (furnace.isLocked() || !v.state.furnaces.contains(furnace.getBlockPos().asLong())
                || b.entity().position().distanceToSqr(Vec3.atCenterOf(furnace.getBlockPos())) > 16) return false;
        var before = Supplies.copy(b.inventory().snapshot().slots()); var next = Supplies.copy(before);
        var prior = Supplies.copy(furnace); var replacement = Supplies.copy(prior); boolean changed = false;
        ItemStack out = replacement.get(2);
        // insert() may modify its disposable target before reporting insufficient capacity.
        // A failed output collection must not leave even a partial copy in the worker's bag.
        var withOutput=Supplies.copy(next);
        if (!out.isEmpty() && Supplies.insert(withOutput, out)) { next=withOutput; replacement.set(2,ItemStack.EMPTY); changed = true; }
        if (replacement.get(0).isEmpty()) {
            int slot = -1;
            boolean charcoal=v.count(s->s.is(Items.CHARCOAL)||s.is(Items.COAL))<8&&v.count(s->s.is(ItemTags.LOGS)||s.is(ItemTags.PLANKS))>16;
            for (int i = 0; i < next.size(); i++) if (next.get(i).is(Items.RAW_IRON) || charcoal&&next.get(i).is(ItemTags.LOGS_THAT_BURN)) { slot = i; if (next.get(i).is(Items.RAW_IRON)) break; }
            if (slot >= 0) {
                ItemStack input = next.get(slot);
                var recipe = v.level.recipeAccess().getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(input), v.level);
                if (recipe.isPresent()) { int n = Math.min(input.getCount(),8); replacement.set(0,input.copyWithCount(n)); input.shrink(n); changed = true; }
            }
        }
        if (!replacement.get(0).isEmpty() && replacement.get(1).getCount() < 4) {
            for (ItemStack fuel : next) if ((fuel.is(Items.COAL) || fuel.is(Items.CHARCOAL) || fuel.is(ItemTags.PLANKS)) && v.level.fuelValues().isFuel(fuel)
                    && (replacement.get(1).isEmpty() || ItemStack.isSameItemSameComponents(replacement.get(1),fuel))) {
                int n = Math.min(fuel.getCount(),4-replacement.get(1).getCount());
                if (replacement.get(1).isEmpty()) replacement.set(1,fuel.copyWithCount(n)); else replacement.get(1).grow(n);
                fuel.shrink(n); changed = true; break;
            }
        }
        if (!changed || !Supplies.matches(prior,Supplies.copy(furnace)) || !Supplies.replace(b,before,next)) return false;
        for (int i = 0; i < 3; i++) furnace.setItem(i,replacement.get(i)); furnace.setChanged(); return true;
    }
    static boolean wantsFurnace(BotHandle b,Village v,FurnaceBlockEntity f){
        return !f.getItem(2).isEmpty()||!f.getItem(0).isEmpty()&&f.getItem(1).isEmpty()
                ||f.getItem(0).isEmpty()&&(b.inventory().count(Items.RAW_IRON)>0
                ||v.count(s->s.is(Items.CHARCOAL)||s.is(Items.COAL))<8&&v.count(s->s.is(ItemTags.LOGS)||s.is(ItemTags.PLANKS))>16
                &&b.inventory().snapshot().slots().stream().anyMatch(s->s.is(ItemTags.LOGS_THAT_BURN)));
    }
}
