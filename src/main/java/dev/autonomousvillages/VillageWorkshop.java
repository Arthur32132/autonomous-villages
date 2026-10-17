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
        return craft(b,v,pattern,material,accept,false);
    }
    private static boolean craft(BotHandle b,Village v,String[] pattern,Predicate<ItemStack> material,Predicate<ItemStack> accept,boolean repairing){
        int width = pattern[0].length(), height = pattern.length;
        if (v.level.getGameRules().get(GameRules.LIMITED_CRAFTING)) return false; // villagers have no recipe book
        var before = Supplies.copy(b.inventory().snapshot().slots()); var next = Supplies.copy(before);
        int plankCount=0;for(String row:pattern)for(char symbol:row.toCharArray())if(symbol=='P')plankCount++;
        Item preferredPlank=null;
        if(plankCount>0)for(ItemStack plank:before)if(PLANK.test(plank)&&Supplies.count(before,s->s.is(plank.getItem()))>=plankCount){preferredPlank=plank.getItem();break;}
        final Item coherentPlank=preferredPlank;
        List<ItemStack> grid = new ArrayList<>();
        for (String row : pattern) for (int x = 0; x < width; x++) {
            char symbol = row.charAt(x);
            if (symbol == ' ') { grid.add(ItemStack.EMPTY); continue; }
            Predicate<ItemStack> ingredient = switch (symbol) {
                case 'P' -> coherentPlank==null?PLANK:s->s.is(coherentPlank); case 'S' -> s -> s.is(Items.STICK); case 'I' -> s -> s.is(Items.IRON_INGOT); default -> material;
            };
            ItemStack chosen = next.stream().filter(s -> !s.isEmpty() && (Inventory.isUsableForCrafting(s)||repairing&&s.isDamageableItem()&&!s.isEnchanted()&&!s.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME)) && ingredient.test(s)).findFirst().orElse(null);
            if (chosen == null) return false;
            grid.add(chosen.copyWithCount(1)); chosen.shrink(1);
        }
        CraftingInput input = CraftingInput.of(width,height,grid);
        var recipe = v.level.recipeAccess().getRecipeFor(RecipeType.CRAFTING, input, v.level).orElse(null);
        if (recipe == null) return false;
        if(repairing&&!(recipe.value() instanceof RepairItemRecipe))return false;
        ItemStack output = recipe.value().assemble(input);
        if (output.isEmpty() || !accept.test(output) || !output.isItemEnabled(v.level.enabledFeatures())) return false;
        var maker=v.workers.get(b.id());
        if(maker!=null&&!SettlementNeeds.mayCraft(v,maker,output,grid,before))return false;
        if(maker!=null&&output.is(Items.BREAD)){
            int spent=Supplies.count(grid,s->s.is(Items.WHEAT));
            if(Supplies.count(next,s->s.is(Items.WHEAT))<v.farm.wheatReserve(maker)||v.count(s->s.is(Items.WHEAT))-spent<SettlementEconomy.budget(v).breedingWheat())return false;
        }
        if(maker!=null&&grid.stream().anyMatch(s->s.is(Items.IRON_INGOT))&&!IronSupply.accepts(v,maker,output))return false;
        if ((width > 2 || height > 2) && v.index.tables.stream().noneMatch(p -> nearTable(b,v,p))) {
            var resident=v.workers.get(b.id());if(resident!=null)resident.needsWorkbench=true;
            return false;
        }
        for (ItemStack rest : recipe.value().getRemainingItems(input)) if (!rest.isEmpty() && !Supplies.insert(next,rest)) return false;
        boolean made=Supplies.insert(next, output) && Supplies.replace(b, before, next);
        if(made){ProductionBuildings.record(v,b.entity().blockPosition(),output);b.entity().swing(net.minecraft.world.InteractionHand.MAIN_HAND);var resident=v.workers.get(b.id());if(resident!=null)resident.needsWorkbench=false;}return made;
    }
    static boolean nearTable(BotHandle b,Village v,net.minecraft.core.BlockPos p){
        if(!v.level.getBlockState(p).is(Blocks.CRAFTING_TABLE)||b.entity().position().distanceToSqr(Vec3.atCenterOf(p))>16)return false;
        var hit=v.level.clip(new net.minecraft.world.level.ClipContext(b.entity().getEyePosition(),Vec3.atCenterOf(p),net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE,b.entity()));
        return hit.getType()==net.minecraft.world.phys.HitResult.Type.MISS||hit.getBlockPos().equals(p);
    }
    static boolean craft(BotHandle b, Village v, Item out, String[] pattern, Predicate<ItemStack> material) {
        return craft(b, v, pattern, material, s -> s.is(out));
    }
    static boolean make(BotHandle b, Village v, boolean steward) {
        var slots = b.inventory().snapshot().slots();
        for(var worn:slots)if((Supplies.tool(worn)||worn.has(net.minecraft.core.component.DataComponents.EQUIPPABLE))&&worn.isDamageableItem()&&!worn.isEnchanted()&&!Supplies.usable(worn)
                &&slots.stream().filter(s->s.is(worn.getItem())&&s.isDamaged()&&!s.isEnchanted()).count()>=2
                &&craft(b,v,new String[]{"MM"},s->s.is(worn.getItem())&&s.isDamaged()&&!s.isEnchanted(),s->s.is(worn.getItem()),true))return true;
        boolean established=v.state.buildings.stream().anyMatch(h->h.kind.equals("warehouse")&&v.town.warehousePhase(h)>=1);
        boolean miner=v.miner(b.id()),farmer=v.farmer(b.id()),guard=v.guard(b.id());
        if (craft(b,v,Items.BREAD,new String[]{"MMM"},s -> s.is(Items.WHEAT))) return true;
        if(guard&&!hasDefensiveSword(b)
                &&craft(b,v,Items.STONE_SWORD,new String[]{"M","M","S"},STONE))return true;
        if(miner&&needsIronSurvey(v)&&!hasSurveyPick(b)&&maintainSurveyPick(b,v))return true;
        if((v.expeditions.preparingMine(b.id())||SettlementNeeds.needsMineKit(v,v.workers.get(b.id())))&&hasPickFor(b,Blocks.IRON_ORE.defaultBlockState())
                &&MineSupplies.inspect(v.workers.get(b.id())).spare()<24
                &&craft(b,v,Items.STONE_PICKAXE,new String[]{"MMM"," S "," S "},STONE))return true;
        int planks = Supplies.count(slots, PLANK);
        if (planks < 16 && craft(b,v,new String[]{"M"},s -> s.is(ItemTags.LOGS_THAT_BURN),PLANK)) return true;
        if (b.inventory().count(Items.STICK) < 4 && craft(b,v,Items.STICK,new String[]{"P","P"},PLANK)) return true;
        if(v.farm.caretaker()==v.workers.get(b.id())&&v.farm.woolRequested()&&Supplies.find(b,s->s.is(Items.SHEARS)&&Supplies.usable(s))<0
                &&craft(b,v,Items.SHEARS,new String[]{" I","I "},s->s.is(Items.IRON_INGOT)))return true;
        // Every role crosses natural ravines and may need to cut an emergency stairway.
        if (Supplies.find(b,s -> s.is(ItemTags.PICKAXES) && Supplies.usable(s)) < 0) {
            if (craft(b,v,Items.STONE_PICKAXE,new String[]{"MMM"," S "," S "},STONE)) return true;
            if (craft(b,v,Items.WOODEN_PICKAXE,new String[]{"MMM"," S "," S "},PLANK)) return true;
        }
        if (miner&&!hasPickFor(b,Blocks.DIAMOND_ORE.defaultBlockState()) && craft(b,v,Items.IRON_PICKAXE,new String[]{"III"," S "," S "},s -> s.is(Items.IRON_INGOT))) return true;
        if ((miner||!v.builder(b.id())&&v.count(s->s.is(Items.COBBLESTONE))>=3)&&!hasPickFor(b,Blocks.IRON_ORE.defaultBlockState())
                && craft(b,v,Items.STONE_PICKAXE,new String[]{"MMM"," S "," S "},STONE)) return true;
        if (Supplies.find(b,s -> s.is(ItemTags.AXES) && Supplies.usable(s)) < 0) {
            if(craft(b,v,Items.STONE_AXE,new String[]{"MM","MS"," S"},STONE))return true;
            if(craft(b,v,Items.WOODEN_AXE,new String[]{"MM","MS"," S"},PLANK))return true;
        }
        if (farmer&&Supplies.find(b,s -> s.is(ItemTags.HOES) && Supplies.usable(s)) < 0 && craft(b,v,Items.WOODEN_HOE,new String[]{"MM"," S"," S"},PLANK)) return true;
        if (craft(b,v,Items.BREAD,new String[]{"MMM"},s -> s.is(Items.WHEAT))) return true;
        if (Supplies.find(b,s -> s.is(ItemTags.SWORDS) && Supplies.usable(s)) < 0) {
            if (craft(b,v,Items.STONE_SWORD,new String[]{"M","M","S"},STONE)) return true;
            if (craft(b,v,Items.WOODEN_SWORD,new String[]{"M","M","S"},PLANK)) return true;
        }
        if(TownCrafting.make(b,v))return true;
        if (guard&&established&&!Supplies.usable(b.entity().getOffhandItem()) && Supplies.find(b,s->s.is(Items.SHIELD)&&Supplies.usable(s))<0
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
            if (Supplies.find(b,s->s.is(Items.SHEARS)&&Supplies.usable(s))<0 && craft(b,v,Items.SHEARS,new String[]{" I","I "},s -> s.is(Items.IRON_INGOT))) return true;
        }
        int shield = Supplies.find(b,s -> s.is(Items.SHIELD) && Supplies.usable(s));
        if (shield >= 0 && !Supplies.usable(b.entity().getOffhandItem())) return b.inventory().equip(shield, EquipmentSlot.OFFHAND);
        EquipmentSlot[] armor={EquipmentSlot.CHEST,EquipmentSlot.HEAD,EquipmentSlot.LEGS,EquipmentSlot.FEET};
        Item[] items={Items.IRON_CHESTPLATE,Items.IRON_HELMET,Items.IRON_LEGGINGS,Items.IRON_BOOTS};
        String[][] patterns={{"III","I I"},{"III","I I"},{"III","I I","I I"},{"I I","I I"}};
        patterns[0]=new String[]{"I I","III","III"};
        for(int i=0;i<armor.length;i++)if(guard&&established&&!Supplies.usable(b.entity().getItemBySlot(armor[i]))){
            Item item=items[i];int slot=Supplies.find(b,s->s.is(item)&&Supplies.usable(s));
            if(slot>=0)return b.inventory().equip(slot,armor[i]);
            if(craft(b,v,item,patterns[i],s->s.is(Items.IRON_INGOT)))return true;
        }
        return false;
    }
    /** The ready graph node selects the recipe; normal vanilla validation still pays every input. */
    static boolean makePlanned(BotHandle b,Village v,SettlementNeeds.Need need){
        return switch(need.produces()){
            case "planks" -> craft(b,v,new String[]{"M"},s->s.is(ItemTags.LOGS_THAT_BURN),PLANK);
            case "sticks" -> craft(b,v,Items.STICK,new String[]{"P","P"},PLANK);
            case "wooden_pick" -> craft(b,v,Items.WOODEN_PICKAXE,new String[]{"PPP"," S "," S "},PLANK);
            case "survey_pick" -> craft(b,v,Items.STONE_PICKAXE,new String[]{"MMM"," S "," S "},STONE);
            case "beds" -> {
                boolean made=false;
                for(var wool:b.inventory().snapshot().slots())if(wool.is(ItemTags.WOOL)&&b.inventory().count(wool.getItem())>=3
                        &&craft(b,v,new String[]{"MMM","PPP"},s->s.is(wool.getItem()),s->s.is(ItemTags.BEDS))){made=true;break;}
                yield made;
            }
            case "iron_shears" -> craft(b,v,Items.SHEARS,new String[]{" I","I "},s->s.is(Items.IRON_INGOT));
            case "iron_pickaxe" -> craft(b,v,Items.IRON_PICKAXE,new String[]{"III"," S "," S "},STONE);
            case "iron_bucket" -> craft(b,v,Items.BUCKET,new String[]{"I I"," I "},STONE);
            case "iron_offhand" -> craft(b,v,Items.SHIELD,new String[]{"PIP","PPP"," P "},PLANK);
            case "iron_chest" -> craft(b,v,Items.IRON_CHESTPLATE,new String[]{"I I","III","III"},STONE);
            case "iron_head" -> craft(b,v,Items.IRON_HELMET,new String[]{"III","I I"},STONE);
            case "iron_legs" -> craft(b,v,Items.IRON_LEGGINGS,new String[]{"III","I I","I I"},STONE);
            case "iron_feet" -> craft(b,v,Items.IRON_BOOTS,new String[]{"I I","I I"},STONE);
            default -> false;
        };
    }
    static boolean hasPickFor(BotHandle b,net.minecraft.world.level.block.state.BlockState state){return Supplies.find(b,s->s.is(ItemTags.PICKAXES)&&Supplies.usable(s)&&s.isCorrectToolForDrops(state))>=0;}
    static boolean needsIronSurvey(Village v){return IronSupply.rawNeeded(v)>0;}
    static boolean hasSurveyPick(BotHandle b){return Supplies.find(b,s->s.is(ItemTags.PICKAXES)&&s.isCorrectToolForDrops(Blocks.IRON_ORE.defaultBlockState())&&s.getMaxDamage()-s.getDamageValue()>=80)>=0;}
    private static boolean maintainSurveyPick(BotHandle b,Village v){
        var slots=b.inventory().snapshot().slots();
        for(var worn:slots)if(worn.is(ItemTags.PICKAXES)&&worn.isCorrectToolForDrops(Blocks.IRON_ORE.defaultBlockState())&&worn.isDamaged()&&!worn.isEnchanted()
                &&slots.stream().filter(s->s.is(worn.getItem())&&s.isDamaged()&&!s.isEnchanted()).count()>=2
                &&craft(b,v,new String[]{"MM"},s->s.is(worn.getItem())&&s.isDamaged()&&!s.isEnchanted(),s->s.is(worn.getItem()),true))return true;
        return v.count(s->s.is(Items.COBBLESTONE))>=6&&craft(b,v,Items.STONE_PICKAXE,new String[]{"MMM"," S "," S "},STONE);
    }
    static boolean hasDefensiveSword(BotHandle b){return Supplies.find(b,s->s.is(ItemTags.SWORDS)&&Supplies.usable(s)&&!s.is(Items.WOODEN_SWORD)&&!s.is(Items.GOLDEN_SWORD))>=0;}
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
            boolean charcoal=charcoalRequested(v,b)||b.inventory().count(Items.RAW_IRON)==0&&v.count(s->s.is(Items.CHARCOAL)||s.is(Items.COAL))<8&&v.count(s->s.is(ItemTags.LOGS)||s.is(ItemTags.PLANKS))>16;
            for (int i = 0; i < next.size(); i++) if (!charcoal&&next.get(i).is(Items.RAW_IRON) || charcoal&&next.get(i).is(ItemTags.LOGS_THAT_BURN)
                    ||v.town.pending(Items.GLASS_PANE)&&v.count(s->s.is(Items.GLASS))<16&&next.get(i).is(Items.SAND)
                    ||v.town.pending(Items.STONE_BRICKS)&&v.count(s->s.is(Items.STONE))<32&&next.get(i).is(Items.COBBLESTONE)) { slot = i; if (next.get(i).is(Items.RAW_IRON)) break; }
            if (slot >= 0) {
                ItemStack input = next.get(slot);
                var recipe = v.level.recipeAccess().getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(input), v.level);
                if (recipe.isPresent()) {
                    int limit=8;var resident=v.workers.get(b.id());
                    if(resident!=null)for(var material:Orders.Material.values())if(material.accepts.test(input))
                        limit=Math.min(limit,SettlementNeeds.furnaceAllowance(v,resident,material));
                    int n=Math.min(input.getCount(),limit);
                    if(n>0){replacement.set(0,input.copyWithCount(n));input.shrink(n);changed=true;}
                }
            }
        }
        if (!replacement.get(0).isEmpty() && replacement.get(1).getCount() < 4) {
            for (ItemStack fuel : next) if ((fuel.is(Items.COAL) || fuel.is(Items.CHARCOAL) || fuel.is(ItemTags.PLANKS)) && v.level.fuelValues().isFuel(fuel)
                    && (replacement.get(1).isEmpty() || ItemStack.isSameItemSameComponents(replacement.get(1),fuel))) {
                int n = Math.min(fuel.getCount(),4-replacement.get(1).getCount());
                var resident=v.workers.get(b.id());
                if(resident!=null)for(var material:Orders.Material.values())if(material.accepts.test(fuel))
                    n=Math.min(n,SettlementNeeds.furnaceAllowance(v,resident,material));
                if(n==0)continue;
                if (replacement.get(1).isEmpty()) replacement.set(1,fuel.copyWithCount(n)); else replacement.get(1).grow(n);
                fuel.shrink(n); changed = true; break;
            }
        }
        if (!changed || !Supplies.matches(prior,Supplies.copy(furnace)) || !Supplies.replace(b,before,next)) return false;
        for (int i = 0; i < 3; i++) furnace.setItem(i,replacement.get(i)); furnace.setChanged();
        if(!prior.get(2).isEmpty()&&replacement.get(2).isEmpty())ProductionBuildings.record(v,furnace.getBlockPos(),prior.get(2));
        return true;
    }
    static boolean wantsFurnace(BotHandle b,Village v,FurnaceBlockEntity f){
        return !f.getItem(2).isEmpty()||!f.getItem(0).isEmpty()&&f.getItem(1).isEmpty()
                ||f.getItem(0).isEmpty()&&(b.inventory().count(Items.RAW_IRON)>0
                ||v.town.pending(Items.GLASS_PANE)&&b.inventory().count(Items.SAND)>0||v.town.pending(Items.STONE_BRICKS)&&b.inventory().count(Items.COBBLESTONE)>0
                ||(charcoalRequested(v,b)||v.count(s->s.is(Items.CHARCOAL)||s.is(Items.COAL))<8&&v.count(s->s.is(ItemTags.LOGS)||s.is(ItemTags.PLANKS))>16)
                &&b.inventory().snapshot().slots().stream().anyMatch(s->s.is(ItemTags.LOGS_THAT_BURN)));
    }
    private static boolean charcoalRequested(Village v,BotHandle b){
        return v.needs.needs().stream().anyMatch(n->n.purpose().equals("charcoal")&&n.executor().equals(b.id().toString())
                &&v.needs.state(n).state()==SettlementNeeds.State.READY);
    }
}
