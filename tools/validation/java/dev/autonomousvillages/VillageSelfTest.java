package dev.autonomousvillages;

import dev.navigationai.api.*;
import java.util.*;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;
import net.minecraft.core.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.*;

/** A separate validation JAR. Never included in the player distribution. */
public final class VillageSelfTest {
    static final net.minecraft.server.level.TicketType PROBE=net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.TICKET_TYPE,
        net.minecraft.resources.Identifier.fromNamespaceAndPath("autonomous_villages_validation","fixture"),new net.minecraft.server.level.TicketType(0,
        net.minecraft.server.level.TicketType.FLAG_LOADING|net.minecraft.server.level.TicketType.FLAG_SIMULATION|net.minecraft.server.level.TicketType.FLAG_KEEP_DIMENSION_ACTIVE));
    static MinecraftServer server;static ServerLevel level;static Village v;static Resident worker;
    static int stage,age,passed;static boolean running;static long since;
    static UUID first,second;static BlockPos origin=new BlockPos(2000,100,2000),crop,rock;
    static Zombie enemy;
    static Resident courier;
    static NavigationTask farmWalk;
    static BlockPos farmStart,securityDoor;
    static float ledgeHealth;
    static boolean ledgeChecked;
    static boolean safetyOnly;
    static boolean longrunOnly;
    static boolean nightOnly;
    static BlockPos nightRoom,nightBed;
    static Optional<GlobalPos> previousHome;
    static boolean nightDoorOpened;
    static long nightThreatSince;
    static NavigationTask cornerWalk;
    static NavigationTask partialWalk;
    static BlockPos partialStart;
    static Resident timberWorker;static Vec3 timberPrevious;static boolean timberNoAi;
    static BlockPos kitChest,kitTable;
    static TownBuilding securityPlan;
    static List<Construction.Step> steps;static int built;
    static final List<BlockPos> growingCrops=new ArrayList<>();static boolean cropGrewWithoutPlayers;
    static final List<BlockPos> canopyLogs=new ArrayList<>();static BlockPos canopyBase,deepIron,atomicBlock;
    static MiningTask atomicTask;static boolean atomicBoundaryApplied;
    static BlockPos returnWaypoint,returnFallback;
    static BlockPos pitCenter,pitProtected;
    static TownBuilding pitHouse;
    static final Map<BlockPos,net.minecraft.world.level.block.state.BlockState> pitSaved=new LinkedHashMap<>();
    static void beginPitRecovery(){
        worker.cancel();v.expeditions.stop();resetBag(worker.bot());give(worker.bot(),Items.STONE_PICKAXE,1);give(worker.bot(),Items.DIRT,8);
        pitCenter=origin.offset(-18,-2,12);pitProtected=pitCenter.east(2).above(2);pitSaved.clear();
        for(BlockPos q:BlockPos.betweenClosed(pitCenter.offset(-3,-1,-3),pitCenter.offset(3,5,3))){
            var p=q.immutable();pitSaved.put(p,level.getBlockState(p));
            boolean inside=Math.abs(p.getX()-pitCenter.getX())<=1&&Math.abs(p.getZ()-pitCenter.getZ())<=1;
            level.setBlock(p,p.getY()<pitCenter.getY()?Blocks.STONE.defaultBlockState():p.getY()<origin.getY()&&!inside?Blocks.DIRT.defaultBlockState():Blocks.AIR.defaultBlockState(),3);
        }
        level.setBlock(pitProtected,Blocks.OAK_PLANKS.defaultBlockState(),3);
        pitHouse=new TownBuilding("native_house",pitProtected);pitHouse.completed=true;pitHouse.add(pitProtected,Blocks.OAK_PLANKS.defaultBlockState());v.state.buildings.add(pitHouse);v.town.invalidate();
        worker.body().snapTo(Vec3.atBottomCenterOf(pitCenter),0,0);worker.body().setDeltaMovement(Vec3.ZERO);worker.lastRecovery=worker.now()-1000;
        require(v.safeFeet(pitCenter),"dug-out pit starts on actual safe footing with no first stair riser");
        require(TerrainRecovery.start(worker,pitCenter.east(6).above(2)),"pit recovery starts with actual carried building material");
        require(worker.bot().inventory().count(Items.DIRT)==7&&v.state.accessBlocks.stream().map(BlockPos::of).anyMatch(p->pitSaved.containsKey(p)&&level.getBlockState(p).is(Blocks.DIRT)),
                "exactly one actual dirt block pays for the missing first stair support");
        pass("dug_out_pit_pays_one_actual_block_for_missing_first_stair_support");stage=53;since=age;
    }
    static void beginReturnFallback(){
        v.expeditions.stop();v.state.expedition=null;worker.cancel();cmd("time set 1000");
        resetBag(worker.bot());give(worker.bot(),Items.STONE_PICKAXE,1);give(worker.bot(),Items.OAK_PLANKS,24);
        returnWaypoint=origin.offset(-20,4,20);returnFallback=returnWaypoint.below(4).south();
        for(int n=0;n<=4;n++){
            BlockPos feet=returnWaypoint.north(4-n).below(4-n);
            for(int y=origin.getY();y<feet.getY();y++)level.setBlock(new BlockPos(feet.getX(),y,feet.getZ()),Blocks.STONE.defaultBlockState(),3);
        }
        worker.body().snapTo(Vec3.atBottomCenterOf(returnFallback),0,0);worker.body().setDeltaMovement(Vec3.ZERO);
        require(v.safeFeet(returnWaypoint)&&v.safeFeet(returnFallback),"real elevated waypoint and lower fallback both have safe physical footing");
        v.expeditions.travel(worker,returnWaypoint);worker.bot().navigator().cancel();
        v.expeditions.travel(worker,returnWaypoint);
        var goal=worker.bot().navigator().goal().orElseThrow();
        require(goal instanceof NavigationGoal.PositionGoal p&&p.position().distanceTo(Vec3.atBottomCenterOf(returnFallback))<.1,
                "after cancelled direct route the real fallback navigation targets the lower stand");
        stage=51;since=age;
    }
    public static void register(){
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((entity,world)->{
            if(running&&stage==33&&entity instanceof ItemEntity item)log("ORE_SPAWN item="+item.getItem()+" pos="+item.position()+" uuid="+item.getUUID());
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_UNLOAD.register((entity,world)->{
            if(running&&stage==33&&entity instanceof ItemEntity item)log("ORE_REMOVE item="+item.getItem()+" pos="+item.position()+" reason="+item.getRemovalReason()+" bag="+worker.bot().inventory().snapshot().slots().stream().filter(s->!s.isEmpty()).toList());
        });
        CommandRegistrationCallback.EVENT.register((dispatcher,access,env)->dispatcher.register(Commands.literal("avtest")
            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(c->{
                server=c.getSource().getServer();level=server.overworld();age=0;stage=0;passed=0;ledgeChecked=false;safetyOnly=false;longrunOnly=false;nightOnly=false;running=true;
                cmd("time set 1000");cmd("time pause");cmd("tick rate 60");setup();log("START");return 1;
            }).then(Commands.literal("night").executes(c->{
                server=c.getSource().getServer();level=server.overworld();age=0;stage=0;passed=0;ledgeChecked=false;safetyOnly=false;longrunOnly=false;nightOnly=true;running=true;
                cmd("time set 1000");cmd("time pause");cmd("tick rate 60");setup();log("NIGHT_START");return 1;
            })).then(Commands.literal("longrun").executes(c->{
                server=c.getSource().getServer();level=server.overworld();age=0;stage=0;passed=0;ledgeChecked=false;safetyOnly=false;longrunOnly=true;running=true;
                cmd("time set 1000");cmd("time pause");cmd("tick rate 60");setup();log("LONGRUN_START");return 1;
            })).then(Commands.literal("safety").executes(c->{
                server=c.getSource().getServer();level=server.overworld();age=0;stage=0;passed=0;ledgeChecked=false;safetyOnly=true;running=true;
                cmd("time set 1000");cmd("time pause");cmd("tick rate 60");setup();log("SAFETY_START");return 1;
            })).then(Commands.literal("restart").executes(c->{
                server=c.getSource().getServer();level=server.overworld();age=0;stage=100;running=true;log("RESTART_START");return 1;
            }))));
        ServerTickEvents.END_SERVER_TICK.register(s->{if(running)try{tick();}catch(Throwable error){log("FAIL stage="+stage+" "+error);error.printStackTrace();running=false;}});
    }
    static void cmd(String command){server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),command);}
    static void log(String text){AutonomousVillagesMod.LOG.info("VILLAGE_TEST {}",text);}
    static void require(boolean ok,String text){if(!ok)throw new AssertionError(text);}
    static void pass(String text){passed++;log("PASS "+text);}
    static void advance(){stage++;since=age;}
    static void resetBag(BotHandle b){b.stopAll();for(int i=0;i<36;i++)b.inventory().extract(i,Integer.MAX_VALUE);}
    static void give(BotHandle b,Item item,int count){require(b.inventory().insert(new ItemStack(item,count)).isEmpty(),"give fits");}
    static void bed(BlockPos p){
        var foot=Blocks.BED.white().defaultBlockState().setValue(BedBlock.FACING,Direction.SOUTH).setValue(BedBlock.PART,BedPart.FOOT);
        level.setBlock(p,foot,18);level.setBlock(p.south(),foot.setValue(BedBlock.PART,BedPart.HEAD),18);
    }
    static Villager spawn(BlockPos p){Villager e=EntityTypes.VILLAGER.create(level,EntitySpawnReason.COMMAND);require(e!=null,"villager create");
        e.setNoAi(true);e.snapTo(Vec3.atBottomCenterOf(p),0,0);level.addFreshEntity(e);return e;}
    static boolean fixtureReady(){
        var tracked=level.getEntitiesOfClass(Villager.class,new AABB(origin).inflate(8),e->e.isAlive());
        return tracked.stream().anyMatch(e->e.getUUID().equals(first))&&tracked.stream().anyMatch(e->e.getUUID().equals(second));
    }
    static void releaseFixture(){
        ((Villager)level.getEntity(first)).setNoAi(false);((Villager)level.getEntity(second)).setNoAi(false);
    }
    static void setup(){
        level.getChunkSource().addTicketWithRadius(PROBE,new net.minecraft.world.level.ChunkPos(origin.getX()>>4,origin.getZ()>>4),4);
        for(int x=-28;x<=28;x++)for(int z=-28;z<=28;z++){
            BlockPos p=origin.offset(x,-1,z);level.setBlock(p,Blocks.GRASS_BLOCK.defaultBlockState(),3);
            for(int y=0;y<=9;y++)level.setBlock(p.above(y+1),Blocks.AIR.defaultBlockState(),3);
        }
        bed(origin.offset(-5,0,0));bed(origin.offset(-2,0,0));
        first=spawn(origin).getUUID();second=spawn(origin.offset(2,0,0)).getUUID();
    }
    static void manual(){if(worker!=null&&(worker.job!=null||stage==5||stage==7))worker.tick();}
    static void repairBeforeUpgrade(){
        Resident worker=v.workers.values().stream().filter(r->v.builder(r.id)).findFirst().orElseThrow();
        Vec3 previousPosition=worker.body().position();worker.cancel();worker.body().snapTo(Vec3.atBottomCenterOf(origin),0,0);worker.body().setDeltaMovement(Vec3.ZERO);
        var repair=new TownBuilding("native_house",origin.offset(3,0,0));repair.completed=true;repair.add(origin.offset(3,0,0),Blocks.OAK_PLANKS.defaultBlockState());
        var upgrade=new TownBuilding("native_house",origin.offset(6,0,0));upgrade.tier=1;upgrade.add(origin.offset(6,0,0),Blocks.COBBLESTONE.defaultBlockState());upgrade.blocks.getFirst().replaceOwned=true;
        level.setBlock(origin.offset(6,0,0),Blocks.OAK_PLANKS.defaultBlockState(),3);v.state.buildings.add(repair);v.state.buildings.add(upgrade);
        resetBag(worker.bot());give(worker.bot(),Items.OAK_PLANKS,8);give(worker.bot(),Items.COBBLESTONE,3);
        var choice=v.town.request(worker,true);
        require(choice==repair.blocks.getFirst(),"urgent plank repair precedes available stone reinforcement; choice="+(choice==null?"none":choice.position())+" ready="+v.ready(repair.blocks.getFirst().position(),worker.id)+" bounds="+VillageRuntime.GSON.toJson(v.state.bounds)+" missing="+v.state.buildings.stream().filter(b->b.completed).flatMap(b->b.blocks.stream()).filter(p->!p.matches(v)).map(p->p.position()+" "+p.block()).toList());
        require(Construction.place(v,worker.bot(),repair.blocks.getFirst().step()),"repair physically placed and paid");
        BlockPos door=origin.offset(3,0,2);repair.add(door,Blocks.OAK_DOOR.defaultBlockState());
        require(v.town.request(worker,true)==null,"missing repair door reaches crafting before lower reinforcement");
        BlockPos table=origin.offset(1,0,2);level.setBlock(table,Blocks.CRAFTING_TABLE.defaultBlockState(),3);v.index.tables.add(table);
        require(TownCrafting.make(worker.bot(),v)&&worker.bot().inventory().count(Items.OAK_DOOR)==3,"actual repair-door recipe runs before optional upgrade");
        require(Construction.place(v,worker.bot(),repair.blocks.getLast().step())&&worker.bot().inventory().count(Items.OAK_DOOR)==2,"real double-height repair door consumes exactly one item");
        pass("urgent_house_repair_and_paid_door_crafting_precede_optional_stone_reinforcement");
        BlockPos depot=origin.offset(1,0,-2);level.setBlock(depot,Blocks.CHEST.defaultBlockState(),3);v.state.storage.add(depot.asLong());
        ((ChestBlockEntity)level.getBlockEntity(depot)).setItem(0,new ItemStack(Items.BREAD,64));worker.body().getInventory().addItem(new ItemStack(Items.BREAD,3));
        boolean noAi=worker.body().isNoAi();worker.body().setNoAi(false);
        resetBag(worker.bot());give(worker.bot(),Items.EGG,1);give(worker.bot(),Items.COBBLESTONE,3);give(worker.bot(),Items.WOODEN_AXE,1);give(worker.bot(),Items.WOODEN_PICKAXE,1);
        worker.cooldown=0;worker.tick();
        require("renovate".equals(worker.job)&&worker.bot().inventory().count(Items.EGG)==1&&((ChestBlockEntity)level.getBlockEntity(depot)).countItem(Items.EGG)==0,
                "one incidental egg must not preempt an available paid building job; job="+worker.job);
        pass("incidental_single_item_does_not_preempt_available_building_work");worker.cancel();
        resetBag(worker.bot());give(worker.bot(),Items.DIRT,64);give(worker.bot(),Items.COBBLESTONE,3);give(worker.bot(),Items.WOODEN_AXE,1);
        give(worker.bot(),Items.WOODEN_PICKAXE,1);
        give(worker.bot(),Items.OAK_LOG,16);give(worker.bot(),Items.CRAFTING_TABLE,1);boolean neededBench=worker.needsWorkbench;worker.needsWorkbench=true;
        worker.cooldown=0;worker.tick();
        require(((ChestBlockEntity)level.getBlockEntity(depot)).countItem(Items.DIRT)==64&&worker.bot().inventory().count(Items.DIRT)==0,
                "excess cargo physically unloaded before another available optional renovation; job="+worker.job);
        require(worker.bot().inventory().count(Items.WOODEN_AXE)==1,"working tool retained while unloading surplus");
        require(worker.bot().inventory().count(Items.OAK_LOG)==16&&worker.bot().inventory().count(Items.CRAFTING_TABLE)==1,
                "builder retains the full64-plank-equivalent timber batch and the workbench needed for its next recipe");
        require(worker.bot().inventory().count(Items.COBBLESTONE)+((ChestBlockEntity)level.getBlockEntity(depot)).countItem(Items.COBBLESTONE)==3,
                "unloading conserves all carried stone");
        pass("resident_unloads_surplus_before_repeating_optional_renovation_and_retains_tools");
        worker.cancel();worker.body().setNoAi(noAi);worker.needsWorkbench=neededBench;v.state.storage.remove(depot.asLong());
        ((ChestBlockEntity)level.getBlockEntity(depot)).clearContent();level.setBlock(depot,Blocks.AIR.defaultBlockState(),18);
        v.state.buildings.remove(repair);v.state.buildings.remove(upgrade);v.index.tables.remove(table);
        for(BlockPos p:List.of(origin.offset(3,0,0),origin.offset(6,0,0),door,door.above(),table))level.setBlock(p,Blocks.AIR.defaultBlockState(),3);
        resetBag(worker.bot());
        worker.body().snapTo(previousPosition,0,0);worker.body().setDeltaMovement(Vec3.ZERO);
    }
    static void builderCraftsBed(){
        Resident builder=v.workers.values().stream().filter(r->v.builder(r.id)&&!v.steward(r.id)).findFirst().orElseThrow();
        var previous=List.copyOf(v.state.buildings);Vec3 position=builder.body().position();builder.cancel();
        builder.body().snapTo(Vec3.atBottomCenterOf(origin),0,0);builder.body().setDeltaMovement(Vec3.ZERO);
        BlockPos table=origin.offset(1,0,-2),foot=origin.offset(3,0,-2);
        level.setBlock(table,Blocks.CRAFTING_TABLE.defaultBlockState(),3);v.index.tables.add(table);
        var house=new TownBuilding("house",foot);house.add(foot,Blocks.BED.white().defaultBlockState().setValue(BedBlock.FACING,Direction.SOUTH));
        v.state.buildings.clear();v.state.buildings.add(house);
        resetBag(builder.bot());give(builder.bot(),Items.WOOL.white(),3);give(builder.bot(),Items.WOOL.black(),2);give(builder.bot(),Items.OAK_PLANKS,3);
        require(TownCrafting.make(builder.bot(),v),"ordinary builder can craft requested bed at a real workbench");
        require(builder.bot().inventory().count(Items.WOOL.white())==0&&builder.bot().inventory().count(Items.WOOL.black())==2
                &&builder.bot().inventory().count(Items.OAK_PLANKS)==0&&builder.bot().inventory().count(Items.BED.white())==1,
                "bed recipe consumes three matching wool and three planks, preserving other colours");
        require(Construction.place(v,builder.bot(),house.blocks.getFirst().step())&&builder.bot().inventory().count(Items.BED.white())==0
                &&level.getBlockState(foot.south()).getBlock() instanceof BedBlock,"builder physically installs and pays both halves of the crafted bed");
        pass("ordinary_builder_crafts_requested_bed_from_matching_wool_and_physically_installs_it");
        v.state.buildings.clear();v.state.buildings.addAll(previous);v.index.tables.remove(table);
        for(BlockPos p:List.of(table,foot,foot.south()))level.setBlock(p,Blocks.AIR.defaultBlockState(),18);
        resetBag(builder.bot());builder.body().snapTo(position,0,0);builder.body().setDeltaMovement(Vec3.ZERO);
    }
    static void sharedChestAccess(){
        var people=v.workers.values().stream().limit(2).toList();var firstWorker=people.get(0);var secondWorker=people.get(1);
        Vec3 a=firstWorker.body().position(),b=secondWorker.body().position();firstWorker.cancel();secondWorker.cancel();resetBag(firstWorker.bot());resetBag(secondWorker.bot());
        BlockPos chest=origin.offset(2,0,4);level.setBlock(chest,Blocks.CHEST.defaultBlockState(),3);v.state.storage.add(chest.asLong());
        firstWorker.body().snapTo(Vec3.atBottomCenterOf(origin.offset(1,0,2)),0,0);secondWorker.body().snapTo(Vec3.atBottomCenterOf(origin.offset(3,0,2)),0,0);
        give(firstWorker.bot(),Items.DIRT,16);give(secondWorker.bot(),Items.GRAVEL,16);
        firstWorker.assign("storage",chest);secondWorker.assign("storage",chest);
        require("storage".equals(firstWorker.job)&&"storage".equals(secondWorker.job),"two actual residents may approach the same shared chest without a journey-long exclusive lease");
        firstWorker.work(firstWorker.bot());secondWorker.work(secondWorker.bot());
        var contents=(ChestBlockEntity)level.getBlockEntity(chest);
        require(contents.countItem(Items.DIRT)==16&&contents.countItem(Items.GRAVEL)==16&&firstWorker.bot().inventory().count(Items.DIRT)==0&&secondWorker.bot().inventory().count(Items.GRAVEL)==0,
                "both nearby residents physically unload into one chest with exact item conservation");
        pass("two_residents_share_physical_chest_without_reserving_it_for_their_entire_journey");
        firstWorker.cancel();secondWorker.cancel();contents.clearContent();v.state.storage.remove(chest.asLong());level.setBlock(chest,Blocks.AIR.defaultBlockState(),18);
        firstWorker.body().snapTo(a,0,0);secondWorker.body().snapTo(b,0,0);firstWorker.body().setDeltaMovement(Vec3.ZERO);secondWorker.body().setDeltaMovement(Vec3.ZERO);
    }
    static void beginNightEntry(){
        worker.cancel();worker.body().setNoAi(false);worker.body().setHealth(20);resetBag(worker.bot());
        previousHome=worker.body().getBrain().getMemory(MemoryModuleType.HOME);
        nightRoom=origin.offset(0,0,-16);nightBed=nightRoom.offset(2,0,6);securityDoor=nightRoom.offset(2,0,0);
        for(int x=0;x<5;x++)for(int z=0;z<9;z++)for(int y=0;y<4;y++)level.setBlock(nightRoom.offset(x,y,z),
                y==3||x==0||x==4||z==0||z==8?Blocks.OAK_PLANKS.defaultBlockState():Blocks.AIR.defaultBlockState(),3);
        var door=Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING,Direction.SOUTH);
        level.setBlock(securityDoor,door,18);level.setBlock(securityDoor.above(),door.setValue(DoorBlock.HALF,net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER),18);
        bed(nightBed);securityPlan=new TownBuilding("native_house",nightRoom);securityPlan.completed=true;
        securityPlan.add(securityDoor,door);securityPlan.add(nightBed,level.getBlockState(nightBed));v.state.buildings.add(securityPlan);v.town.invalidate();
        worker.body().getBrain().setMemory(MemoryModuleType.HOME,GlobalPos.of(level.dimension(),nightBed));
        worker.body().snapTo(Vec3.atBottomCenterOf(nightRoom.offset(2,0,-4)),0,0);worker.body().setDeltaMovement(Vec3.ZERO);
        nightDoorOpened=false;nightThreatSince=0;cmd("time set 14000");worker.assign("home",nightBed);stage=46;since=age;
    }
    static void tick() throws java.io.IOException{
        age++;
        if(stage==100){
            if(age==1){v=AutonomousVillagesMod.runtime(server).get("Test");require(!v.state.active&&!v.ticketed,"paused state initially has no tickets");v.ticket(true);}
            if(age<120)return;
            v=AutonomousVillagesMod.runtime(server).get("Test");
            require(!v.state.active,"paused state survives restart");
            var expected=VillageRuntime.GSON.fromJson(java.nio.file.Files.readString(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("validation-expected-members.json")),UUID[].class);
            require(v.state.residents.equals(new LinkedHashSet<>(Arrays.asList(expected))),"complete membership survives restart");
            long legacyMine=Long.parseLong(java.nio.file.Files.readString(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("validation-legacy-mine.txt")));
            require(v.state.mineEntrance==null&&v.state.worksites.contains(legacyMine)&&v.state.mines.stream().anyMatch(m->m.entrance==legacyMine&&m.length==8),
                    "legacy out-of-footprint local mine migrated to a retained expedition worksite on real server restart");
            pass("legacy_radius_mine_recovered_on_restart_without_losing_worksite_or_residents");
            long diamonds=0;
            for(UUID id:v.state.residents){var b=v.runtime.bots.find(id).orElseThrow();require(b.kind()==BotKind.VILLAGER,"native on restart");diamonds+=b.inventory().count(Items.DIAMOND);}
            require(diamonds==17,"backpack exactly once after restart: "+diamonds);
            require(server.getPlayerList().getPlayerCount()==0,"no fake players after restart");
            pass("restart_metadata_native_backpack");
            var outlying=v.state.residentPositions.entrySet().stream().filter(entry->!v.state.contains(BlockPos.of(entry.getValue()))).findFirst().orElseThrow();
            require(level.getEntity(outlying.getKey()) instanceof Villager resident&&resident.blockPosition().equals(BlockPos.of(outlying.getValue())),"outlying native body loaded at persisted location");
            pass("traveller_outside_village_restored_with_own_chunk_ticket");
            v.ticket(false);
            v.runtime.change("Test","resume");require(v.ticketed&&v.state.active,"resume reacquires ticket");
            v.runtime.change("Test","release");require(!v.ticketed,"release tickets");
            require(v.state.residents.stream().allMatch(id->level.getEntity(id) instanceof Villager e&&e.isAlive()),"release keeps villagers");
            pass("release_keeps_bodies");
            level.getChunkSource().removeTicketWithRadius(PROBE,new net.minecraft.world.level.ChunkPos(v.state.x>>4,v.state.z>>4),4);
            Village auto=v.runtime.get("Auto");
            require(auto.state.active&&auto.ticketed&&auto.chests().size()>0,"active village and storage resume after restart");
            require(auto.state.residents.stream().allMatch(id->level.getEntity(id) instanceof Villager e&&e.isAlive()),"active native bodies restored");
            pass("active_village_resumes_after_restart");v.runtime.change("Auto","release");require(v.runtime.villages.isEmpty(),"released all villages");
            log("RESTART_DONE passed="+passed);running=false;return;
        }
        if(age-since>(stage==9?12000:6000))throw new AssertionError("timeout stage="+stage+" worker="+(worker==null?"none":worker.status+" job="+worker.job+" target="+worker.target+" pos="+worker.body().position()));
        if(stage==0){
            if(age<60||!fixtureReady())return;
            releaseFixture();
            require(level.getBlockState(origin.offset(-5,0,0)).is(Blocks.BED.white()),"bed exists before claim");
            level.getPoiManager().ensureLoadedAndValid(level,origin,24);
            var source=server.createCommandSourceStack().withPosition(Vec3.atCenterOf(origin));
            server.getCommands().performPrefixedCommand(source,"avillage claim Test");
            v=AutonomousVillagesMod.runtime(server).get("Test");
            level.getChunkSource().removeTicketWithRadius(PROBE,new net.minecraft.world.level.ChunkPos(origin.getX()>>4,origin.getZ()>>4),4);
            require(v.state.residents.containsAll(Set.of(first,second)),"claim real residents");require(v.ticketed,"village ticket");
            require(server.getPlayerList().getPlayerCount()==0,"no players registered");
            try{v.runtime.claim(level,origin,"Overlap",24,new UUID(0,0));throw new AssertionError("overlap allowed");}catch(IllegalArgumentException expected){}
            try{v.runtime.claim(level,origin.offset(1000,0,1000),"Empty",24,new UUID(0,0));throw new AssertionError("empty allowed");}catch(IllegalArgumentException expected){}
            pass("command_claim_real_village_and_rejections");advance();return;
        }
        if(stage==1){
            if(age-since<(ledgeChecked?120:80))return;v.sync();v.state.active=false;
            worker=v.workers.get(first);require(worker!=null,"native attached");worker.cancel();
            for(Resident other:v.workers.values())if(other!=worker){other.cancel();other.body().setNoAi(true);other.body().snapTo(Vec3.atBottomCenterOf(origin.offset(-20,0,-10)),0,0);}
            worker.body().snapTo(Vec3.atBottomCenterOf(origin),0,0);
            if(nightOnly){beginNightEntry();return;}
            if(longrunOnly){LongRunRegression.run(v,worker,origin);beginReturnFallback();return;}
            if(!ledgeChecked){
                for(int x=18;x<=25;x++)for(int z=18;z<=25;z++)for(int y=-30;y<0;y++)level.setBlock(origin.offset(x,y,z),Blocks.AIR.defaultBlockState(),3);
                worker.body().snapTo(Vec3.atBottomCenterOf(origin.offset(17,0,17)),0,0);worker.body().setDeltaMovement(Vec3.ZERO);
                worker.bot().activities().run(List.of(new BotActivity.Wait(200)));ledgeHealth=worker.body().getHealth();stage=37;since=age;return;
            }
            BotHandle b=worker.bot();require(b.entity()==level.getEntity(first)&&b.kind()==BotKind.VILLAGER,"identity");
            resetBag(b);give(b,Items.OAK_LOG,1);
            require(VillageWorkshop.craft(b,v,new String[]{"M"},s->s.is(Items.OAK_LOG),s->s.is(Items.OAK_PLANKS)),"loaded plank recipe");
            require(b.inventory().count(Items.OAK_LOG)==0&&b.inventory().count(Items.OAK_PLANKS)==4,"craft material conservation");
            var before=b.inventory().snapshot().slots();
            require(!VillageWorkshop.craft(b,v,Items.DIAMOND,new String[]{"P"},s->true),"wrong output rejected");
            require(Supplies.matches(before,b.inventory().snapshot().slots()),"failed craft atomic");
            pass("native_identity_recipe_material_conservation");
            BlockPos chestPos=origin.offset(0,0,4);level.setBlock(chestPos,Blocks.CHEST.defaultBlockState(),3);
            v.runtime.storage("Test",chestPos,server.createCommandSourceStack());
            ChestBlockEntity chest=(ChestBlockEntity)level.getBlockEntity(chestPos);
            require(Supplies.transfer(b,chest,true,s->s.is(Items.OAK_PLANKS),4)==4,"deposit4");
            require(b.inventory().count(Items.OAK_PLANKS)==0&&chest.countItem(Items.OAK_PLANKS)==4,"deposit conservation");
            require(Supplies.transfer(b,chest,false,s->s.is(Items.OAK_PLANKS),3)==3,"withdraw3");
            require(b.inventory().count(Items.OAK_PLANKS)==3&&chest.countItem(Items.OAK_PLANKS)==1,"withdraw conservation");
            var acaciaPlan=new Construction.Step(origin,Blocks.ACACIA_PLANKS.defaultBlockState(),Items.ACACIA_PLANKS);
            require(Construction.available(b,acaciaPlan)!=null&&Construction.matches(acaciaPlan,Blocks.OAK_PLANKS.defaultBlockState()),"non-oak native repair accepts real available wood variant");
            for(var depot:v.chests())for(int i=0;i<depot.getContainerSize();i++)depot.setItem(i,new ItemStack(Items.COBBLESTONE,64));
            require(v.needsChest(),"full depot requests expansion");
            require(Supplies.transfer(b,chest,true,s->true,3)==0&&b.inventory().count(Items.OAK_PLANKS)==3,"full chest atomic");
            chest.clearContent();require(!v.needsChest(),"empty depot needs no duplicate chest");pass("shared_storage_conservation_and_full_capacity");
            BlockPos wall=origin.offset(0,0,3);level.setBlock(wall,Blocks.OAK_PLANKS.defaultBlockState(),3);level.setBlock(wall.above(),Blocks.OAK_PLANKS.defaultBlockState(),3);
            worker.body().snapTo(Vec3.atBottomCenterOf(origin.offset(0,0,2)),0,0);give(b,Items.GRAVEL,2);
            require(worker.depositOrFetch(b,false)&&"storage".equals(worker.job),"near but occluded chest requires a physical approach");
            require(chest.countItem(Items.GRAVEL)==0,"no transfer through wall");
            worker.cancel();worker.body().snapTo(Vec3.atBottomCenterOf(origin),0,0);level.setBlock(wall,Blocks.AIR.defaultBlockState(),3);level.setBlock(wall.above(),Blocks.AIR.defaultBlockState(),3);
            pass("occluded_nearby_chest_requests_a_visible_physical_approach");
            var masonryPlan=new Construction.Step(origin,Blocks.DYED_TERRACOTTA.white().defaultBlockState(),Items.DYED_TERRACOTTA.white());
            give(b,Items.COBBLESTONE,1);require(Construction.available(b,masonryPlan).item()==Items.COBBLESTONE&&Construction.matches(masonryPlan,Blocks.COBBLESTONE.defaultBlockState()),"missing plaster can be repaired with paid stone");
            give(b,Items.DYED_TERRACOTTA.white(),1);require(Construction.available(b,masonryPlan).item()==Items.DYED_TERRACOTTA.white(),"original repair material preferred when available");pass("structural_repair_material_fallback_and_original_preference");
            var worn=new ItemStack(Items.WOODEN_AXE);worn.setDamageValue(worn.getMaxDamage()-3);var used=new ItemStack(Items.WOODEN_AXE);used.setDamageValue(used.getMaxDamage()-9);
            b.inventory().insert(worn);b.inventory().insert(used);require(VillageWorkshop.make(b,v,false),"actual registered tool repair recipe");
            require(b.inventory().count(Items.WOODEN_AXE)==1&&b.inventory().snapshot().slots().stream().anyMatch(s->s.is(Items.WOODEN_AXE)&&s.getMaxDamage()-s.getDamageValue()>9),"two worn tools become one stronger paid tool");pass("worn_tools_combined_with_registered_recipe_without_duplication");
            resetBag(b);give(b,Items.WHEAT_SEEDS,2);
            crop=origin.offset(7,0,0);level.setBlock(crop.below(),Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE,7),3);
            level.setBlock(crop,Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,7),3);
            v.index.crops.add(crop);v.index.soil.add(crop);worker.assign("harvest",crop);advance();return;
        }
        if(stage==2){
            manual();
            if(worker.job!=null)return;
            require(level.getBlockState(crop).is(Blocks.WHEAT),"replanted crop");
            require(level.getBlockState(crop).getValue(CropBlock.AGE)<7,"young crop: "+worker.status+" at "+worker.body().position());
            require(worker.bot().inventory().count(Items.WHEAT)>0,"actual wheat pickup: pos="+worker.body().position()+" native="+worker.body().getInventory()+" bag="+worker.bot().inventory().snapshot().slots()+" drops="+level.getEntitiesOfClass(ItemEntity.class,new AABB(crop).inflate(4)).stream().map(e->e.getItem()+" at "+e.position()).toList());
            pass("walk_harvest_pickup_and_replant");
            resetBag(worker.bot());give(worker.bot(),Items.WOODEN_PICKAXE,1);
            rock=origin.offset(10,0,3);level.setBlock(rock,Blocks.STONE.defaultBlockState(),3);
            worker.assign("stone",rock);advance();return;
        }
        if(stage==3){
            manual();if(worker.job!=null)return;
            require(level.getBlockState(rock).isAir()&&worker.bot().inventory().count(Items.COBBLESTONE)>0,"mining real drops: "+worker.status+" block="+level.getBlockState(rock)+" bag="+worker.bot().inventory().count(Items.COBBLESTONE)+" pos="+worker.body().position());
            require(worker.bot().inventory().snapshot().slots().stream().anyMatch(s->s.is(Items.WOODEN_PICKAXE)&&s.getDamageValue()>0),"tool wears");
            pass("walk_mine_collect_and_tool_wear");
            canopyBase=origin.offset(-12,0,-12);canopyLogs.clear();
            for(int y=0;y<9;y++){BlockPos p=canopyBase.above(y);level.setBlock(p,Blocks.OAK_LOG.defaultBlockState(),3);canopyLogs.add(p);}
            for(int x=1;x<=2;x++){BlockPos p=canopyBase.offset(x,8,0);level.setBlock(p,Blocks.OAK_LOG.defaultBlockState(),3);canopyLogs.add(p);}
            for(int x=-2;x<=3;x++)for(int z=-2;z<=2;z++)for(int y=6;y<=9;y++){
                BlockPos p=canopyBase.offset(x,y,z);if(!level.getBlockState(p).is(Blocks.OAK_LOG))level.setBlock(p,Blocks.OAK_LEAVES.defaultBlockState(),3);
            }
            timberWorker=v.workers.values().stream().filter(r->v.builder(r.id)).findFirst().orElseThrow();
            timberPrevious=timberWorker.body().position();timberNoAi=timberWorker.body().isNoAi();timberWorker.cancel();resetBag(timberWorker.bot());
            timberWorker.body().setNoAi(false);timberWorker.body().snapTo(Vec3.atBottomCenterOf(canopyBase.east(2)),0,0);timberWorker.body().setDeltaMovement(Vec3.ZERO);
            timberWorker.assign("wood",canopyBase);stage=44;since=age;return;
        }
        if(stage==44){
            timberWorker.tick();if(timberWorker.job!=null)return;
            require(timberWorker.bot().inventory().count(Items.OAK_LOG)>=4&&Supplies.find(timberWorker.bot(),s->s.is(net.minecraft.tags.ItemTags.AXES))<0,
                    "empty-handed builder returns an initial four real logs before attempting inaccessible high branches");
            require(canopyLogs.stream().anyMatch(p->level.getBlockState(p).is(Blocks.OAK_LOG)),"unfinished high tree stays recorded for equipped work");
            pass("empty_handed_builder_finishes_initial_logs_and_returns_for_tools_before_large_timber_batch");
            timberWorker.cancel();resetBag(timberWorker.bot());timberWorker.body().setNoAi(timberNoAi);timberWorker.body().snapTo(timberPrevious,0,0);timberWorker.body().setDeltaMovement(Vec3.ZERO);
            timberWorker=v.workers.values().stream().filter(r->!v.builder(r.id)).findFirst().orElseThrow();
            timberPrevious=timberWorker.body().position();timberNoAi=timberWorker.body().isNoAi();timberWorker.cancel();resetBag(timberWorker.bot());
            timberWorker.body().setNoAi(false);timberWorker.body().snapTo(Vec3.atBottomCenterOf(origin),0,0);timberWorker.body().setDeltaMovement(Vec3.ZERO);timberWorker.cooldown=0;
            kitChest=origin.offset(2,0,-1);kitTable=origin.offset(-1,0,-1);
            level.setBlock(kitChest,Blocks.CHEST.defaultBlockState(),3);v.state.storage.add(kitChest.asLong());
            level.setBlock(kitTable,Blocks.CRAFTING_TABLE.defaultBlockState(),3);v.index.tables.add(kitTable);
            give(timberWorker.bot(),Items.OAK_LOG,4);stage=45;since=age;return;
        }
        if(stage==45){
            timberWorker.tick();var chest=(ChestBlockEntity)level.getBlockEntity(kitChest);
            if(age%120==0)log("KIT_PROGRESS role="+v.roleIndex(timberWorker.id)+" job="+timberWorker.job+" status="+timberWorker.status+" pos="+timberWorker.body().position()+" nearTable="+VillageWorkshop.nearTable(timberWorker.bot(),v,kitTable)+" needsBench="+timberWorker.needsWorkbench+" tableReady="+v.ready(kitTable,timberWorker.id)+" bag="+timberWorker.bot().inventory().snapshot().slots().stream().filter(s->!s.isEmpty()).toList());
            require(chest.countItem(Items.OAK_LOG)==0&&chest.countItem(Items.OAK_PLANKS)==0,"non-builder must use initial wood for basic tools before surplus hauling; role="+v.roleIndex(timberWorker.id)+" job="+timberWorker.job+" bag="+timberWorker.bot().inventory().snapshot().slots()+" chest="+Supplies.copy(chest));
            if(Supplies.find(timberWorker.bot(),s->s.is(net.minecraft.tags.ItemTags.PICKAXES))<0||Supplies.find(timberWorker.bot(),s->s.is(net.minecraft.tags.ItemTags.AXES))<0)return;
            pass("non_builder_crafts_basic_tools_from_first_logs_before_depositing_them");
            timberWorker.cancel();resetBag(timberWorker.bot());timberWorker.body().setNoAi(timberNoAi);timberWorker.body().snapTo(timberPrevious,0,0);timberWorker.body().setDeltaMovement(Vec3.ZERO);
            chest.clearContent();v.state.storage.remove(kitChest.asLong());v.index.tables.remove(kitTable);level.setBlock(kitChest,Blocks.AIR.defaultBlockState(),18);level.setBlock(kitTable,Blocks.AIR.defaultBlockState(),18);
            resetBag(worker.bot());give(worker.bot(),Items.WOODEN_AXE,1);give(worker.bot(),Items.OAK_PLANKS,24);
            worker.assign("wood",canopyBase);stage=31;since=age;return;
        }
        if(stage==31){
            if(age%400==0)log("CANOPY target="+worker.target+" pos="+worker.body().position()+" job="+worker.job+" nav="+(worker.navigation==null?"none":worker.navigation.status())+" approach="+worker.lastApproach+" retries="+worker.triedApproaches+" bag="+worker.bot().inventory().snapshot().slots().stream().filter(s->!s.isEmpty()).toList()+" scaffolds="+v.state.accessBlocks.stream().map(BlockPos::of).toList()+" failure="+worker.lastFailure);
            manual();if(worker.job!=null)return;
            if(canopyLogs.stream().anyMatch(p->level.getBlockState(p).is(Blocks.OAK_LOG))){worker.assign("wood",canopyBase);return;}
            require(worker.body().isAlive()&&worker.bot().inventory().count(Items.OAK_LOG)>=4,"canopy actual drops and survival");
            require(worker.bot().inventory().count(Items.OAK_PLANKS)<24&&!v.state.accessBlocks.isEmpty(),"high-tree access consumes real scaffold planks");
            pass("complete_high_tree_branches_with_paid_scaffold_and_leaf_clearance");
            resetBag(worker.bot());give(worker.bot(),Items.WOODEN_PICKAXE,1);
            BlockPos entrance=origin.offset(14,0,-8);
            // Back the revealed ore with solid rock. A one-block shell lets random vanilla
            // item motion eject drops into the natural ravine below the artificial fixture.
            for(int x=4;x<=15;x++)for(int z=-12;z<=-5;z++)for(int y=90;y<=98;y++)level.setBlock(new BlockPos(origin.getX()+x,y,origin.getZ()+z),Blocks.STONE.defaultBlockState(),3);
            v.state.mineEntrance=entrance.asLong();v.state.mineDirection=Direction.WEST.get2DDataValue();v.state.mineLength=0;
            worker.assign("mine",entrance);stage=30;since=age;return;
        }
        if(stage==30){
            manual();if(worker.job!=null)return;
            require(v.state.mineLength==8,"bounded mine completes: "+worker.lastFailure);
            require(worker.body().blockPosition().distSqr(BlockPos.of(v.state.mineEntrance))<9,"miner returns to entrance");
            require(worker.bot().inventory().count(Items.COBBLESTONE)>1,"mine yields actual stone");
            pass("bounded_staircase_mine_collection_and_return");
            resetBag(worker.bot());give(worker.bot(),Items.STONE_PICKAXE,1);
            var deeper=new ExpeditionState();deeper.purpose="mine";deeper.phase="work";deeper.target=v.state.mineEntrance;deeper.direction=Direction.WEST.get2DDataValue();deeper.completedSegments=8;deeper.team.add(worker.id);
            deeper.length=v.expeditions.mineLength(deeper);require(deeper.length==32,"actual fresh stone pick and unmet iron demand extend the existing8segment mine to32");
            var deepShaft=new ExcavationRequest(BlockPos.of(deeper.target),Direction.WEST,ExcavationMode.STAIRCASE_DOWN,deeper.length);
            var prefix=new HashSet<BlockPos>();for(int n=0;n<=8;n++)prefix.addAll(deepShaft.clearanceAt(n));
            for(int n=9;n<=deeper.length;n++)for(BlockPos cell:deepShaft.clearanceAt(n)){
                level.setBlock(cell,Blocks.STONE.defaultBlockState(),3);
                for(Direction side:Direction.values())if(!prefix.contains(cell.relative(side)))level.setBlock(cell.relative(side),Blocks.STONE.defaultBlockState(),3);
            }
            deepIron=deepShaft.positionAt(24).above().north();level.setBlock(deepIron,Blocks.IRON_ORE.defaultBlockState(),3);
            deeper.started=deeper.phaseSince=level.getGameTime();v.state.expedition=deeper;stage=49;since=age;return;
        }
        if(stage==49){
            v.expeditions.tick();worker.tick();
            if(age%300==0)log("DEEP_MINE phase="+(v.state.expedition==null?"none":v.state.expedition.phase)+" pos="+worker.body().position()+" job="+worker.job+" iron="+worker.bot().inventory().count(Items.RAW_IRON));
            require(v.state.expedition!=null,"equipped mine fixture remains active until actual deep ore pickup");
            if(!v.state.expedition.dug||worker.job!=null||worker.bot().inventory().count(Items.RAW_IRON)==0)return;
            require(v.state.mines.stream().anyMatch(m->m.entrance==v.state.mineEntrance&&m.length==32&&!m.blocked),"real excavation resumed beyond the8segment prefix and recorded32 completed segments");
            pass("equipped_early_mine_resumes_real_prefix_to32segments_with_actual_pick_wear");
            require(level.getBlockState(deepIron).isAir()&&worker.bot().inventory().snapshot().slots().stream().anyMatch(s->s.is(Items.STONE_PICKAXE)&&s.getDamageValue()>24),"deep iron is physically mined after paid stone excavation, not inserted by the observer");
            pass("extended_mine_reveals_and_collects_actual_deep_iron_drop");
            v.expeditions.stop();v.state.expedition=null;worker.cancel();worker.body().snapTo(Vec3.atBottomCenterOf(BlockPos.of(v.state.mineEntrance)),0,0);worker.body().setDeltaMovement(Vec3.ZERO);
            resetBag(worker.bot());give(worker.bot(),Items.STONE_PICKAXE,1);
            var shaft=new dev.navigationai.api.ExcavationRequest(BlockPos.of(v.state.mineEntrance),Direction.WEST,dev.navigationai.api.ExcavationMode.STAIRCASE_DOWN,8);
            BlockPos iron=shaft.positionAt(4).above().north(),coal=shaft.positionAt(6).above().north();
            level.setBlock(iron,Blocks.IRON_ORE.defaultBlockState(),3);level.setBlock(coal,Blocks.COAL_ORE.defaultBlockState(),3);
            var outing=new ExpeditionState();outing.purpose="mine";outing.phase="work";outing.target=v.state.mineEntrance;outing.direction=Direction.WEST.get2DDataValue();outing.length=8;
            outing.dug=true;outing.workedSegments=8;outing.oreSince=outing.started=outing.phaseSince=level.getGameTime();outing.team.add(worker.id);outing.oreTargets.add(iron.asLong());outing.oreTargets.add(coal.asLong());v.state.expedition=outing;
            stage=33;since=age;return;
        }
        if(stage==33){
            v.expeditions.tick();worker.tick();
            if(worker.mining!=null&&worker.mining.status()==MiningStatus.RUNNING){
                try{
                    var controller=((dev.navigationai.runtime.VillagerAccess)worker.body()).navigationAiController();var field=controller.getClass().getDeclaredField("context");field.setAccessible(true);
                    var context=(net.minecraft.server.level.ServerPlayer)field.get(controller);var state=level.getBlockState(worker.target);
                    log("ORE_ACTION target="+worker.target+" bodyTool="+worker.body().getMainHandItem()+" contextTool="+context.getMainHandItem()+" correct="+context.hasCorrectToolForDrops(state)+" mode="+context.gameMode.getGameModeForPlayer()+" creative="+context.getAbilities().instabuild+" selected="+context.getInventory().getSelectedSlot());
                }catch(ReflectiveOperationException error){throw new IllegalStateException(error);}
            }
            if((age-since)%200==0)log("ORE_PICKUP job="+worker.job+" pos="+worker.body().position()+" bag="+worker.bot().inventory().snapshot().slots().stream().filter(s->!s.isEmpty()).toList()+" failure="+worker.lastFailure+" approach="+worker.lastApproach+" drops="+level.getEntitiesOfClass(ItemEntity.class,new AABB(BlockPos.of(v.state.mineEntrance)).inflate(16)).stream().map(item->item.getItem()+" at "+item.position()).toList());
            require(v.state.expedition!=null&&v.state.expedition.phase.equals("work"),"miner must collect both real ore drops before ending fixture: "+worker.lastFailure);
            if(worker.job!=null||worker.bot().inventory().count(Items.RAW_IRON)==0||worker.bot().inventory().count(Items.COAL)==0)return;
            require(v.state.expedition!=null&&v.state.expedition.oreCollected>=2,"ore counted only after actual mining");
            pass("mine_expedition_collects_revealed_iron_and_coal_with_real_drops");
            v.expeditions.stop();v.state.expedition=null;worker.cancel();resetBag(worker.bot());give(worker.bot(),Items.WOODEN_PICKAXE,1);
            atomicBlock=origin.offset(7,0,-1);level.setBlock(atomicBlock,Blocks.STONE.defaultBlockState(),3);
            worker.body().snapTo(Vec3.atBottomCenterOf(atomicBlock.west(2)),0,0);worker.body().setDeltaMovement(Vec3.ZERO);
            cmd("time set "+(9000+(v.roleIndex(worker.id)%3)*1000-6));worker.assign("stone",atomicBlock);worker.work(worker.bot());atomicTask=worker.mining;
            require(atomicTask!=null,"actual stone mining started before schedule boundary");atomicBoundaryApplied=false;stage=50;since=age;return;
        }
        if(stage==50){
            if(!atomicBoundaryApplied&&age-since>=5){
                require(atomicTask.status()==MiningStatus.RUNNING&&level.getBlockState(atomicBlock).is(Blocks.STONE),"real block remains partly mined before controlled fixture advances across schedule boundary");
                cmd("time set "+(9000+(v.roleIndex(worker.id)%3)*1000+1));atomicBoundaryApplied=true;
            }
            worker.tick();
            if(!atomicBoundaryApplied||worker.job!=null||worker.bot().inventory().count(Items.COBBLESTONE)==0)return;
            require(atomicTask.status()==MiningStatus.SUCCESS&&level.getBlockState(atomicBlock).isAir(),"real block and drop complete across schedule pause, without cancelling the accumulated mining damage");
            pass("partly_mined_block_and_actual_drop_complete_across_schedule_pause");cmd("time set 1000");
            v.expeditions.stop();v.state.expedition=null;worker.cancel();resetBag(worker.bot());give(worker.bot(),Items.STONE_PICKAXE,1);
            BlockPos trap=origin.offset(-20,0,16);
            for(int x=0;x<=8;x++)for(int z=-2;z<=2;z++)for(int y=0;y<=5;y++)level.setBlock(trap.offset(x,y,z),Blocks.STONE.defaultBlockState(),3);
            for(int y=0;y<=1;y++)level.setBlock(trap.offset(0,y,0),Blocks.AIR.defaultBlockState(),3);
            worker.body().snapTo(Vec3.atBottomCenterOf(trap),0,0);
            BlockPos exit=trap.offset(6,6,0);worker.assign("escape",exit);
            worker.excavation=worker.bot().miner().excavate(new dev.navigationai.api.ExcavationRequest(trap,Direction.EAST,dev.navigationai.api.ExcavationMode.STAIRCASE_UP,6),new dev.navigationai.api.ExcavationPolicy(32,1800,4,true,false));
            var returning=new ExpeditionState();returning.purpose="wool";returning.phase="return";returning.team.add(worker.id);returning.target=trap.asLong();
            returning.started=returning.phaseSince=level.getGameTime();v.state.expedition=returning;
            var drop=new ItemEntity(level,trap.getX()+.5,trap.getY(),trap.getZ()+.5,new ItemStack(Items.WOOL.white()));drop.setNoPickUpDelay();level.addFreshEntity(drop);
            stage=32;since=age;return;
        }
        if(stage==32){
            manual();if(worker.job!=null)return;
            require(worker.body().isAlive()&&worker.body().getY()>=origin.getY()+5,"actual escape from two-block cave: "+worker.lastFailure+" pos="+worker.body().position());
            require(worker.bot().inventory().snapshot().slots().stream().anyMatch(s->s.is(Items.STONE_PICKAXE)&&s.getDamageValue()>0),"exit mined with actual tool wear");
            pass("dig_visible_clearance_before_ascending_out_of_two_block_cave");
            v.expeditions.stop();v.state.expedition=null;
            worker.cancel();farmStart=origin.offset(12,0,16);
            for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++){
                BlockPos p=farmStart.offset(x,0,z);level.setBlock(p.below(),Blocks.FARMLAND.defaultBlockState(),3);level.setBlock(p,Blocks.WHEAT.defaultBlockState(),3);
            }
            worker.body().snapTo(new Vec3(farmStart.getX()+.5,farmStart.getY()-1.0/16,farmStart.getZ()+.5),0,0);
            farmWalk=worker.bot().navigator().goTo(new NavigationGoal.PositionGoal(Vec3.atBottomCenterOf(farmStart.east(6)),.35),Resident.TRAVEL);
            stage=35;since=age;return;
        }
        if(stage==35){
            if(!farmWalk.completion().isDone())return;
            require(farmWalk.status()==NavigationStatus.SUCCESS,"walk off farmland at actual15/16 support height: "+farmWalk.completion().join());
            for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)require(level.getBlockState(farmStart.offset(x,0,z).below()).is(Blocks.FARMLAND)&&level.getBlockState(farmStart.offset(x,0,z)).is(Blocks.WHEAT),"route preserves crops and farmland");
            pass("navigation_leaves_farmland_without_breaking_crops");
            worker.body().setNoAi(true);securityDoor=origin.offset(17,0,17);
            var state=Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.OPEN,true);
            level.setBlock(securityDoor,state,3);level.setBlock(securityDoor.above(),state.setValue(DoorBlock.HALF,net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER),3);
            securityPlan=new TownBuilding("house",securityDoor);securityPlan.add(securityDoor,state);v.state.buildings.add(securityPlan);cmd("time set 14000");
            stage=36;since=age;return;
        }
        if(stage==36){
            worker.secureDoors(worker.bot());if(age-since<25)return;
            require(!level.getBlockState(securityDoor).getValue(DoorBlock.OPEN),"nearby resident closes unoccupied village doorway at night");
            pass("resident_physically_closes_nearby_door_at_night");
            level.setBlock(securityDoor,Blocks.AIR.defaultBlockState(),3);level.setBlock(securityDoor.above(),Blocks.AIR.defaultBlockState(),3);v.state.buildings.remove(securityPlan);cmd("time set 1000");
            beginNightEntry();return;
        }
        if(stage==46){
            nightDoorOpened|=level.getBlockState(securityDoor).getValue(DoorBlock.OPEN);
            worker.tick();
            if(age%5==0&&age-since<120)log("DOOR_PROGRESS pos="+worker.body().position()+" target="+worker.target+" nav="+(worker.navigation==null?"none":worker.navigation.status())+" sleep="+worker.body().isSleeping()+" open="+level.getBlockState(securityDoor).getValue(DoorBlock.OPEN)+" known="+v.town.doors().contains(securityDoor)+" distance="+worker.body().getEyePosition().distanceTo(Vec3.atCenterOf(securityDoor)));
            if(age-since<160||worker.body().getZ()<nightRoom.getZ()+4)return;
            require(nightDoorOpened&&!level.getBlockState(securityDoor).getValue(DoorBlock.OPEN)&&!level.getBlockState(securityDoor.above()).getValue(DoorBlock.OPEN),
                    "resident closes both door halves behind their actual walk before reaching a bed6blocks into the room; pos="+worker.body().position());
            if(nightThreatSince==0){
                pass("moving_resident_closes_deep_room_door_before_sleeping");cmd("difficulty normal");
                enemy=EntityTypes.ZOMBIE.create(level,EntitySpawnReason.COMMAND);require(enemy!=null,"night zombie creation");enemy.setPersistenceRequired();
                enemy.snapTo(Vec3.atBottomCenterOf(nightRoom.offset(2,0,-1)),0,0);level.addFreshEntity(enemy);enemy.setTarget(worker.body());nightThreatSince=age;return;
            }
            if(age-nightThreatSince<200)return;
            require(worker.body().isAlive()&&worker.body().getHealth()==20&&enemy.isAlive(),"closed home physically protects resting resident from a real pursuing zombie on Normal");
            if(Math.floorMod(level.getGameTime(),20)!=Math.floorMod(worker.id.hashCode(),20))return;
            pass("closed_home_door_protects_resident_from_actual_night_zombie");enemy.discard();cmd("difficulty peaceful");
            worker.cancel();worker.body().stopSleeping();worker.body().getNavigation().stop();
            level.setBlock(securityDoor,level.getBlockState(securityDoor).setValue(DoorBlock.OPEN,true),3);
            level.setBlock(securityDoor.above(),level.getBlockState(securityDoor.above()).setValue(DoorBlock.OPEN,true),3);
            worker.body().snapTo(Vec3.atBottomCenterOf(securityDoor).add(0,0,.7),0,0);worker.body().setDeltaMovement(Vec3.ZERO);
            require(!((dev.navigationai.runtime.VillagerAccess)worker.body()).navigationAiController().controlsBody(),"door handoff scene must release library control");
            stage=47;since=age;return;
        }
        if(stage==47){
            if(age-since<=12)worker.body().move(MoverType.SELF,new Vec3(-1.0/12,0,.25));
            worker.secureDoors(worker.bot());
            if(age-since<=12)log("DOOR_HANDOFF step="+(age-since)+" pos="+worker.body().position()+" sleep="+worker.body().isSleeping()+" open="+level.getBlockState(securityDoor).getValue(DoorBlock.OPEN));
            if(age-since<25)return;
            require(!level.getBlockState(securityDoor).getValue(DoorBlock.OPEN)&&!level.getBlockState(securityDoor.above()).getValue(DoorBlock.OPEN),
                    "released body closes the door during its short physical passage before the bed is out of hand reach; pos="+worker.body().position());
            pass("released_body_closes_door_before_short_native_handoff_window_expires");
            worker.cancel();worker.body().stopSleeping();
            BlockPos wallBed=nightRoom.offset(1,0,2);bed(wallBed);
            worker.body().snapTo(Vec3.atBottomCenterOf(nightRoom.offset(0,0,2)).add(-.85,0,0),0,0);worker.body().setDeltaMovement(Vec3.ZERO);
            worker.assign("home",wallBed);worker.work(worker.bot());
            require("home".equals(worker.job)&&worker.navigation!=null&&worker.lastApproach!=null
                    &&worker.lastApproach.getX()>nightRoom.getX()&&worker.lastApproach.getX()<nightRoom.getX()+4,
                    "return does not finish outside a wall merely because the real bed is within two blocks; approach="+worker.lastApproach);
            pass("home_return_requires_visible_interior_approach_to_actual_bed");
            worker.cancel();worker.body().snapTo(Vec3.atBottomCenterOf(nightBed),0,0);worker.body().setDeltaMovement(Vec3.ZERO);
            worker.body().getBrain().setMemory(MemoryModuleType.HOME,GlobalPos.of(level.dimension(),nightBed));
            worker.assign("home",nightBed);worker.work(worker.bot());require(worker.job==null,"close interior home return completes");
            worker.body().setNoAi(true);stage=48;since=age;return;
        }
        if(stage==48){
            if(age-since<20)return;
            worker.body().setNoAi(false);worker.body().stopSleeping();
            worker.body().snapTo(Vec3.atBottomCenterOf(nightBed.north(3)),0,0);worker.body().setDeltaMovement(Vec3.ZERO);worker.tick();
            require("home".equals(worker.job)&&nightBed.equals(worker.target),"awake native wandering at night triggers an immediate physical return instead of a400tick immunity window; job="+worker.job+" target="+worker.target);
            pass("awake_night_resident_returns_after_short_recheck_instead_of_wandering_for400ticks");
            worker.cancel();cmd("time set 23500");worker.body().stopSleeping();
            worker.body().snapTo(Vec3.atBottomCenterOf(nightBed.north(3)),0,0);worker.body().setDeltaMovement(Vec3.ZERO);worker.tick();
            require("home".equals(worker.job)&&nightBed.equals(worker.target),"twilight remains shelter time even after the world's darkOutside flag turns false");
            pass("resident_stays_near_real_bed_at_bright_dawn_until_morning_work_window");
            worker.cancel();if(previousHome.isPresent())worker.body().getBrain().setMemory(MemoryModuleType.HOME,previousHome.get());else worker.body().getBrain().eraseMemory(MemoryModuleType.HOME);
            v.state.buildings.remove(securityPlan);v.town.invalidate();
            for(BlockPos p:BlockPos.betweenClosed(nightRoom,nightRoom.offset(4,3,8)))level.setBlock(p,Blocks.AIR.defaultBlockState(),3);
            cmd("time set 1000");
            if(nightOnly){log("DONE passed="+passed+" night_only=true");running=false;return;}
            resetBag(worker.bot());worker.body().setNoAi(true);worker.body().snapTo(Vec3.atBottomCenterOf(origin.offset(14,0,16)),0,0);
            courier=v.workers.get(second);require(courier!=null,"native courier exists");courier.cancel();resetBag(courier.bot());give(courier.bot(),Items.CRAFTING_TABLE,1);
            courier.body().setNoAi(false);courier.body().snapTo(Vec3.atBottomCenterOf(origin.offset(8,0,16)),0,0);
            courier.assign("supply",worker.body().blockPosition());courier.supplyRecipient=worker.id;stage=34;since=age;return;
        }
        if(stage==34){
            courier.tick();if(courier.job!=null)return;
            require(worker.bot().inventory().count(Items.CRAFTING_TABLE)==1&&courier.bot().inventory().count(Items.CRAFTING_TABLE)==0,"real carried parcel transferred exactly once: "+courier.lastFailure+" pos="+courier.body().position()+" recipient="+worker.body().position());
            require(courier.body().distanceToSqr(worker.body())<=9,"courier physically approached recipient");
            pass("physical_courier_delivers_existing_workshop_supplies_to_stranded_resident");
            resetBag(worker.bot());resetBag(courier.bot());
            for(int i=0;i<35;i++)give(worker.bot(),Items.COBBLESTONE,64);give(worker.bot(),Items.DIAMOND,62);give(courier.bot(),Items.DIAMOND,9);
            int diamonds=Supplies.find(courier.bot(),s->s.is(Items.DIAMOND));
            require(courier.bot().inventory().transferTo(worker.bot().inventory(),diamonds,9)==2,"only destination capacity transferred");
            require(worker.bot().inventory().count(Items.DIAMOND)==64&&courier.bot().inventory().count(Items.DIAMOND)==7,"partial transfer conserves all71 diamonds");
            require(courier.bot().inventory().transferTo(worker.bot().inventory(),diamonds,7)==0&&courier.bot().inventory().count(Items.DIAMOND)==7,"full destination retains source cargo");
            require(courier.bot().inventory().transferTo(courier.bot().inventory(),diamonds,7)==0,"self transfer is inert");
            pass("native_inventory_transfer_capacity_remainder_and_self_transfer_conservation");resetBag(courier.bot());
            worker.body().setNoAi(false);courier.body().setNoAi(true);courier.body().snapTo(Vec3.atBottomCenterOf(origin.offset(-20,0,-10)),0,0);
            resetBag(worker.bot());give(worker.bot(),Items.RAW_IRON,1);give(worker.bot(),Items.COAL,1);
            BlockPos furnacePos=origin.offset(9,0,3);level.setBlock(furnacePos,Blocks.FURNACE.defaultBlockState(),3);v.state.furnaces.add(furnacePos.asLong());
            worker.assign("furnace",furnacePos);
            stage=4;since=age;return;
        }
        if(stage==37){
            if(age-since>5&&age-since<55||age-since>80&&age-since<130){
                var controller=((dev.navigationai.runtime.VillagerAccess)worker.body()).navigationAiController();
                if(age-since<12)log("LEDGE pos="+worker.body().position()+" grounded="+worker.body().onGround()+" controls="+controller.controlsBody()+" activity="+worker.bot().activities().status()+" guarded="+controller.guardLedge(new Vec3(.8,-.0784,.8)));
                worker.body().move(MoverType.SELF,new Vec3(.8,-.0784,.8));
            }
            require(worker.body().getY()>=origin.getY()-.1&&worker.body().getHealth()==ledgeHealth,"ordinary controlled body must brake above deep diagonal drop: "+worker.body().position());
            if(age-since<75)return;
            if(age-since==75){
                pass("ordinary_villager_movement_brakes_at_deep_diagonal_ledge_without_sneaking");
                worker.cancel();worker.body().snapTo(Vec3.atBottomCenterOf(origin.offset(17,0,17)),0,0);worker.body().setDeltaMovement(Vec3.ZERO);
                require(!((dev.navigationai.runtime.VillagerAccess)worker.body()).navigationAiController().controlsBody(),"idle scene must actually release library movement to native Brain");return;
            }
            if(age-since<150)return;
            pass("idle_native_villager_brakes_at_deep_diagonal_ledge_after_library_tasks_finish");
            worker.cancel();worker.body().snapTo(Vec3.atBottomCenterOf(origin),0,0);worker.body().setDeltaMovement(Vec3.ZERO);
            for(int x=18;x<=25;x++)for(int z=18;z<=25;z++)level.setBlock(origin.offset(x,-1,z),Blocks.GRASS_BLOCK.defaultBlockState(),3);
            for(int x=-19;x<=-10;x++)for(int z=8;z<=16;z++)for(int y=-1;y<=2;y++)level.setBlock(origin.offset(x,y,z),Blocks.STONE.defaultBlockState(),3);
            for(int z=9;z<=15;z++){BlockPos p=origin.offset(-18,0,z);level.setBlock(p,Blocks.WATER.defaultBlockState(),3);level.setBlock(p.above(),Blocks.AIR.defaultBlockState(),3);}
            for(int x=-18;x<=-11;x++){BlockPos p=origin.offset(x,0,15);level.setBlock(p,Blocks.WATER.defaultBlockState(),3);level.setBlock(p.above(),Blocks.AIR.defaultBlockState(),3);}
            worker.body().snapTo(Vec3.atBottomCenterOf(origin.offset(-18,0,9)),0,0);
            cornerWalk=worker.bot().navigator().goTo(new NavigationGoal.PositionGoal(Vec3.atBottomCenterOf(origin.offset(-11,0,15)),.25),Resident.TRAVEL);
            stage=38;since=age;return;
        }
        if(stage==38){
            if(!cornerWalk.completion().isDone())return;
            require(cornerWalk.status()==NavigationStatus.SUCCESS,"native villager passes narrow flooded right-angle corner: "+cornerWalk.completion().join()+" pos="+worker.body().position());
            pass("native_villager_centres_body_before_narrow_flooded_corridor_turn");
            for(int x=-19;x<=-10;x++)for(int z=8;z<=16;z++){
                level.setBlock(origin.offset(x,-1,z),Blocks.GRASS_BLOCK.defaultBlockState(),3);
                for(int y=0;y<=2;y++)level.setBlock(origin.offset(x,y,z),Blocks.AIR.defaultBlockState(),3);
            }
            worker.cancel();worker.body().snapTo(Vec3.atBottomCenterOf(origin),0,0);worker.body().setDeltaMovement(Vec3.ZERO);
            for(int x=7;x<=13;x++)for(int z=-22;z<=-16;z++)for(int y=-6;y<0;y++){
                boolean wall=x==7||x==13||z==-22||z==-16||y==-6;
                level.setBlock(origin.offset(x,y,z),(wall?Blocks.STONE:Blocks.WATER).defaultBlockState(),3);
            }
            worker.body().snapTo(Vec3.atBottomCenterOf(origin.offset(10,-5,-19)),0,0);worker.body().setAirSupply(160);ledgeHealth=worker.body().getHealth();
            stage=39;since=age;return;
        }
        if(stage==39){
            worker.tick();require(worker.body().isAlive()&&worker.body().getHealth()==ledgeHealth,"resident must reach actual air before drowning");
            if(worker.body().isEyeInFluid(net.minecraft.tags.FluidTags.WATER)||worker.body().getAirSupply()<280||worker.body().getY()<origin.getY()-.5)return;
            require(worker.body().getY()>origin.getY()-.5,"physically reached floating surface from five-block-deep pool");
            pass("low_air_interrupts_work_and_native_villager_surfaces_without_drowning_or_air_gifts");
            worker.cancel();worker.body().snapTo(Vec3.atBottomCenterOf(origin),0,0);worker.body().setDeltaMovement(Vec3.ZERO);
            for(int x=7;x<=13;x++)for(int z=-22;z<=-16;z++)level.setBlock(origin.offset(x,-1,z),Blocks.GRASS_BLOCK.defaultBlockState(),3);
            repairBeforeUpgrade();
            partialStart=origin.offset(12,0,10);
            for(int x=-1;x<=3;x++)for(int z=-1;z<=1;z++)for(int y=0;y<=2;y++){
                boolean wall=x==-1||x==3||z!=0;level.setBlock(partialStart.offset(x,y,z),(wall?Blocks.STONE:Blocks.AIR).defaultBlockState(),3);
            }
            for(int x=1;x<=2;x++){level.setBlock(partialStart.east(x),Blocks.FARMLAND.defaultBlockState(),3);level.setBlock(partialStart.east(x).above(),Blocks.WHEAT.defaultBlockState(),3);}
            worker.body().snapTo(Vec3.atBottomCenterOf(partialStart),0,0);worker.body().setDeltaMovement(Vec3.ZERO);
            partialWalk=worker.bot().navigator().goTo(new NavigationGoal.PositionGoal(Vec3.atBottomCenterOf(partialStart.east(2)).add(0,15.0/16,0),.25),Resident.TRAVEL);
            stage=41;since=age;return;
        }
        if(stage==41){
            if(!partialWalk.completion().isDone())return;
            require(partialWalk.status()==NavigationStatus.SUCCESS,"jump onto15/16 rise stored at the same gridY: "+partialWalk.completion().join()+" pos="+worker.body().position());
            for(int x=1;x<=2;x++)require(level.getBlockState(partialStart.east(x)).is(Blocks.FARMLAND)&&level.getBlockState(partialStart.east(x).above()).is(Blocks.WHEAT),"jump preserves actual raised farmland and crops");
            pass("native_villager_jumps_onto_almost_full_partial_block_at_same_grid_y");
            worker.cancel();worker.body().snapTo(Vec3.atBottomCenterOf(origin),0,0);worker.body().setDeltaMovement(Vec3.ZERO);
            for(int x=-1;x<=3;x++)for(int z=-1;z<=1;z++)for(int y=0;y<=2;y++)level.setBlock(partialStart.offset(x,y,z),Blocks.AIR.defaultBlockState(),3);
            for(int x=-1;x<=4;x++)for(int z=-1;z<=1;z++)for(int y=0;y<=3;y++){
                boolean wall=x==-1||x==4||z!=0;level.setBlock(partialStart.offset(x,y,z),(wall?Blocks.STONE:Blocks.AIR).defaultBlockState(),3);
            }
            for(int x=1;x<=3;x++)level.setBlock(partialStart.east(x),Blocks.OAK_PLANKS.defaultBlockState(),3);
            var doorway=Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING,Direction.EAST);
            level.setBlock(partialStart.east().above(),doorway,18);level.setBlock(partialStart.east().above(2),doorway.setValue(DoorBlock.HALF,net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER),18);
            worker.body().snapTo(Vec3.atBottomCenterOf(partialStart),0,0);
            partialWalk=worker.bot().navigator().goTo(new NavigationGoal.PositionGoal(Vec3.atBottomCenterOf(partialStart.east(3).above()),.25),Resident.TRAVEL);
            stage=42;since=age;return;
        }
        if(stage==42){
            if(!partialWalk.completion().isDone())return;
            require(partialWalk.status()==NavigationStatus.SUCCESS,"open a closed door while jumping from one block below its threshold: "+partialWalk.completion().join()+" pos="+worker.body().position());
            require(worker.body().getX()>partialStart.getX()+2&&worker.body().getY()>=partialStart.getY()+.9,"native body passed the raised doorway");
            pass("native_villager_opens_and_enters_closed_door_above_one_block_threshold");
            worker.cancel();worker.body().snapTo(Vec3.atBottomCenterOf(partialStart),0,0);worker.body().setDeltaMovement(Vec3.ZERO);
            for(int x=0;x<=3;x++)level.setBlock(partialStart.east(x).above(3),Blocks.OAK_PLANKS.defaultBlockState(),3);
            var doorway=Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING,Direction.EAST);
            level.setBlock(partialStart.east().above(),doorway,18);level.setBlock(partialStart.east().above(2),doorway.setValue(DoorBlock.HALF,net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER),18);
            partialWalk=worker.bot().navigator().goTo(new NavigationGoal.PositionGoal(Vec3.atBottomCenterOf(partialStart.east(3).above()),.25),Resident.TRAVEL);
            stage=43;since=age;return;
        }
        if(stage==43){
            if(!partialWalk.completion().isDone())return;
            require(partialWalk.status()==NavigationStatus.SUCCESS,"jump into raised two-block doorway under a low canopy: "+partialWalk.completion().join()+" pos="+worker.body().position());
            require(worker.body().getX()>partialStart.getX()+2&&worker.body().getY()>=partialStart.getY()+.9,"native body entered actual two-block-high interior");
            pass("native_villager_enters_raised_door_under_low_canopy_with_actual_head_clearance");
            worker.cancel();worker.body().snapTo(Vec3.atBottomCenterOf(origin),0,0);worker.body().setDeltaMovement(Vec3.ZERO);
            for(int x=-1;x<=4;x++)for(int z=-1;z<=1;z++)for(int y=0;y<=3;y++)level.setBlock(partialStart.offset(x,y,z),Blocks.AIR.defaultBlockState(),3);
            builderCraftsBed();
            sharedChestAccess();
            if(!safetyOnly)LongRunRegression.run(v,worker,origin);
            beginReturnFallback();return;
        }
        if(stage==51){
            if(worker.bot().navigator().status()!=NavigationStatus.SUCCESS)return;
            require(worker.body().blockPosition().distSqr(returnWaypoint)>=9,"successful fallback navigation still leaves the actual waypoint out of reach");
            v.expeditions.travel(worker,returnWaypoint);
            var next=worker.bot().navigator().goal().orElseThrow();
            require(next instanceof NavigationGoal.PositionGoal p&&p.position().distanceTo(Vec3.atBottomCenterOf(returnFallback))>.1,
                    "reaching a lower fallback must choose another approach instead of repeating the same successful stand forever");
            pass("return_waypoint_rejects_successful_but_still_out_of_reach_fallback");stage=52;since=age;return;
        }
        if(stage==52){
            if(worker.job!=null)worker.work(worker.bot());else v.expeditions.travel(worker,returnWaypoint);
            if(worker.body().blockPosition().distSqr(returnWaypoint)>=9)return;
            require(worker.body().getHealth()==20,"real fallback recovery preserves the resident's health");
            pass("return_waypoint_advances_physically_after_lower_fallback_success");
            worker.cancel();v.expeditions.stop();worker.body().snapTo(Vec3.atBottomCenterOf(origin),0,0);worker.body().setDeltaMovement(Vec3.ZERO);
            for(int x=-22;x<=-18;x++)for(int z=15;z<=22;z++)for(int y=0;y<=7;y++)level.setBlock(origin.offset(x,y,z),Blocks.AIR.defaultBlockState(),3);
            beginPitRecovery();return;
        }
        if(stage==53){
            if(worker.job!=null)worker.work(worker.bot());else TerrainRecovery.start(worker,pitCenter.east(6).above(2));
            if(worker.body().getY()<origin.getY()-.1||worker.body().blockPosition().distSqr(pitCenter.above(2))<4)return;
            require(worker.body().getHealth()==20&&level.getBlockState(pitProtected).is(Blocks.OAK_PLANKS),"physical pit escape preserves health and the protected village building");
            pass("dug_out_pit_resident_physically_climbs_paid_stair_without_breaking_house");
            worker.cancel();worker.body().snapTo(Vec3.atBottomCenterOf(origin),0,0);worker.body().setDeltaMovement(Vec3.ZERO);
            v.state.buildings.remove(pitHouse);v.town.invalidate();v.state.accessBlocks.removeIf(p->pitSaved.containsKey(BlockPos.of(p)));pitSaved.forEach((p,s)->level.setBlock(p,s,3));pitSaved.clear();
            UnloadingRegression.begin(v,worker,origin);stage=54;since=age;return;
        }
        if(stage==54){
            if(!UnloadingRegression.tick(origin))return;
            if(longrunOnly||safetyOnly){log("DONE passed="+passed+" "+(longrunOnly?"longrun_only=true":"safety_only=true"));running=false;return;}
            ledgeChecked=true;stage=1;since=age;return;
        }
        if(stage==4){
            manual();
            var f=(FurnaceBlockEntity)level.getBlockEntity(origin.offset(9,0,3));
            if(worker.bot().inventory().count(Items.IRON_INGOT)==0){
                if(!f.getItem(2).isEmpty()&&worker.job==null)worker.assign("furnace",f.getBlockPos());
                else if(worker.job==null)worker.bot().activities().run(List.of(new BotActivity.Wait(20)));
                return;
            }
            require(age-since>=200,"real vanilla smelting time");
            require(worker.bot().inventory().count(Items.IRON_INGOT)==1&&worker.bot().inventory().count(Items.RAW_IRON)==0,"smelting conservation");
            pass("real_furnace_timing_and_fuel");
            resetBag(worker.bot());give(worker.bot(),Items.IRON_INGOT,63);
            for(int i=0;i<34;i++)give(worker.bot(),Items.DIAMOND,64);
            give(worker.bot(),Items.COAL,4);
            f.setItem(0,new ItemStack(Items.RAW_IRON));f.setItem(1,ItemStack.EMPTY);f.setItem(2,new ItemStack(Items.IRON_INGOT,2));
            var full=worker.bot().inventory().snapshot().slots();
            require(VillageWorkshop.serviceFurnace(worker.bot(),v,f),"fuel transfer remains possible while output is full");
            require(worker.bot().inventory().count(Items.IRON_INGOT)==63&&f.getItem(2).getCount()==2&&f.getItem(1).getCount()==4,"failed output collection does not partly copy ingots during another successful transfer");
            // Free one full slot: now exactly two output ingots should move.
            int diamondSlot=Supplies.find(worker.bot(),s->s.is(Items.DIAMOND));worker.bot().inventory().extract(diamondSlot,64);
            require(VillageWorkshop.serviceFurnace(worker.bot(),v,f)&&worker.bot().inventory().count(Items.IRON_INGOT)==65&&f.getItem(2).isEmpty(),"output fits after freeing space");
            pass("full_backpack_furnace_output_has_no_partial_duplicate");
            worker.cancel();resetBag(worker.bot());worker.body().getInventory().clearContent();give(worker.bot(),Items.BREAD,3);worker.cooldown=0;worker.tick();
            require(worker.body().getInventory().countItem(Items.BREAD)==3&&worker.bot().inventory().count(Items.BREAD)==0,"native food transfer: native="+worker.body().getInventory().countItem(Items.BREAD)+" bag="+worker.bot().inventory().count(Items.BREAD)+" job="+worker.job+" status="+worker.status);
            require(worker.body().canBreed(),"native food enables breeding");pass("native_food_and_breeding_prerequisites");
            resetBag(worker.bot());worker.body().snapTo(Vec3.atBottomCenterOf(origin),0,0);worker.body().setHealth(8);
            cmd("difficulty normal");
            enemy=EntityTypes.ZOMBIE.create(level,EntitySpawnReason.COMMAND);enemy.setNoAi(true);enemy.setPersistenceRequired();
            enemy.snapTo(worker.body().position().add(8,0,0),0,0);level.addFreshEntity(enemy);
            // Roof keeps the opponent out of sunlight; the villager supplies every point of combat damage.
            for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)level.setBlock(enemy.blockPosition().offset(x,3,z),Blocks.GLASS.defaultBlockState(),3);
            stage=55;since=age;return;
        }
        if(stage==55){
            worker.tick();if(age-since<120)return;
            require(worker.body().isAlive()&&worker.body().getX()<origin.getX()-2,"injured unarmed resident actually retreats across repeated threat scans: "+worker.body().position());
            pass("injured_resident_physically_retreats_without_restarting_every_threat_scan");
            worker.cancel();worker.body().setHealth(20);give(worker.bot(),Items.STONE_SWORD,1);
            enemy.snapTo(worker.body().position().add(2,0,0),0,0);enemy.setNoAi(false);stage=5;since=age;return;
        }
        if(stage==5){
            manual();if(enemy.isAlive())return;
            require(worker.body().isAlive(),"defender survives");worker.cancel();pass("native_resident_defense");
            cmd("difficulty peaceful");
            resetBag(worker.bot());give(worker.bot(),Items.OAK_PLANKS,64);give(worker.bot(),Items.OAK_PLANKS,32);
            give(worker.bot(),Items.OAK_DOOR,1);give(worker.bot(),Items.BED.white(),2);give(worker.bot(),Items.TORCH,1);
            give(worker.bot(),Items.OAK_STAIRS,1);
            BlockPos base=origin.offset(-16,0,8);require(Construction.site(v,base),"shelter site fits: "+Construction.siteProblem(v,base));
            v.state.shelter=base.asLong();steps=Construction.shelter(base);built=0;
            advance();return;
        }
        if(stage==6){
            if(age%400==0)log("PROGRESS house built="+built+"/"+steps.size()+" job="+worker.job+" status="+worker.status+" target="+worker.target+" pos="+worker.body().position());
            if(worker.job!=null){manual();return;}
            if(built<steps.size()){
                var step=steps.get(built);
                if(level.getBlockState(step.pos()).is(step.state().getBlock())){built++;return;}
                worker.build=step;worker.assign("build",step.pos());manual();return;
            }
            require(Construction.next(v)==null&&v.state.completedShelters==1,"shelter completion");
            require(worker.bot().inventory().count(Items.OAK_PLANKS)==16,"80 paid structural planks");
            require(worker.bot().inventory().count(Items.BED.white())==0&&worker.bot().inventory().count(Items.OAK_DOOR)==0,"bed/door paid once");
            pass("physical_shelter_build_with_beds_door_and_light");
            BlockPos home=steps.stream().filter(s->s.item()==Items.BED.white()).findFirst().orElseThrow().pos().south();
            worker.body().getBrain().setMemory(MemoryModuleType.HOME,GlobalPos.of(level.dimension(),home));
            worker.cancel();cmd("time set 14000");advance();return;
        }
        if(stage==7){
            manual();if(!worker.body().isSleeping())return;
            pass("native_night_return_and_sleep");worker.cancel();worker.body().stopSleeping();cmd("time set 1000");
            BlockPos profession=origin.offset(10,0,-3);level.setBlock(profession,Blocks.COMPOSTER.defaultBlockState(),3);
            worker.body().getBrain().setMemory(MemoryModuleType.JOB_SITE,GlobalPos.of(level.dimension(),profession));cmd("time set "+(2100+v.roleIndex(worker.id)%8*600));
            worker.tick();require("native_work".equals(worker.job)||worker.status.contains("профессии"),"native profession window");
            worker.cancel();worker.body().getBrain().eraseMemory(MemoryModuleType.JOB_SITE);cmd("time set 1000");pass("native_profession_window_releases_work_controller");
            resetBag(worker.bot());give(worker.bot(),Items.DIAMOND,17);
            BlockPos remote=origin.offset(128,0,0);
            for(BlockPos p:BlockPos.betweenClosed(remote.offset(-1,-1,-1),remote.offset(1,3,1)))level.setBlock(p,p.getY()==remote.getY()-1?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState(),3);
            worker.cancel();worker.body().setNoAi(true);worker.body().snapTo(Vec3.atBottomCenterOf(remote),0,0);
            v.state.active=true;v.runtime.change("Test","pause");
            require(!v.state.active&&!v.ticketed&&v.runtime.bots.find(first).orElseThrow().inventory().count(Items.DIAMOND)==17,"pause retains pack/releases ticket");
            pass("pause_preserves_inventory_and_releases_tickets");v.runtime.save();
            origin=new BlockPos(3200,100,3200);setup();
            for(int x:new int[]{-12,-6,0,6,12})for(int z:new int[]{-12,12}){
                BlockPos root=origin.offset(x,0,z);
                for(int y=0;y<4;y++)level.setBlock(root.above(y),Blocks.OAK_LOG.defaultBlockState(),3);
                for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)if(dx!=0||dz!=0)level.setBlock(root.offset(dx,3,dz),Blocks.OAK_LEAVES.defaultBlockState(),3);
                level.setBlock(root.above(4),Blocks.OAK_LEAVES.defaultBlockState(),3);
            }
            for(int x=5;x<=8;x++)for(int z=0;z<4;z++){
                BlockPos p=origin.offset(x,0,z);level.setBlock(p.below(),Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE,7),3);
                level.setBlock(p,Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,7),3);
            }
            level.setBlock(origin.offset(9,-1,1),Blocks.WATER.defaultBlockState(),3);
            growingCrops.clear();cropGrewWithoutPlayers=false;
            level.setBlock(origin.offset(20,-1,20),Blocks.WATER.defaultBlockState(),3);
            for(int x=17;x<=20;x++)for(int z=16;z<=19;z++){
                BlockPos p=origin.offset(x,0,z);level.setBlock(p.below(),Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE,7),3);
                level.setBlock(p,Blocks.WHEAT.defaultBlockState(),3);growingCrops.add(p);
            }
            for(int z=5;z<=12;z++)level.setBlock(origin.offset(-10,0,z),Blocks.STONE.defaultBlockState(),3);
            advance();return;
        }
        if(stage==8){
            if(age-since<60||!fixtureReady())return;
            releaseFixture();
            var rt=AutonomousVillagesMod.runtime(server);
            rt.claim(level,origin,"Auto",24,new UUID(0,0));v=rt.get("Auto");worker=null;
            level.getChunkSource().removeTicketWithRadius(PROBE,new net.minecraft.world.level.ChunkPos(origin.getX()>>4,origin.getZ()>>4),4);
            advance();return;
        }
        if(stage==9){
            cropGrewWithoutPlayers|=growingCrops.stream().anyMatch(p->level.getBlockState(p).is(Blocks.WHEAT)&&level.getBlockState(p).getValue(CropBlock.AGE)>0);
            if(age%600==0){
                log("PROGRESS autonomous "+v.status()+" trees="+v.index.trees.size()+" crops="+v.index.crops.size());
                for(Resident r:v.workers.values())log("PROGRESS worker "+r.id+" job="+r.job+" target="+r.target+" pos="+r.body().position()+" bag="+r.bot().inventory().snapshot().slots().stream().filter(s->!s.isEmpty()).toList()+" mining="+(r.mining==null?"none":r.mining.status())+" failure="+r.lastFailure);
            }
            boolean hasTable=v.index.tables.stream().anyMatch(p->level.getBlockState(p).is(Blocks.CRAFTING_TABLE));
            boolean bread=v.state.residents.stream().anyMatch(id->level.getEntity(id)instanceof Villager e&&e.getInventory().countItem(Items.BREAD)>=3);
            if(v.chests().isEmpty()||!hasTable||!bread)return;
            require(v.state.residents.size()==2&&server.getPlayerList().getPlayerCount()==0,"autonomous real residents without players");
            require(v.index.crops.stream().anyMatch(p->level.getBlockState(p).is(Blocks.WHEAT)&&level.getBlockState(p).getValue(CropBlock.AGE)<7),"autonomous crops reseeded");
            pass("empty_backpacks_autonomous_wood_crafting_storage_farming_food_without_players");
            Resident resident=v.workers.values().iterator().next();BotHandle b=resident.bot();b.stopAll();
            var inventory=b.inventory().snapshot().slots();
            var travel=b.navigator().goTo(new NavigationGoal.BlockGoal(v.state.center().offset(8,0,0),.8),Resident.TRAVEL);
            v.runtime.change("Auto","pause");
            require(travel.completion().isDone()&&Supplies.matches(inventory,b.inventory().snapshot().slots()),"pause cancels active task without inventory change");
            v.runtime.change("Auto","resume");pass("pause_cancels_active_job_and_resume");stage=10;since=age;return;
        }
        if(stage==10){
            cropGrewWithoutPlayers|=growingCrops.stream().anyMatch(p->level.getBlockState(p).is(Blocks.WHEAT)&&level.getBlockState(p).getValue(CropBlock.AGE)>0);
            if(!cropGrewWithoutPlayers&&age-since<3000)return;
            require(cropGrewWithoutPlayers&&server.getPlayerList().getPlayerCount()==0,"natural random-tick crop growth without players");pass("natural_crop_growth_without_online_players");
            Village paused=v.runtime.get("Test");BlockPos legacyMine=null;
            for(var d:Direction.Plane.HORIZONTAL){var p=paused.state.center().relative(d,paused.state.radius-8);if(!paused.state.contains(p)){legacyMine=p;paused.state.mineDirection=d.getOpposite().get2DDataValue();break;}}
            require(legacyMine!=null,"rectangular fixture exposes legacy circumscribed-radius survey bug");
            java.nio.file.Files.writeString(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("validation-legacy-mine.txt"),Long.toString(legacyMine.asLong()));
            // The runner injects this legacy reference after shutdown. Production saves now
            // validate before replacing the file and must never write this obsolete state.
            java.nio.file.Files.writeString(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("validation-legacy-mine.json"),VillageRuntime.GSON.toJson(Map.of("entrance",legacyMine.asLong(),"direction",paused.state.mineDirection,"length",8)));
            v.runtime.save();
            java.nio.file.Files.writeString(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("validation-expected-members.json"),VillageRuntime.GSON.toJson(v.runtime.get("Test").state.residents));
            log("DONE passed="+passed+" restart_required=true");running=false;
        }
    }
}
