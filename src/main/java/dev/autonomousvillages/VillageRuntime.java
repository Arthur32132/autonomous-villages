package dev.autonomousvillages;

import com.google.gson.*;
import dev.navigationai.NavigationAiMod;
import dev.navigationai.api.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.*;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.*;

public final class VillageRuntime {
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public final MinecraftServer server;
    final BotService bots;
    final Map<String, Village> villages = new LinkedHashMap<>();
    final Map<UUID,VillageState.ResidentName> pendingReleases=new LinkedHashMap<>();
    private final Path file;
    private final FileSnapshotWriter writer;
    private final SaveCoalescer saves=new SaveCoalescer(100);
    private final SaveCoalescer.Partition snapshots=new SaveCoalescer.Partition();
    long residentDecisionRemainingNanos;
    private int villageRotation;
    private boolean persistenceWriteFailed;
    public boolean persistenceWriteFailed(){return persistenceWriteFailed;}
    private boolean loadFailed;
    long ticks;
    private final Map<Long,Integer> chunkLeases=new HashMap<>();
    void lease(net.minecraft.world.level.ChunkPos pos,boolean acquire){
        int before=chunkLeases.getOrDefault(pos.pack(),0),after=acquire?before+1:Math.max(0,before-1);
        if(before==0&&after>0)server.overworld().getChunkSource().addTicketWithRadius(AutonomousVillagesMod.TICKET,pos,2);
        if(before>0&&after==0)server.overworld().getChunkSource().removeTicketWithRadius(AutonomousVillagesMod.TICKET,pos,2);
        if(after==0)chunkLeases.remove(pos.pack());else chunkLeases.put(pos.pack(),after);
    }
    public VillageRuntime(MinecraftServer server) {
        this.server = server; this.bots = NavigationAiMod.runtime(server).service();
        file = server.getWorldPath(LevelResource.ROOT).resolve("autonomous_villages.json");
        writer=new FileSnapshotWriter(file);load();
        for(var entity:server.overworld().getAllEntities())if(entity instanceof Villager resident)residentLoaded(resident);
    }
    void residentLoaded(Villager resident){
        if(loadFailed)return;
        UUID id=resident.getUUID();
        if(pendingReleases.containsKey(id)){
            var handle=bots.find(id).orElse(null);
            if(handle==null&&((dev.navigationai.runtime.VillagerAccess)resident).navigationAiSavedState()!=null)handle=bots.attach(resident);
            if(handle!=null&&handle.state()==BotState.ACTIVE)handle.detach();
            // The entry goes only once the body is back to vanilla; a throw above leaves it for the next load.
            VillagePresentation.restoreName(resident,pendingReleases.get(id));pendingReleases.remove(id);save();
        }else if(villages.values().stream().anyMatch(v->!v.state.active&&v.state.residents.contains(id)))
            bots.find(id).filter(b->b.state()==BotState.ACTIVE).ifPresent(BotHandle::stopAll);
    }
    /** Library mining asks here: a worker only breaks protected or farmland blocks its own job is about. */
    boolean mayBreak(UUID id,BlockPos pos){
        Village owner=villages.values().stream().filter(v->v.state.residents.contains(id)).findFirst().orElse(null);
        if(owner==null)return true;
        var worker=owner.workers.get(id);
        var job=worker==null?null:worker.job;
        String activity=job==null?"mine":job.activity();
        if(!owner.state.policy.allows(activity,pos)||owner.state.policy.isPrivate(owner,pos))return false;
        boolean explicit=job!=null&&job.mayBreak(pos);
        for(var other:villages.values())if(other.town.protects(pos))return other==owner&&explicit;
        var block=server.overworld().getBlockState(pos);
        return !block.is(net.minecraft.world.level.block.Blocks.FARMLAND)&&!(block.getBlock() instanceof net.minecraft.world.level.block.CropBlock)||explicit;
    }
    private void mutable() {
        if (!server.isSameThread()) throw new IllegalStateException("Только поток сервера");
        if (loadFailed) throw new IllegalStateException("Ошибка autonomous_villages.json: исправьте файл; он сохранён без перезаписи.");
    }
    public String claim(ServerLevel level, BlockPos origin, String name, int radius, UUID owner) {
        mutable();
        if (level != server.overworld()) throw new IllegalArgumentException("Деревни поддерживаются в обычном мире.");
        if (villages.size() >= 4) throw new IllegalArgumentException("Одновременно поддерживаются 4 деревни.");
        if (villages.containsKey(name.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Это имя занято.");
        var survey=VillageDiscovery.survey(level,origin);
        VillageState state = new VillageState(name, owner, origin, 48);VillageDiscovery.apply(state,survey);state.validate();
        if(VillageState.footprintChunks(state.bounds)>VillageState.MAX_TICKET_CHUNKS)throw new IllegalArgumentException("Территория превышает бюджет 1024 активных чанков.");
        // Babies and NoAI members of another village have no library handle; load() rejects a resident listed twice.
        var residents = level.getEntitiesOfClass(Villager.class, VillageDiscovery.area(state,0),
                v -> v.isAlive() && state.contains(v.blockPosition()) && villages.values().stream().noneMatch(other -> other.state.residents.contains(v.getUUID())));
        if (residents.isEmpty()) throw new IllegalArgumentException("У кроватей нет живых жителей.");
        if(residents.size()>1024)throw new IllegalArgumentException("Слишком много жителей (максимум 1024).");
        for (Village other : villages.values()) if (state.overlaps(other.state)) throw new IllegalArgumentException("Территория пересекает " + other.state.name);
        for (Villager v : residents) if (bots.find(v.getUUID()).isPresent()) throw new IllegalArgumentException("Один из жителей уже управляется библиотекой.");
        residents.forEach(v -> state.residents.add(v.getUUID()));
        Village village = new Village(this, state); villages.put(name.toLowerCase(Locale.ROOT), village); village.ticket(true);village.town.capture(survey);
        village.chronicle.event("Поселение "+name+" основано: жителей "+residents.size());saveNow();
        return "Поселение " + name + " выбрано по зданиям и рабочим точкам: " + state.center().toShortString() + ", жителей сейчас " + residents.size()
                + ". Автономность включена. /avillage status " + name;
    }
    Village get(String name) {
        Village v = villages.get(name.toLowerCase(Locale.ROOT));
        if (v == null) throw new IllegalArgumentException("Деревня не найдена: " + name); return v;
    }
    public String change(String name, String action) {
        mutable(); Village v = get(name);
        switch (action) {
            case "pause" -> { v.state.active = false; v.stop(false); }
            case "resume" -> { if(v.state.bounds!=null&&VillageState.footprintChunks(v.state.bounds)>VillageState.MAX_TICKET_CHUNKS)throw new IllegalArgumentException("Территория превышает бюджет 1024 активных чанков.");v.state.active = true; v.ticket(true); }
            case "release" -> {
                // A loaded body without a handle (baby, NoAI) is released right here by stop(); deferring it would let a
                // later reload detach the controller of a village that claimed it in the meantime.
                var deferred=v.state.residents.stream().filter(id->bots.find(id).isEmpty()&&!(server.overworld().getEntity(id) instanceof Villager)).toList();
                if(pendingReleases.size()+deferred.size()>16384)throw new IllegalStateException("Сначала загрузите жителей ранее освобождённых деревень (очередь 16384).");
                // Without a recorded original the mod never renamed this body; an empty default would erase a name tag.
                for(UUID id:deferred)pendingReleases.put(id,v.state.residentNames.getOrDefault(id,VillageState.ResidentName.untouched()));
                v.state.active = false; v.stop(true); villages.remove(name.toLowerCase(Locale.ROOT));
            }
            default -> throw new IllegalArgumentException("Неизвестное действие");
        }
        saveNow(); return name + ": " + switch (action) { case "pause" -> "приостановлена"; case "resume" -> "работает"; default -> "освобождена, жители продолжают обычную жизнь"; };
    }
    public String storage(String name, BlockPos p, CommandSourceStack source) {
        mutable(); Village v = get(name);
        if (source.getLevel() != server.overworld() || !v.state.contains(p)) throw new IllegalArgumentException("Сундук вне территории.");
        if (source.getEntity() != null && source.getPosition().distanceToSqr(Vec3.atCenterOf(p)) > 64) throw new IllegalArgumentException("Подойдите к сундуку (до 8 блоков).");
        if (!(server.overworld().getBlockEntity(p) instanceof ChestBlockEntity chest) || chest.isLocked()) throw new IllegalArgumentException("Нужен незапертый сундук.");
        if (v.state.storage.size() >= 16 && !v.state.storage.contains(p.asLong())) throw new IllegalArgumentException("Достигнут лимит 16 сундуков.");
        // Explicit player command is the only way an existing chest becomes shared property.
        v.state.policy.privateChests.remove(p.asLong());
        var chestState=v.level.getBlockState(p);
        if(chestState.getBlock() instanceof net.minecraft.world.level.block.ChestBlock&&chestState.getValue(net.minecraft.world.level.block.ChestBlock.TYPE)!=net.minecraft.world.level.block.state.properties.ChestType.SINGLE)
            v.state.policy.privateChests.remove(p.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(chestState)).asLong());
        v.state.storage.add(p.asLong()); saveNow(); return "Общий склад " + name + ": " + p.toShortString();
    }
    public String status(String name) {
        if (loadFailed) return "Ошибка загрузки autonomous_villages.json; исходный файл сохранён.";
        if (name != null) return get(name).status();
        return villages.isEmpty() ? "Деревни не выбраны. /avillage claim <имя>" : String.join("\n", villages.values().stream().map(Village::status).toList());
    }
    public void tick() {
        ticks++;
        if (ticks <= 5) return; // sync retries bodies that have not loaded; do not leave a night restart unguarded
        residentDecisionRemainingNanos=TickWorkBudget.residentDecisionNanos(server.tickRateManager().nanosecondsPerTick());
        var ordered=List.copyOf(villages.values());
        for (int i=0;i<ordered.size();i++) {Village village=ordered.get(Math.floorMod(i+villageRotation,ordered.size()));if (village.state.active) try { village.tick(); }
            catch (RuntimeException error) { village.problem = "ошибка планирования: " + error.getClass().getSimpleName();
                // Rate-limit per village instead of logging only failures that happen to land on a multiple of 200.
                if (ticks >= village.nextErrorLog) { village.nextErrorLog = ticks + 200; AutonomousVillagesMod.LOG.error("Village {}", village.state.name, error); } }}
        villageRotation++;
        var error=writer.takeFailure();if(error!=null){persistenceWriteFailed=true;AutonomousVillagesMod.LOG.error("Cannot save periodic villages snapshot",error);}
        // Chronicle totals and resident positions change without save(): the slow periodic snapshot still covers them.
        if (ticks % 1200 == 0) {saves.clear();writeSave(true);}
        else if (saves.take(ticks)) writeSave(true);
    }
    /** Marks the state dirty; the next coalesced snapshot (at most one per 100 ticks) is written off the server thread. */
    public void save() {
        saves.dirty();
    }
    /** Claim, release, commands and shutdown write at once, before the player sees the answer. */
    public void saveNow() {
        saves.clear();writeSave(false);
    }
    private void writeSave(boolean periodic) {
        if (loadFailed) return;
        try {
            // Each village is validated on its own: one broken state keeps its last valid snapshot and never blocks the others.
            JsonArray encoded=snapshots.encode(villages.values(),v->v.state.name.toLowerCase(Locale.ROOT),
                    v->{v.rememberResidents();v.state.compactMetadata();v.state.validate();return GSON.toJsonTree(v.state);},
                    (name,invalid)->AutonomousVillagesMod.LOG.error("Village {} has invalid state and is saved from its last valid snapshot",name,invalid));
            Save data = new Save(); data.villages = List.of();data.pendingReleases.putAll(pendingReleases);
            JsonObject root=GSON.toJsonTree(data).getAsJsonObject();root.add("villages",encoded);
            String json=GSON.toJson(root);
            if(periodic)writer.periodic(json);else{writer.immediate(json);persistenceWriteFailed=false;}
        } catch (Exception error) { persistenceWriteFailed=true;AutonomousVillagesMod.LOG.error("Cannot save villages", error); }
    }
    private void load() {
        if (!Files.exists(file)) return;
        try {
            var json=JsonParser.parseString(Files.readString(file));
            int version=json.isJsonObject()&&json.getAsJsonObject().has("version")?json.getAsJsonObject().get("version").getAsInt():0;
            if(version>=1&&version<Save.VERSION){retire(json,version);return;}
            Save data = GSON.fromJson(json, Save.class);
            if (data == null || data.version!=Save.VERSION || data.villages == null || data.villages.size() > 4
                    ||data.pendingReleases==null||data.pendingReleases.size()>16384||data.pendingReleases.entrySet().stream().anyMatch(e->e.getKey()==null||e.getValue()==null||e.getValue().json==null)) throw new IllegalArgumentException("Invalid village save");
            Map<String, Village> restored = new LinkedHashMap<>(); Set<UUID> people = new HashSet<>();
            for (VillageState state : data.villages) {
                state.compactMetadata();
                state.validate(); String key = state.name.toLowerCase(Locale.ROOT);
                if (restored.containsKey(key) || restored.values().stream().anyMatch(v -> state.overlaps(v.state))) throw new IllegalArgumentException("Duplicate/overlapping village");
                for (UUID id : state.residents) if (!people.add(id)) throw new IllegalArgumentException("Duplicate resident");
                restored.put(key, new Village(this, state));
            }
            pendingReleases.putAll(data.pendingReleases);
            villages.putAll(restored); villages.values().stream().filter(v -> v.state.active).toList().forEach(v -> v.ticket(true));
        } catch (Exception error) { loadFailed = true; AutonomousVillagesMod.LOG.error("Cannot load villages; preserving original file", error); }
    }
    /**
     * A save from 0.2.x describes orders and expeditions that no longer exist. It is kept as a backup;
     * its residents are released with their original names, and the player claims the village again.
     */
    /** Every resident of an old save with the name it had before the mod renamed it. */
    static Map<UUID,VillageState.ResidentName> legacyReleases(JsonObject root){
        var names=new LinkedHashMap<UUID,VillageState.ResidentName>();
        if(root.get("pendingReleases") instanceof JsonObject pending)for(var e:pending.entrySet())
            legacyName(e.getKey(),e.getValue()).ifPresent(n->names.put(UUID.fromString(e.getKey()),n));
        if(root.get("villages") instanceof JsonArray villages)for(var village:villages){
            if(!(village instanceof JsonObject state)||!(state.get("residents") instanceof JsonArray residents))continue;
            var recorded=state.get("residentNames") instanceof JsonObject r?r:new JsonObject();
            for(var id:residents){
                if(!id.isJsonPrimitive())continue;
                String key=id.getAsString();
                var name=recorded.has(key)?legacyName(key,recorded.get(key)):legacyName(key,null);
                name.ifPresent(n->names.putIfAbsent(UUID.fromString(key),n));
            }
        }
        while(names.size()>16384)names.remove(names.keySet().iterator().next());
        return names;
    }
    /** One entry of an old save; an unreadable entry is skipped, not the whole file. */
    private static Optional<VillageState.ResidentName> legacyName(String id,JsonElement recorded){
        try{
            UUID.fromString(id);
            if(recorded==null)return Optional.of(VillageState.ResidentName.untouched());
            var name=GSON.fromJson(recorded,VillageState.ResidentName.class);
            return name==null||name.json==null?Optional.empty():Optional.of(name);
        }catch(RuntimeException malformed){return Optional.empty();}
    }
    private void retire(JsonElement json,int version)throws java.io.IOException{
        Path backup=file.resolveSibling("autonomous_villages.v"+version+"-backup.json");
        for(int n=1;Files.exists(backup);n++)backup=file.resolveSibling("autonomous_villages.v"+version+"-backup-"+n+".json");
        Files.copy(file,backup);
        var names=legacyReleases(json.getAsJsonObject());
        pendingReleases.putAll(names);
        AutonomousVillagesMod.LOG.warn("Village save v{} is from an older mod version: backed up to {}; {} residents return to vanilla life. Claim the villages again with /avillage claim.",version,backup.getFileName(),names.size());
        saveNow();
    }
    public void close() {
        saveNow();
        writer.close();
        // Do not detach on shutdown: library may already be closed and it owns backpack persistence.
        // One village failing to close must not keep the next one's tickets or boss bars alive.
        for (Village v : villages.values()) {
            try{v.presentation.close();}catch(RuntimeException error){AutonomousVillagesMod.LOG.error("Village {}: presentation close failed",v.state.name,error);}
            try{v.ticket(false);}catch(RuntimeException error){AutonomousVillagesMod.LOG.error("Village {}: ticket release failed",v.state.name,error);}
        }
    }
    static final class Save { static final int VERSION=4; int version = VERSION; List<VillageState> villages;Map<UUID,VillageState.ResidentName> pendingReleases=new LinkedHashMap<>(); }
}
