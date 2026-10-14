package dev.autonomousvillages;

import dev.navigationai.api.*;
import java.util.*;
import java.util.function.Predicate;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.*;

final class Village {
    final VillageRuntime runtime;
    final VillageState state;
    final VillageIndex index = new VillageIndex();
    final Reservations reservations = new Reservations();
    final Map<UUID, Resident> workers = new LinkedHashMap<>();
    final Map<Long, Long> rejected = new HashMap<>();
    final ServerLevel level;
    boolean ticketed;
    String problem = "осмотр территории";
    private int rotation;
    Village(VillageRuntime runtime, VillageState state) { this.runtime = runtime; this.state = state; this.level = runtime.server.overworld(); }
    void ticket(boolean enabled) {
        if (ticketed == enabled) return;
        ChunkPos chunk = new ChunkPos(state.x >> 4, state.z >> 4);
        // Cover the claim even if its center lies on a chunk edge. Ticket radius 1 = entity-ticking center.
        int radius = (state.radius + 15) / 16 + 2;
        if (enabled) level.getChunkSource().addTicketWithRadius(AutonomousVillagesMod.TICKET, chunk, radius);
        else level.getChunkSource().removeTicketWithRadius(AutonomousVillagesMod.TICKET, chunk, radius);
        ticketed = enabled;
    }
    void tick() {
        ticket(true); long time = level.getGameTime(); index.tick(level, state, time);
        if (time % 100 == 0 || workers.isEmpty()) sync();
        List<Resident> order = List.copyOf(workers.values()); long deadline = System.nanoTime() + 2_000_000L;
        for (int n = 0; n < order.size(); n++) {
            Resident r = order.get(Math.floorMod(n + rotation, order.size()));
            if (r.job==null && r.combat==null && time % 10 != Math.floorMod(r.id.hashCode(), 10)) continue;
            if (System.nanoTime() > deadline && n > 0) break;
            try { r.tick(); }
            catch (RuntimeException error) { r.fail("повторит действие позже"); if (time % 200 < 10) AutonomousVillagesMod.LOG.warn("Resident {}", r.id, error); }
        }
        rotation++;
        if (time % 200 == 0) {
            state.furnaces.removeIf(p->level.hasChunkAt(BlockPos.of(p))&&!(level.getBlockEntity(BlockPos.of(p)) instanceof net.minecraft.world.level.block.entity.FurnaceBlockEntity));
            rejected.values().removeIf(expiry -> expiry <= time);
            if (count(s -> s.is(ItemTags.LOGS) || s.is(ItemTags.PLANKS)) == 0 && index.trees.isEmpty()) problem = "нужна древесина: деревья или материалы на складе";
            else if (index.crops.isEmpty() && count(s -> s.is(Items.WHEAT_SEEDS) || s.is(Items.CARROT) || s.is(Items.POTATO)) == 0) problem = "ищут семена и место для грядок";
            else if(state.shelter!=null){
                Construction.Step step=Construction.next(this);
                if(step!=null&&!Construction.clear(level.getBlockState(step.pos())))problem="стройка ждёт освобождения места "+step.pos().toShortString();
                else if(step!=null&&step.item()==Items.BED.white()&&count(s->s.is(net.minecraft.tags.ItemTags.WOOL))<3)problem="для новых кроватей нужна шерсть или доступные овцы";
                else problem="развивают хозяйство и строят жильё";
            }
            else problem = "хозяйство работает";
        }
    }
    void sync() {
        var area = new AABB(Vec3.atCenterOf(state.center()), Vec3.atCenterOf(state.center())).inflate(state.radius, 48, state.radius);
        for (Villager v : level.getEntitiesOfClass(Villager.class, area, v -> v.isAlive() && state.contains(v.blockPosition()))) {
            boolean foreign = runtime.villages.values().stream().anyMatch(other -> other != this && other.state.residents.contains(v.getUUID()));
            if (!foreign && state.residents.size() < 64 && (runtime.bots.find(v.getUUID()).isEmpty() || state.residents.contains(v.getUUID()))) state.residents.add(v.getUUID());
        }
        for (UUID id : List.copyOf(state.residents)) {
            var e = level.getEntity(id);
            if (e instanceof Villager v && v.isAlive()) {
                if (v.isBaby()) continue; // native childhood, never assigned labor
                if (v.isNoAi()) continue; // do not override map-maker NoAI residents
                BotHandle handle = runtime.bots.find(id).orElseGet(() -> runtime.bots.attach(v));
                if (handle.kind() == BotKind.VILLAGER) workers.computeIfAbsent(id, ignored -> new Resident(this, id));
            } else if (e != null && !e.isAlive()) { state.residents.remove(id); Resident r = workers.remove(id); if (r != null) reservations.release(id); }
        }
        workers.entrySet().removeIf(entry -> {
            var bot = runtime.bots.find(entry.getKey()).orElse(null);
            if (bot != null && bot.state() == BotState.DEAD) { state.residents.remove(entry.getKey()); reservations.release(entry.getKey()); return true; }
            return false;
        });
    }
    List<ChestBlockEntity> chests() {
        List<ChestBlockEntity> result = new ArrayList<>();
        for (long packed : List.copyOf(state.storage)) {
            BlockPos p = BlockPos.of(packed);
            if (!level.hasChunkAt(p)) continue;
            if (level.getBlockEntity(p) instanceof ChestBlockEntity c && !c.isLocked()) result.add(c);
            else if(!(level.getBlockEntity(p) instanceof ChestBlockEntity))state.storage.remove(packed);
        }
        return result;
    }
    boolean needsChest(){
        var available=chests();
        return state.storage.size()<16&&(available.isEmpty()||available.stream().mapToInt(c->(int)Supplies.copy(c).stream().filter(ItemStack::isEmpty).count()).sum()<2);
    }
    int count(Predicate<ItemStack> test) {
        int n = 0;
        for (var chest : chests()) n += Supplies.count(Supplies.copy(chest), test);
        for (Resident r : workers.values()) { BotHandle b = r.bot(); if (b != null && b.state() == BotState.ACTIVE) n += Supplies.count(b.inventory().snapshot().slots(), test); }
        return n;
    }
    boolean ready(BlockPos p, UUID worker) {
        return state.contains(p) && rejected.getOrDefault(p.asLong(), 0L) <= level.getGameTime()
                && reservations.available(p.asLong(), worker, level.getGameTime());
    }
    boolean steward(UUID id) { return workers.keySet().stream().min(Comparator.comparing(UUID::toString)).map(id::equals).orElse(false); }
    boolean safeFeet(BlockPos p) {
        var feet = level.getBlockState(p); var head = level.getBlockState(p.above()); var ground = level.getBlockState(p.below());
        var support=ground.getCollisionShape(level,p.below());
        return state.contains(p) && level.hasChunkAt(p) && feet.getCollisionShape(level,p).isEmpty() && feet.getFluidState().isEmpty()
                && head.getCollisionShape(level,p.above()).isEmpty() && head.getFluidState().isEmpty()
                && !support.isEmpty() && support.max(Direction.Axis.Y)>=.875 && !ground.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK)
                && !feet.is(net.minecraft.world.level.block.Blocks.FIRE);
    }
    BlockPos stand(BlockPos target, Vec3 from) {
        return stand(target,from,3.0);
    }
    BlockPos stand(BlockPos target, Vec3 from,double reach) {
        return stand(target,from,reach,true);
    }
    BlockPos stand(BlockPos target,Vec3 from,double reach,boolean sight){
        return stand(target,from,reach,sight,Set.of());
    }
    BlockPos stand(BlockPos target,Vec3 from,double reach,boolean sight,Set<BlockPos> rejectedPositions){
        BlockPos best = null; double distance = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = -6; dy <= 1; dy++) {
            BlockPos p = target.offset(dx,dy,dz);
            if(rejectedPositions.contains(p))continue;
            if (p.equals(target) || p.above().equals(target)) continue;
            Vec3 eye=Vec3.atBottomCenterOf(p).add(0,1.62,0);
            if (!safeFeet(p) || eye.distanceToSqr(Vec3.atCenterOf(target)) > (reach-.65)*(reach-.65)) continue;
            var hit=level.clip(new net.minecraft.world.level.ClipContext(eye,Vec3.atCenterOf(target),
                    net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,net.minecraft.world.phys.shapes.CollisionContext.empty()));
            if(sight&&hit.getType()!=HitResult.Type.MISS&&!hit.getBlockPos().equals(target))continue;
            double d = from.distanceToSqr(Vec3.atBottomCenterOf(p)); if (d < distance) { best = p; distance = d; }
        }
        return best;
    }
    void stop(boolean detach) {
        for (Resident r : workers.values()) {
            BotHandle b = r.bot(); if (b != null && b.state() == BotState.ACTIVE) { b.stopAll(); if (detach) b.detach(); }
            reservations.release(r.id);
        }
        // On a restart-before-first-tick, members may already have library handles without Resident objects.
        for (UUID id : state.residents) runtime.bots.find(id).filter(b -> b.kind() == BotKind.VILLAGER && b.state() == BotState.ACTIVE).ifPresent(b -> { b.stopAll(); if (detach) b.detach(); });
        workers.clear(); ticket(false);
    }
    String status() {
        long living = state.residents.stream().filter(id -> level.getEntity(id) instanceof Villager v && v.isAlive()).count();
        String jobs = String.join(", ", workers.values().stream().map(r -> r.status).distinct().toList());
        return state.name + " | " + (state.active ? "работает" : "пауза") + " | центр " + state.center().toShortString() + ", R=" + state.radius
                + " | жителей загружено " + living + ", работников " + workers.size() + ", кроватей " + index.beds.size()
                + " | складов " + chests().size() + ", еды " + count(Supplies::food) + " | " + problem + (jobs.isEmpty() ? "" : "\n" + jobs);
    }
}
