package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VillagePresentationTest {
    @Test void boardLinesAreClippedToWhatASignHolds(){
        assertEquals("Дубки",VillagePresentation.clip("Дубки"));
        assertEquals("",VillagePresentation.clip(null));
        String fits="а".repeat(VillagePresentation.SIGN_LINE);
        assertEquals(fits,VillagePresentation.clip(fits));
        String cut=VillagePresentation.clip("cobblestone_wall 12");
        assertEquals(VillagePresentation.SIGN_LINE,cut.length());assertTrue(cut.endsWith("…"),cut);assertTrue(cut.startsWith("cobblestone_wa"),cut);
        var lines=VillagePresentation.boardLines("Очень_длинное_название_деревни_32",List.of("стройке нужно:","булыжник 12","доски 4","лишняя строка"));
        assertEquals(4,lines.size());
        assertEquals(List.of("Очень_длинное_…","стройке нужно:","булыжник 12","доски 4"),lines);
        assertEquals(List.of("Дубки","всё построено","",""),VillagePresentation.boardLines("Дубки",List.of("всё построено")));
        for(String heading:VillagePresentation.BOARD_HEADINGS)if(!heading.equals("нужно на стройку:"))
            assertTrue(heading.length()<=VillagePresentation.SIGN_LINE,heading);
    }

    @Test void onlyABoardTheVillageWroteItselfIsRecognisedFromAnOlderVersion(){
        assertTrue(VillagePresentation.oldBoard("Дубки","нужно на стройку:","Дубки"));
        assertTrue(VillagePresentation.oldBoard("Дубки","ЗАКАЗ игроку:","Дубки"));
        assertTrue(VillagePresentation.oldBoard("Очень_длинное_…","всё построено","Очень_длинное_название_деревни_32"));
        // A player's welcome sign, a landmark's sign, the barn's sign and another village's board are left alone.
        assertFalse(VillagePresentation.oldBoard("Дубки","добро пожаловать","Дубки"));
        assertFalse(VillagePresentation.oldBoard("","КУЗНИЦА","Дубки"));
        assertFalse(VillagePresentation.oldBoard("СКЛАД","Дубки","Дубки"));
        assertFalse(VillagePresentation.oldBoard("Берёзки","всё построено","Дубки"));
    }

    @Test void aPlayersNameTagIsNeverMistakenForTheVillagesLabel(){
        assertTrue(VillagePresentation.generatedLabel("Иван, строитель • рубит дерево","Иван",null));
        assertFalse(VillagePresentation.generatedLabel("Бобик","Иван",null));
        // A tag that happens to read like the generated name is still the player's.
        assertFalse(VillagePresentation.generatedLabel("Иван","Иван",null));
        assertFalse(VillagePresentation.generatedLabel("Иван, мой друг","Иван",null));
        // The label an older version wrote over a player's name is the village's, so the name comes back.
        assertTrue(VillagePresentation.generatedLabel("Бобик, фермер • пашет","Иван","Бобик"));
        assertFalse(VillagePresentation.generatedLabel("Шарик","Иван","Бобик"));
        UUID id=UUID.fromString("0f8e7d6c-5b4a-3928-1706-f5e4d3c2b1a0");
        assertEquals(VillagePresentation.generatedName(id),VillagePresentation.generatedName(new UUID(id.getMostSignificantBits(),id.getLeastSignificantBits())));
    }
}
