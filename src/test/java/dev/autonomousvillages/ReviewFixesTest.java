package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class ReviewFixesTest {
    @Test void placedMetadataStaysInsideTheFootprint(){
        var s=new VillageState("A",UUID.randomUUID(),new BlockPos(0,64,0),24);s.bounds=new VillageState.Bounds(-24,-24,24,24);
        var outside=new BlockPos(40,64,0);
        assertTrue(s.mayRecordPlacement(new BlockPos(0,64,0),false));
        assertFalse(s.mayRecordPlacement(outside,false));
        // A sapling replanted where a tree in the outskirts was felled is remembered; far away it is not.
        assertTrue(s.mayRecordPlacement(outside,true));assertFalse(s.mayRecordPlacement(new BlockPos(200,64,0),true));
    }
    @Test void releaseMarkerSurvivesSaveAndOldNamesStayRestorable(){
        var legacy=VillageRuntime.GSON.fromJson("{\"json\":\"\",\"visible\":false}",VillageState.ResidentName.class);
        assertFalse(legacy.untouched);
        var id=UUID.randomUUID();var save=new VillageRuntime.Save();save.villages=List.of();
        save.pendingReleases.put(id,VillageState.ResidentName.untouched());
        var restored=VillageRuntime.GSON.fromJson(VillageRuntime.GSON.toJson(save),VillageRuntime.Save.class);
        assertTrue(restored.pendingReleases.get(id).untouched);assertEquals("",restored.pendingReleases.get(id).json);
    }
    @Test void oldSaveReleasesEveryResidentWithTheOriginalName(){
        UUID named=new UUID(0,1),plain=new UUID(0,2),pending=new UUID(0,3);
        String json="{\"version\":3,\"villages\":[{\"name\":\"Old\",\"residents\":[\""+named+"\",\""+plain+"\"],"
                +"\"residentNames\":{\""+named+"\":{\"json\":\"\\\"Bob\\\"\",\"visible\":true}},\"orders\":[{\"purpose\":\"x\"}]}],"
                +"\"pendingReleases\":{\""+pending+"\":{\"json\":\"\",\"visible\":false}}}";
        var names=VillageRuntime.legacyReleases(JsonParser.parseString(json).getAsJsonObject());
        assertEquals(3,names.size());
        assertEquals("\"Bob\"",names.get(named).json);assertTrue(names.get(named).visible);
        assertTrue(names.get(plain).untouched);
        assertFalse(names.get(pending).untouched);
    }
    @Test void unreadableOldSaveStillReleasesWhatItCan(){
        UUID ok=new UUID(0,7);
        String json="{\"version\":2,\"villages\":[{\"residents\":[\""+ok+"\",\"not-a-uuid\"]}]}";
        var names=VillageRuntime.legacyReleases(JsonParser.parseString(json).getAsJsonObject());
        assertTrue(names.containsKey(ok));
    }
}
