package dev.autonomousvillages;

import com.google.gson.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import java.util.function.Consumer;

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
    /** After the watchtower the village fences itself in, with gates on its streets shut at night. */
    public boolean palisade=true;
    /** Once a day the forge mends tools, the market sets out surplus, the mill bakes, the tavern cooks and brews mead, the pier brings fish. */
    public boolean landmarkGoods=true;
    /** A fair every seventh day once there is a market (bell, music, a wandering trader); a feast at the tavern when a building is finished; a lullaby for a birth, a lantern glow on the night watch, a bell stroke for news. */
    public boolean lifeEvents=true;
    /** Once a day a merchant caravan at the bell swaps the store's surplus for the wool, logs and iron the village cannot get itself (with lifeEvents on). */
    public boolean caravans=true;
    /** The material the build lacks most is posted as a request to players on the notice board; filling it is rewarded from the store. */
    public boolean requests=true;
    /** Village news in the chat of players nearby (at most one line per village every 5 seconds); off keeps the chronicle but mutes the chat. */
    public boolean chatAnnouncements=true;

    /** Evenings: a campfire on the square and residents gathering around it before bed. */
    public boolean campfire=true;
    /** An orchard of apple trees in rows at the edge of the village; apples count as meals. */
    public boolean orchard=true;
    /** The village's own coat of arms: banners of its colours on the tower, barn and gates. */
    public boolean heraldry=true;
    /** In a thunderstorm residents go under a roof; a lightning rod crowns the watchtower. */
    public boolean storm=true;
    /** Residents who keep to one craft earn a title (подмастерье, мастер) that the chronicle remembers. */
    public boolean mastery=true;
    /** Farmers keep chickens and cows by the pen: eggs, milk and meat for the table. */
    public boolean livestock=true;
    /** Beehives by the flower beds; honey bottles count as meals. */
    public boolean apiary=true;
    /** A wedding at the bell before a couple's first child: flowers, music and a feast line in the chronicle. */
    public boolean weddings=true;
    /** Snowy villages build snow golems that guard the square at night. */
    public boolean snowGuards=true;
    /** Scarecrows (fence, hay and a carved pumpkin) watch over the fields. */
    public boolean scarecrows=true;

    static final String FILE="autonomous_villages.json";
    private static final Gson GSON=new GsonBuilder().setPrettyPrinting().create();

    static ModConfig load(Path dir){return load(dir,message->AutonomousVillagesMod.LOG.warn(message));}

    /**
     * A missing or blank file is written with defaults. A file that does not parse is never touched: a copy goes beside it
     * (.broken, then .broken-1...) and every switch is on until the owner fixes it. A parsed file keeps the owner's values
     * and keys and is rewritten only when that adds missing switches or turns "false" into false.
     */
    static ModConfig load(Path dir,Consumer<String> warn){
        Path file=dir.resolve(FILE);
        byte[] bytes;
        try{bytes=Files.exists(file)?Files.readAllBytes(file):new byte[0];}
        catch(IOException|RuntimeException unreadable){warn.accept("Cannot read "+file+" ("+unreadable+"); every switch is on and the file is left as is");return new ModConfig();}
        JsonObject owner,normalized;ModConfig config;
        try{owner=parse(bytes);normalized=normalized(owner);config=GSON.fromJson(normalized,ModConfig.class);}
        catch(IOException|RuntimeException broken){
            Path copy=keepCopy(file,bytes);
            warn.accept("Unreadable "+file+": "+reason(broken)+". The file is left as is"+(copy==null?" (no copy could be made)":", copy at "+copy)+"; every switch is on until it is fixed");
            return new ModConfig();
        }
        var known=defaults();
        var unknown=owner.keySet().stream().filter(k->!known.has(k)).toList();
        if(!unknown.isEmpty())warn.accept("Unknown keys in "+file+" are ignored: "+unknown);
        if(!normalized.equals(owner))write(file,GSON.toJson(normalized),warn);
        return config;
    }

    /** The file as a JSON object (comments allowed); blank text is an empty object. */
    static JsonObject parse(byte[] bytes)throws CharacterCodingException{
        String text=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        if(text.startsWith("\uFEFF"))text=text.substring(1);
        if(text.isBlank())return new JsonObject();
        JsonElement tree=JsonParser.parseString(text);
        if(!tree.isJsonObject())throw new JsonParseException("expected one object {...} of switches");
        return tree.getAsJsonObject();
    }

    /** The owner's keys in the owner's order with switches as plain true/false, then each missing switch at its default. */
    static JsonObject normalized(JsonObject owner){
        JsonObject defaults=defaults(),out=new JsonObject();
        for(var e:owner.entrySet()){
            JsonElement d=defaults.get(e.getKey()),v=e.getValue();
            if(d!=null&&d.isJsonPrimitive()&&d.getAsJsonPrimitive().isBoolean())v=new JsonPrimitive(bool(e.getKey(),v));
            out.add(e.getKey(),v);
        }
        for(var e:defaults.entrySet())if(!out.has(e.getKey()))out.add(e.getKey(),e.getValue());
        return out;
    }

    private static JsonObject defaults(){return GSON.toJsonTree(new ModConfig()).getAsJsonObject();}

    private static boolean bool(String key,JsonElement v){
        if(v instanceof JsonPrimitive p&&(p.isBoolean()||p.isString()&&(p.getAsString().equalsIgnoreCase("true")||p.getAsString().equalsIgnoreCase("false"))))return p.getAsBoolean();
        throw new JsonParseException("\""+key+"\" must be true or false, found "+v);
    }

    /** A byte-for-byte copy of an unreadable file beside it; an identical earlier copy is reused, none is overwritten. */
    static Path keepCopy(Path file,byte[] bytes){
        for(int i=0;i<1000;i++){
            Path copy=file.resolveSibling(file.getFileName()+".broken"+(i==0?"":"-"+i));
            try{
                if(!Files.exists(copy,LinkOption.NOFOLLOW_LINKS)){Files.write(copy,bytes,StandardOpenOption.CREATE_NEW);return copy;}
                if(Files.isRegularFile(copy,LinkOption.NOFOLLOW_LINKS)&&Arrays.equals(Files.readAllBytes(copy),bytes))return copy;
            }catch(IOException|RuntimeException failed){return null;}
        }
        return null;
    }

    /** The parser's own words, first line only (Gson appends a troubleshooting link). */
    static String reason(Throwable broken){
        Throwable root=broken;
        while(root.getCause()!=null&&root.getCause()!=root)root=root.getCause();
        String m=root instanceof CharacterCodingException?"not UTF-8 text":root.getMessage();
        if(m==null||m.isBlank())m=root.toString();
        int nl=m.indexOf('\n');
        return nl<0?m:m.substring(0,nl);
    }

    /** Through a temporary file, so a crash mid-write never leaves half a config; a symlinked file is written at its target. */
    private static void write(Path file,String json,Consumer<String> warn){
        try{
            Files.createDirectories(file.getParent());
            Path target=Files.exists(file)?file.toRealPath():file,temp=target.resolveSibling(target.getFileName()+".tmp");
            Files.writeString(temp,json,StandardCharsets.UTF_8);
            try{Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException notAtomic){Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);}
        }catch(IOException|RuntimeException unwritable){warn.accept("Cannot write "+file+": "+unwritable);}
    }
}
