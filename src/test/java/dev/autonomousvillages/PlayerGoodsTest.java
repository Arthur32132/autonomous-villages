package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PlayerGoodsTest {
    @BeforeAll static void bootstrapRegistries(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
    @Test void aLedgerNamesItsVillage(){
        assertEquals("Дубки",PlayerGoods.ledgerVillage("Ведомость Дубки"));
        assertNull(PlayerGoods.ledgerVillage("Летопись Дубки"));
    }

    @Test void biggerGiftsEarnMoreGoodwillUpToALimit(){
        assertEquals(1,PlayerGoods.goodwill(1));
        assertEquals(1,PlayerGoods.goodwill(4));
        assertEquals(2,PlayerGoods.goodwill(5));
        assertEquals(16,PlayerGoods.goodwill(64));
        assertEquals(20,PlayerGoods.goodwill(1000));
    }

    @Test void ledgerTextStaysShortAndReadable(){
        assertEquals("Дом",PlayerGoods.kind("native_house"));
        assertEquals("Амбар",PlayerGoods.kind("storehouse"));
        assertEquals("abc",PlayerGoods.shorten("abc",5));
        assertEquals("abcd…",PlayerGoods.shorten("abcdefgh",5));
        assertEquals("",PlayerGoods.shorten(null,5));
    }

    @Test void donationRecordsSurviveAnOldSaveAndStayBounded(){
        var s=new VillageState("A",UUID.randomUUID(),BlockPos.ZERO,48);
        s.donations=null;s.donorNames=null;s.honoured=null;
        s.compactMetadata();
        assertNotNull(s.donations);assertNotNull(s.donorNames);assertNotNull(s.honoured);
        for(int i=0;i<300;i++){var id=UUID.randomUUID();s.donations.put(id,(long)i);s.donorNames.put(id,"p"+i);}
        var stranger=UUID.randomUUID();s.donorNames.put(stranger,"ghost");
        s.compactMetadata();
        assertEquals(256,s.donations.size());
        assertFalse(s.donorNames.containsKey(stranger));
        assertTrue(s.donations.keySet().containsAll(s.donorNames.keySet()));
    }

    @Test void newSwitchesAreOnByDefault(){
        var config=new ModConfig();
        assertTrue(config.ledger&&config.donations);
    }

    @Test void onlyWhatTheVillageUsesIsTaken(){
        // (Wooden blocks, wool and seeds are matched by tags, which a unit test has no data pack to bind; a stack needs bound components.)
        for(Item item:new Item[]{Items.COBBLESTONE,Items.BREAD,Items.IRON_INGOT,Items.COAL,Items.TORCH,Items.CHEST,Items.FURNACE,Items.GLASS_PANE,Items.COBBLESTONE_STAIRS,
                Items.EMERALD,Items.BONE_MEAL,Items.BEEF,Items.COOKED_COD,Items.WHEAT,Items.WHEAT_SEEDS,Items.SAND,Items.LANTERN,Items.SHEARS,Items.POTATO,Items.LADDER})
            assertTrue(PlayerGoods.used(item),item.toString());
        for(Item item:new Item[]{Items.DIRT,Items.GRAVEL,Items.ROTTEN_FLESH,Items.DIAMOND,Items.ENDER_PEARL,Items.STRING,Items.POPPED_CHORUS_FRUIT,Items.NETHERRACK,Items.WRITTEN_BOOK})
            assertFalse(PlayerGoods.used(item),item.toString());
    }

    @Test void giftsLeaveTheWorkersRoomInTheStore(){
        int r=PlayerGoods.RESERVE;
        // Topping up stacks already in the store costs no slot.
        assertEquals(10,PlayerGoods.room(64,10,r,64));
        assertEquals(0,PlayerGoods.room(64,0,r,64));
        assertEquals(64,PlayerGoods.room(64,0,r+1,64));
        assertEquals(128+5,PlayerGoods.room(300,5,r+2,64));
        assertEquals(16,PlayerGoods.room(16,0,r+1,16));
        assertEquals(0,PlayerGoods.room(64,0,0,64));
        assertEquals(0,PlayerGoods.room(0,40,40,64));
    }
}
