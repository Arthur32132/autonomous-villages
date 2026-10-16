package dev.autonomousvillages;

import dev.navigationai.api.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.*;
import net.minecraft.world.item.*;

/** Recipe requests come from actual missing architectural pieces. Every recipe debits real items. */
final class TownCrafting {
    static boolean make(BotHandle b,Village v){
        Resident r=v.workers.get(b.id());if(r==null)return false;
        for(var p:v.town.wantedPlans(r)){
            if(Supplies.find(b,Construction.material(p.step()))>=0)continue;
            if(make(b,v,p.block().getBlock().asItem()))return true;
        }
        return false;
    }
    private static boolean make(BotHandle b,Village v,Item out){
        ItemStack desired=new ItemStack(out);
        if(desired.is(ItemTags.BEDS)){
            for(ItemStack wool:b.inventory().snapshot().slots())if(wool.is(ItemTags.WOOL)&&b.inventory().count(wool.getItem())>=3
                    &&VillageWorkshop.craft(b,v,new String[]{"MMM","PPP"},s->s.is(wool.getItem()),s->s.is(ItemTags.BEDS)))return true;
            return false;
        }
        if(out instanceof BlockItem block){
            var state=block.getBlock().defaultBlockState();
            if(Construction.stoneStairs(state))return VillageWorkshop.craft(b,v,Items.COBBLESTONE_STAIRS,new String[]{"M  ","MM ","MMM"},s->s.is(Items.COBBLESTONE));
            if(Construction.stoneSlab(state))return VillageWorkshop.craft(b,v,Items.COBBLESTONE_SLAB,new String[]{"MMM"},s->s.is(Items.COBBLESTONE));
            if(state.is(BlockTags.WALLS))return VillageWorkshop.craft(b,v,Items.COBBLESTONE_WALL,new String[]{"MMM","MMM"},s->s.is(Items.COBBLESTONE));
            if(state.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock)return VillageWorkshop.craft(b,v,new String[]{"SPS","SPS"},VillageWorkshop.PLANK,s->s.getItem() instanceof BlockItem item&&item.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock);
        }
        String id=BuiltInRegistries.ITEM.getKey(out).toString();
        if(desired.is(ItemTags.WOODEN_TRAPDOORS))return VillageWorkshop.craft(b,v,new String[]{"PPP","PPP"},VillageWorkshop.PLANK,s->s.is(ItemTags.WOODEN_TRAPDOORS));
        if(desired.is(ItemTags.WOODEN_PRESSURE_PLATES))return VillageWorkshop.craft(b,v,new String[]{"PP"},VillageWorkshop.PLANK,s->s.is(ItemTags.WOODEN_PRESSURE_PLATES));
        if(desired.is(ItemTags.WOODEN_BUTTONS))return VillageWorkshop.craft(b,v,new String[]{"P"},VillageWorkshop.PLANK,s->s.is(ItemTags.WOODEN_BUTTONS));
        if(desired.is(ItemTags.WOODEN_DOORS))return VillageWorkshop.craft(b,v,new String[]{"PP","PP","PP"},VillageWorkshop.PLANK,s->s.is(ItemTags.WOODEN_DOORS));
        if(desired.is(ItemTags.WOODEN_STAIRS))return VillageWorkshop.craft(b,v,new String[]{"P  ","PP ","PPP"},VillageWorkshop.PLANK,s->s.is(ItemTags.WOODEN_STAIRS));
        if(desired.is(ItemTags.WOODEN_SLABS))return VillageWorkshop.craft(b,v,new String[]{"PPP"},VillageWorkshop.PLANK,s->s.is(ItemTags.WOODEN_SLABS));
        if(desired.is(ItemTags.WOODEN_FENCES))return VillageWorkshop.craft(b,v,new String[]{"PSP","PSP"},VillageWorkshop.PLANK,s->s.is(ItemTags.WOODEN_FENCES));
        if(out==Items.CHEST)return VillageWorkshop.craft(b,v,out,new String[]{"PPP","P P","PPP"},VillageWorkshop.PLANK);
        if(out==Items.CRAFTING_TABLE)return VillageWorkshop.craft(b,v,out,new String[]{"PP","PP"},VillageWorkshop.PLANK);
        if(out==Items.FURNACE)return VillageWorkshop.craft(b,v,out,new String[]{"MMM","M M","MMM"},s->s.is(Items.COBBLESTONE));
        if(out==Items.TORCH)return VillageWorkshop.craft(b,v,out,new String[]{"M","S"},s->s.is(Items.COAL)||s.is(Items.CHARCOAL));
        if(id.endsWith("_sign"))return VillageWorkshop.craft(b,v,new String[]{"PPP","PPP"," S "},VillageWorkshop.PLANK,s->s.getItem() instanceof SignItem);
        if(out==Items.GLASS_PANE)return VillageWorkshop.craft(b,v,out,new String[]{"MMM","MMM"},s->s.is(Items.GLASS));
        if(out==Items.COBBLESTONE_STAIRS)return VillageWorkshop.craft(b,v,out,new String[]{"M  ","MM ","MMM"},s->s.is(Items.COBBLESTONE));
        if(out==Items.COBBLESTONE_SLAB)return VillageWorkshop.craft(b,v,out,new String[]{"MMM"},s->s.is(Items.COBBLESTONE));
        if(out==Items.STONE_BRICKS)return VillageWorkshop.craft(b,v,out,new String[]{"MM","MM"},s->s.is(Items.STONE));
        if(id.endsWith("_bed"))for(ItemStack wool:b.inventory().snapshot().slots())if(wool.is(ItemTags.WOOL)&&b.inventory().count(wool.getItem())>=3)
            if(VillageWorkshop.craft(b,v,new String[]{"MMM","PPP"},s->s.is(wool.getItem()),s->s.is(ItemTags.BEDS)))return true;
        return false;
    }
}
