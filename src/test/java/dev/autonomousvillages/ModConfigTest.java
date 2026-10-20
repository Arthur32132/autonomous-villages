package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModConfigTest {
    @Test void firstStartWritesEverySwitchOnAndAnOwnersChoiceSurvives(@TempDir Path dir) throws Exception {
        var first=ModConfig.load(dir);
        assertTrue(first.gifts&&first.summonGolems&&first.watchtower);
        Path file=dir.resolve("autonomous_villages.json");
        assertTrue(Files.readString(file).contains("\"chronicleBook\": true"));
        // An owner turns gifts off; a later start keeps that and still lists the other switches.
        Files.writeString(file,"{\"gifts\": false}");
        var second=ModConfig.load(dir);
        assertFalse(second.gifts);assertTrue(second.compost);
        assertTrue(Files.readString(file).contains("\"compost\": true"));
    }
    @Test void chatAnnouncementsAreOnByDefaultAndAnOwnerCanMuteThem(@TempDir Path dir) throws Exception {
        assertTrue(new ModConfig().chatAnnouncements);
        Path file=dir.resolve("autonomous_villages.json");
        // A file from before the switch existed gains it, switched on.
        Files.writeString(file,"{\"gifts\": true}");
        assertTrue(ModConfig.load(dir).chatAnnouncements);
        assertTrue(Files.readString(file).contains("\"chatAnnouncements\": true"));
        Files.writeString(file,Files.readString(file).replace("\"chatAnnouncements\": true","\"chatAnnouncements\": false"));
        var muted=ModConfig.load(dir);
        assertFalse(muted.chatAnnouncements);assertTrue(muted.morningNews);
        assertTrue(Files.readString(file).contains("\"chatAnnouncements\": false"));
        assertFalse(ModConfig.load(dir).chatAnnouncements);
    }
}
