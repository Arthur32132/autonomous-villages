package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.*;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.Vec3;
import static dev.autonomousvillages.VillageSelfTest.*;

/** Focused real-entity role, native schedule, cargo and selection checks. Not an autonomy soak. */
final class RolesRegression {
    static void run(Village v,BlockPos origin){
        v.state.active=false;v.expeditions.stop();v.state.expedition=null;
        v.state.buildings.clear();v.state.orders.clear();v.state.storage.clear();v.state.furnaces.clear();
        v.index.crops.clear();v.index.trees.clear();v.index.soil.clear();v.index.tables.clear();v.index.stone.clear();v.index.ore.clear();v.town.invalidate();
        var people=new ArrayList<Villager>();
        for(var r:v.workers.values()){
            r.cancel();r.body().setNoAi(people.size()>=7);
            if(people.size()<7)people.add(r.body());
        }
        while(people.size()<7){var body=spawn(origin.offset(people.size(),0,4));body.setNoAi(false);v.state.residents.add(body.getUUID());people.add(body);}
        // The generated seed can contain additional native villagers in the discovered
        // footprint. Isolate exactly seven already-attached adults, rather than assuming
        // that only the two entities created by the initial claim fixture exist.
        v.state.residents.clear();people.forEach(body->v.state.residents.add(body.getUUID()));
        v.workers.keySet().retainAll(v.state.residents);
        var professions=List.of(VillagerProfession.FARMER,VillagerProfession.MASON,VillagerProfession.ARMORER,
                VillagerProfession.TOOLSMITH,VillagerProfession.CARTOGRAPHER,VillagerProfession.FLETCHER,VillagerProfession.LIBRARIAN);
        for(int i=0;i<7;i++)people.get(i).setVillagerData(people.get(i).getVillagerData().withProfession(v.level.registryAccess(),professions.get(i)).withLevel(2));
        v.state.residentRoles.clear();v.sync();
        require(v.state.residentRoles.size()==7&&v.state.residentRoles.values().stream().map(a->a.role).distinct().count()==7,"seven native professions cover seven explicit roles: "+VillageRuntime.GSON.toJson(v.state.residentRoles));
        Resident farmer=role(v,ResidentRoles.Role.FARMER),builder=role(v,ResidentRoles.Role.BUILDER),
                porter=role(v,ResidentRoles.Role.PORTER),guard=role(v,ResidentRoles.Role.GUARD),logger=role(v,ResidentRoles.Role.LOGGER);
        require(farmer.id.equals(people.get(0).getUUID())&&builder.id.equals(people.get(1).getUUID())&&guard.id.equals(people.get(2).getUUID()),"native farmer/mason/armorer assigned matching duties");
        pass("native_professions_assign_seven_explicit_permanent_specialties");

        var before=VillageRuntime.GSON.toJson(v.state.residentRoles);
        var child=spawn(origin.offset(8,0,4));child.setAge(-24000);child.setNoAi(false);v.state.residents.add(child.getUUID());
        v.sync();require(!v.state.residentRoles.containsKey(child.getUUID())&&before.equals(VillageRuntime.GSON.toJson(v.state.residentRoles)),"new child has no job and changes no adult assignment");
        // Remove the loaded controller from the scheduling roster; membership stays intact.
        var absent=v.workers.remove(logger.id);v.syncRoles();
        require(before.equals(VillageRuntime.GSON.toJson(v.state.residentRoles)),"temporarily unavailable neighbour does not reshuffle roles or schedule");
        v.workers.put(logger.id,absent);
        var restored=VillageRuntime.GSON.fromJson(VillageRuntime.GSON.toJson(v.state),VillageState.class);restored.validate();
        require(before.equals(VillageRuntime.GSON.toJson(restored.residentRoles)),"assignments, profession reason and native schedule slots survive serialization");
        pass("birth_unavailable_neighbour_and_save_reload_preserve_all_adult_assignments");

        cmd("time set 1000");
        for(var r:v.workers.values()){
            r.cancel();resetBag(r.bot());r.body().setNoAi(false);r.body().setHealth(20);r.body().stopSleeping();
            r.body().getInventory().clearContent();r.body().getBrain().eraseMemory(MemoryModuleType.JOB_SITE);
            r.body().snapTo(Vec3.atBottomCenterOf(origin.offset(12,0,12)),0,0);r.fresh=false;r.cooldown=0;
            give(r.bot(),Items.WOODEN_AXE,1);give(r.bot(),Items.STONE_PICKAXE,1);give(r.bot(),Items.WOODEN_HOE,1);
            r.body().getInventory().addItem(new ItemStack(Items.BREAD,3));
        }
        farmer.body().snapTo(Vec3.atBottomCenterOf(origin),0,0);builder.body().snapTo(Vec3.atBottomCenterOf(origin.east(2)),0,0);
        give(farmer.bot(),Items.OAK_PLANKS,14);give(farmer.bot(),Items.STONE_PICKAXE,1);
        var assignment=v.state.residentRoles.get(farmer.id);
        var order=order(v,builder,6);require(farmer.canHelp(),"idle farmer can temporarily help before harvest ripens");
        farmer.deliver(order,builder.id);farmer.work(farmer.bot());
        require(order.delivered==6&&farmer.bot().inventory().count(Items.OAK_PLANKS)==8&&builder.bot().inventory().count(Items.OAK_PLANKS)==6,"real six planks delivered; farmer keeps eight personal planks");
        BlockPos crop=origin.west(2);v.level.setBlock(crop.below(),Blocks.FARMLAND.defaultBlockState(),3);
        v.level.setBlock(crop,Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,7),3);v.index.crops.add(crop);
        farmer.cooldown=0;farmer.tick();
        require("harvest".equals(farmer.job)&&crop.equals(farmer.target)&&v.state.residentRoles.get(farmer.id)==assignment,"ordinary resident tick returns courier to ripe crop without changing role");
        require(!farmer.canHelp(),"ripe harvest blocks a new temporary delivery");
        pass("farmer_delivers_real_surplus_then_returns_to_ripe_harvest_with_same_role");

