package dev.autonomousvillages;

import java.nio.file.*;
import java.util.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.*;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.*;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.slf4j.*;

/** Observes the production planner in a generated village. Never gives residents items or places fixtures. */
final class SettlementSimulationProbe implements ClientModInitializer {
    static final Logger LOG=LoggerFactory.getLogger("colony_observer");
    volatile boolean launched,finished,success;
    volatile BlockPos camera;
    volatile String observation="загрузка";
    int ticks,frames,clientTicks;
    long nextFrame,begin=System.nanoTime();
    long simulationStarted,rateWindowStarted,nextSnapshotNanos;int rateWindowTick;
    final int tickRate=Math.clamp(Integer.getInteger("avillage.test.tickRate",400),1,10000);
    final int wallTimeout=Integer.getInteger("avillage.test.wallTimeout",1260);
    final Map<UUID,String> observedFailures=new HashMap<>();String lastPartyPhase="";
    final TickSpeedBenchmark benchmark=Boolean.getBoolean("avillage.test.benchmarkTps")?new TickSpeedBenchmark():null;
    final int maxTicks=Math.clamp(Integer.getInteger("avillage.test.maxTicks",48000),4800,144000);
    Village village;
    BlockPos locate;
    int locateTick;
    final Map<String,Object> evidence=new LinkedHashMap<>();
    final Map<UUID,Map<String,Long>> jobTicks=new LinkedHashMap<>();
    final List<TownBuilding.Plan> damage=new ArrayList<>();
    final Set<UUID> originalMembers=new HashSet<>(),seenThreats=new HashSet<>();
    final Set<Object> observedHarvests=Collections.newSetFromMap(new WeakHashMap<>());
    final Set<Object> observedAtomicTasks=Collections.newSetFromMap(new WeakHashMap<>());
    int nightTicks,sleepTicks;
    boolean warehouse,repair,upgrade,coverage,atomic;
    UUID miningWorker;
    BlockPos miningBlock;
    int miningTestTick,damagedAt;
    long inheritedMiningDeliveries,inheritedExpeditionItems;
    @Override public void onInitializeClient(){
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(server->{
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),"difficulty "+(Boolean.getBoolean("avillage.test.liveThreats")?"normal":"peaceful"));
            AutonomousVillagesMod.runtime(server).villages.values().forEach(v->originalMembers.addAll(v.state.residents));
        });
        ServerTickEvents.END_SERVER_TICK.register(this::server);ClientTickEvents.END_CLIENT_TICK.register(this::client);
    }
    void client(Minecraft mc){
        try{
            clientTicks++;
            if(!launched&&mc.gui.screen() instanceof TitleScreen&&clientTicks>20){
                launched=true;
                if(Boolean.getBoolean("avillage.test.copiedWorld"))mc.createWorldOpenFlows().openWorld("ColonySmoke",()->stop(false,"world_open_cancelled"));
                else{
                    var settings=new LevelSettings("Colony observation",GameType.CREATIVE,new LevelSettings.DifficultySettings(Boolean.getBoolean("avillage.test.liveThreats")?Difficulty.NORMAL:Difficulty.PEACEFUL,false,false),true,WorldDataConfiguration.DEFAULT);
                    mc.createWorldOpenFlows().createFreshLevel("ColonySmoke",settings,new WorldOptions(8675309L,true,false),WorldPresets::createNormalWorldDimensions,new TitleScreen());
                }
            }
            if(mc.player!=null&&mc.level!=null){
                if(mc.gui.screen()!=null)mc.gui.setScreen(null);
                if(camera!=null){
                    double dx=camera.getX()+.5-mc.player.getX(),dz=camera.getZ()+.5-mc.player.getZ();
                    double dy=camera.getY()+1-mc.player.getEyeY();
                    mc.player.setYRot((float)Math.toDegrees(Math.atan2(-dx,dz)));
                    mc.player.setXRot((float)-Math.toDegrees(Math.atan2(dy,Math.hypot(dx,dz))));
                }
                long now=System.nanoTime();
                if(now>=nextFrame){nextFrame=now+3_000_000_000L;
                    Screenshot.grab(mc.gameDirectory,String.format(Locale.ROOT,"colony-%05d.png",frames++),mc.gameRenderer.mainRenderTarget(),1,
                            message->LOG.info("COLONY_FRAME {} {}",frames,observation));
                }
            }
            if(Files.exists(Path.of("stop-requested")))stop(false,"stopped_for_diagnosis");
            if(finished){mc.stop();return;}
            if(System.nanoTime()-begin>wallTimeout*1_000_000_000L)stop(false,"client_observation_timeout");
        }catch(Throwable error){LOG.error("COLONY_TEST FAIL",error);stop(false,error.toString());}
    }
    void server(MinecraftServer server){
        if(finished||server.getPlayerList().getPlayerCount()==0)return;
        try{
            ticks++;
            long realNow=System.nanoTime();
            if(simulationStarted==0){simulationStarted=rateWindowStarted=realNow;rateWindowTick=ticks;}
            if(realNow-rateWindowStarted>=1_000_000_000L){
                evidence.put("measuredTps",(ticks-rateWindowTick)*1_000_000_000.0/(realNow-rateWindowStarted));
                evidence.put("averageTps",(ticks-1)*1_000_000_000.0/Math.max(1,realNow-simulationStarted));
                evidence.put("meanTickMs",server.getAverageTickTimeNanos()/1_000_000.0);
                rateWindowStarted=realNow;rateWindowTick=ticks;
            }
            if(ticks==1){
                var restored=AutonomousVillagesMod.runtime(server);
                restored.villages.values().forEach(v->originalMembers.addAll(v.state.residents));
                boolean threats=Boolean.getBoolean("avillage.test.liveThreats");
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),"gamemode creative VillageProbe");
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),"difficulty "+(threats?"normal":"peaceful"));
                // Spectators may not generate chunks. Observation mode must not prevent the
                // peaceful scene from loading its naturally generated residents.
                var observer=server.getPlayerList().getPlayers().getFirst();observer.getAbilities().flying=true;observer.onUpdateAbilities();
                evidence.put("difficulty",threats?"normal":"peaceful");
                int initialRate=benchmark==null?tickRate:40;
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),"tick rate "+initialRate);
                evidence.put("itemDonations",0);evidence.put("productionFixturesPlaced",0);
                evidence.put("simulationTickRate",initialRate);evidence.put("requestedTps",tickRate);
                evidence.put("fixedDaylight",Boolean.getBoolean("avillage.test.daylight"));
                evidence.put("naturalThreats",threats);
                if(Boolean.getBoolean("avillage.test.daylight")){
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),"time set 1000");
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),"time pause");
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),"weather clear 1000000");
                    evidence.put("fixedClearWeather",true);
                }else server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),"time resume");
            }
            var runtime=AutonomousVillagesMod.runtime(server);
            if(village==null&&ticks>80){
                if(!runtime.villages.isEmpty())village=runtime.villages.values().iterator().next();
                else if(locate==null){
                    locate=server.overworld().findNearestMapStructure(StructureTags.VILLAGE,server.getPlayerList().getPlayers().getFirst().blockPosition(),16,false);
                    if(locate==null)throw new IllegalStateException("No generated village found");
                    locateTick=ticks;
                    int y=server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,locate.getX(),locate.getZ());
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),"tp VillageProbe "+locate.getX()+" "+(y+15)+" "+locate.getZ());
                }else if(ticks-locateTick>200&&ticks%20==0){
                    var area=new net.minecraft.world.phys.AABB(locate).inflate(160,128,160);
                    if(server.overworld().getEntitiesOfClass(Villager.class,area,Villager::isAlive).isEmpty()){
                        if(ticks-locateTick>4000)throw new IllegalStateException("Generated village residents did not load");
                        return;
                    }
                    runtime.claim(server.overworld(),locate,"Observed",48,new UUID(0,0));village=runtime.villages.values().iterator().next();
                }
                if(village!=null){
                    originalMembers.addAll(village.state.residents);evidence.put("initialResidents",village.state.residents.size());evidence.put("initialFood",village.count(Supplies::food));
                    inheritedMiningDeliveries=village.state.verifiedMiningDeliveries;inheritedExpeditionItems=village.state.expeditionDeliveries;
                    evidence.put("inheritedVerifiedMiningDeliveries",inheritedMiningDeliveries);evidence.put("inheritedExpeditionItemsUnloaded",inheritedExpeditionItems);
                }
            }
            if(village==null)return;
            var v=village;
            for(var r:v.workers.values())jobTicks.computeIfAbsent(r.id,id->new LinkedHashMap<>()).merge(r.job==null?"idle":r.job,1L,Long::sum);
            evidence.put("residentJobTicks",jobTicks);
            if(!Boolean.getBoolean("avillage.test.daylight"))for(var r:v.workers.values())
                if(r.mining!=null&&!r.mining.completion().isDone()&&!r.clearingLeaves
                        &&Set.of("wood","stone","ore","sand").contains(r.job)&&observedAtomicTasks.add(r.mining)){
                    var task=r.mining;var target=r.target;long startedClock=v.level.getOverworldClockTime();int role=v.roleIndex(r.id);
                    boolean profession=r.body().getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.JOB_SITE).filter(p->p.dimension().equals(v.level.dimension())).isPresent();
                    task.completion().thenAccept(result->{
                        long endedClock=v.level.getOverworldClockTime();
                        if(!atomic&&task.status()==dev.navigationai.api.MiningStatus.SUCCESS&&Resident.crossedScheduleBoundary(startedClock,endedClock,role,profession)){
                            atomic=true;evidence.put("atomicMiningAcrossSchedule",true);evidence.put("atomicTaskStartClock",startedClock);evidence.put("atomicTaskEndClock",endedClock);evidence.put("atomicTarget",target.toString());
                            LOG.info("COLONY_TEST PASS atomic_mining_across_schedule task={} start={} end={}",target,startedClock,endedClock);
                        }
                    });
                }
            for(var r:v.workers.values())if("harvest".equals(r.job)&&r.mining!=null&&observedHarvests.add(r.mining)){
                var task=r.mining;var target=r.target;
                task.completion().thenAccept(result->{
                    if(task.status()==dev.navigationai.api.MiningStatus.SUCCESS||r.bot()==null)return;
                    var hit=v.level.clip(new ClipContext(r.body().getEyePosition(),net.minecraft.world.phys.Vec3.atCenterOf(target),ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,r.body()));
                    LOG.info("HARVEST_DIAGNOSTIC worker={} result={} pos={} target={} state={} outlineHit={} hitState={}",r.id,result,r.body().position(),target,v.level.getBlockState(target),hit.getBlockPos(),v.level.getBlockState(hit.getBlockPos()));
                });
            }
            long clockInDay=Math.floorMod(v.level.getOverworldClockTime(),24_000);
            if(clockInDay>=13_000&&clockInDay<23_000)nightTicks++;
            if(v.workers.values().stream().anyMatch(r->r.body().isSleeping()))sleepTicks++;
            if(!v.state.residents.containsAll(originalMembers)){
                var lost=new HashSet<>(originalMembers);lost.removeAll(v.state.residents);evidence.put("lostOriginalResidents",lost);stop(false,"resident_lost_during_live_observation");return;
            }
            if(!coverage&&ticks>400){
                var all=server.overworld().getEntitiesOfClass(Villager.class,VillageDiscovery.area(v.state,0),e->e.isAlive());
                if(all.stream().anyMatch(e->!v.state.residents.contains(e.getUUID())))throw new IllegalStateException("Unmanaged resident in complete footprint");
                // Claim can run before every chunk/entity has arrived. Freeze the complete
                // initial cohort once enrollment is verified, so the first later death stops
                // acceptance instead of tracking only the first loaded villager.
                addResidents(v);
                all=server.overworld().getEntitiesOfClass(Villager.class,VillageDiscovery.area(v.state,0),e->e.isAlive());
                originalMembers.addAll(all.stream().map(Villager::getUUID).toList());
                evidence.put("initialResidents",originalMembers.size());
                evidence.put("completeFootprint",v.state.bounds);evidence.put("allLoadedResidentsEnrolled",all.size());coverage=true;
                LOG.info("COLONY_TEST PASS whole_village_enrollment {} residents",all.size());
            }
            var depot=v.state.buildings.stream().filter(b->b.kind.equals("warehouse")).findFirst().orElse(null);
            var outing=v.state.expedition;
            if(outing!=null&&outing.departingTogether&&outing.team.size()>1)evidence.put("partyDepartedTogether",true);
            if(v.state.verifiedMiningDeliveries>0){evidence.put("miningExpeditionsReturnedAndUnloaded",v.state.verifiedMiningDeliveries);evidence.put("expeditionItemsUnloaded",v.state.expeditionDeliveries);evidence.put("verifiedMineralDeposits",v.state.mineralDeposits);}
            evidence.put("newMiningExpeditionsReturnedAndUnloaded",v.state.verifiedMiningDeliveries-inheritedMiningDeliveries);
            evidence.put("newExpeditionItemsUnloaded",v.state.expeditionDeliveries-inheritedExpeditionItems);
            camera=depot==null?v.state.center():BlockPos.of(depot.origin).offset(3,1,3);
            if(!atomic&&miningBlock==null&&ticks>500&&depot!=null&&depot.built(v)>30){
                var worker=v.workers.values().stream().filter(r->r.mining!=null&&!r.mining.completion().isDone()&&"wood".equals(r.job)&&!r.clearingLeaves)
                        .filter(r->{
                            if(Boolean.getBoolean("avillage.test.daylight"))return true;
                            int role=v.roleIndex(r.id),social=9000+(role%3)*1000,professional=2000+(role%8)*600;
                            return clockInDay>=social&&clockInDay<social+20||clockInDay>=11500&&clockInDay<11520
                                    ||r.body().getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.JOB_SITE)
                                        .filter(p->p.dimension().equals(v.level.dimension())).isPresent()&&clockInDay>=professional&&clockInDay<professional+20;
                        }).findFirst().orElse(null);
                if(worker!=null){miningWorker=worker.id;miningBlock=worker.target;miningTestTick=ticks;
                    if(Boolean.getBoolean("avillage.test.daylight"))server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),"time set "+(9001+(v.roleIndex(worker.id)%3)*1000));
                    evidence.put("atomicBoundaryClock",v.level.getOverworldClockTime());
                    LOG.info("COLONY_TEST atomic mining across social boundary {}",miningBlock);
                }
            }
            if(!atomic&&miningBlock!=null&&ticks-miningTestTick>160){
                if(server.overworld().getBlockState(miningBlock).is(BlockTags.LOGS))throw new IllegalStateException("Worker abandoned partly mined log at schedule boundary");
                atomic=true;evidence.put("atomicMiningAcrossSchedule",true);LOG.info("COLONY_TEST PASS atomic_mining_across_schedule");
                if(Boolean.getBoolean("avillage.test.daylight"))server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),"time set 1000");
            }
            if(depot!=null&&depot.completed&&!warehouse){
                warehouse=true;evidence.put("warehouseBlocks",depot.blocks.size());evidence.put("warehouseOrigin",depot.origin);
                LOG.info("COLONY_TEST PASS autonomous_separate_warehouse {} blocks",depot.blocks.size());
                var house=v.state.buildings.stream().filter(b->b.kind.equals("native_house")&&b.completed).findFirst().orElse(null);
                if(house!=null){
                    house.blocks.stream().filter(p->p.block().is(BlockTags.PLANKS)).limit(2).forEach(damage::add);
                    house.blocks.stream().filter(p->p.block().getBlock() instanceof DoorBlock).limit(1).forEach(damage::add);
                    v.state.buildings.stream().filter(b->b.kind.equals("native_house")).flatMap(b->b.blocks.stream()).filter(p->p.block().is(BlockTags.TERRACOTTA)).limit(1).forEach(damage::add);
                    for(var p:damage)server.overworld().destroyBlock(p.position(),false);
                    damagedAt=ticks;evidence.put("damagedHouseBlocks",damage.stream().map(p->p.pos).toList());
                    LOG.info("COLONY_TEST damage applied for repair observation {}",damage.size());
                }
            }
            if(warehouse&&!damage.isEmpty()&&!repair&&damage.stream().allMatch(p->p.matches(v))){repair=true;evidence.put("repairTicks",ticks-damagedAt);evidence.put("repairedBlocks",damage.stream().map(p->Map.of("original",p.block().toString(),"actual",v.level.getBlockState(p.position()).toString())).toList());LOG.info("COLONY_TEST PASS autonomous_existing_house_repair");}
            if(warehouse&&!upgrade&&v.state.buildings.stream().anyMatch(b->b.tier>0&&b.completed)){upgrade=true;evidence.put("houseUpgrade",true);LOG.info("COLONY_TEST PASS autonomous_visible_house_upgrade");}
            if(!repair&&!damage.isEmpty())camera=damage.getFirst().position();
            var improving=v.state.buildings.stream().filter(b->b.tier>0&&!b.completed).findFirst().orElse(null);
            if(improving!=null&&repair)camera=BlockPos.of(improving.origin);
            if(warehouse&&ticks%1800<400&&depot!=null)camera=BlockPos.of(depot.origin).offset(3,1,3);
            var newHome=v.state.buildings.stream().filter(b->b.kind.equals("house")).findFirst().orElse(null);
            BlockPos interiorView=null;
            if(warehouse&&repair&&newHome!=null&&ticks%1800>=1000){
                camera=BlockPos.of(newHome.origin).offset(2,1,2);
                if(newHome.completed&&Boolean.getBoolean("avillage.test.daylight")&&ticks%1800<1400){
                    camera=BlockPos.of(newHome.origin).offset(2,1,3);interiorView=BlockPos.of(newHome.origin).offset(2,1,1);
                }
            }
            if(outing!=null&&ticks%1800>=400&&ticks%1800<1000&&!outing.team.isEmpty()){
                var leader=v.workers.get(outing.team.getFirst());if(leader!=null&&leader.bot()!=null)camera=leader.body().blockPosition();
            }
            boolean grown=v.state.buildings.stream().anyMatch(b->b.kind.equals("house")&&b.completed);
            boolean nightVerified=!Boolean.getBoolean("avillage.test.liveThreats")||nightTicks>=9000&&sleepTicks>0;
            boolean acceptanceComplete=benchmark==null&&warehouse&&repair&&upgrade&&atomic&&v.state.verifiedMiningDeliveries>inheritedMiningDeliveries&&ticks>damagedAt+200&&nightVerified&&(!Boolean.getBoolean("avillage.test.growth")||grown);
            if(runtime.persistenceWriteFailed()){stop(false,"metadata_write_failed");return;}
            String partyPhase=outing==null?"none":outing.phase;
            if(!partyPhase.equals(lastPartyPhase)){lastPartyPhase=partyPhase;LOG.info("COLONY_EVENT ticks={} expedition={}",ticks,partyPhase);}
            if(ticks%20==0)for(var r:v.workers.values())if(!r.lastFailure.isEmpty()&&!r.lastFailure.equals(observedFailures.put(r.id,r.lastFailure)))LOG.info("COLONY_EVENT ticks={} worker={} failure={} pos={}",ticks,r.id,r.lastFailure,r.body().position());
            if((ticks%200==0&&realNow>=nextSnapshotNanos)||acceptanceComplete||ticks==maxTicks||ticks==48000||ticks==96000){
                nextSnapshotNanos=realNow+1_000_000_000L;
                evidence.put("navigationMetrics",dev.navigationai.NavigationAiMod.runtime(server).metrics());
                for(var threat:v.level.getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class,VillageDiscovery.area(v.state,0),m->m.isAlive()))seenThreats.add(threat.getUUID());
                evidence.put("observedNightTicks",nightTicks);evidence.put("observedSleepTicks",sleepTicks);evidence.put("naturalThreatsSeen",seenThreats.size());
                evidence.put("clockTime",v.level.getOverworldClockTime());evidence.put("raining",v.level.isRaining());evidence.put("thundering",v.level.isThundering());evidence.put("darkOutside",v.level.isDarkOutside());
                evidence.put("knownMineWallOres",LiveDiagnostics.mineOres(v));
                evidence.put("completedNewHouses",v.state.buildings.stream().filter(b->b.kind.equals("house")&&b.completed).count());
                evidence.put("newHouseContents",v.state.buildings.stream().filter(b->b.kind.equals("house")&&b.completed)
                        .map(b->Map.of("origin",BlockPos.of(b.origin).toString(),"built",b.built(v),"total",b.blocks.size(),"beds",b.blocks.stream()
                                .filter(p->p.block().getBlock() instanceof BedBlock).map(p->Map.of("pos",p.position().toString(),"actual",v.level.getBlockState(p.position()).toString())).toList())).toList());
                evidence.put("shearsObserved",Boolean.TRUE.equals(evidence.get("shearsObserved"))||v.count(s->s.is(net.minecraft.world.item.Items.SHEARS))>0);
                evidence.put("peakWool",Math.max(((Number)evidence.getOrDefault("peakWool",0)).intValue(),v.count(s->s.is(ItemTags.WOOL))));
                evidence.put("currentStock",Map.of("cobblestone",v.count(s->s.is(net.minecraft.world.item.Items.COBBLESTONE)),"coal",v.count(s->s.is(net.minecraft.world.item.Items.COAL)||s.is(net.minecraft.world.item.Items.CHARCOAL)),"rawIron",v.count(s->s.is(net.minecraft.world.item.Items.RAW_IRON)),"iron",v.count(s->s.is(net.minecraft.world.item.Items.IRON_INGOT))));
                evidence.put("sharedChestContents",v.chests().stream().map(c->Map.of("pos",c.getBlockPos().toString(),"items",Supplies.copy(c).stream().filter(s->!s.isEmpty()).map(Object::toString).toList())).toList());
                observation=v.status();LOG.info("COLONY_PROGRESS ticks={} {}",ticks,observation);
                for(var r:v.workers.values())if(r.bot()!=null)LOG.info("COLONY_WORKER {} {} job={} target={} pos={} bag={} reason={}",r.id,v.role(r.id),r.job,r.target,r.bot().entity().position(),r.bot().inventory().snapshot().slots().stream().filter(s->!s.isEmpty()).toList(),r.lastFailure);
                String json=VillageRuntime.GSON.toJson(Map.of("ticks",ticks,"state",v.state,"status",observation,"evidence",evidence,
                        "tables",v.index.tables.toString(),"trees",v.index.trees.size(),"crops",v.index.crops.size(),"wood",v.count(s->s.is(ItemTags.PLANKS)||s.is(ItemTags.LOGS)),"actors",LiveDiagnostics.workers(v),"projects",v.state.buildings.stream().filter(b->b.built(v)<b.blocks.size()).map(b->Map.of("kind",b.kind,"built",b.built(v),"total",b.blocks.size(),"pendingCuts",b.cuts.stream().filter(c->!Construction.clear(v.level.getBlockState(BlockPos.of(c)))&&java.util.stream.Stream.concat(b.blocks.stream(),b.foundation.stream()).noneMatch(p->p.pos==c&&p.matches(v))).count(),"pendingFoundation",b.foundation.stream().filter(c->!c.matches(v)).count(),"approachReady",Infrastructure.approachReady(v,b),"remaining",b.blocks.stream().filter(p->!p.matches(v)).limit(20).map(p->Map.of("pos",p.position().toString(),"expected",p.block().toString(),"actual",v.level.getBlockState(p.position()).toString())).toList())).toList()));Files.writeString(Path.of("colony-live.json"),json);
            }
            if(ticks%200==0){
                var p=camera;
                String view=interiorView==null?(p.getX()+12)+" "+(p.getY()+9)+" "+(p.getZ()-15):(interiorView.getX()+.5)+" "+interiorView.getY()+" "+(interiorView.getZ()+.5);
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),"tp VillageProbe "+view);
            }
            if(benchmark!=null){if(benchmark.tick(server,ticks,v,evidence))stop(true,"tick_speed_benchmark_completed");}
            else if(acceptanceComplete)stop(true,grown?"warehouse_repair_upgrade_mining_return_and_new_housing_verified":"warehouse_repair_upgrade_mining_return_verified");
            if((ticks==48000||ticks==96000)&&maxTicks>ticks){
                var checkpoint=new LinkedHashMap<String,Object>();checkpoint.put("ticks",ticks);checkpoint.put("complete",warehouse&&repair&&upgrade&&atomic&&grown&&nightVerified&&v.state.verifiedMiningDeliveries>inheritedMiningDeliveries);
                checkpoint.put("status",v.status());checkpoint.put("evidence",evidence);checkpoint.put("warehouse",warehouse);checkpoint.put("repair",repair);checkpoint.put("upgrade",upgrade);checkpoint.put("growth",grown);
                Files.writeString(Path.of(ticks==48000?"colony-checkpoint.json":"colony-checkpoint-96000.json"),VillageRuntime.GSON.toJson(checkpoint));LOG.info("COLONY_TEST CHECKPOINT ticks={} complete={}",ticks,checkpoint.get("complete"));
            }
            if(benchmark==null&&ticks>maxTicks)stop(false,"production_observation_timeout");
        }catch(Throwable error){LOG.error("COLONY_TEST FAIL",error);stop(false,error.toString());}
    }
    void addResidents(Village v){
        int wanted=Math.clamp(Integer.getInteger("avillage.test.residents",0),0,32);
        if(wanted==0)return;
        long adults=v.state.residents.stream().map(v.level::getEntity).filter(e->e instanceof Villager body&&body.isAlive()&&!body.isBaby()).count();
        int added=0;var starts=new ArrayList<BlockPos>();
        for(var door:v.town.doors())for(var dir:Direction.Plane.HORIZONTAL)for(int dy:new int[]{0,-1,1}){
            var feet=door.relative(dir).offset(0,dy,0);if(v.state.contains(feet)&&v.safeFeet(feet))starts.add(feet);
        }
        for(int n=(int)adults;n<wanted;n++){
            if(starts.isEmpty())throw new IllegalStateException("No actual safe footing for added villagers");
            var body=net.minecraft.world.entity.EntityTypes.VILLAGER.create(v.level,net.minecraft.world.entity.EntitySpawnReason.COMMAND);
            if(body==null)throw new IllegalStateException("Cannot create test adult");
            body.setAge(0);body.setPersistenceRequired();
            if(added<2)body.setVillagerData(body.getVillagerData().withProfession(v.level.registryAccess(),net.minecraft.world.entity.npc.villager.VillagerProfession.MASON).withLevel(2));
            body.snapTo(v.feetPosition(starts.get(added%starts.size())),0,0);v.level.addFreshEntity(body);v.state.residents.add(body.getUUID());added++;
        }
        v.sync();v.runtime.save();evidence.put("artificialAdultsAdded",added);evidence.put("requestedAdultMinimum",wanted);
        evidence.put("initialRoles",v.state.residentRoles);
    }
    synchronized void stop(boolean passed,String reason){
        if(finished)return;success=passed;
        try{var result=new LinkedHashMap<String,Object>();result.put("success",passed);result.put("scope",benchmark!=null?"tick_speed_benchmark":"colony");result.put("fullAutonomyAccepted",benchmark==null&&passed);result.put("reason",reason);result.put("ticks",ticks);result.put("screenshots",frames);result.put("evidence",evidence);Files.writeString(Path.of("client-result.json"),VillageRuntime.GSON.toJson(result));}
        catch(Exception error){LOG.error("Result",error);}
        LOG.info("COLONY_TEST DONE success={} reason={}",passed,reason);finished=true;
    }
}
