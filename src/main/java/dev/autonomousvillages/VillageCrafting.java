package dev.autonomousvillages;

import dev.navigationai.api.BotHandle;
import java.util.*;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

/** Vanilla recipes crafted from a worker's backpack; datapack replacements and leftovers are respected. */
final class VillageCrafting {
    private VillageCrafting(){}
    static final Predicate<ItemStack> PLANK=s->s.is(ItemTags.PLANKS);
    static final Predicate<ItemStack> LOG=s->s.is(ItemTags.LOGS_THAT_BURN);
    static final Predicate<ItemStack> STICK=s->s.is(Items.STICK);
    static final Predicate<ItemStack> COBBLE=s->s.is(ItemTags.STONE_TOOL_MATERIALS);
    /** Stone the mine brings up besides cobblestone; it builds foundations and walls just as well. */
    static final Predicate<ItemStack> RUBBLE=s->s.is(Items.ANDESITE)||s.is(Items.DIORITE)||s.is(Items.GRANITE)||s.is(Items.TUFF)||s.is(Items.STONE)
            ||s.is(Items.MOSSY_COBBLESTONE)||s.is(Items.DEEPSLATE);
    static final Predicate<ItemStack> IRON=s->s.is(Items.IRON_INGOT);
    static final Predicate<ItemStack> FUEL=s->s.is(Items.COAL)||s.is(Items.CHARCOAL);
    static final Predicate<ItemStack> WHEAT=s->s.is(Items.WHEAT);
    static final Predicate<ItemStack> WOOL=s->s.is(ItemTags.WOOL);

    /** M is the recipe's main material, P planks, S sticks, I iron ingots. */
    record Recipe(String name,String[] pattern,Predicate<ItemStack> material,Predicate<ItemStack> output,String label){
        boolean needsTable(){return pattern.length>2||pattern[0].length()>2;}
        /** Vanilla wants one kind of wood for these; tools, chests and tables take mixed planks. */
        boolean coherent(){return Set.of("door","fence","stairs","slab","sign","trapdoor").contains(name);}
        Map<Character,Integer> symbols(){
            var out=new TreeMap<Character,Integer>();
            for(String row:pattern)for(char c:row.toCharArray())if(c!=' ')out.merge(c,1,Integer::sum);
            return out;
        }
        Predicate<ItemStack> ingredient(char symbol){return switch(symbol){case 'P'->PLANK;case 'S'->STICK;case 'I'->IRON;default->material;};}
    }
    static final Recipe PLANKS=new Recipe("planks",new String[]{"M"},LOG,PLANK,"доски");
    static final Recipe STICKS=new Recipe("sticks",new String[]{"P","P"},PLANK,STICK,"палки");
    static final Recipe TABLE=new Recipe("crafting_table",new String[]{"PP","PP"},PLANK,s->s.is(Items.CRAFTING_TABLE),"верстак");
    static final Recipe CHEST=new Recipe("chest",new String[]{"PPP","P P","PPP"},PLANK,s->s.is(Items.CHEST),"сундук");
    static final Recipe FURNACE=new Recipe("furnace",new String[]{"MMM","M M","MMM"},COBBLE,s->s.is(Items.FURNACE),"печь");
    static final Recipe DOOR=new Recipe("door",new String[]{"PP","PP","PP"},PLANK,s->s.is(ItemTags.WOODEN_DOORS),"двери");
    static final Recipe TORCH=new Recipe("torch",new String[]{"M","S"},FUEL,s->s.is(Items.TORCH),"факелы");
    static final Recipe BREAD=new Recipe("bread",new String[]{"MMM"},WHEAT,s->s.is(Items.BREAD),"хлеб");
    static final Recipe SHEARS=new Recipe("shears",new String[]{" I","I "},IRON,s->s.is(Items.SHEARS),"ножницы");
    static final Recipe FENCE=new Recipe("fence",new String[]{"PSP","PSP"},PLANK,s->s.is(ItemTags.WOODEN_FENCES),"забор");
    static final Recipe STAIRS=new Recipe("stairs",new String[]{"P  ","PP ","PPP"},PLANK,s->s.is(ItemTags.WOODEN_STAIRS),"ступени");
    static final Recipe SLAB=new Recipe("slab",new String[]{"PPP"},PLANK,s->s.is(ItemTags.WOODEN_SLABS),"плиты");
    static final Recipe SIGN=new Recipe("sign",new String[]{"PPP","PPP"," S "},PLANK,s->s.is(ItemTags.SIGNS),"табличка");
    static final Predicate<ItemStack> COBBLESTONE=s->s.is(Items.COBBLESTONE);
    static final Predicate<ItemStack> SAND=s->s.is(Items.SAND)||s.is(Items.RED_SAND);
    static final Predicate<ItemStack> GLASS=s->s.is(Items.GLASS);
    static final Recipe COBBLE_STAIRS=new Recipe("cobblestone_stairs",new String[]{"M  ","MM ","MMM"},COBBLESTONE,s->s.is(Items.COBBLESTONE_STAIRS),"каменные ступени");
    static final Recipe COBBLE_SLAB=new Recipe("cobblestone_slab",new String[]{"MMM"},COBBLESTONE,s->s.is(Items.COBBLESTONE_SLAB),"каменные плиты");
    static final Recipe COBBLE_WALL=new Recipe("cobblestone_wall",new String[]{"MMM","MMM"},COBBLESTONE,s->s.is(Items.COBBLESTONE_WALL),"каменная ограда");
    static final Recipe TRAPDOOR=new Recipe("trapdoor",new String[]{"PPP","PPP"},PLANK,s->s.is(ItemTags.WOODEN_TRAPDOORS),"ставни");
    static final Recipe LADDER=new Recipe("ladder",new String[]{"S S","SSS","S S"},STICK,s->s.is(Items.LADDER),"лестница");
    static final Recipe GLASS_PANE=new Recipe("glass_pane",new String[]{"MMM","MMM"},GLASS,s->s.is(Items.GLASS_PANE),"оконное стекло");
    static Recipe tool(String family,String tier){
        Predicate<ItemStack> material=switch(tier){case "stone"->COBBLE;case "iron"->IRON;default->PLANK;};
        String[] pattern=switch(family){
            case "pick"->new String[]{"MMM"," S "," S "};
            case "axe"->new String[]{"MM","MS"," S"};
            case "hoe"->new String[]{"MM"," S"," S"};
            case "sword"->new String[]{"M","M","S"};
            default->new String[]{"M","S","S"}; // shovel
        };
        var tag=switch(family){case "pick"->ItemTags.PICKAXES;case "axe"->ItemTags.AXES;case "hoe"->ItemTags.HOES;case "sword"->ItemTags.SWORDS;default->ItemTags.SHOVELS;};
        return new Recipe(tier+"_"+family,pattern,material,s->s.is(tag),family+" ("+tier+")");
    }
    /** A bed needs three wool of one colour; the colour is chosen from what is actually available. */
    static Recipe bed(Item wool){return new Recipe("bed",new String[]{"MMM","PPP"},s->s.is(wool),s->s.is(ItemTags.BEDS),"кровать");}