        // Concrete construction inputs need their own producers before the full future
        // growth reserve is filled; the farmer still services the actual ripe crop.
        require(SettlementEconomy.food(v)>=Math.max(6,v.state.residents.size())&&SettlementEconomy.food(v)<SettlementEconomy.budget(v).food(),"basic native food covered while full growth reserve remains unmet");
        var tree=origin.offset(-6,0,-6);for(int y=0;y<3;y++)v.level.setBlock(tree.above(y),Blocks.OAK_LOG.defaultBlockState(),3);
        v.level.setBlock(tree.above(3).east(),Blocks.OAK_LEAVES.defaultBlockState(),3);v.index.trees.add(tree);
        var timber=order(v,builder,8);timber.material="LOGS";timber.purpose=ConstructionSupply.prefix(builder)+"logs";
        logger.body().snapTo(Vec3.atBottomCenterOf(tree.east(2)),0,0);logger.cooldown=0;logger.tick();
        require("wood".equals(logger.job)&&v.hasRole(logger.id,ResidentRoles.Role.LOGGER)&&"harvest".equals(farmer.job),"logger services real timber order while permanent farmer keeps ripe harvest");
        pass("logger_services_construction_order_before_optional_harvest_help_with_basic_food_covered");logger.cancel();
        v.state.fellingTrees.remove(tree.asLong());v.state.treeWork.remove(tree.asLong());v.index.trees.clear();
        for(int y=0;y<3;y++)v.level.setBlock(tree.above(y),Blocks.AIR.defaultBlockState(),3);v.level.setBlock(tree.above(3).east(),Blocks.AIR.defaultBlockState(),3);
        var miner=role(v,ResidentRoles.Role.MINER);var stone=origin.offset(-6,0,-3);v.level.setBlock(stone,Blocks.STONE.defaultBlockState(),3);v.index.stone.add(stone);
        var masonry=order(v,builder,16);masonry.material="COBBLESTONE";masonry.purpose=ConstructionSupply.prefix(builder)+"stone";
        miner.body().snapTo(Vec3.atBottomCenterOf(stone.east(2)),0,0);miner.cooldown=0;miner.tick();
        require("stone".equals(miner.job)&&v.hasRole(miner.id,ResidentRoles.Role.MINER)&&"harvest".equals(farmer.job),"miner services actual stone input order before optional harvest help");
        pass("miner_services_construction_input_order_without_lowering_full_food_reserve");miner.cancel();v.index.stone.clear();v.level.setBlock(stone,Blocks.AIR.defaultBlockState(),3);

