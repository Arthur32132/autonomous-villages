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
    final TownPlanner town;
    final SheepFarm farm;
    final VillagePresentation presentation;
    final ExpeditionManager expeditions;
    final Reservations reservations = new Reservations();
    /** Roadmap 1: the current dependency graph, recomputed with the orders each planning cycle. */
    SettlementNeeds.Plan needs = SettlementNeeds.Plan.empty();
    final Map<UUID, Resident> workers = new LinkedHashMap<>();
    final Map<Long, Long> rejected = new HashMap<>();
    final ServerLevel level;
    boolean ticketed;
    String problem = "осмотр территории";
    private int rotation;
    private final Set<Long> leases=new HashSet<>();
    private long nextTickets;
    private long nextSurvey;
    private long nextAlarm;
    private final ArrayDeque<String> history=new ArrayDeque<>();
    Village(VillageRuntime runtime, VillageState state) { this.runtime = runtime; this.state = state; this.level = runtime.server.overworld();this.town=new TownPlanner(this);this.farm=new SheepFarm(this);this.presentation=new VillagePresentation(this);this.expeditions=new ExpeditionManager(this);
        if(state.nextExpedition==0)state.nextExpedition=level.getGameTime()+1200;
    }
    void ticket(boolean enabled) {
        if(enabled&&state.bounds!=null&&VillageState.footprintChunks(state.bounds)>VillageState.MAX_TICKET_CHUNKS){state.active=false;stop(false);problem="пауза: территория превышает бюджет активных чанков";return;}
        if(enabled&&ticketed&&level.getGameTime()<nextTickets)return;
        nextTickets=level.getGameTime()+100;
        Set<Long> wanted=new HashSet<>();
        if(enabled){
            rememberResidents();
            int minX=state.bounds==null?state.x-state.radius:state.bounds.minX,maxX=state.bounds==null?state.x+state.radius:state.bounds.maxX;
            int minZ=state.bounds==null?state.z-state.radius:state.bounds.minZ,maxZ=state.bounds==null?state.z+state.radius:state.bounds.maxZ;
            for(int x=minX>>4;x<=maxX>>4;x++)for(int z=minZ>>4;z<=maxZ>>4;z++)wanted.add(new ChunkPos(x,z).pack());
            wanted.addAll(expeditions.chunks());
            // An expedition timeout must not unload a traveller before they walk home.
            for(long position:state.residentPositions.values()){BlockPos p=BlockPos.of(position);wanted.add(ChunkPos.pack(p.getX()>>4,p.getZ()>>4));}
            for(long position:state.sheepPastures){BlockPos p=BlockPos.of(position);wanted.add(ChunkPos.pack(p.getX()>>4,p.getZ()>>4));}
            for(long planted:state.plantedTrees)wanted.add(new ChunkPos(BlockPos.of(planted).getX()>>4,BlockPos.of(planted).getZ()>>4).pack());
        }
        for(long p:List.copyOf(leases))if(!wanted.contains(p)){runtime.lease(ChunkPos.unpack(p),false);leases.remove(p);}
        for(long p:wanted)if(leases.add(p))runtime.lease(ChunkPos.unpack(p),true);
        ticketed = enabled;
    }
    void rememberResidents(){
        state.residentPositions.keySet().retainAll(state.residents);
        for(UUID id:state.residents){
            var entity=level.getEntity(id);
            if(entity==null)entity=runtime.bots.find(id).map(dev.navigationai.api.BotHandle::entity).orElse(null);
            if(entity instanceof Villager resident&&resident.isAlive()&&resident.level()==level)state.residentPositions.put(id,resident.blockPosition().asLong());
        }
    }
    void tick() {
        if(state.bounds==null){try{var survey=VillageDiscovery.survey(level,state.center());VillageDiscovery.apply(state,survey);town.capture(survey);event("Границы обновлены по всей деревне");runtime.save();}catch(IllegalArgumentException ignored){}}
        ticket(true); long time = level.getGameTime(); index.tick(level, state, time);
        if(time>=nextSurvey){
            nextSurvey=time+2400;
            try{
                var survey=VillageDiscovery.survey(level,state.center());
                if(expand(survey.bounds())){town.capture(survey);runtime.save();}
            }catch(IllegalArgumentException ignored){}
        }
        if (time % 100 == 0 || workers.isEmpty()) sync();
        town.tick();
        farm.tick();
        ManagedLand.tick(this);
        ProductionBuildings.tick(this);
        PopulationPlanning.tick(this);
        if(time%100==0)town.syncOrders(time);
        expeditions.tick();
        SupplyRuns.tick(this);
        DeliveryRuns.tick(this);
        List<Resident> order = List.copyOf(workers.values());
        for (int n = 0; n < order.size(); n++) {
            Resident r = order.get(Math.floorMod(n + rotation, order.size()));
            if (r.job==null && r.combat==null && time % 10 != Math.floorMod(r.id.hashCode(), 10)) continue;
            boolean mayPlan=runtime.residentDecisionRemainingNanos>0;
            long start=System.nanoTime();boolean idle=r.job==null;
            try { r.tick(mayPlan); }
            catch (RuntimeException error) { r.fail("повторит действие позже"); if (time % 200 < 10) AutonomousVillagesMod.LOG.warn("Resident {}", r.id, error); }
            finally{if(idle&&mayPlan)runtime.residentDecisionRemainingNanos-=System.nanoTime()-start;}
        }
        rotation++;
        presentation.tick();
        if (time % 200 == 0) {
            state.temporaryWorkbenches.removeIf(p->level.hasChunkAt(BlockPos.of(p))&&!level.getBlockState(BlockPos.of(p)).is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE));
            state.plantedTrees.removeIf(p->{var pos=BlockPos.of(p);return level.hasChunkAt(pos)&&!(level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.SaplingBlock)
                    &&!level.getBlockState(pos).is(net.minecraft.tags.BlockTags.LOGS)&&!state.fellingTrees.contains(p)&&!state.reforestation.contains(p);});
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
        var area = VillageDiscovery.area(state,64);
        for (Villager v : level.getEntitiesOfClass(Villager.class, area, v -> v.isAlive() && (state.contains(v.blockPosition())
                ||v.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.HOME)
                .filter(home->home.dimension().equals(level.dimension())&&state.contains(home.pos())).isPresent()))) {
            boolean foreign = runtime.villages.values().stream().anyMatch(other -> other != this && other.state.residents.contains(v.getUUID()));
            if (!foreign && state.residents.size()<1024 && (runtime.bots.find(v.getUUID()).isEmpty() || state.residents.contains(v.getUUID()))) state.residents.add(v.getUUID());
        }
        for (UUID id : List.copyOf(state.residents)) {
            var e = level.getEntity(id);
            if (e instanceof Villager v && v.isAlive()) {
                if (v.isBaby()) continue; // native childhood, never assigned labor
                if (v.isNoAi()) continue; // do not override map-maker NoAI residents
                BotHandle handle = runtime.bots.find(id).orElseGet(() -> runtime.bots.attach(v));
                if (handle.kind() == BotKind.VILLAGER) workers.computeIfAbsent(id, ignored -> new Resident(this, id));
            } else if (e != null && !e.isAlive()) { state.residents.remove(id); Resident r = workers.remove(id); if (r != null) reservations.release(id); residentLost(id); }
        }
        workers.entrySet().removeIf(entry -> {
            var bot = runtime.bots.find(entry.getKey()).orElse(null);
            if (bot != null && bot.state() == BotState.DEAD) { state.residents.remove(entry.getKey()); reservations.release(entry.getKey()); residentLost(entry.getKey()); return true; }
            return false;
        });
        syncRoles();
        if(!state.vacantRoles.isEmpty())PopulationPlanning.recoverVacancies(this);
    }
    void syncRoles(){
        var adults=new LinkedHashMap<UUID,String>();
        for(UUID id:state.residents)if(level.getEntity(id) instanceof Villager body&&body.isAlive()&&!body.isBaby()&&!body.isNoAi())
            adults.put(id,body.getVillagerData().profession().unwrapKey().map(k->k.identifier().getPath()).orElse("none"));
        state.residentRoles.keySet().retainAll(state.residents);
        if(ResidentRoles.assign(state.residentRoles,adults)){
            for(UUID id:adults.keySet())if(state.residentRoles.containsKey(id)&&!announcedRoles.contains(id)){
                var a=state.residentRoles.get(id);event(a.role.label+": "+a.reason);announcedRoles.add(id);
            }
            runtime.save();
        }
    }
    private final Set<UUID> announcedRoles=new HashSet<>();
    // Captured before vanilla drops/clears the real backpack, consumed only after confirmed death.
    final Map<UUID,Map<Orders.Material,Integer>> dyingCargo=new HashMap<>();
    void rememberDyingCargo(UUID id){
        var b=runtime.bots.find(id).orElse(null);if(b==null)return;
        var cargo=new EnumMap<Orders.Material,Integer>(Orders.Material.class);
        for(var material:Orders.Material.values())cargo.put(material,Supplies.count(b.inventory().snapshot().slots(),material.accepts));
        dyingCargo.put(id,cargo);
    }
    void residentLost(UUID id){
        var lost=primaryRole(id);if(lost!=null)state.vacantRoles.add(lost);
        state.residentRoles.remove(id);announcedRoles.remove(id);
        var stock=new EnumMap<Orders.Material,Integer>(Orders.Material.class);
        long now=level.getGameTime();
        for(var material:Orders.Material.values()){
            stock.put(material,chestCount(material));Orders.recovered(state.orders,material,stock.get(material),now);
        }
        for(var order:state.orders)if(id.toString().equals(order.recipient)&&!order.returnCargo()){order.recipient="";order.delivered=0;Orders.release(order);}
        var cargo=dyingCargo.remove(id);
        if(Orders.workerLost(state.orders,id,meetingPoint().asLong(),now,cargo==null?Map.of():cargo,stock)){
            event("Заказы погибшего ожидают преемника; реальный груз отмечен к возврату");runtime.save();
        }
    }
    boolean expand(VillageState.Bounds addition){
        if(state.bounds==null)return false;
        var old=state.bounds;
        var combined=new VillageState.Bounds(Math.min(old.minX,addition.minX),Math.min(old.minZ,addition.minZ),Math.max(old.maxX,addition.maxX),Math.max(old.maxZ,addition.maxZ));
        if(!state.policy.expansionAllowed(old,combined))return false;
        if(VillageState.footprintChunks(combined)>VillageState.MAX_TICKET_CHUNKS)return false;
        if((long)combined.maxX-combined.minX>4096||(long)combined.maxZ-combined.minZ>4096)return false;
        state.bounds=combined;
        if(runtime.villages.values().stream().anyMatch(other->other!=this&&state.overlaps(other.state))){state.bounds=old;return false;}
        state.x=(combined.minX+combined.maxX)/2;state.z=(combined.minZ+combined.maxZ)/2;
        state.radius=Math.max(24,(int)Math.ceil(Math.hypot(combined.maxX-combined.minX,combined.maxZ-combined.minZ)/2));
        nextTickets=0;return true;
    }
    List<ChestBlockEntity> chests() {
        List<ChestBlockEntity> result = new ArrayList<>();
        for (long packed : List.copyOf(state.storage)) {
            BlockPos p = BlockPos.of(packed);
            if (!level.hasChunkAt(p)) continue;
            if(state.policy.isPrivate(this,p))continue;
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
        for (var chest : chests()) if(!ManagedLand.seedChest(this,chest.getBlockPos().asLong()))n += Supplies.count(Supplies.copy(chest), test);
        for (Resident r : workers.values()) { BotHandle b = r.bot(); if (b != null && b.state() == BotState.ACTIVE) n += Supplies.count(b.inventory().snapshot().slots(), test); }
        return n;
    }
    int chestCount(Orders.Material material){
        int n=0;for(var chest:chests())if(!ManagedLand.seedChest(this,chest.getBlockPos().asLong()))n+=Supplies.count(Supplies.copy(chest),material.accepts);return n;
    }
    /** Free stock, personal kits, material reserved for open orders and cargo already in transit. */
    Orders.Ledger ledger(Orders.Material material){
        Orders.refreshStock(this);
        List<Resident> active=workers.values().stream().filter(r->{BotHandle b=r.bot();return b!=null&&b.state()==BotState.ACTIVE;}).toList();
        int[] bags=new int[active.size()],kits=new int[active.size()];
        for(int i=0;i<active.size();i++){bags[i]=active.get(i).count(material);kits[i]=active.get(i).kit(material);}
        return Orders.split(state.orders,material,chestCount(material),bags,kits);
    }
    /** Earlier open orders keep their chest allocation; unrelated fetches see only free stock. */
    int claimableFromChests(Orders.Order wanted){
        var allocation=Orders.allocations(this,wanted.kind()).get(wanted);
        return allocation==null?0:allocation.chest();
    }
    int claimableFromBag(Orders.Order wanted,Resident donor){
        var allocation=Orders.allocations(this,wanted.kind()).get(wanted);
        return allocation==null?0:allocation.bags().getOrDefault(donor.id,0);
    }
    /** Residents whose backpack physically carries this material and can hand it over. */
    List<Resident> holders(Orders.Material material){
        List<Resident> result=new ArrayList<>();
        for(Resident r:workers.values()){BotHandle b=r.bot();if(b==null||b.state()!=BotState.ACTIVE)continue;
            if(r.count(material)>0)result.add(r);}
        return result;
    }
    boolean ready(BlockPos p, UUID worker) {
        return canWork(p) && rejected.getOrDefault(p.asLong(), 0L) <= level.getGameTime()
                && reservations.available(p.asLong(), worker, level.getGameTime());
    }
    boolean canWork(BlockPos p){return state.recordedWork(p)||expeditions.permits(p);}
    ResidentRoles.Role primaryRole(UUID id){var a=state.residentRoles.get(id);return a==null?null:a.role;}
    boolean hasRole(UUID id,ResidentRoles.Role role){return primaryRole(id)==role;}
    boolean smallCrew(){return state.residentRoles.size()<3;}
    boolean steward(UUID id){return hasRole(id,ResidentRoles.Role.STEWARD)||smallCrew()&&builder(id);}
    /** Stable native/social schedule slot; this number no longer encodes a specialization. */
    int roleIndex(UUID id){var a=state.residentRoles.get(id);return a==null?Math.floorMod(id.hashCode(),24):a.schedule;}
    boolean builder(UUID id){return hasRole(id,ResidentRoles.Role.BUILDER)||hasRole(id,ResidentRoles.Role.STEWARD)||smallCrew()&&primaryRole(id)!=null;}
    boolean farmer(UUID id){return hasRole(id,ResidentRoles.Role.FARMER)||smallCrew()&&primaryRole(id)!=null;}
    boolean miner(UUID id){return hasRole(id,ResidentRoles.Role.MINER)||smallCrew()&&primaryRole(id)!=null;}
    boolean guard(UUID id){return hasRole(id,ResidentRoles.Role.GUARD);}
    boolean porter(UUID id){return hasRole(id,ResidentRoles.Role.PORTER);}
    boolean logger(UUID id){return hasRole(id,ResidentRoles.Role.LOGGER);}
    String role(UUID id){var a=primaryRole(id);return a==null?"без назначения":a.label;}
    boolean expeditionCoverage(Set<UUID> away){
        var available=new LinkedHashMap<UUID,ResidentRoles.Role>();
        for(var r:workers.values())if(r.bot()!=null&&r.bot().state()==BotState.ACTIVE&&r.body().isAlive()&&!r.body().isBaby()
                &&!r.body().isNoAi()&&r.body().getHealth()>=12&&state.contains(r.body().blockPosition())&&primaryRole(r.id)!=null)
            available.put(r.id,primaryRole(r.id));
        return ResidentRoles.homeCoverage(available,away);
    }
    long activeJobs(String... kinds){var names=Set.of(kinds);return workers.values().stream().filter(r->r.job!=null&&names.contains(r.job)).count();}
    void raiseAlarm(Resident caller,net.minecraft.world.entity.monster.Monster enemy){
        long now=level.getGameTime();if(now<nextAlarm)return;nextAlarm=now+40;
        workers.values().stream().filter(r->r!=caller&&r.bot()!=null&&r.body().isAlive()&&!r.body().isSleeping()&&r.body().getHealth()>=16
                &&guard(r.id)&&!r.body().isTrading()
                &&r.body().distanceToSqr(enemy)<24*24&&r.body().hasLineOfSight(enemy)
                &&(r.combat==null||r.combat.completion().isDone())&&VillageWorkshop.hasDefensiveSword(r.bot()))
                .sorted(Comparator.comparingDouble(r->r.body().distanceToSqr(enemy))).limit(2).toList().forEach(r->{r.defend(enemy);ProductionBuildings.alarm(this);});
    }
    void event(String message){history.addLast(message);while(history.size()>12)history.removeFirst();AutonomousVillagesMod.LOG.info("SETTLEMENT {}: {}",state.name,message);}
    boolean safeFeet(BlockPos p) {
        var feet = level.getBlockState(p); var head = level.getBlockState(p.above()); var ground = level.getBlockState(p.below());
        var support=ground.getCollisionShape(level,p.below());var footShape=feet.getCollisionShape(level,p);
        boolean low=!footShape.isEmpty()&&footShape.max(Direction.Axis.Y)<1;
        if(!canWork(p)||!level.hasChunkAt(p)||VillageIndex.hazard(feet)||VillageIndex.hazard(ground)||VillageIndex.hazard(head)
                ||!feet.getFluidState().isEmpty()||!head.getFluidState().isEmpty()||!low&&(support.isEmpty()||support.max(Direction.Axis.Y)<.5))return false;
        Vec3 point=feetPosition(p);var body=new AABB(point.x-.3,point.y+.0001,point.z-.3,point.x+.3,point.y+1.95,point.z+.3);
        for(BlockPos block:BlockPos.betweenClosed(BlockPos.containing(body.minX,body.minY,body.minZ),BlockPos.containing(body.maxX,body.maxY,body.maxZ)))
            for(var box:level.getBlockState(block).getCollisionShape(level,block).toAabbs())if(box.move(block).intersects(body))return false;
        return true;
    }
    Vec3 feetPosition(BlockPos p){
        var feet=level.getBlockState(p).getCollisionShape(level,p);var below=level.getBlockState(p.below()).getCollisionShape(level,p.below());
        double offset=!feet.isEmpty()&&feet.max(Direction.Axis.Y)<1?feet.max(Direction.Axis.Y):!below.isEmpty()?below.max(Direction.Axis.Y)-1:0;
        return Vec3.atBottomCenterOf(p).add(0,offset,0);
    }
    BlockPos meetingPoint(){
        var depot=state.buildings.stream().filter(b->b.kind.equals("warehouse")).findFirst().orElse(null);
        if(depot!=null)return BlockPos.of(depot.origin).offset(3,1,-2);
        int[] heights=index.beds.stream().mapToInt(BlockPos::getY).sorted().toArray();int height=heights.length==0?state.y:heights[heights.length/2];
        var chest=chests().stream().min(Comparator.comparingInt(c->Math.abs(c.getBlockPos().getY()-height))).orElse(null);if(chest!=null)return chest.getBlockPos();
        for(var b:state.buildings)for(var p:b.blocks)if(p.block().getBlock() instanceof net.minecraft.world.level.block.DoorBlock)return p.position();
        return new BlockPos(state.x,level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,state.x,state.z),state.z);
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
            if (p.equals(target) || p.above().equals(target)||p.below().equals(target)) continue;
            Vec3 eye=feetPosition(p).add(0,1.62,0);
            if (!safeFeet(p) || eye.distanceToSqr(Vec3.atCenterOf(target)) > (reach-.65)*(reach-.65)) continue;
            var hit=level.clip(new net.minecraft.world.level.ClipContext(eye,Vec3.atCenterOf(target),
                    net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,net.minecraft.world.phys.shapes.CollisionContext.empty()));
            if(sight&&hit.getType()!=HitResult.Type.MISS&&!hit.getBlockPos().equals(target))continue;
            double d = from.distanceToSqr(feetPosition(p)); if (d < distance) { best = p; distance = d; }
        }
        return best;
    }
    void stop(boolean detach) {
        rememberResidents();
        expeditions.stop();
        presentation.close();
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
        return state.name + " | " + (state.active ? "работает" : "пауза") + " | центр " + state.center().toShortString()
                +(state.bounds==null?" | осмотр границ":" | территория "+state.bounds.minX+","+state.bounds.minZ+" — "+state.bounds.maxX+","+state.bounds.maxZ)
                + " | жителей загружено " + living + ", работников " + workers.size() + ", кроватей " + index.beds.size()
                + " | сундуков " + chests().size() + ", еды " + count(Supplies::food) + " | " + town.stage
                + (state.orders.isEmpty() ? "" : " | заказы: " + String.join(", ", state.orders.stream()
                        .map(o -> o.material + " " + o.delivered + "/" + o.quantity + (o.transit ? " в пути" : "") + " " + o.purpose).toList()))
                + " | запас еды нужен "+SettlementEconomy.budget(this).food()+" | "+(SettlementEconomy.canGrow(this)?"рост обеспечен":"рост ждёт ресурсов/жилья")
                + " | потребности: "+needs.summary()
                + " | " + problem + (jobs.isEmpty() ? "" : "\n" + jobs);
    }
}
