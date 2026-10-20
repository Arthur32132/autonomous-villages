package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class FeaturesTest {
    @Test void everyFeatureHasAUniqueKeyAndAConfigSwitch() throws Exception {
        Set<String> keys=new HashSet<>();
        for(var f:Features.ALL){
            assertTrue(keys.add(f.key()),"duplicate key "+f.key());
            var field=ModConfig.class.getField(f.key());
            assertEquals(boolean.class,field.getType());
            assertTrue(field.getBoolean(new ModConfig()),f.key()+" defaults to on");
        }
    }

    @Test void throttleLetsTheFirstCallThroughAndHoldsTheNext() {
        Map<String,Long> last=new HashMap<>();
        assertFalse(Features.throttled(last,"a",100,1200));
        assertTrue(Features.throttled(last,"a",500,1200));
        assertFalse(Features.throttled(last,"a",1300,1200));
        assertFalse(Features.throttled(last,"b",500,1200));
        // A clock that went backwards (new world, /time set) never locks a feature out.
        assertFalse(Features.throttled(last,"a",10,1200));
    }

    @Test void oversizedOrNullFeatureStateIsDroppedOnCompaction() {
        var s=new VillageState();
        s.features.put("ok","{\"a\":1}");
        s.features.put("huge","x".repeat(Features.MAX_STATE_CHARS+1));
        s.features.put("nothing",null);
        s.compactMetadata();
        assertEquals(Set.of("ok"),s.features.keySet());
        s.features=null;
        s.compactMetadata();
        assertNotNull(s.features);
    }
}
