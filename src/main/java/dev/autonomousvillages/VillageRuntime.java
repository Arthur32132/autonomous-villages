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
    private final Path file;
    private boolean loadFailed;
    long ticks;
    public VillageRuntime(MinecraftServer server) {
        this.server = server; this.bots = NavigationAiMod.runtime(server).service();
        file = server.getWorldPath(LevelResource.ROOT).resolve("autonomous_villages.json"); load();
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
        var beds = level.getPoiManager().getInRange(h -> h.is(PoiTypes.HOME), origin, 64, PoiManager.Occupancy.ANY)
                .map(PoiRecord::getPos).filter(p -> level.hasChunkAt(p)).sorted(Comparator.comparingDouble(origin::distSqr)).toList();
        if (beds.isEmpty()) throw new IllegalArgumentException("Рядом нет деревни: нужны жители и кровати (до 64 блоков).");
        BlockPos anchor = beds.getFirst();
        List<BlockPos> cluster = beds.stream().filter(p -> p.distSqr(anchor) <= radius * radius).toList();
        BlockPos center = new BlockPos((int)cluster.stream().mapToInt(BlockPos::getX).average().orElseThrow(),
                (int)cluster.stream().mapToInt(BlockPos::getY).average().orElseThrow(),
                (int)cluster.stream().mapToInt(BlockPos::getZ).average().orElseThrow());
        VillageState state = new VillageState(name, owner, center, radius); state.validate();
        var residents = level.getEntitiesOfClass(Villager.class, new AABB(Vec3.atCenterOf(center), Vec3.atCenterOf(center)).inflate(radius, 48, radius),
                v -> v.isAlive() && state.contains(v.blockPosition()));
        if (residents.isEmpty()) throw new IllegalArgumentException("У кроватей нет живых жителей.");
        if (residents.size() > 64) throw new IllegalArgumentException("В территории больше 64 жителей; выберите меньший радиус.");
        for (Village other : villages.values()) if (state.overlaps(other.state)) throw new IllegalArgumentException("Территория пересекает " + other.state.name);
        for (Villager v : residents) if (bots.find(v.getUUID()).isPresent()) throw new IllegalArgumentException("Один из жителей уже управляется библиотекой.");
        residents.forEach(v -> state.residents.add(v.getUUID()));
        Village village = new Village(this, state); villages.put(name.toLowerCase(Locale.ROOT), village); village.ticket(true); save();
        return "Деревня " + name + " выбрана: " + center.toShortString() + ", радиус " + radius + ", жителей " + residents.size()
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
            case "resume" -> { v.state.active = true; v.ticket(true); }
            case "release" -> { v.state.active = false; v.stop(true); villages.remove(name.toLowerCase(Locale.ROOT)); }
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
        v.state.storage.add(p.asLong()); save(); return "Общий склад " + name + ": " + p.toShortString();
    }
    public String status(String name) {
        if (loadFailed) return "Ошибка загрузки autonomous_villages.json; исходный файл сохранён.";
        if (name != null) return get(name).status();
        return villages.isEmpty() ? "Деревни не выбраны. /avillage claim <имя>" : String.join("\n", villages.values().stream().map(Village::status).toList());
    }
    public void tick() {
        ticks++;
        if (ticks <= 40) return; // library/entity restoration finishes first
        for (Village village : villages.values()) if (village.state.active) try { village.tick(); }
            catch (RuntimeException error) { village.problem = "ошибка планирования: " + error.getClass().getSimpleName();
                if (ticks % 200 == 0) AutonomousVillagesMod.LOG.error("Village {}", village.state.name, error); }
        if (ticks % 1200 == 0) save();
    }
    public void save() {
        if (loadFailed) return;
        try {
            Save data = new Save(); data.villages = villages.values().stream().map(v -> v.state).toList();
            Path temp = file.resolveSibling(file.getFileName() + ".tmp"); Files.writeString(temp, GSON.toJson(data));
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception error) { AutonomousVillagesMod.LOG.error("Cannot save villages", error); }
    }
    private void load() {
        if (!Files.exists(file)) return;
        try {
            Save data = GSON.fromJson(Files.readString(file), Save.class);
            if (data == null || data.version != 1 || data.villages == null || data.villages.size() > 4) throw new IllegalArgumentException("Invalid village save");
            Map<String, Village> restored = new LinkedHashMap<>(); Set<UUID> people = new HashSet<>();
            for (VillageState state : data.villages) {
                state.validate(); String key = state.name.toLowerCase(Locale.ROOT);
                if (restored.containsKey(key) || restored.values().stream().anyMatch(v -> state.overlaps(v.state))) throw new IllegalArgumentException("Duplicate/overlapping village");
                for (UUID id : state.residents) if (id == null || !people.add(id)) throw new IllegalArgumentException("Duplicate resident");
                restored.put(key, new Village(this, state));
            }
            villages.putAll(restored); villages.values().stream().filter(v -> v.state.active).forEach(v -> v.ticket(true));
        } catch (Exception error) { loadFailed = true; AutonomousVillagesMod.LOG.error("Cannot load villages; preserving original file", error); }
    }
    public void close() {
        save();
        // Do not detach on shutdown: library may already be closed and it owns backpack persistence.
        for (Village v : villages.values()) v.ticket(false);
    }
    static final class Save { int version = 1; List<VillageState> villages; }
}
