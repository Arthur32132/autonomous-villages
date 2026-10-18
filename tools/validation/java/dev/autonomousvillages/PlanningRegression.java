package dev.autonomousvillages;

import java.util.*;
import dev.navigationai.api.*;
import net.minecraft.core.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.Vec3;
import static dev.autonomousvillages.VillageSelfTest.*;

/** Real-world payment and interaction fixture. Positions/materials are supplied; not autonomy acceptance. */
final class PlanningRegression {
    static Village town;static Resident builder,farmer;static BlockPos base,soil,water,source;
    static TownBuilding graded;static ManagedLand.Field field;static FurnaceBlockEntity smelter;
    static int phase,clock;static MiningTask forbidden;
    static void begin(Village v,BlockPos origin){
        town=v;base=origin;phase=0;clock=0;v.state.active=false;v.expeditions.stop();
        v.state.buildings.clear();v.state.orders.clear();v.state.storage.clear();v.state.furnaces.clear();v.index.tables.clear();v.index.trees.clear();
        v.state.routeRoot=null;v.state.accessFailures.clear();
        v.state.land=new ManagedLand();v.state.policy=new VillagePolicy();v.state.workshopOutput.clear();v.state.vacantRoles.clear();v.town.invalidate();
        v.state.bounds=new VillageState.Bounds(origin.getX()-28,origin.getZ()-28,origin.getX()+28,origin.getZ()+28);
        v.state.x=origin.getX();v.state.y=origin.getY();v.state.z=origin.getZ();
        var people=new ArrayList<>(v.workers.values());builder=people.get(0);farmer=people.get(1);
        for(var r:people){r.cancel();resetBag(r.bot());r.body().getInventory().clearContent();r.body().setNoAi(false);r.body().setHealth(20);r.fresh=false;r.body().snapTo(Vec3.atBottomCenterOf(origin.offset(-20,0,-10)),0,0);}
        v.state.residents.retainAll(Set.of(builder.id,farmer.id));
        for(var r:people)if(r!=builder&&r!=farmer){r.body().setNoAi(true);v.workers.remove(r.id);}
        v.state.residentRoles.keySet().retainAll(v.state.residents);
        role(builder,ResidentRoles.Role.BUILDER);role(farmer,ResidentRoles.Role.FARMER);
        soil=origin.offset(18,0,-10);v.level.setBlock(soil,Blocks.GRASS_BLOCK.defaultBlockState(),3);
        // The new graph may go around this water instead of forcing a rectangular crossing.
        for(int x=4;x<=5;x++)v.level.setBlock(origin.offset(x,-1,-12),Blocks.WATER.defaultBlockState(),3);
        graded=new TownBuilding("house",soil);graded.add(soil,Blocks.OAK_PLANKS.defaultBlockState());
        require(Infrastructure.site(v,soil,1,1)!=null,"raised soil site accepts bounded real earthworks");
        require(Infrastructure.prepare(v,graded,1,1)&&!graded.cuts.isEmpty(),"real raised soil has a paid preparation and route from "+v.meetingPoint()+" to "+soil);
        v.state.buildings.add(graded);v.town.invalidate();give(builder.bot(),Items.STONE_SHOVEL,1);give(builder.bot(),Items.COBBLESTONE,8);
        require(Infrastructure.approachReady(v,graded),"search reuses safe ground around the two water cells without waiting for lighting");
        pass("network_detours_around_water_and_unbuilt_lighting_does_not_block_access");
        for(var b:List.copyOf(v.state.buildings))if(b.kind.equals("road"))for(var p:b.blocks){require(p.pos!=soil.asLong(),"approach cannot assign a stair on future building floor");paid(builder,p);}
        near(builder,soil.west());require(Infrastructure.work(builder)&&"grade".equals(builder.job),"ready approach releases actual grading job");
    }
    static void role(Resident r,ResidentRoles.Role role){town.state.residentRoles.put(r.id,new ResidentRoles.Assignment(r.id,role,"none","planning fixture"));}
    static void near(Resident r,BlockPos feet){r.body().snapTo(Vec3.atBottomCenterOf(feet),0,0);r.body().setDeltaMovement(Vec3.ZERO);}
    static void paid(Resident r,TownBuilding.Plan plan){
        if(plan.matches(town))return;near(r,plan.position().north(2));resetBag(r.bot());give(r.bot(),plan.step().item(),Construction.cost(plan.step()));
        require(Construction.place(town,r.bot(),plan.step())&&plan.matches(town)&&r.bot().inventory().count(plan.step().item())==0,"real paid placement "+plan.position()+" "+plan.block());
    }
    static TownBuilding workshop(String kind,BlockPos pos){
        var b=ProductionBuildings.blueprint(kind,pos);b.validate(town.state);
        require(b.blocks.stream().map(p->p.pos).distinct().count()==b.blocks.size(),"workshop has unique physical cells "+kind);
        town.state.buildings.add(b);town.town.invalidate();for(var p:b.blocks)paid(builder,p);b.completed=true;return b;
    }
    static boolean tick(){
        if(phase!=5)for(var r:List.of(builder,farmer))if(r.job==null)r.bot().activities().run(List.of(new BotActivity.Wait(40)));
        clock++;require(clock<(systemsOnly?40000:4000),"planning timeout phase="+phase+" job="+builder.job+" pos="+builder.body().position());
        if(phase==0){
            if(builder.job!=null){builder.work(builder.bot());return false;}
            require(Construction.clear(town.level.getBlockState(soil))&&builder.bot().inventory().count(Items.DIRT)>0,"actual grading mined soil and collected drop");
            paid(builder,graded.blocks.getFirst());require(Infrastructure.prepared(town,graded),"paid floor at cut cell retains prepared status");graded.completed=true;
            pass("real_grading_preserves_drop_and_replacement_does_not_reopen_cut");
            field=new ManagedLand.Field();BlockPos f=base.offset(8,0,8);field.water=f.offset(3,-1,3).asLong();field.entry=f.offset(3,0,-1).asLong();
            field.seedChest=f.offset(1,0,0).asLong();field.produceChest=f.offset(5,0,0).asLong();water=BlockPos.of(field.water);source=base.offset(9,-1,7);
            // The generic flat fixture is a single grass layer over the generated terrain.
            // Give the dug irrigation hole real subsoil so its real dirt drop cannot fall below the lab.
            town.level.setBlock(water.below(),Blocks.STONE.defaultBlockState(),3);
            for(int x=1;x<=5;x++)for(int z=1;z<=5;z++)if(x!=3)field.cells.add(f.offset(x,0,z).asLong());town.state.land.fields.add(field);
            var plan=new TownBuilding("field",f);plan.add(BlockPos.of(field.seedChest),Blocks.CHEST.defaultBlockState());plan.add(BlockPos.of(field.produceChest),Blocks.CHEST.defaultBlockState());plan.add(f.offset(2,0,0),Blocks.CRAFTING_TABLE.defaultBlockState());
            town.state.buildings.add(plan);for(var p:plan.blocks)paid(builder,p);plan.completed=true;
            resetBag(farmer.bot());give(farmer.bot(),Items.IRON_INGOT,3);near(farmer,f.offset(2,0,2));
            require(IronSupply.next(town).recipient()==farmer,"primary farmer owns one irrigation bucket request even in two-worker helper mode");
            require(VillageWorkshop.craft(farmer.bot(),town,Items.BUCKET,new String[]{"I I"," I "},s->s.is(Items.IRON_INGOT))&&farmer.bot().inventory().count(Items.IRON_INGOT)==0,"real three-ingot bucket recipe at field workbench");
            near(farmer,water.above().east());require(!ManagedLand.transferWater(farmer,water,false),"cannot pour through unmined real soil");
            farmer.assign("dig_irrigation",water);phase=1;return false;
        }
        if(phase==1){
            if(farmer.job!=null){farmer.work(farmer.bot());return false;}
            require(farmer.bot().inventory().count(Items.DIRT)>0&&town.level.getBlockState(water).isAir(),"irrigation hole paid by actual mining");
            town.level.setBlock(source,Blocks.WATER.defaultBlockState(),3);near(farmer,source.above().west());farmer.assign("fill_bucket",source);farmer.work(farmer.bot());
            require(farmer.job==null&&farmer.bot().inventory().count(Items.WATER_BUCKET)==1&&town.level.getBlockState(source).isAir(),"actual source goes into one filled bucket");
            near(farmer,water.above().east());farmer.assign("irrigate",water);farmer.work(farmer.bot());
            require(farmer.job==null&&farmer.bot().inventory().count(Items.BUCKET)==1&&farmer.bot().inventory().count(Items.WATER_BUCKET)==0&&town.level.getFluidState(water).isSource(),"same bucket places actual field water");
            pass("real_bucket_recipe_soil_mining_source_pickup_and_irrigation_conserve_materials");
            var bank=(ChestBlockEntity)town.level.getBlockEntity(BlockPos.of(field.seedChest));bank.setItem(0,new ItemStack(Items.CARROT,16));
            require(town.count(s->s.is(Items.CARROT))==0&&ManagedLand.withdrawLimit(new Resident(town,UUID.randomUUID()),field.seedChest,new ItemStack(Items.CARROT),8)==0,"seed bank excluded from shared food and unassigned resident access");
            near(farmer,BlockPos.of(field.seedChest).west());farmer.depositOrFetch(farmer.bot(),false);
            require(bank.countItem(Items.CARROT)==8&&farmer.bot().inventory().count(Items.CARROT)==8&&SettlementEconomy.food(town)==0,"farmer takes eight real planting carrots without making edible free stock");
            pass("seed_bank_access_and_food_budget_preserve_real_planting_reserve");
            var yard=workshop("lumber_yard",base.offset(-24,0,-23));role(builder,ResidentRoles.Role.LOGGER);resetBag(builder.bot());give(builder.bot(),Items.OAK_LOG,1);near(builder,BlockPos.of(yard.origin).offset(2,1,2));
            require(ProductionBuildings.work(builder)&&builder.bot().inventory().count(Items.OAK_LOG)==0&&builder.bot().inventory().count(Items.OAK_PLANKS)==4&&town.state.workshopOutput.getOrDefault("lumber_yard",0L)==4,"yard processes actual log into measured planks");
            var barn=workshop("granary",base.offset(-24,0,12));resetBag(farmer.bot());give(farmer.bot(),Items.WHEAT,3);near(farmer,BlockPos.of(barn.origin).offset(2,1,2));
            require(ProductionBuildings.work(farmer)&&farmer.bot().inventory().count(Items.WHEAT)==0&&farmer.bot().inventory().count(Items.BREAD)==1&&town.state.workshopOutput.getOrDefault("granary",0L)==1,"barn processes real wheat into measured bread");
            var smith=workshop("smithy",base.offset(12,0,-23));role(builder,ResidentRoles.Role.MINER);resetBag(builder.bot());give(builder.bot(),Items.RAW_IRON,1);give(builder.bot(),Items.COAL,1);
            smelter=(FurnaceBlockEntity)town.level.getBlockEntity(BlockPos.of(smith.origin).offset(1,1,4));near(builder,smelter.getBlockPos().south());
            require(ProductionBuildings.work(builder)&&"furnace".equals(builder.job),"primary miner selects smithy despite small-crew farmer helper status");
            builder.work(builder.bot());require(builder.job==null&&smelter.getItem(0).is(Items.RAW_IRON),"smithy receives actual raw iron and coal");
            phase=2;return false;
        }
        if(phase==2){
            near(builder,smelter.getBlockPos().south());if(!smelter.getItem(2).is(Items.IRON_INGOT))return false;
            require(VillageWorkshop.serviceFurnace(builder.bot(),town,smelter)&&builder.bot().inventory().count(Items.IRON_INGOT)==1&&town.state.workshopOutput.getOrDefault("smithy",0L)==1,"real furnace ingot counted only after actual output transfer");
            pass("distinct_workshops_pay_actual_blocks_and_measure_planks_bread_and_smelted_iron");
            var blocked=base.offset(-4,0,-4);town.level.setBlock(blocked,Blocks.STONE.defaultBlockState(),3);
            var z=new VillagePolicy.Zone();z.activity="mine";z.minX=z.maxX=blocked.getX();z.minZ=z.maxZ=blocked.getZ();town.state.policy.zones.add(z);
            builder.cancel();builder.job="escape";builder.target=blocked;near(builder,blocked.west());give(builder.bot(),Items.STONE_PICKAXE,1);
            require(!town.runtime.mayBreak(builder.id,blocked)&&town.state.policy.allows(Resident.activity("home"),blocked),"auxiliary mining denied while movement home stays allowed");
            forbidden=builder.bot().miner().breakBlock(blocked);phase=3;return false;
        }
        if(phase==3){
            if(!forbidden.completion().isDone())return false;
            require(forbidden.status()!=MiningStatus.SUCCESS&&town.level.getBlockState(builder.target).is(Blocks.STONE),"library mining refuses protected real stone");builder.cancel();
            var edge=base.offset(28,0,3);var chest=Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING,Direction.NORTH);
            town.level.setBlock(edge,chest.setValue(ChestBlock.TYPE,ChestType.LEFT),18);town.level.setBlock(edge.east(),chest.setValue(ChestBlock.TYPE,ChestType.RIGHT),18);town.state.storage.add(edge.asLong());
            cmd("avillage private Test "+edge.toShortString().replace(",", ""));town.state.validate();
            require(town.state.policy.isPrivate(town,edge)&&town.state.policy.isPrivate(town,edge.east())&&!town.state.storage.contains(edge.asLong()),"boundary double chest private without corrupting saved territory");
            var repair=new TownBuilding("native_house",base.offset(-5,0,5));repair.completed=true;repair.add(BlockPos.of(repair.origin),Blocks.OAK_PLANKS.defaultBlockState());town.state.buildings.add(repair);town.town.invalidate();
            cmd("avillage accept Test "+BlockPos.of(repair.origin).toShortString().replace(",", ""));
            require(repair.blocks.isEmpty(),"explicit accepted absent wall is no longer a repair target");
            var lower=BlockPos.of(repair.origin).south(2);var door=Blocks.OAK_DOOR.defaultBlockState();repair.add(lower,door);town.level.setBlock(lower,door,18);
            cmd("avillage accept Test "+lower.above().toShortString().replace(",", ""));require(repair.blocks.isEmpty(),"accepting removed upper door does not re-adopt the orphan lower half");
            pass("real_commands_preserve_boundary_chest_save_and_accept_removed_house_cell");
            resetBag(farmer.bot());give(farmer.bot(),Items.WOODEN_HOE,1);give(farmer.bot(),Items.WHEAT_SEEDS,32);
            for(long packed:field.cells){var p=BlockPos.of(packed);near(farmer,p.north());farmer.assign("hoe",p);farmer.work(farmer.bot());require(farmer.job==null&&town.level.getBlockState(p.below()).is(Blocks.FARMLAND),"actual watered cell hoed "+p);}
            var beds=new ArrayList<BlockPos>();for(var offset:List.of(new BlockPos(-12,0,12),new BlockPos(6,0,16))){
                var house=new TownBuilding("house",base.offset(offset));for(var step:Construction.shelter(BlockPos.of(house.origin)))house.add(step.pos(),step.state());town.state.buildings.add(house);town.town.invalidate();
                for(var p:house.blocks)paid(builder,p);house.completed=true;for(var p:house.blocks)if(p.block().getBlock() instanceof BedBlock)beds.add(p.position().south());
            }
            town.index.beds=List.copyOf(beds);var food=(ChestBlockEntity)town.level.getBlockEntity(BlockPos.of(field.produceChest));food.setItem(0,new ItemStack(Items.BREAD,64));
            var indoor=base.offset(-11,1,13);paid(builder,new TownBuilding.Plan(indoor,Blocks.CHEST.defaultBlockState()));
            var previousStorage=new ArrayList<>(town.state.storage);town.state.storage.clear();town.state.storage.add(indoor.asLong());
            require(town.meetingPoint().equals(indoor)&&!town.safeFeet(indoor),"fixture meeting point is actual obstructed indoor chest below roof: "+town.meetingPoint());
            var nextHouse=new TownBuilding("house",base.offset(-3,0,-20));
            require(Infrastructure.prepare(town,nextHouse,5,5),"new plot gets real access from house exit despite indoor chest meeting point");
            town.state.storage.addAll(previousStorage);
            pass("indoor_storage_below_roof_does_not_block_outdoor_building_access");
            builder.body().getInventory().clearContent();farmer.body().getInventory().clearContent();near(builder,food.getBlockPos().west());near(farmer,food.getBlockPos().north());
            require(SettlementEconomy.canGrow(town),"real intact rooms, twenty watered farmland cells and surplus food permit growth");cmd("time set 9500");phase=4;return false;
        }
        if(phase==4){
            if(town.level.getGameTime()%100!=0)return false;
            near(builder,BlockPos.of(field.produceChest).west());near(farmer,BlockPos.of(field.produceChest).north());PopulationPlanning.tick(town);
            require(builder.body().getInventory().countItem(Items.BREAD)==6&&farmer.body().getInventory().countItem(Items.BREAD)==6,"native breeding pair receives actual nearby surplus bread");
            var food=(ChestBlockEntity)town.level.getBlockEntity(BlockPos.of(field.produceChest));require(food.countItem(Items.BREAD)==52,"pair bread conserved from actual chest");
            pass("growth_prerequisites_precede_real_native_food_issue_without_spawning_children");
            if(buildersOnly){BuilderRegression.begin(town,base);stage=73;since=age;return false;}
            if(systemsOnly){EngineeringRegression.begin();phase=5;return false;}
            return finish();
        }
        if(phase==5&&EngineeringRegression.tick())return finish();
        return false;
    }
    static boolean finish(){
            var order=new Orders.Order();order.material="WOOL";order.purpose="bed_wool";order.quantity=6;order.recipient=farmer.id.toString();order.work=graded.origin;order.delivery=base.asLong();town.state.orders.add(order);
            give(farmer.bot(),Items.IRON_INGOT,2);farmer.body().kill(town.level);town.sync();PopulationPlanning.recoverVacancies(town);PopulationPlanning.reassignOrders(town);
            require(town.primaryRole(builder.id)==ResidentRoles.Role.FARMER&&order.recipient.equals(builder.id.toString()),"confirmed death transfers essential specialization and order to living successor");
            require(town.state.orders.stream().anyMatch(o->o.returnCargo()&&o.kind()==Orders.Material.IRON&&o.quantity==2),"actual dead worker iron retained as recoverable dropped cargo");
            town.state.validate();pass("confirmed_death_transfers_role_and_order_and_preserves_real_drop_recovery");
            log("PLANNING_TPS target="+server.tickRateManager().tickrate()+" simulatedTicks="+clock);return true;
    }
}
