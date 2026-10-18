package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class ReviewFixesTest {
    @Test void placedMetadataFollowsTheSaveValidationArea(){
        var s=new VillageState("A",UUID.randomUUID(),new BlockPos(0,64,0),24);s.bounds=new VillageState.Bounds(-24,-24,24,24);
        var outside=new BlockPos(40,64,0);var remote=new BlockPos(1010,70,0);
        assertTrue(s.mayRecordPlacement(new BlockPos(0,64,0),false));
        assertFalse(s.mayRecordPlacement(outside,false));assertFalse(s.mayRecordPlacement(outside,true));
        assertTrue(s.rememberWorksite(new BlockPos(1000,70,0).asLong()));
        assertTrue(s.mayRecordPlacement(remote,true));assertFalse(s.mayRecordPlacement(remote,false));
        // What the guard prevents: one furnace recorded outside the claim fails validate(), i.e. every later save.
        s.furnaces.add(outside.asLong());assertThrows(IllegalArgumentException.class,s::validate);
        s.furnaces.clear();s.plantedTrees.add(remote.asLong());s.validate();
    }
    @Test void releaseMarkerSurvivesSaveAndOldNamesStayRestorable(){
        var legacy=VillageRuntime.GSON.fromJson("{\"json\":\"\",\"visible\":false}",VillageState.ResidentName.class);
        assertFalse(legacy.untouched);
        var id=UUID.randomUUID();var save=new VillageRuntime.Save();save.villages=List.of();
        save.pendingReleases.put(id,VillageState.ResidentName.untouched());
        var restored=VillageRuntime.GSON.fromJson(VillageRuntime.GSON.toJson(save),VillageRuntime.Save.class);
        assertTrue(restored.pendingReleases.get(id).untouched);assertEquals("",restored.pendingReleases.get(id).json);
    }
}
