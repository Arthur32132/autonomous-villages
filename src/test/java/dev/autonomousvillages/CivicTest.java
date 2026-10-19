package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class CivicTest {
    @Test void aWorkplaceIsNamedByItsTrade(){
        assertEquals("tool_smith",Blueprints.trade("plains_tool_smith_1"));
        assertEquals("library",Blueprints.trade("desert_library_1"));
        assertEquals("кузница",Projects.trade("taiga_tool_smith_1"));
        assertEquals("оружейная",Projects.trade("snowy_weapon_smith_1"));
        assertEquals("мясная лавка",Projects.trade("savanna_butchers_shop_2"));
        assertEquals("храм",Projects.trade("plains_temple_4"));
        assertEquals("small_farm",Blueprints.trade("plains_small_farm_1"));
        assertEquals("поле",Projects.trade("taiga_large_farm_2"));
    }
}
