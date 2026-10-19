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
}