        // A porter performs both physical handovers; a busy farmer is the cargo donor.
        farmer.cancel();give(farmer.bot(),Items.OAK_PLANKS,6);
        resetBag(builder.bot());order=order(v,builder,6);
        porter.body().snapTo(Vec3.atBottomCenterOf(origin.south(2)),0,0);
        porter.collectOrder(order,farmer,builder.id);porter.work(porter.bot());
        require("deliver".equals(porter.job)&&porter.bot().inventory().count(Items.OAK_PLANKS)==6&&farmer.bot().inventory().count(Items.OAK_PLANKS)==8,"porter takes only donor surplus and leaves donor kit");
        porter.body().snapTo(Vec3.atBottomCenterOf(origin.east()),0,0);porter.work(porter.bot());
        require(order.delivered==6&&porter.bot().inventory().count(Items.OAK_PLANKS)==0&&builder.bot().inventory().count(Items.OAK_PLANKS)==6,"porter passes actual cargo to recipient without absorbing it into kit");
        require(v.porter(porter.id)&&v.farmer(farmer.id),"both permanent assignments unchanged by cargo job");
        pass("porter_collects_and_delivers_six_actual_planks_preserving_donor_kit");

        // Native work takes precedence even when an expedition claims the resident.
        farmer.cancel();farmer.body().getBrain().setMemory(MemoryModuleType.JOB_SITE,GlobalPos.of(v.level.dimension(),farmer.body().blockPosition()));
        var nativeData=farmer.body().getVillagerData();
        var party=new ExpeditionState();party.purpose="wood";party.phase="travel";party.team.add(farmer.id);party.target=origin.east(20).asLong();v.state.expedition=party;
        cmd("time set "+(2100+v.roleIndex(farmer.id)%8*600));
        farmer.tick();
        require(farmer.status.contains("профессии")&&nativeData.equals(farmer.body().getVillagerData())&&!farmer.canHelp(),"native profession window outranks expedition without replacing profession/level");
        v.state.expedition=null;
        pass("native_work_schedule_and_profession_survive_even_during_party_assignment");

        cmd("time set 1000");guard.cooldown=0;guard.tick();
        require(v.guard(guard.id)&&!v.logger(guard.id)&&!guard.canHelp()&&!("wood".equals(guard.job)||"mine".equals(guard.job)),"explicit watch does not become a logging or courier job");
        require(v.expeditionCoverage(Set.of(logger.id,porter.id)),"food repair and watch remain for a two-person outing");
        require(!v.expeditionCoverage(Set.of(farmer.id))&&!v.expeditionCoverage(Set.of(guard.id)),"cannot take the only farmer or guard away");
        pass("guard_stays_on_watch_and_expedition_requires_home_food_repair_and_guard");

        String survivors=VillageRuntime.GSON.toJson(v.state.residentRoles.entrySet().stream().filter(e->!e.getKey().equals(logger.id)).collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,Map.Entry::getValue)));
        logger.body().kill(v.level);v.sync();
        require(!v.state.residentRoles.containsKey(logger.id),"confirmed death releases only deceased appointment");
        String after=VillageRuntime.GSON.toJson(v.state.residentRoles.entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,Map.Entry::getValue)));
        require(survivors.equals(after),"death leaves all survivor roles and schedule slots unchanged");
        pass("actual_death_removes_only_deceased_role_without_reshuffling_survivors");
    }
    private static Resident role(Village v,ResidentRoles.Role role){return v.workers.values().stream().filter(r->v.hasRole(r.id,role)).findFirst().orElseThrow();}
    private static Orders.Order order(Village v,Resident recipient,int quantity){
        v.state.orders.clear();var o=new Orders.Order();o.material="PLANKS";o.purpose="role_fixture";o.quantity=quantity;
        o.recipient=recipient.id.toString();o.delivery=recipient.body().blockPosition().asLong();o.work=o.delivery;v.state.orders.add(o);return o;
    }
}