    static boolean craft(BotHandle b,Village v,Recipe recipe){return craft(b,v,recipe.pattern(),recipe.material(),recipe.output());}
    static boolean craft(BotHandle b,Village v,String[] pattern,Predicate<ItemStack> material,Predicate<ItemStack> accept){
        int width=pattern[0].length(),height=pattern.length;
        if(v.level.getGameRules().get(GameRules.LIMITED_CRAFTING))return false; // villagers have no recipe book
        var before=Supplies.copy(b.inventory().snapshot().slots());var next=Supplies.copy(before);
        int plankCount=0;for(String row:pattern)for(char symbol:row.toCharArray())if(symbol=='P')plankCount++;
        Item coherentPlank=null;
        if(plankCount>0)for(ItemStack plank:before)if(PLANK.test(plank)&&Supplies.count(before,s->s.is(plank.getItem()))>=plankCount){coherentPlank=plank.getItem();break;}
        final Item plank=coherentPlank;
        List<ItemStack> grid=new ArrayList<>();
        for(String row:pattern)for(int x=0;x<width;x++){
            char symbol=row.charAt(x);
            if(symbol==' '){grid.add(ItemStack.EMPTY);continue;}
            Predicate<ItemStack> ingredient=switch(symbol){case 'P'->plank==null?PLANK:s->s.is(plank);case 'S'->STICK;case 'I'->IRON;default->material;};
            ItemStack chosen=next.stream().filter(s->!s.isEmpty()&&Inventory.isUsableForCrafting(s)&&ingredient.test(s)).findFirst().orElse(null);
            if(chosen==null)return false;
            grid.add(chosen.copyWithCount(1));chosen.shrink(1);
        }
        CraftingInput input=CraftingInput.of(width,height,grid);
        var recipe=v.level.recipeAccess().getRecipeFor(RecipeType.CRAFTING,input,v.level).orElse(null);
        if(recipe==null)return false;
        ItemStack output=recipe.value().assemble(input);
        if(output.isEmpty()||!accept.test(output)||!output.isItemEnabled(v.level.enabledFeatures()))return false;
        if((width>2||height>2)&&v.index.tables.stream().noneMatch(p->nearTable(b,v,p)))return false;
        for(ItemStack rest:recipe.value().getRemainingItems(input))if(!rest.isEmpty()&&!Supplies.insert(next,rest))return false;
        boolean made=Supplies.insert(next,output)&&Supplies.replace(b,before,next);
        if(made){b.entity().swing(net.minecraft.world.InteractionHand.MAIN_HAND);v.chronicle.crafted(output);}
        return made;
    }
    static boolean nearTable(BotHandle b,Village v,BlockPos p){
        if(!v.level.getBlockState(p).is(Blocks.CRAFTING_TABLE)||b.entity().position().distanceToSqr(Vec3.atCenterOf(p))>16)return false;
        var hit=v.level.clip(new net.minecraft.world.level.ClipContext(b.entity().getEyePosition(),Vec3.atCenterOf(p),net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE,b.entity()));
        return hit.getType()==net.minecraft.world.phys.HitResult.Type.MISS||hit.getBlockPos().equals(p);
    }
    static boolean hasPickFor(BotHandle b,BlockState state){return Supplies.find(b,s->s.is(ItemTags.PICKAXES)&&Supplies.usable(s)&&s.isCorrectToolForDrops(state))>=0;}
    static boolean hasSword(BotHandle b){return Supplies.find(b,s->s.is(ItemTags.SWORDS)&&Supplies.usable(s))>=0;}
    static boolean hasDefensiveSword(BotHandle b){return Supplies.find(b,s->s.is(ItemTags.SWORDS)&&Supplies.usable(s)&&!s.is(Items.WOODEN_SWORD)&&!s.is(Items.GOLDEN_SWORD))>=0;}

