package dev.autonomousvillages;

import com.google.gson.*;
import dev.navigationai.NavigationAiMod;
import dev.navigationai.api.BotCreateOptions;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Headless soak observer for disposable worlds: finds the nearest generated village, claims it,
 * runs a number of game days and writes metrics that are observable in the world itself
 * (villagers, births, deaths, beds, placed blocks, chest stock, TPS). Never shipped to players.
 */
public final class SoakProbe implements ModInitializer {
    static final TicketType AREA=Registry.register(BuiltInRegistries.TICKET_TYPE,Identifier.fromNamespaceAndPath("autonomous_villages_soak","area"),
            new TicketType(TicketType.NO_TIMEOUT,TicketType.FLAG_LOADING|TicketType.FLAG_SIMULATION|TicketType.FLAG_KEEP_DIMENSION_ACTIVE));
    static final Gson GSON=new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeSpecialFloatingPointValues().create();
    static final Gson LINE=new GsonBuilder().disableHtmlEscaping().serializeSpecialFloatingPointValues().create();
    /** Blocks that only appear because somebody built them; their growth is construction progress. */
    static final List<String> BUILT=List.of("planks","cobblestone","doors","lights","chests","crafting_tables","furnaces","fences","stairs","slabs","glass","paths","beds","wool","bells");

    enum Stage { FIND, LOAD, WAIT_VILLAGERS, RUN, DONE }
    static Properties config=new Properties();
    static Stage stage=Stage.FIND;
    static MinecraftServer server;
    static ServerLevel level;
    static BlockPos center;
    static int minX,minZ,maxX,maxZ;
    static long startGame,lastSampleGame,stageTick,startNanos,lastSampleNanos,runNanos,stageNanos;
    static int ticks,lastSeenCount=-1;
    static long stableSince;
    static double seconds(long since){return (System.nanoTime()-since)/1e9;}
    static String claimMessage,claimError;
    static boolean claimed;
    static JsonObject startCensus,lastCensus;
    static int startVillagers,startBeds,peakVillagers,minVillagers=Integer.MAX_VALUE,samples;
    static double tpsSum;
    static int tpsSamples;
    static final Set<UUID> seen=new HashSet<>();
    static final Set<UUID> initial=new HashSet<>();
    static final JsonArray births=new JsonArray(),deaths=new JsonArray();
    static final Map<UUID,Vec3> lastPos=new HashMap<>();
    static final Map<String,Long> idleByJob=new TreeMap<>();
    static Writer timeline;

    @Override public void onInitialize(){
        ServerTickEvents.END_SERVER_TICK.register(SoakProbe::tick);
        ServerLivingEntityEvents.AFTER_DEATH.register(SoakProbe::death);
        ServerEntityEvents.ENTITY_LOAD.register((entity,world)->loaded(entity));
    }
    static int intConfig(String key,int fallback){return Integer.parseInt(config.getProperty(key,Integer.toString(fallback)).trim());}
    static double doubleConfig(String key,double fallback){return Double.parseDouble(config.getProperty(key,Double.toString(fallback)).trim());}
    static boolean flag(String key,boolean fallback){return Boolean.parseBoolean(config.getProperty(key,Boolean.toString(fallback)).trim());}
    static void log(String message){System.out.println("SOAK "+message);System.out.flush();}

