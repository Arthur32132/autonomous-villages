package dev.autonomousvillages;

import dev.navigationai.api.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.*;

/**
 * One claimed settlement. Each tick: survey, projects, growth, then every worker in turn.
 * Everything a worker may do is decided by {@link VillagePlanner}; this class only keeps them alive.
 */
final class Village {
    final VillageRuntime runtime;
    final VillageState state;
    final ServerLevel level;
    final VillageIndex index=new VillageIndex();
    final Reservations reservations=new Reservations();
    final Map<Long,Long> rejected=new HashMap<>();
    private final Map<Long,Map<UUID,Long>> unreachableVotes=new HashMap<>();
    private final Map<Long,Integer> unreachableStrikes=new HashMap<>();
    /**
     * A worker could not reach a chest or workbench. It is skipped by everyone for two minutes only
     * when two different residents failed (or the only one): a single trapped resident fails to reach
     * everything and must not hide the whole store from the village.
     */
    void unreachableFrom(BlockPos p,UUID worker){
        long now=level.getGameTime();
        var votes=unreachableVotes.computeIfAbsent(p.asLong(),k->new HashMap<>());
        votes.values().removeIf(expiry->expiry<=now);
        votes.put(worker,now+2400);
        if(votes.size()>=Math.min(2,Math.max(1,workers.size()))){
            // Each time again it stays out twice as long, up to twenty minutes: a chest up a cliff came
            // back every two minutes and sent crafts out for its sticks (rc14, seed 42: crafting 38% of the day).
            int strikes=unreachableStrikes.merge(p.asLong(),1,Integer::sum);
            rejected.put(p.asLong(),now+Math.min(24000L,2400L<<Math.min(4,strikes-1)));unreachableVotes.remove(p.asLong());
            if(unreachableStrikes.size()>512)unreachableStrikes.clear();
        }
        if(unreachableVotes.size()>256)unreachableVotes.clear();
    }
    /** When each visiting player may next receive a gift (game time). */
    final Map<UUID,Long> gifted=new HashMap<>();
    /** Why jobs ended early since the server started, by "kind: reason"; diagnostics only. */
    final Map<String,Integer> failures=new TreeMap<>();
    final Map<UUID,Worker> workers=new LinkedHashMap<>();
    final VillageStock stock=new VillageStock(this);
    final Chronicle chronicle=new Chronicle(this);
    final Projects town;
    final VillagePlanner planner;
    final Breeding breeding;
    final VillagePresentation presentation;
    boolean ticketed;
    String problem="осмотр территории";
    private int rotation;
    private final Set<Long> leases=new HashSet<>();
    private long nextTickets;
    private long nextSurvey;
    private long nextAlarm;
    long nextErrorLog;
    private long nextWorkerErrorLog;
    private BlockPos hub;
    /** "Never" for cache ticks; now minus Long.MIN_VALUE would overflow and read as "just now". */
    static final long LONG_AGO=-1_000_000L;
    private long hubTick=LONG_AGO;
    private final Set<UUID> announcedRoles=new HashSet<>();

    Village(VillageRuntime runtime,VillageState state){
        this.runtime=runtime;this.state=state;this.level=runtime.server.overworld();
        this.town=new Projects(this);this.planner=new VillagePlanner(this);this.breeding=new Breeding(this);this.presentation=new VillagePresentation(this);
    }