    /** Collects the output, loads one input stack and tops up fuel. Charcoal is made from logs when nothing else burns. */
    static boolean serviceFurnace(BotHandle b,Village v,FurnaceBlockEntity furnace,Predicate<ItemStack> input){
        if(furnace.isLocked()||b.entity().position().distanceToSqr(Vec3.atCenterOf(furnace.getBlockPos()))>16)return false;
        var before=Supplies.copy(b.inventory().snapshot().slots());var next=Supplies.copy(before);
        var prior=Supplies.copy(furnace);var replacement=Supplies.copy(prior);boolean changed=false;
        ItemStack out=replacement.get(2);var withOutput=Supplies.copy(next);
        if(!out.isEmpty()&&Supplies.insert(withOutput,out)){next=withOutput;replacement.set(2,ItemStack.EMPTY);changed=true;}
        if(replacement.get(0).isEmpty())for(ItemStack stack:next)if(!stack.isEmpty()&&input.test(stack)
                &&v.level.recipeAccess().getRecipeFor(RecipeType.SMELTING,new SingleRecipeInput(stack),v.level).isPresent()){
            int n=Math.min(stack.getCount(),16);replacement.set(0,stack.copyWithCount(n));stack.shrink(n);changed=true;break;
        }
        if(!replacement.get(0).isEmpty()&&replacement.get(1).getCount()<4)for(ItemStack fuel:next)
            if((FUEL.test(fuel)||fuel.is(ItemTags.PLANKS)||fuel.is(ItemTags.LOGS_THAT_BURN)&&!input.test(fuel))&&v.level.fuelValues().isFuel(fuel)
                    &&(replacement.get(1).isEmpty()||ItemStack.isSameItemSameComponents(replacement.get(1),fuel))){
                int n=Math.min(fuel.getCount(),4-replacement.get(1).getCount());
                if(replacement.get(1).isEmpty())replacement.set(1,fuel.copyWithCount(n));else replacement.get(1).grow(n);
                fuel.shrink(n);changed=true;break;
            }
        if(!changed||!Supplies.matches(prior,Supplies.copy(furnace))||!Supplies.replace(b,before,next))return false;
        for(int i=0;i<3;i++)furnace.setItem(i,replacement.get(i));
        furnace.setChanged();
        return true;
    }
}
