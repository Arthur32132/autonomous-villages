package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.goal.EatBlockGoal;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.phys.Vec3;
import static dev.autonomousvillages.VillageSelfTest.*;

/** Bounded physical production chain. Fixture resources and scripted grazing are explicit. */
final class ProductionRegression {
    static Village village;
    static Resident farmer,builder,miner,guard;
    static BlockPos base,table,furnace,ore;
    static TownBuilding house;
    static int phase,clock,round,sinceRound,lastPhase,phaseSince;
    static final List<Sheep> parents=new ArrayList<>();
    static void begin(Village v,BlockPos origin){
        village=v;base=origin;clock=0;phase=0;round=0;lastPhase=-1;phaseSince=0;parents.clear();v.state.active=false;v.expeditions.stop();v.state.expedition=null;
        v.state.buildings.clear();v.state.orders.clear();v.state.storage.clear();v.state.furnaces.clear();v.index.tables.clear();v.index.crops.clear();
        v.index.trees.clear();v.index.soil.clear();v.state.sheepFarm=new SheepFarm.State();
        var crew=new ArrayList<>(v.workers.values());
        for(var r:crew){r.cancel();r.body().setNoAi(true);resetBag(r.bot());r.body().snapTo(Vec3.atBottomCenterOf(origin.offset(25,0,25)),0,0);}
        while(crew.size()<4){var body=spawn(origin.offset(20,0,20));body.setNoAi(false);v.state.residents.add(body.getUUID());v.sync();crew=new ArrayList<>(v.workers.values());}
        farmer=crew.get(0);builder=crew.get(1);miner=crew.get(2);guard=crew.get(3);
        v.state.residentRoles.clear();
        var selected=List.of(farmer,builder,miner,guard);var roles=List.of(ResidentRoles.Role.FARMER,ResidentRoles.Role.BUILDER,ResidentRoles.Role.MINER,ResidentRoles.Role.GUARD);
        for(int i=0;i<4;i++){
            var r=selected.get(i);v.state.residentRoles.put(r.id,new ResidentRoles.Assignment(r.id,roles.get(i),"none","production fixture"));
            r.body().setNoAi(false);r.body().setHealth(20);r.body().stopSleeping();r.fresh=false;
            r.body().getInventory().clearContent();r.body().getInventory().addItem(new ItemStack(Items.BREAD,3));
            r.body().snapTo(Vec3.atBottomCenterOf(origin.offset(-20+i*2,0,-6)),0,0);
            give(r.bot(),Items.STONE_PICKAXE,1);give(r.bot(),Items.WOODEN_AXE,1);give(r.bot(),Items.WOODEN_HOE,1);
        }
        table=origin.offset(8,0,3);v.level.setBlock(table,Blocks.CRAFTING_TABLE.defaultBlockState(),3);v.index.tables.add(table);
        furnace=origin.offset(5,0,-6);v.level.setBlock(furnace,Blocks.FURNACE.defaultBlockState(),3);v.state.furnaces.add(furnace.asLong());
        BlockPos chest=origin.offset(6,0,-6);v.level.setBlock(chest,Blocks.CHEST.defaultBlockState(),3);v.state.storage.add(chest.asLong());
        ((ChestBlockEntity)v.level.getBlockEntity(chest)).setItem(0,new ItemStack(Items.BREAD,64));
        var pen=SheepFarm.blueprint(origin.offset(-15,0,4));pen.validate(v.state);v.state.buildings.add(pen);v.farm.state().base=pen.origin;
        for(var p:pen.blocks)paid(v,builder,p);
        require(v.farm.ready()&&v.level.getBlockState(v.farm.gate()).getBlock() instanceof FenceGateBlock,"paid complete fenced grass pen and gate");
        pass("paid_grass_pen_and_gate_preserve_grass_for_repeatable_wool");
        house=new TownBuilding("house",origin.offset(10,0,5));
        for(var step:Construction.shelter(BlockPos.of(house.origin)))house.add(step.pos(),step.state());v.state.buildings.add(house);
        for(var p:house.blocks)if(!(p.block().getBlock() instanceof BedBlock))paid(v,builder,p);
        resetBag(builder.bot());give(builder.bot(),Items.OAK_PLANKS,6);
        v.town.invalidate();v.town.syncOrders(v.level.getGameTime());IronSupply.sync(v,v.level.getGameTime());
        require(IronSupply.next(v).purpose().equals("iron_shears"),"first concrete iron need is the shearer's shears");
        give(miner.bot(),Items.IRON_INGOT,3);give(miner.bot(),Items.STICK,2);miner.body().snapTo(Vec3.atBottomCenterOf(table.west()),0,0);
        require(!VillageWorkshop.craft(miner.bot(),v,Items.IRON_PICKAXE,new String[]{"III"," S "," S "},s->s.is(Items.IRON_INGOT))&&miner.bot().inventory().count(Items.IRON_INGOT)==3,"lower-priority pick cannot spend iron before required shears");
        // Remove fixture ingots; the following shears must come from actual ore and a vanilla furnace.
        resetBag(miner.bot());give(miner.bot(),Items.STONE_PICKAXE,2);give(miner.bot(),Items.COAL,1);give(miner.bot(),Items.OAK_PLANKS,8);
        require(MineSupplies.inspect(miner).ready(),"two real suitable picks, native carried food and eight real exit planks");
        ore=origin.offset(1,0,-6);v.level.setBlock(ore,Blocks.IRON_ORE.defaultBlockState(),3);v.level.setBlock(ore.north(),Blocks.IRON_ORE.defaultBlockState(),3);
        miner.body().snapTo(Vec3.atBottomCenterOf(ore.south(2)),0,0);miner.cancel();miner.assign("ore",ore);
        pass("iron_priority_and_real_mining_kit_gate_work_before_production");
    }
    static void beginFlock(Village v,BlockPos origin){
        begin(v,origin);miner.cancel();give(farmer.bot(),Items.IRON_INGOT,2);
        require(VillageWorkshop.craft(farmer.bot(),v,Items.SHEARS,new String[]{" I","I "},s->s.is(Items.IRON_INGOT)),"focused flock scene pays for shears with two fixture ingots");
        startHerd();
    }
    static void startHerd(){
        var v=village;give(farmer.bot(),Items.WHEAT,4);farmer.body().snapTo(Vec3.atBottomCenterOf(v.farm.gate().north(3)),0,0);farmer.cancel();
        for(int i=0;i<2;i++){
            Sheep s=EntityTypes.SHEEP.create(v.level,EntitySpawnReason.COMMAND);require(s!=null,"sheep created");s.setColor(DyeColor.WHITE);s.setPersistenceRequired();
            s.snapTo(Vec3.atBottomCenterOf(v.farm.gate().north(6+i*3).east(i==0?2:-2)),0,0);v.level.addFreshEntity(s);parents.add(s);
        }
        phase=2;
    }
    static boolean tick(){
        clock++;Village v=village;v.farm.tick();
        // Hold idle fixture staff at their stations; the paused village does not schedule
        // their native wandering. The herder and all active production jobs stay free.
        for(var r:List.of(builder,miner,guard))if(r.job==null)r.bot().activities().run(List.of(new dev.navigationai.api.BotActivity.Wait(40)));
        if((phase<2||phase==3)&&farmer.job==null)farmer.bot().activities().run(List.of(new dev.navigationai.api.BotActivity.Wait(40)));
        if(lastPhase!=phase){lastPhase=phase;phaseSince=clock;}
        require(clock<20000&&clock-phaseSince<4000,"bounded production fixture timeout phase="+phase+" farmer="+farmer.status+" job="+farmer.job+" pos="+farmer.body().position()+" flock="+v.farm.state().flock.size());
        if(phase==0){
            if(clock%400==0)log("PRODUCTION_ORE job="+miner.job+" status="+miner.status+" pos="+miner.body().position()+" bag="+miner.bot().inventory().snapshot().slots()+" drops="+v.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,new net.minecraft.world.phys.AABB(ore).inflate(12)).stream().map(e->e.getItem()+" at "+e.position()).toList());
            if(miner.job!=null){miner.work(miner.bot());return false;}
            if(v.level.getBlockState(ore.north()).is(Blocks.IRON_ORE)){miner.assign("ore",ore.north());return false;}
            miner.pickup(miner.bot());if(miner.bot().inventory().count(Items.RAW_IRON)<2)return false;
            miner.body().snapTo(Vec3.atBottomCenterOf(furnace.west()),0,0);
            require(VillageWorkshop.serviceFurnace(miner.bot(),v,(FurnaceBlockEntity)v.level.getBlockEntity(furnace)),"actual mined raw iron and coal enter vanilla furnace");
            phase=1;return false;
        }
        if(phase==1){
            miner.body().snapTo(Vec3.atBottomCenterOf(furnace.west()),0,0);miner.body().setDeltaMovement(Vec3.ZERO);
            VillageWorkshop.serviceFurnace(miner.bot(),v,(FurnaceBlockEntity)v.level.getBlockEntity(furnace));
            if(miner.bot().inventory().count(Items.IRON_INGOT)<2)return false;
            farmer.body().snapTo(Vec3.atBottomCenterOf(base.offset(0,0,-10)),0,0);
            miner.body().snapTo(Vec3.atBottomCenterOf(base.offset(2,0,-10)),0,0);
            var order=v.state.orders.stream().filter(o->o.purpose.split("@",2)[0].equals("iron_shears")).findFirst().orElseThrow();
            miner.deliver(order,farmer.id);phase=11;return false;
        }
        if(phase==11){
            farmer.body().snapTo(Vec3.atBottomCenterOf(base.offset(0,0,-10)),0,0);
            miner.body().snapTo(Vec3.atBottomCenterOf(base.offset(2,0,-10)),0,0);
            if(miner.job!=null){miner.work(miner.bot());return false;}
            require(farmer.bot().inventory().count(Items.IRON_INGOT)==2,"actual smelted iron physically handed to shearer: miner="+miner.bot().inventory().snapshot().slots()+" farmer="+farmer.bot().inventory().snapshot().slots()+" order="+VillageRuntime.GSON.toJson(v.state.orders));
            require(VillageWorkshop.craft(farmer.bot(),v,Items.SHEARS,new String[]{" I","I "},s->s.is(Items.IRON_INGOT))&&farmer.bot().inventory().count(Items.IRON_INGOT)==0,"actual two smelted ingots pay for shears");
            IronSupply.sync(v,v.level.getGameTime());require(IronSupply.next(v).purpose().equals("iron_pickaxe"),"pick request follows shears");
            give(miner.bot(),Items.IRON_INGOT,3);give(miner.bot(),Items.STICK,2);miner.body().snapTo(Vec3.atBottomCenterOf(table.west()),0,0);
            require(VillageWorkshop.craft(miner.bot(),v,Items.IRON_PICKAXE,new String[]{"III"," S "," S "},s->s.is(Items.IRON_INGOT))&&miner.bot().inventory().count(Items.IRON_INGOT)==0,"next pick recipe debits three fixture ingots");
            pass("real_ore_vanilla_smelting_physical_iron_delivery_and_paid_shears_then_pick");
            var record=MineKnowledge.record(v,ore.asLong(),0);record.iron.add(ore.north().asLong());record.blocked=true;record.hazard="observed unsafe test direction";
            var restored=VillageRuntime.GSON.fromJson(VillageRuntime.GSON.toJson(v.state),VillageState.class);restored.validate();
            require(restored.mines.getLast().blocked&&restored.mines.getLast().hazard.equals(record.hazard),"mine direction observations survive metadata reload");
            pass("mine_resource_and_rejected_direction_memory_survives_reload");
            startHerd();return false;
        }
        if(phase==2){
            if(v.farm.state().flock.size()<2||parents.stream().anyMatch(s->!v.farm.owns(s)||!v.farm.inside(s.blockPosition()))){
                if(farmer.job==null)v.farm.planWork(farmer);
                if(farmer.job!=null)farmer.work(farmer.bot());
                if(clock%200==0)log("FARM_PROGRESS phase=herd pos="+farmer.body().position()+" sheep="+parents.stream().map(s->s.position().toString()).toList()+" owned="+v.farm.state().flock.size());
                return false;
            }
            require(parents.stream().allMatch(s->v.farm.owns(s)&&v.farm.inside(s.blockPosition())),"two original sheep physically walked through the gate; no animal teleporting");
            require(!v.level.getBlockState(v.farm.gate()).getValue(FenceGateBlock.OPEN)&&parents.stream().noneMatch(s->s.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(v.farm.gate()).inflate(.1))),"animals fully clear the physically closed gate before breeding");
            farmer.cancel();farmer.body().snapTo(Vec3.atBottomCenterOf(v.farm.inside()),0,0);
            int before=farmer.bot().inventory().count(Items.WHEAT);
            require(v.farm.feedPair(farmer)&&farmer.bot().inventory().count(Items.WHEAT)==before-2,"exactly two wheat pay for the native breeding pair");
            pass("two_original_sheep_walk_through_gate_and_breeding_consumes_two_real_wheat");
            phase=3;return false;
        }
        if(phase==3){
            if(clock%100==0)log("BREED_DIAGNOSTIC parents="+parents.stream().map(s->"age="+s.getAge()+" love="+s.isInLove()+" panic="+s.isPanicking()+" noAi="+s.isNoAi()+" pos="+s.position()+" breedGoal="+new net.minecraft.world.entity.ai.goal.BreedGoal(s,1).canUse()).toList()+" babies="+v.level.getEntitiesOfClass(Sheep.class,new net.minecraft.world.phys.AABB(v.farm.inside()).inflate(16)).stream().map(s->s.getUUID()+" age="+s.getAge()+" pos="+s.position()).toList());
            if(v.farm.flock().stream().noneMatch(Sheep::isBaby))return false;
            require(parents.stream().allMatch(Sheep::isAlive)&&v.farm.flock().size()>=3,"native breeding produced a real lamb without consuming parents");
            pass("native_breeding_creates_persisted_lamb_and_preserves_breeding_parents");
            phase=4;sinceRound=clock;return false;
        }
        if(phase==4){
            if(clock-sinceRound==1){
                // Script the vanilla grazing action to avoid a long random wait in this focused
                // scene. It consumes real grass; autonomous grazing frequency is not asserted.
                for(Sheep s:parents){
                    v.level.setBlock(s.blockPosition().below(),Blocks.GRASS_BLOCK.defaultBlockState(),3);
                    var eat=new EatBlockGoal(s);eat.start();for(int n=0;n<40;n++)eat.tick();eat.stop();
                    require(!s.isSheared(),"vanilla grass eating restores the same animal's wool");
                    farmer.cancel();farmer.body().snapTo(Vec3.atBottomCenterOf(s.blockPosition().north()),0,0);farmer.assign("shear",s.blockPosition());farmer.work(farmer.bot());farmer.cancel();
                }
            }
            for(Sheep s:parents){farmer.body().snapTo(Vec3.atBottomCenterOf(s.blockPosition().north()),0,0);farmer.pickup(farmer.bot());}
            if(clock-sinceRound<25)return false;
            int needed=6;
            if(farmer.count(Orders.Material.WOOL)<needed){sinceRound=clock;round++;require(round<7,"bounded grazing rounds");return false;}
            // Use the production physical delivery work path with a real outstanding bed order.
            var wool=v.state.orders.stream().filter(o->o.purpose.equals("bed_wool")&&o.open()).findFirst().orElseThrow();
            builder.cancel();builder.body().snapTo(Vec3.atBottomCenterOf(table.east()),0,0);
            farmer.cancel();farmer.body().snapTo(Vec3.atBottomCenterOf(v.farm.inside()),0,0);farmer.deliver(wool,builder.id);phase=5;return false;
        }
        if(phase==5){
            if(clock%400==0)log("WOOL_DELIVERY pos="+farmer.body().position()+" recipient="+builder.body().position()+" status="+farmer.status+" nav="+(farmer.navigation==null?"none":farmer.navigation.status())+" job="+farmer.job);
            if(farmer.job!=null){farmer.work(farmer.bot());return false;}
            farmer.body().snapTo(Vec3.atBottomCenterOf(v.farm.inside()),0,0);
            builder.body().snapTo(Vec3.atBottomCenterOf(table.east()),0,0);
            for(var p:house.blocks)if(p.block().getBlock() instanceof BedBlock&&!p.matches(v)){
                require(TownCrafting.make(builder.bot(),v),"bed maker crafts actual delivered matching wool and planks");
                builder.body().snapTo(Vec3.atBottomCenterOf(p.position().east(2)),0,0);
                require(Construction.place(v,builder.bot(),Construction.available(builder.bot(),p.step()))&&p.matches(v),"real paid bed installed in house");
                builder.body().snapTo(Vec3.atBottomCenterOf(table.east()),0,0);
            }
            v.town.syncOrders(v.level.getGameTime());
            if(phase==5&&round<3){
                require(house.built(v)==house.blocks.size(),"complete physical new two-bed house from own flock wool");
                pass("own_flock_wool_physically_reaches_bed_maker_and_completes_two_bed_house");
                house=new TownBuilding("house",base.offset(15,0,-2));house.add(BlockPos.of(house.origin),Blocks.BED.white().defaultBlockState().setValue(BedBlock.FACING,Direction.SOUTH));v.state.buildings.add(house);
                house.add(BlockPos.of(house.origin).east(3),Blocks.BED.white().defaultBlockState().setValue(BedBlock.FACING,Direction.SOUTH));
                give(builder.bot(),Items.OAK_PLANKS,6);v.town.invalidate();v.town.syncOrders(v.level.getGameTime());
                round=3;sinceRound=clock;phase=4;return false;
            }
            require(parents.stream().allMatch(s->s.isAlive()&&v.farm.owns(s)),"next wool demand uses the original parents, no replacement wild flock");
            var saved=VillageRuntime.GSON.fromJson(VillageRuntime.GSON.toJson(v.state),VillageState.class);saved.validate();
            require(saved.sheepFarm.flock.containsAll(parents.stream().map(Entity::getUUID).toList()),"same breeding flock persists");
            pass("next_bed_demand_reuses_same_persisted_flock_after_vanilla_grass_eating");return true;
        }
        return false;
    }
    static void paid(Village v,Resident r,TownBuilding.Plan p){
        if(p.matches(v))return;resetBag(r.bot());give(r.bot(),p.block().getBlock().asItem(),Construction.cost(p.step()));
        r.body().snapTo(Vec3.atBottomCenterOf(p.position().north(2)),0,0);
        require(Construction.place(v,r.bot(),p.step())&&p.matches(v)&&r.bot().inventory().count(p.block().getBlock().asItem())==0,"paid fixture blueprint "+p.position());
    }
}
