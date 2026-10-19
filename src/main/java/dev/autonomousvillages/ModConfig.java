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