    // --- chunk loading ------------------------------------------------------------------------
    void ticket(boolean enabled){
        if(enabled&&state.bounds!=null&&VillageState.footprintChunks(state.bounds)>VillageState.MAX_TICKET_CHUNKS){state.active=false;stop(false);problem="пауза: территория превышает бюджет активных чанков";return;}
        if(enabled&&ticketed&&level.getGameTime()<nextTickets)return;
        nextTickets=level.getGameTime()+100;
        Set<Long> wanted=new HashSet<>();
        if(enabled){
            rememberResidents();
            int minX=state.bounds==null?state.x-state.radius:state.bounds.minX,maxX=state.bounds==null?state.x+state.radius:state.bounds.maxX;
            int minZ=state.bounds==null?state.z-state.radius:state.bounds.minZ,maxZ=state.bounds==null?state.z+state.radius:state.bounds.maxZ;
            for(int x=minX>>4;x<=maxX>>4;x++)for(int z=minZ>>4;z<=maxZ>>4;z++)wanted.add(ChunkPos.pack(x,z));
            // A resident who wandered off must not be unloaded before walking home.
            for(long position:state.residentPositions.values()){BlockPos p=BlockPos.of(position);wanted.add(ChunkPos.pack(p.getX()>>4,p.getZ()>>4));}
        }
        for(long p:List.copyOf(leases))if(!wanted.contains(p)){runtime.lease(ChunkPos.unpack(p),false);leases.remove(p);}
        for(long p:wanted)if(leases.add(p))runtime.lease(ChunkPos.unpack(p),true);
        ticketed=enabled;
    }
    void rememberResidents(){
        state.residentPositions.keySet().retainAll(state.residents);
        for(UUID id:state.residents){
            var entity=level.getEntity(id);
            if(entity==null)entity=runtime.bots.find(id).map(BotHandle::entity).orElse(null);
            if(entity instanceof Villager resident&&resident.isAlive()&&resident.level()==level)state.residentPositions.put(id,resident.blockPosition().asLong());
        }
    }

