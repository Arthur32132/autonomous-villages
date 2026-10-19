package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class PlayerGoodsTest {
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
}
