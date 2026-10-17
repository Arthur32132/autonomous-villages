package dev.autonomousvillages;

import java.util.*;
import dev.navigationai.api.*;
import net.minecraft.core.*;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.*;
import static dev.autonomousvillages.VillageSelfTest.*;

/** Extended physical checks; terrain, positions and materials are explicit fixtures. */
final class EngineeringRegression {
    static Village town;static Resident builder,farmer;static BlockPos base,end,tree;
    static int phase,clock,bornAt;static NavigationTask walk;static boolean crossed;
    static Set<UUID> initial;static Villager child;static String parentRoles;
    static net.minecraft.world.entity.ai.behavior.VillagerMakeLove courtship;static boolean courting;
    static void begin(){
        town=PlanningRegression.town;builder=PlanningRegression.builder;farmer=PlanningRegression.farmer;base=PlanningRegression.base;
        phase=0;clock=0;crossed=false;child=null;courtship=new net.minecraft.world.entity.ai.behavior.VillagerMakeLove();courting=false;
        var from=base.offset(-14,0,-18);end=base.offset(-8,0,-18);
        for(int x=-12;x<=-11;x++)for(int z=-24;z<=-10;z++)town.level.setBlock(base.offset(x,-1,z),Blocks.WATER.defaultBlockState(),3);
        town.level.setBlock(base.offset(-10,-1,-18),Blocks.WATER.defaultBlockState(),3);
        require(Infrastructure.route(town,from,end)==null,"three water columns exceed supported bridge span");
        town.level.setBlock(base.offset(-10,-1,-18),Blocks.GRASS_BLOCK.defaultBlockState(),3);
        var bridge=Infrastructure.route(town,from,end);require(bridge!=null&&bridge.blocks.stream().anyMatch(p->p.block().is(Blocks.OAK_FENCE)),"two-cell water route plans real deck and rails");
        town.state.buildings.add(bridge);town.town.invalidate();for(var p:bridge.blocks)PlanningRegression.paid(builder,p);
        bridge.completed=true;PlanningRegression.near(builder,from);walk=builder.bot().navigator().goTo(new NavigationGoal.PositionGoal(Vec3.atBottomCenterOf(end),.4),Resident.TRAVEL);
    }
    static boolean tick(){
        if(phase<3&&farmer.job==null)farmer.bot().activities().run(List.of(new BotActivity.Wait(40)));
        clock++;require(clock<34000,"systems timeout phase="+phase+" child="+(child==null?"none":child.getAge())+" builder="+builder.body().position());
        if(phase==0){
            var p=builder.body().position();crossed|=p.x>=base.getX()-12&&p.x<base.getX()-10&&Math.abs(p.z-(base.getZ()-17.5))<.8&&p.y>base.getY()+.4;
            if(!walk.completion().isDone())return false;
            require(walk.status()==NavigationStatus.SUCCESS&&crossed&&builder.body().distanceToSqr(Vec3.atBottomCenterOf(end))<2,"resident actually crossed paid deck above water, without teleporting or bank detour: "+walk.status()+" crossed="+crossed);
            pass("paid_two_cell_bridge_is_physically_crossed_and_three_cell_span_rejected");
            var post=PlanningRegression.workshop("watch_post",base.offset(-4,0,3));var point=ProductionBuildings.post(town);
            require(point.equals(BlockPos.of(post.origin).offset(2,1,2))&&ProductionBuildings.accepts("watch_post",new ItemStack(Items.IRON_CHESTPLATE))&&!ProductionBuildings.accepts("watch_post",new ItemStack(Items.OAK_LOG)),"real guard station and specialized supply chest");
            PlanningRegression.role(builder,ResidentRoles.Role.GUARD);PlanningRegression.near(builder,base.offset(-4,0,-4));builder.assign("guard",point);phase=1;return false;
        }
        if(phase==1){
            if(builder.job!=null){builder.work(builder.bot());return false;}
            require(builder.body().blockPosition().distSqr(ProductionBuildings.post(town))<=16&&!builder.canHelp(),"guard reaches actual post and stays out of temporary cargo work");
            pass("guard_walks_to_paid_watch_post_with_distinct_equipment_storage");
            PlanningRegression.role(builder,ResidentRoles.Role.LOGGER);tree=base.offset(-18,0,7);
            for(int y=0;y<3;y++)town.level.setBlock(tree.above(y),Blocks.OAK_LOG.defaultBlockState(),3);
            town.level.setBlock(tree.above(3),Blocks.OAK_LEAVES.defaultBlockState(),3);
            town.index.trees.clear();town.index.trees.add(tree);var lot=new ManagedLand.Forest();lot.center=tree.asLong();town.state.land.forests.add(lot);
            resetBag(builder.bot());give(builder.bot(),Items.IRON_AXE,1);give(builder.bot(),Items.OAK_SAPLING,1);PlanningRegression.near(builder,tree.east(2));cmd("time set 1000");builder.assign("wood",tree);phase=2;return false;
        }
        if(phase==2){
            if(builder.job!=null){builder.work(builder.bot());return false;}
            require(builder.bot().inventory().count(Items.OAK_LOG)==3&&(town.state.reforestation.contains(tree.asLong())||town.level.getBlockState(tree).is(Blocks.OAK_SAPLING)),"actual three-log harvest is recorded for replanting: bag="+builder.bot().inventory().snapshot().slots()+" block="+town.level.getBlockState(tree)+" status="+builder.status);
            PlanningRegression.paid(builder,new TownBuilding.Plan(tree,Blocks.OAK_SAPLING.defaultBlockState()));
            var lot=ManagedLand.owner(town,tree);require(lot.harvested==1&&lot.replanted==1&&lot.restUntil>town.level.getGameTime()&&!ManagedLand.mayHarvest(town,tree),"real planted sapling rests its harvested lot");
            var other=base.offset(18,0,20);town.level.setBlock(other,Blocks.OAK_LOG.defaultBlockState(),3);town.level.setBlock(other.above(),Blocks.OAK_LEAVES.defaultBlockState(),3);town.index.trees.add(other);
            var next=new ManagedLand.Forest();next.center=other.asLong();town.state.land.forests.add(next);require(ManagedLand.mayHarvest(town,other),"rotation moves to another actual stocked lot");
            pass("real_felling_paid_replant_and_rest_rotate_to_another_stocked_lot");
            int z=town.state.policy.zones.size();cmd("avillage protect Test wood "+other.toShortString().replace(",","")+" "+other.toShortString().replace(",",""));
            builder.assign("wood",other);require(builder.job==null&&!town.state.policy.allows("wood",other),"real command blocks assignment on protected lot");cmd("avillage unprotect Test "+z);require(town.state.policy.allows("wood",other),"unprotect releases the actual plot");
            cmd("avillage expansion Test east");var bounds=town.state.bounds;
            require(!town.state.policy.expansionAllowed(bounds,new VillageState.Bounds(bounds.minX-1,bounds.minZ,bounds.maxX,bounds.maxZ))&&town.state.policy.expansionAllowed(bounds,new VillageState.Bounds(bounds.minX,bounds.minZ,bounds.maxX+1,bounds.maxZ)),"command permits east expansion only");
            cmd("avillage orders Test");cmd("avillage expansion Test all");
            pass("real_protect_unprotect_expansion_and_order_commands_apply_to_work");
            PlanningRegression.role(builder,ResidentRoles.Role.MINER);builder.cancel();farmer.cancel();builder.bot().stopAll();farmer.bot().stopAll();
            initial=new HashSet<>();town.level.getEntitiesOfClass(Villager.class,new AABB(base).inflate(80)).forEach(e->initial.add(e.getUUID()));
            parentRoles=VillageRuntime.GSON.toJson(town.state.residentRoles);
            for(var r:List.of(builder,farmer)){r.body().getBrain().eraseMemory(MemoryModuleType.JOB_SITE);r.body().getBrain().eraseMemory(MemoryModuleType.HOME);r.body().getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);}
            PlanningRegression.near(builder,base.offset(-10,1,14));PlanningRegression.near(farmer,base.offset(-9,1,14));
            town.level.getPoiManager().ensureLoadedAndValid(town.level,base,48);cmd("time set 9500");bornAt=clock;phase=3;return false;
        }
        if(phase==3){
            // Choose only the pair in this fixture; vanilla still checks food, finds a reachable
            // vacant bed, consumes food and creates the child. No child or age is injected.
            var a=builder.body();var b=farmer.body();long now=town.level.getGameTime();
            if(!courting){
                a.getBrain().setMemory(MemoryModuleType.BREED_TARGET,b);
                a.getBrain().setMemory(MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES,new net.minecraft.world.entity.ai.memory.NearestVisibleLivingEntities(town.level,a,List.of(b)));
                courting=courtship.tryStart(town.level,a,now);
            }else{courtship.tickOrStop(town.level,a,now);if(courtship.getStatus()==net.minecraft.world.entity.ai.behavior.Behavior.Status.STOPPED)courting=false;}
            var babies=town.level.getEntitiesOfClass(Villager.class,new AABB(base).inflate(80),e->e.isBaby()&&!initial.contains(e.getUUID()));
            if(babies.isEmpty()){require(clock-bornAt<5000,"native parents did not produce child with real surplus and four actual sheltered beds: ages="+a.getAge()+","+b.getAge()+" food="+a.getInventory().countItem(Items.BREAD)+","+b.getInventory().countItem(Items.BREAD)+" pos="+a.position()+","+b.position()+" courtship="+courting);return false;}
            child=babies.getFirst();bornAt=clock;town.state.residents.add(child.getUUID());town.syncRoles();
            require(!town.state.residentRoles.containsKey(child.getUUID())&&parentRoles.equals(VillageRuntime.GSON.toJson(town.state.residentRoles)),"real newborn has no job and preserves parent assignments");
            pass("native_parents_create_real_child_using_paid_housing_and_actual_food");phase=4;return false;
        }
        if(phase==4){
            PopulationPlanning.tick(town);require(child.isAlive(),"native child remains alive on peaceful");if(child.isBaby())return false;
            require(clock-bornAt>=23990,"adulthood required actual vanilla age ticks");town.sync();town.state.residents.retainAll(Set.of(builder.id,farmer.id,child.getUUID()));town.workers.keySet().retainAll(town.state.residents);town.state.residentRoles.keySet().retainAll(town.state.residents);
            require(town.workers.containsKey(child.getUUID())&&town.primaryRole(child.getUUID())==ResidentRoles.Role.STEWARD&&town.primaryRole(builder.id)==ResidentRoles.Role.MINER&&town.primaryRole(farmer.id)==ResidentRoles.Role.FARMER,"naturally grown worker gets missing specialty and preserves parents");
            town.workers.get(child.getUUID()).bot().stopAll();pass("native_child_ages_24000_ticks_then_joins_missing_specialty_without_parent_reassignment");return true;
        }
        return false;
    }
}
