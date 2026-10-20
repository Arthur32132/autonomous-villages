package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LandmarkGoodsTest {
    @BeforeAll static void bootstrapRegistries(){
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        // Item stacks need the items' default components, which a server binds when it loads its data.
        if(!Items.BREAD.builtInRegistryHolder().areComponentsBound())
            BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createLookup()).forEach(DataComponentInitializers.PendingComponents::apply);
    }

    @Test void theMarketSellsOnlyWhatTheStoreCanSpare(){
        assertEquals(0,LandmarkGoods.surplus(100,256));
        assertEquals(10,LandmarkGoods.surplus(266,256));
        assertEquals(64,LandmarkGoods.surplus(1000,256));
        for(var ware:LandmarkGoods.WARES)assertTrue(ware.keep()>=16,ware.item().toString());
    }

    @Test void theMillBakesFromSpareWheatOnly(){
        assertEquals(0,LandmarkGoods.loaves(16));
        assertEquals(1,LandmarkGoods.loaves(19));
        assertEquals(LandmarkGoods.MILL_BREAD,LandmarkGoods.loaves(500));
    }

    @Test void theKitchenKeepsSeedPotatoes(){
        assertEquals(0,LandmarkGoods.cookable(Items.POTATO,30));
        assertEquals(8,LandmarkGoods.cookable(Items.POTATO,40));
        assertEquals(5,LandmarkGoods.cookable(Items.BEEF,5));
        assertEquals(Items.COOKED_COD,LandmarkGoods.KITCHEN.get(Items.COD));
    }

    @Test void goodsDaysFromAnOldSaveAreFilledInAndCleaned(){
        var s=new VillageState("A",UUID.randomUUID(),BlockPos.ZERO,48);
        s.goodsDays=null;s.compactMetadata();assertNotNull(s.goodsDays);
        s.goodsDays.put("forge",3L);s.goodsDays.put("castle",3L);s.compactMetadata();
        assertEquals(java.util.Set.of("forge"),s.goodsDays.keySet());
        assertTrue(new ModConfig().landmarkGoods);
    }

    private static List<ItemStack> chest(ItemStack... goods){
        var slots=new ArrayList<ItemStack>(Collections.nCopies(27,ItemStack.EMPTY));
        for(int i=0;i<goods.length;i++)slots.set(i,goods[i]);
        return slots;
    }
    @Test void roomCountsFreeSlotsAndPartialStacks(){
        var named=new ItemStack(Items.BREAD,10);named.set(DataComponents.CUSTOM_NAME,Component.literal("Каравай"));
        var slots=chest(new ItemStack(Items.BREAD,60),new ItemStack(Items.COD,10),named,new ItemStack(Items.BREAD,64));
        assertEquals(23*64+4,LandmarkGoods.room(slots,new ItemStack(Items.BREAD)));
        assertEquals(23*64+54,LandmarkGoods.room(slots,new ItemStack(Items.COD)));
        var full=new ArrayList<ItemStack>();for(int i=0;i<27;i++)full.add(new ItemStack(Items.COBBLESTONE,64));
        assertEquals(0,LandmarkGoods.room(full,new ItemStack(Items.BREAD)));
        assertEquals(0,LandmarkGoods.room(List.of(),new ItemStack(Items.COOKED_BEEF)));
    }
    /** A store that fills up while goods are made: what does not fit is handed on, nothing vanishes. */
    @Test void noGoodsAreDestroyedWhenTheStoreIsFull(){
        var slots=new ArrayList<ItemStack>();for(int i=0;i<26;i++)slots.add(new ItemStack(Items.COBBLESTONE,64));
        slots.add(new ItemStack(Items.BREAD,59));
        java.util.function.UnaryOperator<ItemStack> store=goods->goods.copyWithCount(goods.getCount()-Supplies.insertPartial(slots,goods));
        var spilled=new ArrayList<ItemStack>();
        int stored=LandmarkGoods.keep(new ItemStack(Items.BREAD,8),store,spilled::add);
        assertEquals(5,stored);assertEquals(64,slots.get(26).getCount());
        assertEquals(1,spilled.size());assertEquals(3,spilled.get(0).getCount());assertTrue(spilled.get(0).is(Items.BREAD));
        assertEquals(0,LandmarkGoods.keep(new ItemStack(Items.COOKED_COD,4),store,spilled::add));
        assertEquals(4,spilled.get(1).getCount());
        int total=spilled.stream().mapToInt(ItemStack::getCount).sum()+stored;
        assertEquals(12,total);
        // Nothing to hand on when everything fits.
        var empty=chest();var none=new ArrayList<ItemStack>();
        assertEquals(16,LandmarkGoods.keep(new ItemStack(Items.BREAD,16),g->g.copyWithCount(g.getCount()-Supplies.insertPartial(empty,g)),none::add));
        assertTrue(none.isEmpty());
        // And a full store is not offered more: room() stops the mill, the tavern and the pier first.
        assertEquals(0,LandmarkGoods.room(slots,new ItemStack(Items.BREAD)));
    }
}