    static void tick(MinecraftServer s){
        if(stage==Stage.DONE)return;
        if(server==null){
            server=s;level=s.overworld();startNanos=System.nanoTime();
            try(var in=Files.newBufferedReader(Path.of("soak.properties"),StandardCharsets.UTF_8)){config.load(in);}catch(IOException missing){log("no soak.properties, defaults");}
            // Setup runs at vanilla speed: chunk and entity loading are asynchronous and need real time.
            s.tickRateManager().setTickRate(20);
        }
        ticks++;
        try{
            if((System.nanoTime()-startNanos)/1e9>doubleConfig("maxRealSeconds",3600)){finish(false,"real_time_limit_"+stage.name().toLowerCase(Locale.ROOT));return;}
            switch(stage){
                case FIND->find();
                case LOAD->load();
                case WAIT_VILLAGERS->waitVillagers();
                case RUN->run();
                default->{}
            }
        }catch(Throwable error){
            error.printStackTrace();finish(false,"probe_error: "+error);
        }
    }
    static void find(){
        if(ticks<20)return;
        BlockPos spawn=level.getRespawnData().pos();
        BlockPos found=level.findNearestMapStructure(StructureTags.VILLAGE,spawn,intConfig("searchChunks",96),false);
        if(found==null){finish(false,"no_village_found");return;}
        center=found;stageTick=ticks;stageNanos=System.nanoTime();
        level.getChunkSource().addTicketWithRadius(AREA,new ChunkPos(found.getX()>>4,found.getZ()>>4),intConfig("ticketRadius",9));
        log("village at "+found.toShortString()+" (spawn "+spawn.toShortString()+")");
        stage=Stage.LOAD;
    }
    static void load(){
        if(!level.hasChunkAt(center)){if(seconds(stageNanos)>120)finish(false,"village_chunks_not_loaded");return;}
        int y=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,center.getX(),center.getZ());
        center=new BlockPos(center.getX(),y,center.getZ());
        int r=intConfig("radius",112);minX=center.getX()-r;maxX=center.getX()+r;minZ=center.getZ()-r;maxZ=center.getZ()+r;
        if(flag("observer",true)){
            // A creative, flying observer: natural spawning (monsters included) needs a nearby non-spectator player.
            var bot=NavigationAiMod.runtime(server).service().create(new BotCreateOptions("SoakCam",UUID.nameUUIDFromBytes("SoakCam".getBytes(StandardCharsets.UTF_8)),
                    level,new Vec3(center.getX()+.5,y+intConfig("observerHeight",28),center.getZ()+.5),0,90));
            ServerPlayer player=bot.player();player.setGameMode(GameType.CREATIVE);player.getAbilities().flying=true;player.onUpdateAbilities();player.setNoGravity(true);
        }
        stageTick=ticks;stageNanos=System.nanoTime();stage=Stage.WAIT_VILLAGERS;
    }
    static void waitVillagers(){
        var villagers=villagers();
        // Keep waiting until the count stops growing: entity sections arrive chunk by chunk.
        if(villagers.size()!=lastSeenCount){lastSeenCount=villagers.size();stableSince=System.nanoTime();}
        if((villagers.isEmpty()||seconds(stableSince)<10)&&seconds(stageNanos)<120)return;
        if(villagers.isEmpty()){finish(false,"no_villagers_loaded");return;}
        if(flag("claim",true)){
            Villager anchor=villagers.stream().min(Comparator.comparingDouble(v->v.distanceToSqr(Vec3.atCenterOf(center)))).orElseThrow();
            try{claimMessage=AutonomousVillagesMod.runtime(server).claim(level,anchor.blockPosition(),"Soak",48,new UUID(0,0));claimed=true;}
            catch(Exception error){claimError=Objects.toString(error.getMessage(),error.getClass().getSimpleName());}
            log(claimed?"claimed: "+claimMessage:"claim failed: "+claimError);
            Village village=village();
            if(village!=null&&village.state.bounds!=null){
                var b=village.state.bounds;int margin=16;
                minX=Math.min(minX,b.minX-margin);maxX=Math.max(maxX,b.maxX+margin);minZ=Math.min(minZ,b.minZ-margin);maxZ=Math.max(maxZ,b.maxZ+margin);
            }
        }
        for(var v:villagers()){seen.add(v.getUUID());initial.add(v.getUUID());lastPos.put(v.getUUID(),v.position());}
        startVillagers=initial.size();startBeds=beds(PoiManager.Occupancy.ANY);
        startCensus=census();lastCensus=startCensus;
        writeMap("map-start.json");
        startGame=level.getGameTime();lastSampleGame=startGame;runNanos=System.nanoTime();lastSampleNanos=runNanos;
        try{timeline=Files.newBufferedWriter(Path.of(config.getProperty("timeline","soak-timeline.jsonl")),StandardCharsets.UTF_8);}catch(IOException error){throw new UncheckedIOException(error);}
        stage=Stage.RUN;server.tickRateManager().setTickRate((float)doubleConfig("tickRate",400));
        log("run started: villagers="+startVillagers+" beds="+startBeds+" area="+minX+","+minZ+".."+maxX+","+maxZ);
    }
    static void run(){
        long elapsed=level.getGameTime()-startGame;
        if(elapsed>0&&elapsed%intConfig("sampleTicks",1200)==0)sample();
        if(elapsed>0&&elapsed%intConfig("censusTicks",6000)==0)lastCensus=census();
        // Frames for a timelapse of the village (tools/soak/map.py --gif), twice a game day.
        if(elapsed>0&&elapsed%intConfig("mapTicks",12000)==0)writeMap(String.format("map-t%06d.json",elapsed));
        if(elapsed>=(long)(doubleConfig("days",3)*24000))finish(true,"completed");
    }

    static AABB area(){return new AABB(minX,level.getMinY(),minZ,maxX+1,level.getMaxY(),maxZ+1);}
    static boolean inArea(BlockPos p){return p.getX()>=minX&&p.getX()<=maxX&&p.getZ()>=minZ&&p.getZ()<=maxZ;}
    static List<Villager> villagers(){return level.getEntitiesOfClass(Villager.class,area(),Villager::isAlive);}
    static int beds(PoiManager.Occupancy occupancy){
        int r=Math.max(maxX-minX,maxZ-minZ)/2+1;
        return (int)level.getPoiManager().getInSquare(h->h.is(PoiTypes.HOME),new BlockPos((minX+maxX)/2,center.getY(),(minZ+maxZ)/2),r,occupancy)
                .filter(p->inArea(p.getPos())).count();
    }
    static Village village(){
        try{return AutonomousVillagesMod.runtime(server).villages.get("soak");}catch(RuntimeException missing){return null;}
    }

    static void loaded(Entity entity){
        if(stage!=Stage.RUN||!(entity instanceof Villager v)||!inArea(v.blockPosition())||!seen.add(v.getUUID()))return;
        // A newborn starts at age -24000; an older baby that merely loaded late is not a birth.
        if(v.isBaby()&&v.getAge()<=-23000){
            var birth=new JsonObject();birth.addProperty("tick",level.getGameTime()-startGame);birth.addProperty("pos",v.blockPosition().toShortString());births.add(birth);
            log("birth at "+v.blockPosition().toShortString());
        }
    }
    static void death(LivingEntity entity,DamageSource source){
        if(stage!=Stage.RUN||!(entity instanceof Villager v)||!(inArea(v.blockPosition())||seen.contains(v.getUUID())))return;
        var death=new JsonObject();death.addProperty("tick",level.getGameTime()-startGame);death.addProperty("cause",source.getMsgId());
        death.addProperty("attacker",source.getEntity()==null?null:BuiltInRegistries.ENTITY_TYPE.getKey(source.getEntity().getType()).toString());
        death.addProperty("pos",v.blockPosition().toShortString());death.addProperty("initial",initial.contains(v.getUUID()));
        death.addProperty("night",level.isDarkOutside());deaths.add(death);
        log("death "+death);
    }

    static void sample(){
        long now=level.getGameTime(),elapsed=now-startGame;long nanos=System.nanoTime();
        double tps=(now-lastSampleGame)/Math.max(1e-9,(nanos-lastSampleNanos)/1e9);lastSampleGame=now;lastSampleNanos=nanos;tpsSum+=tps;tpsSamples++;
        var villagers=villagers();int adults=0,babies=0,sleeping=0,idle=0;
        var jobs=jobs();
        for(var v:villagers){
            if(v.isBaby())babies++;else adults++;
            if(v.isSleeping())sleeping++;
            Vec3 before=lastPos.put(v.getUUID(),v.position());
            if(before!=null&&!v.isBaby()&&!v.isSleeping()&&before.distanceToSqr(v.position())<2.25){
                idle++;idleByJob.merge(jobs.getOrDefault(v.getUUID(),"unmanaged"),1L,Long::sum);
            }
            seen.add(v.getUUID());
        }
        peakVillagers=Math.max(peakVillagers,villagers.size());minVillagers=Math.min(minVillagers,villagers.size());samples++;
        var line=new JsonObject();
        line.addProperty("tick",elapsed);line.addProperty("day",Math.round(elapsed/240.0)/100.0);line.addProperty("night",level.isDarkOutside());
        line.addProperty("tps",Math.round(tps*10)/10.0);line.addProperty("mspt",Math.round(server.getAverageTickTimeNanos()/1e4)/100.0);
        line.addProperty("villagers",villagers.size());line.addProperty("adults",adults);line.addProperty("babies",babies);
        line.addProperty("sleeping",sleeping);line.addProperty("idle",idle);
        line.addProperty("beds",beds(PoiManager.Occupancy.ANY));line.addProperty("births",births.size());line.addProperty("deaths",deaths.size());
        line.add("mod",modView());
        var m=dev.navigationai.NavigationAiMod.runtime(server).metrics();
        var nav=new JsonObject();
        nav.addProperty("snapshots",m.snapshots());nav.addProperty("snapshotMs",Math.round(m.averageSnapshotMillis()*100)/100.0);
        nav.addProperty("searches",m.pathSearchesCompleted());nav.addProperty("searchMs",Math.round(m.averagePathSearchMillis()*100)/100.0);
        nav.addProperty("submitted",m.pathSearchesSubmitted());nav.addProperty("rejected",m.pathSearchesRejected());nav.addProperty("cancelled",m.pathSearchesCancelled());
        nav.addProperty("backpressure",m.backpressureDeferrals());nav.addProperty("nodeBudget",m.nodeBudgetDeferrals());nav.addProperty("replans",m.replans());
        nav.addProperty("queued",m.queuedSearches());nav.addProperty("active",m.activeSearches());nav.addProperty("botTickMs",Math.round(m.averageTickMillis()*100)/100.0);
        line.add("nav",nav);
        try{timeline.write(LINE.toJson(line));timeline.write('\n');timeline.flush();}catch(IOException error){throw new UncheckedIOException(error);}
        log("day="+line.get("day")+" tps="+line.get("tps")+" villagers="+villagers.size()+" beds="+line.get("beds")+" births="+births.size()+" deaths="+deaths.size()+" idle="+idle);
    }
    /** Implementation-specific view of the mod. Kept in one place so a rewrite only touches this method. */
    static Map<UUID,String> jobs(){
        var out=new HashMap<UUID,String>();var village=village();if(village==null)return out;
        for(var w:village.workers.values())out.put(w.id,w.job==null?"none":w.job.kind());
        return out;
    }
    static JsonObject modView(){
        var out=new JsonObject();var village=village();
        if(village==null){out.addProperty("claimed",false);return out;}
        out.addProperty("stage",village.town.stage);out.addProperty("problem",village.problem);
        out.addProperty("residents",village.state.residents.size());out.addProperty("workers",village.workers.size());
        var kinds=new TreeMap<String,Integer>();var statuses=new TreeMap<String,Integer>();var failures=new TreeMap<String,Integer>();
        for(var w:village.workers.values()){
            kinds.merge(w.job==null?"none":w.job.kind(),1,Integer::sum);statuses.merge(w.status,1,Integer::sum);
            if(!w.lastFailure.isEmpty())failures.merge(w.lastFailure,1,Integer::sum);
        }
        out.add("jobs",GSON.toJsonTree(kinds));out.add("statuses",GSON.toJsonTree(statuses));out.add("lastFailures",GSON.toJsonTree(failures));
        out.add("failures",GSON.toJsonTree(village.failures));
        int buildings=0,completed=0,planned=0,built=0;
        for(var b:village.state.buildings){if(b.kind.equals("native_house"))continue;buildings++;if(b.completed)completed++;planned+=village.town.size(b);built+=village.town.built(b);}
        out.addProperty("projects",buildings);out.addProperty("projectsCompleted",completed);out.addProperty("projectBlocks",built+"/"+planned);
        var s=village.planner.snapshot();
        var stock=new JsonObject();
        stock.addProperty("planks",s.planks());stock.addProperty("logs",s.logs());stock.addProperty("cobble",s.cobble());stock.addProperty("bread",s.bread());
        stock.addProperty("wheat",s.wheat());stock.addProperty("wool",s.wool());stock.addProperty("iron",s.iron());stock.addProperty("torches",s.torches());
        stock.addProperty("chests",s.chests());stock.addProperty("freeSlots",s.freeSlots());stock.addProperty("table",s.table()!=null);stock.addProperty("furnace",s.furnace()!=null);
        out.add("stock",stock);out.add("missing",GSON.toJsonTree(s.missing()));
        // What fills the store: the fifteen most plentiful items in the village's shared chests.
        var items=new TreeMap<String,Integer>();
        for(var c:village.stock.chests())for(int i=0;i<c.getContainerSize();i++){var it=c.getItem(i);if(!it.isEmpty())items.merge(BuiltInRegistries.ITEM.getKey(it.getItem()).getPath(),it.getCount(),Integer::sum);}
        var top=new JsonObject();
        items.entrySet().stream().sorted(Map.Entry.<String,Integer>comparingByValue().reversed()).limit(15).forEach(e->top.addProperty(e.getKey(),e.getValue()));
        out.add("storeItems",top);
        out.addProperty("growth",village.breeding.blocker());
        var people=new com.google.gson.JsonArray();
        for(var w:village.workers.values()){
            var b=w.bot();if(b==null)continue;
            var row=new JsonObject();
            row.addProperty("name",village.presentation.personName(w.id));row.addProperty("role",village.role(w.id));
            row.addProperty("job",w.job==null?"none":w.job.kind());row.addProperty("age",w.job==null?0:level.getGameTime()-w.job.started);
            row.addProperty("status",w.status);row.addProperty("nav",String.valueOf(b.navigator().status()));
            row.addProperty("pos",b.entity().blockPosition().toShortString());
            if(w.job!=null&&w.job.target()!=null)row.addProperty("target",w.job.target().toShortString());
            people.add(row);
        }
        out.add("workers",people);
        out.add("chronicle",GSON.toJsonTree(village.state.chronicle.totals));
        return out;
    }

    static String category(BlockState s){
        if(s.is(BlockTags.LOGS))return "logs";
        if(s.is(BlockTags.PLANKS))return "planks";
        if(s.is(Blocks.COBBLESTONE)||s.is(Blocks.MOSSY_COBBLESTONE))return "cobblestone";
        if(s.is(BlockTags.BEDS))return s.getValue(BedBlock.PART)==BedPart.FOOT?"beds":null;
        if(s.is(BlockTags.WOODEN_DOORS))return s.getValue(DoorBlock.HALF)==DoubleBlockHalf.LOWER?"doors":null;
        if(s.is(Blocks.TORCH)||s.is(Blocks.WALL_TORCH)||s.is(Blocks.LANTERN))return "lights";
        if(s.is(Blocks.CHEST))return "chests";
        if(s.is(Blocks.CRAFTING_TABLE))return "crafting_tables";
        if(s.is(Blocks.FURNACE))return "furnaces";
        if(s.is(Blocks.FARMLAND))return "farmland";
        if(s.is(BlockTags.CROPS))return "crops";
        if(s.is(BlockTags.FENCES))return "fences";
        if(s.is(BlockTags.STAIRS))return "stairs";
        if(s.is(BlockTags.SLABS))return "slabs";
        if(s.getBlock() instanceof SaplingBlock)return "saplings";
        if(s.is(Blocks.DIRT_PATH))return "paths";
        if(s.is(Blocks.GLASS)||s.is(Blocks.GLASS_PANE))return "glass";
        if(s.is(BlockTags.WOOL))return "wool";
        if(s.is(Blocks.BELL))return "bells";
        return null;
    }
    static String itemCategory(ItemStack s){
        if(s.is(ItemTags.LOGS))return "logs";
        if(s.is(ItemTags.PLANKS))return "planks";
        if(s.is(Items.STICK))return "sticks";
        if(s.is(Items.COBBLESTONE)||s.is(Items.COBBLED_DEEPSLATE))return "cobblestone";
        if(s.is(Items.WHEAT))return "wheat";
        if(s.is(Items.BREAD))return "bread";
        if(s.is(Items.CARROT)||s.is(Items.POTATO)||s.is(Items.BEETROOT))return "vegetables";
        if(s.is(Items.WHEAT_SEEDS)||s.is(Items.BEETROOT_SEEDS))return "seeds";
        if(s.is(ItemTags.WOOL))return "wool";
        if(s.is(ItemTags.BEDS))return "beds";
        if(s.is(Items.IRON_INGOT))return "iron";
        if(s.is(Items.RAW_IRON))return "raw_iron";
        if(s.is(Items.COAL)||s.is(Items.CHARCOAL))return "coal";
        if(s.is(ItemTags.SAPLINGS))return "saplings";
        if(s.is(ItemTags.PICKAXES)||s.is(ItemTags.AXES)||s.is(ItemTags.HOES)||s.is(ItemTags.SWORDS)||s.is(Items.SHEARS))return "tools";
        return "other";
    }
    /** Surface blocks by category plus the content of every chest in the area. */
    /** Runs "/avillage <args>" with server permissions and returns what it printed. */
    static String command(String args){
        var out=new StringBuilder();
        var sink=new net.minecraft.commands.CommandSource(){
            public void sendSystemMessage(net.minecraft.network.chat.Component message){out.append(message.getString()).append('\n');}
            public boolean acceptsSuccess(){return true;}
            public boolean acceptsFailure(){return true;}
            public boolean shouldInformAdmins(){return false;}
        };
        try{server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSource(sink),"avillage "+args);}
        catch(RuntimeException error){out.append("EXCEPTION ").append(error);}
        return out.toString();
    }
    /** Top block of every column in the area (palette + indices + heights) and villager positions, for map.py. */
    static void writeMap(String file){
        try{
            var palette=new LinkedHashMap<String,Integer>();int w=maxX-minX+1,d=maxZ-minZ+1;
            var cells=new StringBuilder();var heights=new StringBuilder();
            for(int z=minZ;z<=maxZ;z++)for(int x=minX;x<=maxX;x++){
                int y=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,x,z)-1;
                var state=level.getBlockState(new BlockPos(x,y,z));
                String id=BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
                int index=palette.computeIfAbsent(id,k->palette.size());
                cells.append(index).append(',');heights.append(y).append(',');
            }
            var out=new JsonObject();out.addProperty("minX",minX);out.addProperty("minZ",minZ);out.addProperty("width",w);out.addProperty("depth",d);
            out.add("palette",GSON.toJsonTree(palette.keySet()));out.addProperty("cells",cells.toString());out.addProperty("heights",heights.toString());
            var people=new com.google.gson.JsonArray();
            for(var v:villagers()){var row=new com.google.gson.JsonArray();row.add(v.getX());row.add(v.getZ());row.add(v.isBaby());people.add(row);}
            out.add("villagers",people);
            var village=village();
            if(village!=null){
                var bell=village.meetingPoint();var hub=new com.google.gson.JsonArray();hub.add(bell.getX());hub.add(bell.getZ());out.add("bell",hub);
                var projects=new com.google.gson.JsonArray();
                for(var b:village.state.buildings)if(!b.kind.equals("native_house")){var o=BlockPos.of(b.origin);var row=new com.google.gson.JsonArray();row.add(o.getX());row.add(o.getZ());row.add(b.kind+" "+b.design);row.add(b.completed);projects.add(row);}
                out.add("projects",projects);
            }
            Files.writeString(Path.of(file),GSON.toJson(out),StandardCharsets.UTF_8);
        }catch(IOException|RuntimeException error){log("map failed: "+error);}
    }
    static JsonObject census(){
        long started=System.nanoTime();
        var blocks=new TreeMap<String,Integer>();var stock=new TreeMap<String,Integer>();int columns=0;
        var cursor=new BlockPos.MutableBlockPos();
        for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++){
            if(!level.hasChunkAt(cursor.set(x,center.getY(),z)))continue;columns++;
            int top=level.getHeight(Heightmap.Types.WORLD_SURFACE,x,z);
            for(int y=Math.max(level.getMinY(),top-24);y<=top;y++){
                String category=category(level.getBlockState(cursor.set(x,y,z)));
                if(category!=null)blocks.merge(category,1,Integer::sum);
            }
        }
        for(int cx=minX>>4;cx<=maxX>>4;cx++)for(int cz=minZ>>4;cz<=maxZ>>4;cz++){
            var chunk=level.getChunkSource().getChunkNow(cx,cz);if(chunk==null)continue;
            for(var entity:chunk.getBlockEntities().values())if(entity instanceof ChestBlockEntity chest&&inArea(chest.getBlockPos())){
                stock.merge("chests",1,Integer::sum);
                for(int i=0;i<chest.getContainerSize();i++){var item=chest.getItem(i);if(!item.isEmpty())stock.merge(itemCategory(item),item.getCount(),Integer::sum);}
            }
        }
        var out=new JsonObject();out.addProperty("tick",level.getGameTime()-startGame);out.addProperty("columns",columns);
        out.add("blocks",GSON.toJsonTree(blocks));out.add("chestStock",GSON.toJsonTree(stock));
        out.addProperty("millis",(System.nanoTime()-started)/1_000_000);
        return out;
    }
    static JsonObject delta(JsonObject start,JsonObject end,String key){
        var out=new JsonObject();var a=start.getAsJsonObject(key);var b=end.getAsJsonObject(key);var keys=new TreeSet<String>();keys.addAll(a.keySet());keys.addAll(b.keySet());
        for(var k:keys){int d=(b.has(k)?b.get(k).getAsInt():0)-(a.has(k)?a.get(k).getAsInt():0);if(d!=0)out.addProperty(k,d);}
        return out;
    }

    static void finish(boolean completed,String reason){
        if(stage==Stage.DONE)return;
        var summary=new JsonObject();
        summary.addProperty("completed",completed);summary.addProperty("reason",reason);
        summary.addProperty("seed",config.getProperty("seed","?"));summary.addProperty("difficulty",config.getProperty("difficulty","?"));
        summary.addProperty("label",config.getProperty("label",""));summary.addProperty("days",doubleConfig("days",3));
        summary.addProperty("requestedTps",doubleConfig("tickRate",400));
        summary.addProperty("realSeconds",Math.round((System.nanoTime()-startNanos)/1e7)/100.0);
        if(center!=null)summary.addProperty("village",center.toShortString());
        summary.addProperty("claimed",claimed);if(claimError!=null)summary.addProperty("claimError",claimError);
        try{
            if(stage==Stage.RUN){
                long elapsed=level.getGameTime()-startGame;
                summary.addProperty("gameTicks",elapsed);summary.addProperty("gameDays",Math.round(elapsed/240.0)/100.0);
                summary.addProperty("avgTps",tpsSamples==0?0:Math.round(tpsSum/tpsSamples*10)/10.0);
                summary.addProperty("runRealSeconds",Math.round((System.nanoTime()-runNanos)/1e7)/100.0);
                var endCensus=census();var villagers=villagers();
                writeMap("map-end.json");
                var start=new JsonObject();start.addProperty("villagers",startVillagers);start.addProperty("beds",startBeds);start.add("census",startCensus);
                var end=new JsonObject();end.addProperty("villagers",villagers.size());end.addProperty("beds",beds(PoiManager.Occupancy.ANY));
                end.addProperty("occupiedBeds",beds(PoiManager.Occupancy.IS_OCCUPIED));end.add("census",endCensus);
                long survivors=villagers.stream().filter(v->initial.contains(v.getUUID())).count();
                end.addProperty("initialSurvivors",survivors);
                summary.add("start",start);summary.add("end",end);
                var blocks=delta(startCensus,endCensus,"blocks");var stock=delta(startCensus,endCensus,"chestStock");
                int built=0;for(var k:BUILT)if(blocks.has(k))built+=Math.max(0,blocks.get(k).getAsInt());
                var outcome=new JsonObject();
                outcome.addProperty("births",births.size());outcome.addProperty("deaths",deaths.size());
                outcome.addProperty("netVillagers",villagers.size()-startVillagers);
                outcome.addProperty("newBeds",end.get("beds").getAsInt()-startBeds);
                outcome.addProperty("builtBlocks",built);
                outcome.addProperty("logsChange",blocks.has("logs")?blocks.get("logs").getAsInt():0);
                outcome.addProperty("farmlandChange",blocks.has("farmland")?blocks.get("farmland").getAsInt():0);
                outcome.addProperty("peakVillagers",peakVillagers);outcome.addProperty("minVillagers",minVillagers==Integer.MAX_VALUE?villagers.size():minVillagers);
                long idleSamples=idleByJob.values().stream().mapToLong(Long::longValue).sum();
                outcome.addProperty("idleSamples",idleSamples);
                // One comparable number; the formula is documented in tools/soak/README.md.
                double score=10*Math.max(0,end.get("beds").getAsInt()-startBeds)+20*births.size()+built/10.0-25*deaths.size();
                outcome.addProperty("score",Math.round(score*10)/10.0);
                summary.add("outcome",outcome);summary.add("blocksDelta",blocks);summary.add("chestStockDelta",stock);
                summary.add("idleByJob",GSON.toJsonTree(idleByJob));
                summary.add("births",births);summary.add("deaths",deaths);
                summary.add("modFinal",modView());
                try{summary.addProperty("modStatus",AutonomousVillagesMod.runtime(server).status(null));}catch(RuntimeException ignored){}
                // Player commands as an operator would type them; the last one releases the village.
                var commands=new JsonObject();
                for(String command:List.of("status Soak","plan Soak","chronicle Soak","chronicle Soak 2","release Soak","status"))commands.addProperty(command,command(command));
                summary.add("commands",commands);
            }
        }catch(Throwable error){summary.addProperty("summaryError",error.toString());}
        stage=Stage.DONE;
        try{
            if(timeline!=null)timeline.close();
            Files.writeString(Path.of(config.getProperty("out","soak-result.json")),GSON.toJson(summary),StandardCharsets.UTF_8);
        }catch(IOException error){error.printStackTrace();}
        log("finished: "+reason);
        server.halt(false);
    }
}
