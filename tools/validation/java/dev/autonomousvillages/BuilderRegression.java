package dev.autonomousvillages;

import java.util.*;
import dev.navigationai.api.*;
import net.minecraft.core.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;
import static dev.autonomousvillages.VillageSelfTest.*;

/** Twelve adults, supplied donor/chest and terrain. Real courier work feeds normal Resident.tick. */
final class BuilderRegression {
    static Village town;static Resident builder,donor,porter;static BlockPos base;static TownBuilding house,road;
    static int delivered,batches,returns;static Map<Orders.Order,Integer> seen=new IdentityHashMap<>();
    static int clock,phase;static Map<String,Integer> jobs=new LinkedHashMap<>();
    static void begin(Village village,BlockPos origin){
        town=village;base=origin;builder=PlanningRegression.builder;clock=0;phase=0;jobs.clear();delivered=0;batches=0;returns=0;seen.clear();
        town.state.active=false;town.expeditions.stop();town.state.expedition=null;
        for(var r:List.copyOf(town.workers.values())){r.cancel();r.body().setNoAi(true);}
        town.workers.keySet().retainAll(Set.of(builder.id));town.state.residents.clear();town.state.residents.add(builder.id);town.state.residentRoles.clear();
        builder.body().setNoAi(false);builder.body().setHealth(20);builder.body().getInventory().clearContent();builder.body().getInventory().addItem(new ItemStack(Items.BREAD,3));
        builder.fresh=false;builder.cooldown=0;
        for(int n=0;n<11;n++){
            var extra=spawn(n<2?origin.offset(-15-n,0,-13):origin.offset(24,0,24));town.state.residents.add(extra.getUUID());
            var role=n==0?ResidentRoles.Role.LOGGER:n==1?ResidentRoles.Role.PORTER:ResidentRoles.Role.values()[n%7];
            town.state.residentRoles.put(extra.getUUID(),new ResidentRoles.Assignment(extra.getUUID(),role,"none","twelve-adult construction fixture"));
            if(n<2){extra.setNoAi(false);var handle=town.runtime.bots.attach(extra);var worker=new Resident(town,extra.getUUID());worker.fresh=false;town.workers.put(worker.id,worker);resetBag(handle);give(handle,Items.STONE_PICKAXE,1);give(handle,Items.STONE_AXE,1);if(n==0){donor=worker;give(handle,Items.OAK_LOG,32);}else porter=worker;}
        }
        role(ResidentRoles.Role.STEWARD);
        town.state.buildings.clear();town.state.orders.clear();town.state.storage.clear();town.state.furnaces.clear();town.state.land=new ManagedLand();town.index.tables.clear();town.index.crops.clear();town.index.trees.clear();town.town.invalidate();
        for(int x=-8;x<=2;x++)for(int z=-23;z<=-12;z++)town.level.setBlock(origin.offset(x,0,z),Blocks.GRASS_BLOCK.defaultBlockState(),3);
        var from=origin.offset(-15,0,-21);var target=origin.offset(-3,1,-21);
        road=Infrastructure.route(town,from,target);require(road!=null&&road.blocks.stream().anyMatch(p->p.replaceOwned),"real uphill route requires paid ground replacement");
        house=new TownBuilding("house",origin.offset(-5,1,-19));for(var step:Construction.shelter(BlockPos.of(house.origin)))house.add(step.pos(),step.state());house.approach=road.origin;
        town.state.buildings.add(road);town.state.buildings.add(house);town.town.invalidate();
        require(road.built(town)==0&&Infrastructure.approachReady(town,house),"already walkable soil steps and missing lights cannot deadlock bootstrap construction");
        pass("walkable_unlit_soil_approach_releases_building_while_road_improvements_remain_unpaid");
        var chest=origin.offset(-17,0,-17);town.level.setBlock(chest,Blocks.CHEST.defaultBlockState(),3);town.state.storage.add(chest.asLong());
        var stock=(ChestBlockEntity)town.level.getBlockEntity(chest);stock.clearContent();stock.setItem(2,new ItemStack(Items.COBBLESTONE,24));stock.setItem(3,new ItemStack(Items.COAL,8));stock.setItem(4,new ItemStack(Items.STICK,16));stock.setItem(5,new ItemStack(Items.WOOL.white(),6));stock.setItem(6,new ItemStack(Items.BREAD,64));
        var table=origin.offset(-17,0,-19);town.level.setBlock(table,Blocks.CRAFTING_TABLE.defaultBlockState(),3);town.index.tables.add(table);
        resetBag(builder.bot());give(builder.bot(),Items.STONE_PICKAXE,1);give(builder.bot(),Items.STONE_AXE,1);give(builder.bot(),Items.STONE_SWORD,1);give(builder.bot(),Items.STICK,8);give(builder.bot(),Items.FURNACE,1);
        builder.body().snapTo(Vec3.atBottomCenterOf(from),0,0);town.town.syncOrders(town.level.getGameTime());
        var bootstrapTree=from.north(2);var unpaidCut=from.east(3);
        town.level.setBlock(bootstrapTree.below(),Blocks.GRASS_BLOCK.defaultBlockState(),3);
        for(int y=0;y<3;y++)town.level.setBlock(bootstrapTree.above(y),Blocks.OAK_LOG.defaultBlockState(),3);
        town.level.setBlock(bootstrapTree.above(3),Blocks.OAK_LEAVES.defaultBlockState(),3);town.index.trees.add(bootstrapTree);
        town.level.setBlock(unpaidCut,Blocks.GRASS_BLOCK.defaultBlockState(),3);house.cuts.add(unpaidCut.asLong());
        resetBag(builder.bot());role(ResidentRoles.Role.BUILDER);cmd("time set 1000");builder.cooldown=0;builder.tick();
        require(Set.of("wood","storage").contains(builder.job),"unequipped primary builder obtains tool supplies before unrelated grading; job="+builder.job);
        pass("primary_builder_bootstraps_missing_tools_before_grading_more_sites");
        builder.cancel();town.index.trees.clear();town.state.fellingTrees.clear();town.state.treeWork.clear();house.cuts.clear();
        for(int y=0;y<4;y++)town.level.setBlock(bootstrapTree.above(y),Blocks.AIR.defaultBlockState(),3);town.level.setBlock(unpaidCut,Blocks.AIR.defaultBlockState(),3);
        var screen=from.east().above();town.level.setBlock(screen,Blocks.OAK_PLANKS.defaultBlockState(),3);
        resetBag(builder.bot());give(builder.bot(),Items.OAK_PLANKS,1);
        builder.build=new Construction.Step(unpaidCut,Blocks.OAK_PLANKS.defaultBlockState(),Items.OAK_PLANKS);builder.assign("build",unpaidCut);
        builder.navigation=builder.bot().navigator().goTo(new NavigationGoal.PositionGoal(Vec3.atBottomCenterOf(from.west(8)),.35),Resident.TRAVEL);
        builder.work(builder.bot());
        require(town.level.getBlockState(unpaidCut).is(Blocks.OAK_PLANKS)&&builder.bot().inventory().count(Items.OAK_PLANKS)==0&&builder.job==null,"in-reach assembly stops unnecessary approach despite an existing wall obscuring the empty target center");
        pass("paid_in_reach_assembly_does_not_wait_for_an_unnecessary_hidden_approach");
        town.level.setBlock(screen,Blocks.AIR.defaultBlockState(),3);town.level.setBlock(unpaidCut,Blocks.AIR.defaultBlockState(),3);
        resetBag(builder.bot());give(builder.bot(),Items.STONE_PICKAXE,1);give(builder.bot(),Items.STONE_AXE,1);give(builder.bot(),Items.STONE_SWORD,1);give(builder.bot(),Items.STICK,8);give(builder.bot(),Items.OAK_PLANKS,8);give(builder.bot(),Items.OAK_LOG,1);
        builder.cooldown=0;builder.tick();
        require(builder.job==null&&builder.bot().inventory().count(Items.OAK_LOG)==0&&builder.bot().inventory().count(Items.OAK_PLANKS)==12&&house.built(town)==0,"builder converts actual log before spending every small plank batch on the floor");
        builder.cooldown=0;builder.tick();
        require(builder.job==null&&builder.bot().inventory().count(Items.OAK_STAIRS)==4&&builder.bot().inventory().count(Items.OAK_PLANKS)==6&&house.built(town)==0,"actual six-plank entrance recipe is paid before optional plain-block placement; job="+builder.job+" bag="+builder.bot().inventory().snapshot().slots());
        pass("builder_prepares_real_input_batch_and_entrance_recipe_before_consuming_all_planks_in_masonry");builder.cancel();
        role(ResidentRoles.Role.STEWARD);resetBag(builder.bot());give(builder.bot(),Items.STONE_PICKAXE,1);give(builder.bot(),Items.STONE_AXE,1);give(builder.bot(),Items.STONE_SWORD,1);give(builder.bot(),Items.STICK,8);give(builder.bot(),Items.FURNACE,1);
        cmd("time set 14000");builder.assign("home",origin.offset(27,0,27));cmd("time set 1000");builder.tick();
        require(!"home".equals(builder.job),"healthy resident abandons obsolete night route on next work shift");
        pass("twelve_adults_release_overnight_route_on_day_shift");
    }
    static void role(ResidentRoles.Role role){town.state.residentRoles.put(builder.id,new ResidentRoles.Assignment(builder.id,role,"mason","construction fixture"));}
    static boolean tick(){
        clock++;require(clock<15000,"builder timeout phase="+phase+" job="+builder.job+" status="+builder.status+" pos="+builder.body().position()+" road="+road.built(town)+"/"+road.blocks.size()+" house="+house.built(town)+"/"+house.blocks.size()+" bag="+builder.bot().inventory().snapshot().slots());
        for(var order:town.state.orders)if(order.purpose.startsWith(ConstructionSupply.prefix(builder))){int previous=seen.getOrDefault(order,0);if(order.delivered>previous){delivered+=order.delivered-previous;batches++;}seen.put(order,order.delivered);}
        if(clock%100==0){
            var moving=town.state.orders.stream().filter(o->!o.carrier.isEmpty()).toList();
            town.town.syncOrders(town.level.getGameTime());town.state.validate();
            require(town.state.orders.containsAll(moving),"replanning retains exact courier order objects during collection and transit");
        }
        DeliveryRuns.tick(town);
        for(var helper:List.of(donor,porter)){
            boolean returning="delivery_return".equals(helper.job);var destination=helper.target;
            if(helper.job!=null)helper.work(helper.bot());else helper.bot().activities().run(List.of(new BotActivity.Wait(40)));
            if(returning&&helper.job==null&&helper.body().position().distanceToSqr(town.feetPosition(destination))<=.64){
                require(!town.town.constructionCell(helper.body().blockPosition())&&!town.town.constructionCell(helper.body().blockPosition().below()),"courier actually returned outside the building footprint");returns++;
            }
        }
        jobs.merge(builder.job==null?"idle":builder.job,1,Integer::sum);builder.tick();
        if(clock%500==0)log("BUILDER_PROGRESS phase="+phase+" job="+builder.job+" target="+builder.target+" nav="+builder.bot().navigator().status()+" road="+road.built(town)+" house="+house.built(town)+" pos="+builder.body().position()+" porter="+porter.job+"@"+porter.body().position()+" remaining="+house.blocks.stream().filter(p->!p.matches(town)).map(p->p.position()+" "+p.block()).toList());
        if(phase==0){
            if(town.state.furnaces.isEmpty())return false;
            require(builder.bot().inventory().count(Items.FURNACE)==0&&town.state.buildings.stream().noneMatch(b->b.kind.equals("warehouse")),"carried furnace paid and registered before any warehouse exists");
            pass("bootstrap_steward_physically_installs_paid_furnace_before_warehouse_and_road_lighting");role(ResidentRoles.Role.BUILDER);builder.cancel();
            var ripe=base.offset(-13,0,-17);town.level.setBlock(ripe.below(),Blocks.FARMLAND.defaultBlockState(),3);town.level.setBlock(ripe,Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,7),3);town.index.crops.add(ripe);
            require(TownCrafting.ingredientReserve(builder,new ItemStack(Items.COBBLESTONE))>=6,"primary builder fetches real stone input for pending stairs");phase=1;
        }
        if(phase==1&&road.built(town)==road.blocks.size()){
            require(Infrastructure.approachReady(town,house),"real paid route releases house dependency");pass("primary_builder_fetches_crafts_mines_and_finishes_real_uphill_road");phase=2;
        }
        if(phase==2&&house.built(town)==house.blocks.size()){
            require(delivered>8&&batches>=2&&donor.count(Orders.Material.LOGS)>=donor.kit(Orders.Material.LOGS),"multiple real timber batches replenished after consumption, donor kit conserved; logs="+delivered+" batches="+batches);
            pass("porter_physically_collects_and_delivers_refillable_construction_batches_preserving_donor_kit");
            require(returns>=1,"porter clears the construction site and physically returns to a safe store waiting point");
            pass("porter_physically_returns_after_handoff_instead_of_parking_on_future_entrance_stairs");
            require(jobs.getOrDefault("harvest",0)==0&&town.primaryRole(builder.id)==ResidentRoles.Role.BUILDER,"primary builder kept construction specialization despite ripe crop");
            require(house.blocks.stream().filter(p->p.block().getBlock() instanceof BedBlock).allMatch(p->p.matches(town)),"two actual paid beds installed");
            pass("primary_builder_completes_paid_two_bed_house_using_normal_scheduler_without_placement_or_travel_shortcuts");
            log("BUILDER_JOBS "+jobs+" simulatedTicks="+clock);return true;
        }
        return false;
    }
}
