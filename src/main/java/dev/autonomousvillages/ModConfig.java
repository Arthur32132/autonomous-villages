package dev.autonomousvillages;

import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Switches for the parts of village life a server owner may not want: config/autonomous_villages.json,
 * written with every switch on at first start. Core work (gathering, building, farming, defending)
 * is not configurable; these are the extras around it.
 */
public final class ModConfig {
    /** Fireworks over a finished house and hearts for a newborn. */
    public boolean celebrations=true;
    /** A village of five or more under attack calls an iron golem (at most once a day). */
    public boolean summonGolems=true;
    /** Residents bring a visiting player a loaf from a well-stocked store, once a day. */
    public boolean gifts=true;
    /** The chronicle as a written book on the librarian's lectern. */
    public boolean chronicleBook=true;
    /** Vanilla professions bring home a daily product (wool, bread, fish, iron...). */
    public boolean professionProducts=true;
    /** Spare saplings into the composter, bone meal onto young crops. */
    public boolean compost=true;
    /** A housed village builds a watchtower by the bell. */
    public boolean watchtower=true;
    /** A torch in every dark bedroom, so that zombies do not spawn beside the sleepers. */
    public boolean lightHomes=true;
    /** At sunrise, players near the village hear what it did since the last morning. */
    public boolean morningNews=true;
    /** While every bed is taken, a single bed in a home gets a second one beside it. */
    public boolean spareBeds=true;
    /** A sign with the name of each resident lost, at a cemetery on the edge of the village. */
    public boolean graves=true;
    /** A sign beside each home's door naming who lives there. */
    public boolean nameplates=true;
    /** Flowers from the store planted along the walls of the homes. */
    public boolean flowers=true;
    /** The village bell rings three strokes at sunrise and at dusk (sound only). */
    public boolean dayBells=true;
    /** After homes and the watchtower the village builds the workplaces of its style: library, temple, smiths... */
    public boolean civicBuildings=true;
    /** A timber barn by the bell with the store's chests inside. */
    public boolean storehouse=true;
    /** A fenced sheep pen with a gate; the guild brings the first two sheep. */
    public boolean pen=true;
    /** Spare iron becomes lanterns that replace the torches on the lamp posts. */
    public boolean lanterns=true;
    /** Once the footpaths are done, the ground around the bell is trodden into a small square. */
    public boolean plaza=true;
    /** Particles and sounds only: smoke over lived-in homes in the evening, fireflies at night, residents greeting a player and frowning at a failed task. */
    public boolean ambience=true;
    /** A book and quill handed to a resident (sneaking) becomes the village ledger, which is current whenever opened. */
    public boolean ledger=true;
    /** A stack handed to a resident (sneaking) goes to the store; the village remembers it in trades and honours a generous donor. */
    public boolean donations=true;
    /** After the watchtower the village builds its own forge, market square and tavern as it grows. */
    public boolean landmarks=true;
    /** Once a day the forge mends tools, the market sets out surplus, the mill bakes, the tavern cooks and brews mead, the pier brings fish. */
    public boolean landmarkGoods=true;
    /** A fair every seventh day once there is a market (bell, music, a wandering trader); a feast at the tavern when a building is finished; a lullaby for a birth, a lantern glow on the night watch, a bell stroke for news. */
    public boolean lifeEvents=true;
    /** The material the build lacks most is posted as a request to players on the notice board; filling it is rewarded from the store. */
    public boolean requests=true;

    static ModConfig load(Path dir){
        Path file=dir.resolve("autonomous_villages.json");
        var gson=new GsonBuilder().setPrettyPrinting().create();
        ModConfig config=null;
        try{if(Files.exists(file))config=gson.fromJson(Files.readString(file,StandardCharsets.UTF_8),ModConfig.class);}
        catch(IOException|RuntimeException broken){AutonomousVillagesMod.LOG.warn("Unreadable {}, using defaults: {}",file,broken.toString());}
        if(config==null)config=new ModConfig();
        // Rewritten every start, so switches added by an update appear in the file with their defaults.
        try{Files.createDirectories(dir);Files.writeString(file,gson.toJson(config),StandardCharsets.UTF_8);}
        catch(IOException unwritable){AutonomousVillagesMod.LOG.warn("Cannot write {}: {}",file,unwritable.toString());}
        return config;
    }
}
