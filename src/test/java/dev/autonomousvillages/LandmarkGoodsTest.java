package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LandmarkGoodsTest {
    @BeforeAll static void bootstrapRegistries(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}

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
}
