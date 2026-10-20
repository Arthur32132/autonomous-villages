package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
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
    @Test void anUnreadableFileIsLeftByteForByteWithACopyBesideItAndDefaultsInMemory(@TempDir Path dir) throws Exception {
        Path file=dir.resolve("autonomous_villages.json"),copy=dir.resolve("autonomous_villages.json.broken");
        // A dangling comma used to rewrite the file with every switch the owner had turned off switched back on.
        byte[] owner="{\n  \"gifts\": false,\n  \"graves\": false,\n}\n".getBytes(StandardCharsets.UTF_8);
        Files.write(file,owner);
        List<String> warnings=new ArrayList<>();
        var config=ModConfig.load(dir,warnings::add);
        assertTrue(config.gifts&&config.graves&&config.storm);
        assertArrayEquals(owner,Files.readAllBytes(file));
        assertArrayEquals(owner,Files.readAllBytes(copy));
        assertEquals(1,warnings.size());
        String warning=warnings.get(0);
        assertTrue(warning.contains(file.toString())&&warning.contains(copy.toString())&&warning.contains("Expected name at line 4"),warning);
        assertFalse(warning.contains("\n"),warning);
        // The same broken file on the next start reuses its copy; a different one gets the next free name.
        assertTrue(ModConfig.load(dir,warnings::add).gifts);
        assertFalse(Files.exists(dir.resolve("autonomous_villages.json.broken-1")));
        byte[] typo="{\"gifts\": flase}".getBytes(StandardCharsets.UTF_8);
        Files.write(file,typo);
        warnings.clear();
        assertTrue(ModConfig.load(dir,warnings::add).gifts);
        assertArrayEquals(typo,Files.readAllBytes(file));
        assertArrayEquals(owner,Files.readAllBytes(copy));
        assertArrayEquals(typo,Files.readAllBytes(dir.resolve("autonomous_villages.json.broken-1")));
        assertTrue(warnings.get(0).contains("\"gifts\" must be true or false, found \"flase\""),warnings.get(0));
    }
    @Test void everyKindOfUnreadableFileIsKeptAndNeverRewritten(@TempDir Path dir) throws Exception {
        Path file=dir.resolve("autonomous_villages.json");
        List<byte[]> broken=List.of("[\"gifts\"]".getBytes(),"null".getBytes(),"{\"gifts\": false} {}".getBytes(),
                "{\"gifts\": 0}".getBytes(),"{\"gifts\": \"нет\"}".getBytes(StandardCharsets.UTF_8),"{\"gifts\": {}}".getBytes(),
                new byte[]{'{','"','g','"',':',(byte)0xFF,'}'});
        for(byte[] bytes:broken){
            Files.write(file,bytes);
            List<String> warnings=new ArrayList<>();
            assertTrue(ModConfig.load(dir,warnings::add).gifts,new String(bytes,StandardCharsets.UTF_8));
            assertArrayEquals(bytes,Files.readAllBytes(file));
            assertEquals(1,warnings.size());
            assertTrue(warnings.get(0).startsWith("Unreadable "+file),warnings.get(0));
        }
        try(var files=Files.list(dir)){assertEquals(broken.size()+1,files.count(),"one copy each, no temporary files");}
    }
    @Test void aParsedFileIsRewrittenOnlyWhenSomethingChanges(@TempDir Path dir) throws Exception {
        Path file=dir.resolve("autonomous_villages.json");
        List<String> warnings=new ArrayList<>();
        ModConfig.load(dir,warnings::add);
        // Every switch present in the owner's own layout, with a comment: read and left exactly as written.
        String own="// мой сервер\n"+Files.readString(file).replaceAll("\\s*\\n\\s*","").replace("\"gifts\": true","\"gifts\":false");
        Files.writeString(file,own);
        var read=ModConfig.load(dir,warnings::add);
        assertFalse(read.gifts);assertTrue(read.graves);
        assertEquals(own,Files.readString(file));
        assertEquals(List.of(),warnings);
        // A short file gains the missing switches; the owner's values, an unknown key and its order survive.
        Files.writeString(file,"{\"gfts\": false, \"storm\": \"false\", \"gifts\": false}");
        var merged=ModConfig.load(dir,warnings::add);
        assertFalse(merged.gifts||merged.storm);assertTrue(merged.campfire&&merged.chatAnnouncements);
        String text=Files.readString(file);
        assertTrue(text.startsWith("{\n  \"gfts\": false,\n  \"storm\": false,\n  \"gifts\": false,\n  \"celebrations\": true,"),text);
        assertTrue(text.contains("\"livestock\": true"),text);
        assertEquals(1,warnings.size());assertTrue(warnings.get(0).contains("[gfts]"),warnings.get(0));
        ModConfig.load(dir,warnings::add);
        assertEquals(text,Files.readString(file));
        assertFalse(Files.exists(dir.resolve("autonomous_villages.json.broken")));
        assertFalse(Files.exists(dir.resolve("autonomous_villages.json.tmp")));
    }
    @Test void aMissingOrBlankFileIsWrittenWithDefaults(@TempDir Path dir) throws Exception {
        Path config=dir.resolve("config"),file=config.resolve("autonomous_villages.json");
        assertTrue(ModConfig.load(config,w->fail(w)).gifts);
        String defaults=Files.readString(file);
        Files.writeString(file," \n");
        assertTrue(ModConfig.load(config,w->fail(w)).gifts);
        assertEquals(defaults,Files.readString(file));
        try(var files=Files.list(config)){assertEquals(1,files.count());}
    }
    @Test void everyPublicSwitchIsOnByDefaultAndInTheFirstWrittenFile(@TempDir Path dir) throws Exception {
        ModConfig.load(dir,w->fail(w));
        var written=JsonParser.parseString(Files.readString(dir.resolve("autonomous_villages.json"))).getAsJsonObject();
        Set<String> switches=new TreeSet<>();
        for(var f:ModConfig.class.getFields())if(f.getType()==boolean.class&&!Modifier.isStatic(f.getModifiers())){
            switches.add(f.getName());
            assertTrue(f.getBoolean(new ModConfig()),f.getName()+" defaults to on");
            assertTrue(written.has(f.getName())&&written.get(f.getName()).getAsBoolean(),f.getName()+" written on");
        }
        assertEquals(switches,new TreeSet<>(written.keySet()));
        for(var f:Features.ALL)assertTrue(switches.contains(f.key()),f.key());
        assertTrue(switches.containsAll(Set.of("celebrations","chatAnnouncements","landmarkGoods")));
    }
}
