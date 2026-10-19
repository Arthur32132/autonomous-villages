package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class VillageRequestsTest {
    private static VillageState.Request request(String key,int amount,int delivered){
        var r=new VillageState.Request();r.key=key;r.amount=amount;r.delivered=delivered;return r;
    }
    @Test void theBoardShowsWhatIsLeftToBring(){
        var r=request("planks",32,12);
        assertEquals(20,VillageRequests.left(r));
        assertEquals(List.of("ЗАКАЗ игроку:","доски ×20","награда ждёт"),VillageRequests.board(r));
        r.delivered=40;assertEquals(0,VillageRequests.left(r));
        assertEquals("hay_block",VillageRequests.label("minecraft:hay_block"));
    }
    @Test void aBrokenRequestInTheSaveIsDropped(){
        var s=new VillageState("A",UUID.randomUUID(),BlockPos.ZERO,48);
        s.request=request("door",4,1);s.compactMetadata();assertNotNull(s.request);
        s.request=request("door",500,0);s.compactMetadata();assertNull(s.request);
        s.request=request(null,4,0);s.compactMetadata();assertNull(s.request);
        assertTrue(new ModConfig().requests);
    }

    @Test void playerRecordsSurviveASaveAndReload(){
        var s=new VillageState("Дубки",UUID.randomUUID(),BlockPos.ZERO,48);
        var donor=UUID.randomUUID();
        s.donations.put(donor,140L);s.donorNames.put(donor,"Mars");s.honoured.add(donor);
        s.goodsDays.put("tavern",12L);s.fairDay=14;s.nextRequest=5000;s.request=request("planks",20,7);
        var loaded=VillageRuntime.GSON.fromJson(VillageRuntime.GSON.toJson(s),VillageState.class);
        loaded.compactMetadata();loaded.validate();
        assertEquals(140L,loaded.donations.get(donor));
        assertEquals("Mars",loaded.donorNames.get(donor));
        assertTrue(loaded.honoured.contains(donor));
        assertEquals(12L,loaded.goodsDays.get("tavern"));
        assertEquals(14,loaded.fairDay);assertEquals(5000,loaded.nextRequest);
        assertEquals("planks",loaded.request.key);assertEquals(13,VillageRequests.left(loaded.request));
    }
    @Test void anOldSaveWithoutTheNewFieldsLoads(){
        var s=new VillageState("Old",UUID.randomUUID(),BlockPos.ZERO,48);
        var json=VillageRuntime.GSON.toJsonTree(s).getAsJsonObject();
        for(String f:List.of("donations","donorNames","honoured","goodsDays","fairDay","request","nextRequest"))json.remove(f);
        var loaded=VillageRuntime.GSON.fromJson(json,VillageState.class);
        loaded.compactMetadata();loaded.validate();
        assertTrue(loaded.donations.isEmpty());assertNull(loaded.request);assertEquals(-1,loaded.fairDay);
    }
}
