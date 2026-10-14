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
    static List<Construction.Step> steps;static int built;
    public static void register(){
        CommandRegistrationCallback.EVENT.register((dispatcher,access,env)->dispatcher.register(Commands.literal("avtest")
            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(c->{
                server=c.getSource().getServer();level=server.overworld();age=0;stage=0;passed=0;running=true;
                cmd("time set 1000");cmd("time pause");cmd("tick rate 60");setup();log("START");return 1;
            }).then(Commands.literal("restart").executes(c->{
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
    static void tick(){
        age++;
        if(stage==100){
            if(age==1){v=AutonomousVillagesMod.runtime(server).get("Test");level.getChunkSource().addTicketWithRadius(PROBE,new net.minecraft.world.level.ChunkPos(v.state.x>>4,v.state.z>>4),4);}
            if(age<120)return;
            v=AutonomousVillagesMod.runtime(server).get("Test");
            require(!v.state.active,"paused state survives restart");require(v.state.residents.size()==2,"membership survives restart");
            long diamonds=0;
            for(UUID id:v.state.residents){var b=v.runtime.bots.find(id).orElseThrow();require(b.kind()==BotKind.VILLAGER,"native on restart");diamonds+=b.inventory().count(Items.DIAMOND);}
            require(diamonds==17,"backpack exactly once after restart: "+diamonds);
            require(server.getPlayerList().getPlayerCount()==0,"no fake players after restart");
            pass("restart_metadata_native_backpack");
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
            server.getCommands().performPrefixedCommand(source,"avillage claim Test 24");
            v=AutonomousVillagesMod.runtime(server).get("Test");
            level.getChunkSource().removeTicketWithRadius(PROBE,new net.minecraft.world.level.ChunkPos(origin.getX()>>4,origin.getZ()>>4),4);
            require(v.state.residents.containsAll(Set.of(first,second)),"claim real residents");require(v.ticketed,"village ticket");
            require(server.getPlayerList().getPlayerCount()==0,"no players registered");
            try{v.runtime.claim(level,origin,"Overlap",24,new UUID(0,0));throw new AssertionError("overlap allowed");}catch(IllegalArgumentException expected){}
            try{v.runtime.claim(level,origin.offset(1000,0,1000),"Empty",24,new UUID(0,0));throw new AssertionError("empty allowed");}catch(IllegalArgumentException expected){}
            pass("command_claim_real_village_and_rejections");advance();return;
        }
        if(stage==1){
            if(age-since<80)return;v.sync();v.state.active=false;
            worker=v.workers.get(first);require(worker!=null,"native attached");worker.cancel();
            for(Resident other:v.workers.values())if(other!=worker){other.cancel();other.body().setNoAi(true);other.body().snapTo(Vec3.atBottomCenterOf(origin.offset(-20,0,-10)),0,0);}
            worker.body().snapTo(Vec3.atBottomCenterOf(origin),0,0);
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
            for(int i=0;i<chest.getContainerSize();i++)chest.setItem(i,new ItemStack(Items.COBBLESTONE,64));
            require(v.needsChest(),"full depot requests expansion");
            require(Supplies.transfer(b,chest,true,s->true,3)==0&&b.inventory().count(Items.OAK_PLANKS)==3,"full chest atomic");
            chest.clearContent();require(!v.needsChest(),"empty depot needs no duplicate chest");pass("shared_storage_conservation_and_full_capacity");
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
            require(worker.bot().inventory().count(Items.WHEAT)>0,"actual wheat pickup");
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
            BlockPos entrance=origin.offset(14,0,-8);
            for(int x=4;x<=15;x++)for(int z=-9;z<=-7;z++)for(int y=90;y<=98;y++)level.setBlock(new BlockPos(origin.getX()+x,y,origin.getZ()+z),Blocks.STONE.defaultBlockState(),3);
            v.state.mineEntrance=entrance.asLong();v.state.mineDirection=Direction.WEST.get2DDataValue();v.state.mineLength=0;
            worker.assign("mine",entrance);stage=30;since=age;return;
        }
        if(stage==30){
            manual();if(worker.job!=null)return;
            require(v.state.mineLength==8,"bounded mine completes: "+worker.lastFailure);
            require(worker.body().blockPosition().distSqr(BlockPos.of(v.state.mineEntrance))<9,"miner returns to entrance");
            require(worker.bot().inventory().count(Items.COBBLESTONE)>1,"mine yields actual stone");
            pass("bounded_staircase_mine_collection_and_return");
            resetBag(worker.bot());give(worker.bot(),Items.RAW_IRON,1);give(worker.bot(),Items.COAL,1);
            BlockPos furnacePos=origin.offset(9,0,3);level.setBlock(furnacePos,Blocks.FURNACE.defaultBlockState(),3);v.state.furnaces.add(furnacePos.asLong());
            worker.assign("furnace",furnacePos);
            stage=4;since=age;return;
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
            resetBag(worker.bot());give(worker.bot(),Items.BREAD,3);worker.cooldown=0;worker.tick();
            require(worker.body().getInventory().countItem(Items.BREAD)==3&&worker.bot().inventory().count(Items.BREAD)==0,"native food transfer: native="+worker.body().getInventory().countItem(Items.BREAD)+" bag="+worker.bot().inventory().count(Items.BREAD)+" job="+worker.job+" status="+worker.status);
            require(worker.body().canBreed(),"native food enables breeding");pass("native_food_and_breeding_prerequisites");
            resetBag(worker.bot());give(worker.bot(),Items.STONE_SWORD,1);
            cmd("difficulty normal");
            enemy=EntityTypes.ZOMBIE.create(level,EntitySpawnReason.COMMAND);enemy.setNoAi(true);enemy.setPersistenceRequired();
            enemy.snapTo(worker.body().position().add(2,0,0),0,0);level.addFreshEntity(enemy);
            // Roof keeps the opponent out of sunlight; the villager supplies every point of combat damage.
            for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)level.setBlock(enemy.blockPosition().offset(x,3,z),Blocks.GLASS.defaultBlockState(),3);
            advance();return;
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
            worker.body().getBrain().setMemory(MemoryModuleType.JOB_SITE,GlobalPos.of(level.dimension(),profession));cmd("time set 3000");
            worker.tick();require("native_work".equals(worker.job)||worker.status.contains("профессии"),"native profession window");
            worker.cancel();worker.body().getBrain().eraseMemory(MemoryModuleType.JOB_SITE);cmd("time set 1000");pass("native_profession_window_releases_work_controller");
            resetBag(worker.bot());give(worker.bot(),Items.DIAMOND,17);
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
            if(age-since<120)return;
            v.runtime.save();
            log("DONE passed="+passed+" restart_required=true");running=false;
        }
    }
}
