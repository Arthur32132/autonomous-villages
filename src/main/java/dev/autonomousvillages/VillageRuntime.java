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
            VillagePresentation.restoreName(resident,pendingReleases.remove(id));save();
        }else if(villages.values().stream().anyMatch(v->!v.state.active&&v.state.residents.contains(id)))
            bots.find(id).filter(b->b.state()==BotState.ACTIVE).ifPresent(BotHandle::stopAll);
    }
    boolean mayBreak(UUID id,BlockPos pos){
        Village owner=villages.values().stream().filter(v->v.state.residents.contains(id)).findFirst().orElse(null);
        if(owner==null)return true;
        var worker=owner.workers.get(id);
        String activity=worker==null?"mine":Resident.miningActivity(worker.job);
        if(!owner.state.policy.allows(activity,pos)||owner.state.policy.isPrivate(owner,pos))return false;
        boolean explicit=worker!=null&&("renovate".equals(worker.job)||"harvest".equals(worker.job)||"grade".equals(worker.job)||"dig_irrigation".equals(worker.job))&&pos.equals(worker.target);
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
        var residents = level.getEntitiesOfClass(Villager.class, VillageDiscovery.area(state,0),
                v -> v.isAlive() && state.contains(v.blockPosition()));
        if (residents.isEmpty()) throw new IllegalArgumentException("У кроватей нет живых жителей.");
        if(residents.size()>1024)throw new IllegalArgumentException("Слишком много жителей (максимум 1024).");
        for (Village other : villages.values()) if (state.overlaps(other.state)) throw new IllegalArgumentException("Территория пересекает " + other.state.name);
        for (Villager v : residents) if (bots.find(v.getUUID()).isPresent()) throw new IllegalArgumentException("Один из жителей уже управляется библиотекой.");
        residents.forEach(v -> state.residents.add(v.getUUID()));
        Village village = new Village(this, state); villages.put(name.toLowerCase(Locale.ROOT), village); village.ticket(true);village.town.capture(survey);save();
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
                var deferred=v.state.residents.stream().filter(id->bots.find(id).isEmpty()).toList();
                if(pendingReleases.size()+deferred.size()>16384)throw new IllegalStateException("Сначала загрузите жителей ранее освобождённых деревень (очередь 16384).");
                for(UUID id:deferred)pendingReleases.put(id,v.state.residentNames.getOrDefault(id,new VillageState.ResidentName()));
                v.state.active = false; v.stop(true); villages.remove(name.toLowerCase(Locale.ROOT));
            }
            default -> throw new IllegalArgumentException("Неизвестное действие");
        }
        save(); return name + ": " + switch (action) { case "pause" -> "приостановлена"; case "resume" -> "работает"; default -> "освобождена, жители продолжают обычную жизнь"; };
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
        v.state.storage.add(p.asLong()); save(); return "Общий склад " + name + ": " + p.toShortString();
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
                if (ticks % 200 == 0) AutonomousVillagesMod.LOG.error("Village {}", village.state.name, error); }}
        villageRotation++;
        var error=writer.takeFailure();if(error!=null){persistenceWriteFailed=true;AutonomousVillagesMod.LOG.error("Cannot save periodic villages snapshot",error);}
        if (ticks % 1200 == 0) writeSave(true);
    }
    public void save() {
        writeSave(false);
    }
    private void writeSave(boolean periodic) {
        if (loadFailed) return;
        try {
            villages.values().forEach(v->{v.rememberResidents();v.state.compactMetadata();v.state.validate();});
            Save data = new Save(); data.villages = villages.values().stream().map(v -> v.state).toList();data.pendingReleases.putAll(pendingReleases);
            String json=GSON.toJson(data);
            if(periodic)writer.periodic(json);else{writer.immediate(json);persistenceWriteFailed=false;}
        } catch (Exception error) { persistenceWriteFailed=true;AutonomousVillagesMod.LOG.error("Cannot save villages", error); }
    }
    private void load() {
        if (!Files.exists(file)) return;
        try {
            Save data = GSON.fromJson(Files.readString(file), Save.class);
            if (data == null || data.version<1||data.version>3 || data.villages == null || data.villages.size() > 4
                    ||data.pendingReleases==null||data.pendingReleases.size()>16384||data.pendingReleases.entrySet().stream().anyMatch(e->e.getKey()==null||e.getValue()==null||e.getValue().json==null)) throw new IllegalArgumentException("Invalid village save");
            Map<String, Village> restored = new LinkedHashMap<>(); Set<UUID> people = new HashSet<>();
            for (VillageState state : data.villages) {
                state.compactMetadata();
                state.recoverProjectBounds();
                if(state.migrateLegacyMineReference())AutonomousVillagesMod.LOG.warn("Recovered legacy local mine outside footprint for {}; existing excavation retained as an expedition worksite",state.name);
                state.validate(); String key = state.name.toLowerCase(Locale.ROOT);
                if (restored.containsKey(key) || restored.values().stream().anyMatch(v -> state.overlaps(v.state))) throw new IllegalArgumentException("Duplicate/overlapping village");
                for (UUID id : state.residents) if (id == null || !people.add(id)) throw new IllegalArgumentException("Duplicate resident");
                restored.put(key, new Village(this, state));
            }
            pendingReleases.putAll(data.pendingReleases);
            villages.putAll(restored); villages.values().stream().filter(v -> v.state.active).toList().forEach(v -> v.ticket(true));
        } catch (Exception error) { loadFailed = true; AutonomousVillagesMod.LOG.error("Cannot load villages; preserving original file", error); }
    }
    public void close() {
        save();
        writer.close();
        // Do not detach on shutdown: library may already be closed and it owns backpack persistence.
        for (Village v : villages.values()) {v.presentation.close();v.ticket(false);}
    }
    static final class Save { int version = 3; List<VillageState> villages;Map<UUID,VillageState.ResidentName> pendingReleases=new LinkedHashMap<>(); }
}