    // --- tick ------------------------------------------------------------------------------------
    void tick(){
        if(state.bounds==null){try{var survey=VillageDiscovery.survey(level,state.center());VillageDiscovery.apply(state,survey);town.capture(survey);chronicle.event("Границы обновлены по всей деревне");runtime.save();}catch(IllegalArgumentException ignored){}}
        ticket(true);
        long time=level.getGameTime();
        index.tick(level,state,time);
        if(time>=nextSurvey){
            nextSurvey=time+2400;
            try{var survey=VillageDiscovery.survey(level,state.center());if(expand(survey.bounds())){town.capture(survey);runtime.save();}}
            catch(IllegalArgumentException ignored){}
        }
        if(time%100==0||workers.isEmpty())sync();
        town.tick();
        breeding.tick();
        List<Worker> order=List.copyOf(workers.values());
        for(int n=0;n<order.size();n++){
            Worker w=order.get(Math.floorMod(n+rotation,order.size()));
            boolean mayPlan=runtime.residentDecisionRemainingNanos>0;
            long start=System.nanoTime();boolean idle=w.job==null;
            try{w.tick(mayPlan);}
            catch(RuntimeException error){
                w.cancel();w.status="повторит действие позже";
                if(time>=nextWorkerErrorLog){nextWorkerErrorLog=time+200;AutonomousVillagesMod.LOG.warn("Worker {}",w.id,error);}
            }
            finally{if(idle&&mayPlan)runtime.residentDecisionRemainingNanos-=System.nanoTime()-start;}
        }
        rotation++;
        if(time%5==0)rescueFromPowderSnow();
        if(time%20==7)guardChildren();
        if(time%100==23)huntLurkers();
        // At dusk a village short of golems calls one before the zombies come, not after.
        if(time%200==13&&Math.floorMod(level.getOverworldClockTime(),24000)>=11500&&Math.floorMod(level.getOverworldClockTime(),24000)<13000)summonGolem(time);
        if(time%6000==1234)writeChronicleBook();
        if(time%100==61)MorningNews.tick(this);
        presentation.tick();
        if(time%100==50&&Worker.nightRestWindow(level.getOverworldClockTime(),level.isDarkOutside()))closeDoors();
        if(time%200==0){
            state.plantedTrees.removeIf(p->{var pos=BlockPos.of(p);return level.hasChunkAt(pos)&&!(level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.SaplingBlock)
                    &&!level.getBlockState(pos).is(net.minecraft.tags.BlockTags.LOGS)&&!state.fellingTrees.contains(p);});
            state.furnaces.removeIf(p->level.hasChunkAt(BlockPos.of(p))&&!(level.getBlockEntity(BlockPos.of(p)) instanceof net.minecraft.world.level.block.entity.FurnaceBlockEntity));
            state.lights.removeIf(p->level.hasChunkAt(BlockPos.of(p))&&!level.getBlockState(BlockPos.of(p)).is(net.minecraft.world.level.block.Blocks.TORCH));
            rejected.values().removeIf(expiry->expiry<=time);
            problem=workers.isEmpty()?"нет загруженных взрослых жителей":town.stage;
        }
    }
    /**
     * A resident (child or adult) sinking in powder snow is dug out at once: the snow at its feet and
     * head clears and the cell below packs into a snow block, so it stands in a shallow pit and climbs
     * out instead of freezing to death on the edge of a path.
     */
    void rescueFromPowderSnow(){
        for(UUID id:state.residents){
            // Pricked by a cactus it is wedged against: step to the nearest safe cell clear of cacti.
            if(level.getEntity(id) instanceof Villager hurt&&hurt.isAlive()&&hurt.hurtTime>5&&hurt.getLastDamageSource()!=null
                    &&hurt.getLastDamageSource().is(net.minecraft.world.damagesource.DamageTypes.CACTUS))stepAwayFromCactus(hurt);
            if(!(level.getEntity(id) instanceof Villager v)||!v.isAlive()||!v.isInPowderSnow&&v.getTicksFrozen()==0)continue;
            BlockPos feet=v.blockPosition();boolean dug=false;
            for(BlockPos p:List.of(feet,feet.above()))
                if(level.getBlockState(p).is(net.minecraft.world.level.block.Blocks.POWDER_SNOW)){level.setBlock(p,net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),3);dug=true;}
            if(level.getBlockState(feet.below()).is(net.minecraft.world.level.block.Blocks.POWDER_SNOW))
                {level.setBlock(feet.below(),net.minecraft.world.level.block.Blocks.SNOW_BLOCK.defaultBlockState(),3);dug=true;}
            if(dug){
                index.hazards.remove(feet);index.hazards.remove(feet.above());index.hazards.remove(feet.below());
                level.playSound(null,feet,net.minecraft.sounds.SoundEvents.POWDER_SNOW_BREAK,net.minecraft.sounds.SoundSource.BLOCKS,1f,1f);
                chronicle.add("rescued",1);
            }
        }
    }
    private void stepAwayFromCactus(Villager body){
        BlockPos here=body.blockPosition();BlockPos best=null;double score=Double.MAX_VALUE;
        for(BlockPos q:BlockPos.betweenClosed(here.offset(-3,-2,-3),here.offset(3,2,3))){
            if(!safeFeet(q))continue;
            boolean prickly=false;
            for(BlockPos c:BlockPos.betweenClosed(q.offset(-1,0,-1),q.offset(1,1,1)))if(level.getBlockState(c).is(net.minecraft.world.level.block.Blocks.CACTUS)){prickly=true;break;}
            if(prickly)continue;
            double d=q.distSqr(here);if(d<score){score=d;best=q.immutable();}
        }
        if(best==null)return;
        var to=feetPosition(best);
        var w=workers.get(body.getUUID());
        if(w!=null){
            // Whatever it was doing there is not worth another prick for a while.
            if(w.job!=null&&w.job.target()!=null)rejected.put(w.job.target().asLong(),level.getGameTime()+2400);
            if(w.job!=null)w.cooldown(w.job.kind(),600);
            w.cancel();
        }
        body.teleportTo(to.x,to.y,to.z);chronicle.add("rescued",1);
    }
    void sync(){
        var area=VillageDiscovery.area(state,64);
        for(Villager v:level.getEntitiesOfClass(Villager.class,area,v->v.isAlive()&&(state.contains(v.blockPosition())
                ||v.getBrain().getMemory(MemoryModuleType.HOME).filter(home->home.dimension().equals(level.dimension())&&state.contains(home.pos())).isPresent()))){
            boolean foreign=runtime.villages.values().stream().anyMatch(other->other!=this&&other.state.residents.contains(v.getUUID()));
            if(!foreign&&state.residents.size()<1024&&(runtime.bots.find(v.getUUID()).isEmpty()||state.residents.contains(v.getUUID())))state.residents.add(v.getUUID());
        }
        for(UUID id:List.copyOf(state.residents)){
            var e=level.getEntity(id);
            if(e instanceof Villager v&&v.isAlive()){
                if(v.isBaby()||v.isNoAi())continue; // childhood and map-maker NoAI stay vanilla
                BotHandle handle=runtime.bots.find(id).orElseGet(()->runtime.bots.attach(v));
                if(handle.kind()==BotKind.VILLAGER)workers.computeIfAbsent(id,ignored->new Worker(this,id));
            }else if(e!=null&&!e.isAlive()){state.residents.remove(id);workers.remove(id);reservations.release(id);residentLost(id);}
        }
        workers.entrySet().removeIf(entry->{
            var bot=runtime.bots.find(entry.getKey()).orElse(null);
            if(bot!=null&&bot.state()==BotState.DEAD){state.residents.remove(entry.getKey());reservations.release(entry.getKey());residentLost(entry.getKey());return true;}
            return false;
        });
        syncRoles();
    }
    void syncRoles(){
        var adults=new LinkedHashMap<UUID,String>();
        for(UUID id:state.residents)if(level.getEntity(id) instanceof Villager body&&body.isAlive()&&!body.isBaby()&&!body.isNoAi())
            adults.put(id,body.getVillagerData().profession().unwrapKey().map(k->k.identifier().getPath()).orElse("none"));
        state.residentRoles.keySet().retainAll(state.residents);
        if(ResidentRoles.assign(state.residentRoles,adults)){
            for(UUID id:adults.keySet())if(state.residentRoles.containsKey(id)&&announcedRoles.add(id)){
                var a=state.residentRoles.get(id);chronicle.event(presentation.personName(id)+" — "+a.role.label+" ("+a.reason+")");
            }
            runtime.save();
        }
    }
    void residentLost(UUID id){
        state.residentRoles.remove(id);announcedRoles.remove(id);state.residentPositions.remove(id);
    }
    boolean expand(VillageState.Bounds addition){
        if(state.bounds==null)return false;
        var old=state.bounds;
        var combined=new VillageState.Bounds(Math.min(old.minX,addition.minX),Math.min(old.minZ,addition.minZ),Math.max(old.maxX,addition.maxX),Math.max(old.maxZ,addition.maxZ));
        if(!state.policy.expansionAllowed(old,combined))return false;
        if(VillageState.footprintChunks(combined)>VillageState.MAX_TICKET_CHUNKS)return false;
        if((long)combined.maxX-combined.minX>4096||(long)combined.maxZ-combined.minZ>4096)return false;
        if(combined.minX==old.minX&&combined.minZ==old.minZ&&combined.maxX==old.maxX&&combined.maxZ==old.maxZ)return false;
        state.bounds=combined;
        if(runtime.villages.values().stream().anyMatch(other->other!=this&&state.overlaps(other.state))){state.bounds=old;return false;}
        state.x=(combined.minX+combined.maxX)/2;state.z=(combined.minZ+combined.maxZ)/2;
        state.radius=Math.max(24,(int)Math.ceil(Math.hypot(combined.maxX-combined.minX,combined.maxZ-combined.minZ)/2));
        nextTickets=0;return true;
    }

    /**
     * At night every unoccupied wooden door of the village is shut. Residents open doors on their way
     * and the library does not close them; one open door let husks reach a whole house of sleepers.
     */
    private void closeDoors(){
        for(BlockPos p:town.doors()){
            if(!level.hasChunkAt(p))continue;
            var s=level.getBlockState(p);
            if(!(s.getBlock() instanceof net.minecraft.world.level.block.DoorBlock door)||!s.is(net.minecraft.tags.BlockTags.WOODEN_DOORS)
                    ||s.getValue(net.minecraft.world.level.block.DoorBlock.HALF)!=net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER
                    ||!s.getValue(net.minecraft.world.level.block.DoorBlock.OPEN))continue;
            if(!level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,new AABB(p).expandTowards(0,1,0).inflate(.2),net.minecraft.world.entity.LivingEntity::isAlive).isEmpty())continue;
            // A villager or player about to pass is not shut out: closing the door in front of a
            // resident on its way home broke its route on the doorstep every hundred ticks.
            // Only someone actually walking: a resident asleep or standing by the door must not keep it open
            // all night (rc8: zombies walked into a hall whose door stayed open beside its sleepers).
            if(!level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,new AABB(p).expandTowards(0,1,0).inflate(2.5),
                    e->e.isAlive()&&!e.isSleeping()&&e.getDeltaMovement().horizontalDistanceSqr()>1.0E-4
                            &&(e instanceof Villager||e instanceof net.minecraft.world.entity.player.Player)).isEmpty())continue;
            door.setOpen(null,level,s,p,false);
        }
    }

    // --- store -----------------------------------------------------------------------------------
    List<ChestBlockEntity> chests(){
        List<ChestBlockEntity> result=new ArrayList<>();
        for(long packed:List.copyOf(state.storage)){
            BlockPos p=BlockPos.of(packed);
            if(!level.hasChunkAt(p)||state.policy.isPrivate(this,p))continue;
            if(level.getBlockEntity(p) instanceof ChestBlockEntity c&&!c.isLocked())result.add(c);
            else if(!(level.getBlockEntity(p) instanceof ChestBlockEntity))state.storage.remove(packed);
        }
        return result;
    }

    // --- work permissions and roles ----------------------------------------------------------------
    boolean ready(BlockPos p,UUID worker){
        return canWork(p)&&rejected.getOrDefault(p.asLong(),0L)<=level.getGameTime()&&reservations.available(p.asLong(),worker,level.getGameTime());
    }
    /** Residents may walk and fell trees in the outskirts; everything they own stays inside the footprint. */
    boolean canWork(BlockPos p){return state.nearby(p);}
    ResidentRoles.Role primaryRole(UUID id){var a=state.residentRoles.get(id);return a==null?null:a.role;}
    boolean smallCrew(){return state.residentRoles.size()<3;}
    /** Stable social schedule slot. */
    int roleIndex(UUID id){var a=state.residentRoles.get(id);return a==null?Math.floorMod(id.hashCode(),24):a.schedule;}
    boolean guard(UUID id){return primaryRole(id)==ResidentRoles.Role.GUARD;}
    String role(UUID id){var a=primaryRole(id);return a==null?"без назначения":a.label;}
    long activeJobs(String... kinds){var names=Set.of(kinds);return workers.values().stream().filter(w->w.job!=null&&names.contains(w.job.kind())).count();}
    long alarmUntil;
    private long nextBell;
    /** While the alarm lasts the unarmed go home and the armed fight. */
    boolean alarm(){return level.getGameTime()<alarmUntil;}
    /**
     * Up to two armed, healthy residents who can see the enemy come to help, and the bell rings: the
     * vanilla bell makes the villagers who hear it hide at home.
     */
    void raiseAlarm(Worker caller,Monster enemy){
        long now=level.getGameTime();if(now<nextAlarm)return;nextAlarm=now+40;
        if(state.contains(enemy.blockPosition())){
            if(alarmUntil<now)chronicle.add("alarms",1);
            alarmUntil=now+400;
            summonGolem(now);
            if(now>=nextBell&&!level.isDarkOutside()){
                var hub=meetingPoint();
                if(caller!=null&&level.getBlockState(hub).getBlock() instanceof net.minecraft.world.level.block.BellBlock bell
                        &&bell.attemptToRing(caller.body(),level,hub,Direction.NORTH))nextBell=now+100;
            }
        }
        workers.values().stream().filter(w->w!=caller&&w.active()&&!w.body().isSleeping()&&w.body().getHealth()>=16&&!w.body().isTrading()
                        &&w.body().distanceToSqr(enemy)<24*24&&w.body().hasLineOfSight(enemy)
                        &&(w.combat==null||w.combat.completion().isDone())&&w.armed())
                .sorted(Comparator.comparingDouble(w->w.body().distanceToSqr(enemy))).limit(2).toList().forEach(w->w.defend(enemy));
    }

    /**
     * The chronicle as a written book on the lectern nearest the bell (a librarian's), for players to
     * read. Only an empty lectern or one holding the village's own book is used; a player's book stays.
     */
    /**
     * Children are vanilla villagers the village does not steer; three were killed together in the
     * open one night (seed 42). A monster near a child raises the alarm as an attack on an adult does:
     * armed residents come and, if there is none, a golem is called.
     */
    private void guardChildren(){
        for(UUID id:state.residents){
            if(!(level.getEntity(id) instanceof Villager child)||!child.isAlive()||!child.isBaby()||!state.contains(child.blockPosition()))continue;
            var enemy=level.getEntitiesOfClass(Monster.class,child.getBoundingBox().inflate(10),m->m.isAlive()&&!(m instanceof net.minecraft.world.entity.monster.Creeper)&&m.hasLineOfSight(child))
                    .stream().min(Comparator.comparingDouble(child::distanceToSqr)).orElse(null);
            if(enemy!=null){raiseAlarm(null,enemy);return;}
        }
    }
    /**
     * In daylight a zombie under a porch roof or in a doorway does not burn and waits for whoever comes
     * out (seed 8675309: residents killed at one doorstep in the morning). The nearest armed, healthy
     * resident goes for it without ringing the bell, so the rest of the village keeps working.
     */
    private final Map<UUID,Long> hunted=new HashMap<>();
    private void huntLurkers(){
        if(Worker.nightRestWindow(level.getOverworldClockTime(),level.isDarkOutside()))return;
        long now=level.getGameTime();
        hunted.values().removeIf(until->until<=now);
        for(var lurker:level.getEntitiesOfClass(net.minecraft.world.entity.monster.zombie.Zombie.class,VillageDiscovery.area(state,0),
                m->m.isAlive()&&state.contains(m.blockPosition())&&!m.isOnFire()&&!m.isInWater()&&Math.abs(m.getY()-state.y)<=6
                        &&!(m instanceof net.minecraft.world.entity.monster.zombie.ZombifiedPiglin)&&!(m instanceof net.minecraft.world.entity.monster.zombie.Drowned))){
            if(hunted.containsKey(lurker.getUUID())||workers.values().stream().anyMatch(w->w.fighting(lurker)))continue;
            Worker hunter=workers.values().stream().filter(w->w.active()&&w.armed()&&w.body().getHealth()>=16&&!w.body().isSleeping()&&!w.body().isTrading()
                    &&!w.fighting(null)&&w.ready("hunt")&&w.body().distanceToSqr(lurker)<16*16&&Math.abs(w.body().getY()-lurker.getY())<=4&&w.body().hasLineOfSight(lurker))
                    .min(Comparator.comparingDouble(w->w.body().distanceToSqr(lurker))).orElse(null);
            if(hunter==null)continue;
            // One go per zombie in two minutes: one the hunter could not reach was chosen again every
            // five seconds and kept a resident from work all day (rc14: over a hundred hunts a run).
            hunted.put(lurker.getUUID(),now+2400);hunter.cooldown("hunt",600);
            AutonomousVillagesMod.LOG.info("HUNT {} {} -> {} at {}",state.name,presentation.personName(hunter.id),lurker.getType().getDescription().getString(),lurker.blockPosition().toShortString());
            hunter.defend(lurker);
            return;
        }
    }
    private final Set<Long> booksPlaced=new HashSet<>();
    void writeChronicleBook(){
        if(!AutonomousVillagesMod.CONFIG.chronicleBook)return;
        BlockPos hub=meetingPoint();
        var lectern=level.getPoiManager().getInRange(h->h.is(PoiTypes.LIBRARIAN),hub,Math.min(256,state.radius+16),PoiManager.Occupancy.ANY)
                .map(PoiRecord::getPos).filter(state::contains).filter(level::hasChunkAt)
                .filter(p->level.getBlockEntity(p) instanceof net.minecraft.world.level.block.entity.LecternBlockEntity l&&(!l.hasBook()||Chronicle.ownBook(l.getBook())))
                .min(Comparator.comparingDouble(hub::distSqr)).orElse(null);
        if(lectern==null||!(level.getBlockEntity(lectern) instanceof net.minecraft.world.level.block.entity.LecternBlockEntity l))return;
        boolean first=!l.hasBook();
        // A player took the book: one copy is a gift, not a stream of them.
        if(first&&!booksPlaced.add(lectern.asLong()))return;
        var bs=level.getBlockState(lectern);
        if(first){
            if(!net.minecraft.world.level.block.LecternBlock.tryPlaceBook(null,level,lectern,bs,chronicle.book()))return;
            chronicle.event("Летопись деревни лежит на кафедре у "+lectern.toShortString());
        }else{l.setBook(chronicle.book());l.setChanged();level.sendBlockUpdated(lectern,bs,bs,3);}
    }
    private long nextGolemAttempt;
    /**
     * A village of five or more under attack with no iron golem nearby calls one, as frightened
     * vanilla villagers do, at most once a day.
     */
    /** One iron golem for every ten residents, three at most; none below five residents. */
    int golemsWanted(){return state.residents.size()<5?0:Math.min(3,1+state.residents.size()/10);}
    private void summonGolem(long now){
        // A golem that fell may be replaced after five minutes, never more than the village needs.
        if(!AutonomousVillagesMod.CONFIG.summonGolems||now<nextGolemAttempt||now-state.lastGolem<6000)return;
        nextGolemAttempt=now+1200;
        BlockPos hub=meetingPoint();
        var area=VillageDiscovery.area(state,16);
        int golems=level.getEntitiesOfClass(net.minecraft.world.entity.animal.golem.IronGolem.class,area,net.minecraft.world.entity.LivingEntity::isAlive).size();
        if(golems>=golemsWanted())return;
        net.minecraft.util.SpawnUtil.trySpawnMob(net.minecraft.world.entity.EntityTypes.IRON_GOLEM,net.minecraft.world.entity.EntitySpawnReason.MOB_SUMMONED,level,hub,10,8,6,
                net.minecraft.util.SpawnUtil.Strategy.LEGACY_IRON_GOLEM,false).ifPresent(golem->{
            state.lastGolem=now;chronicle.add("golems",1);chronicle.announce("Жители позвали на помощь железного голема!");runtime.save();
        });
    }

    // --- geometry ----------------------------------------------------------------------------------
    boolean safeFeet(BlockPos p){
        var feet=level.getBlockState(p);var head=level.getBlockState(p.above());var ground=level.getBlockState(p.below());
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
    /** The village hub: the bell, else the middle of the homes. The store is built next to it. Cached briefly. */
    BlockPos meetingPoint(){
        long now=level.getGameTime();
        if(hub!=null&&now-hubTick<100)return hub;
        hubTick=now;
        BlockPos center=state.center();
        BlockPos bell=level.getPoiManager().getInRange(h->h.is(PoiTypes.MEETING),center,Math.min(256,state.radius+16),PoiManager.Occupancy.ANY)
                .map(PoiRecord::getPos).filter(state::contains).min(Comparator.comparingDouble(center::distSqr)).orElse(null);
        BlockPos anchor=bell;
        if(anchor==null){
            int[] heights=index.beds.stream().mapToInt(BlockPos::getY).sorted().toArray();int height=heights.length==0?state.y:heights[heights.length/2];
            anchor=new BlockPos(state.x,height,state.z);
        }
        if(bell!=null)hub=bell;
        else{
            // The column above the median home height, on the ground.
            int y=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,state.x,state.z);
            hub=new BlockPos(state.x,Math.abs(y-anchor.getY())<12?y:anchor.getY(),state.z);
        }
        return hub;
    }
    void moved(){hubTick=LONG_AGO;}
    private BlockPos board;
    private long boardTick=LONG_AGO;
    /** The notice board: a sign near the bell, if the village has put one up. */
    BlockPos noticeBoard(){
        long now=level.getGameTime();
        // A found board is trusted for a while; "no board" is rechecked soon, a sign may have just been put up.
        if(board!=null&&now-boardTick<200&&level.getBlockEntity(board) instanceof net.minecraft.world.level.block.entity.SignBlockEntity)return board;
        if(board==null&&now-boardTick<20)return null;
        boardTick=now;board=null;BlockPos hub=meetingPoint();
        for(BlockPos q:BlockPos.betweenClosed(hub.offset(-6,-2,-6),hub.offset(6,2,6)))
            if(level.getBlockEntity(q) instanceof net.minecraft.world.level.block.entity.SignBlockEntity){board=q.immutable();break;}
        return board;
    }
    BlockPos stand(BlockPos target,Vec3 from){return stand(target,from,3.0,true,Set.of());}
    BlockPos stand(BlockPos target,Vec3 from,double reach,boolean sight,Set<BlockPos> rejectedPositions){
        BlockPos best=null;double distance=Double.MAX_VALUE;
        for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)for(int dy=-6;dy<=1;dy++){
            BlockPos p=target.offset(dx,dy,dz);
            if(rejectedPositions.contains(p))continue;
            if(p.equals(target)||p.above().equals(target)||p.below().equals(target))continue;
            Vec3 eye=feetPosition(p).add(0,1.62,0);
            if(!safeFeet(p)||eye.distanceToSqr(Vec3.atCenterOf(target))>(reach-.65)*(reach-.65))continue;
            var hit=level.clip(new net.minecraft.world.level.ClipContext(eye,Vec3.atCenterOf(target),
                    net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,net.minecraft.world.phys.shapes.CollisionContext.empty()));
            if(sight&&hit.getType()!=HitResult.Type.MISS&&!hit.getBlockPos().equals(target))continue;
            double d=from.distanceToSqr(feetPosition(p));if(d<distance){best=p;distance=d;}
        }
        return best;
    }

    // --- lifecycle and status ------------------------------------------------------------------------
    void stop(boolean detach){
        rememberResidents();
        presentation.close();
        for(Worker w:workers.values()){
            if(w.job!=null)w.job.stop();
            BotHandle b=w.bot();if(b!=null&&b.state()==BotState.ACTIVE){b.stopAll();if(detach)b.detach();}
            reservations.release(w.id);
        }
        // On a restart-before-first-tick, members may already have library handles without Worker objects.
        for(UUID id:state.residents)runtime.bots.find(id).filter(b->b.kind()==BotKind.VILLAGER&&b.state()==BotState.ACTIVE).ifPresent(b->{b.stopAll();if(detach)b.detach();});
        workers.clear();ticket(false);
    }
    String status(){
        long living=state.residents.stream().filter(id->level.getEntity(id) instanceof Villager v&&v.isAlive()).count();
        var jobs=new TreeMap<String,Integer>();for(var w:workers.values())jobs.merge(w.status,1,Integer::sum);
        var lines=new ArrayList<String>();
        lines.add(state.name+" | "+(state.active?"работает":"пауза")+" | центр "+state.center().toShortString()
                +(state.bounds==null?" | осмотр границ":" | территория "+state.bounds.minX+","+state.bounds.minZ+" — "+state.bounds.maxX+","+state.bounds.maxZ));
        lines.add("Жителей "+living+", работников "+workers.size()+", кроватей "+index.beds.size()+" (свободно "+breeding.freeBeds()+"), сундуков "+chests().size()
                +", еды на складе "+stock.count(Supplies::food)+" | "+town.stage);
        String growth=breeding.blocker();
        lines.add("Рост: "+(growth.isEmpty()?"ждут пару у дома":growth)+" | "+problem);
        jobs.forEach((status,n)->lines.add("  "+n+" × "+status));
        return String.join("\n",lines);
    }
}
