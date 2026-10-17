package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;

import static dev.autonomousvillages.VillageSelfTest.*;

/** Bounded scheduler/payment fixture, not a claim of autonomous resource gathering or travel. */
final class HousingRegression {
    static void run(Village v,BlockPos origin){
        v.state.active=false;v.expeditions.stop();v.state.buildings.clear();v.state.orders.clear();
        v.state.storage.clear();v.state.furnaces.clear();v.index.tables.clear();
        v.state.bounds=new VillageState.Bounds(origin.getX()-28,origin.getZ()-28,origin.getX()+28,origin.getZ()+28);
        var r=v.workers.values().stream().filter(w->v.builder(w.id)).findFirst().orElseThrow();
        for(var w:v.workers.values()){w.cancel();w.body().setNoAi(true);w.body().snapTo(Vec3.atBottomCenterOf(origin.offset(26,0,26)),0,0);}
        resetBag(r.bot());
        var depot=v.town.warehouse(origin.offset(-18,0,-16));v.state.buildings.add(depot);v.town.invalidate();
        // A fresh planner has no cadence delay; it uses the production site search and order expansion.
        new TownPlanner(v).tick();
        var house=v.state.buildings.stream().filter(b->b.kind.equals("house")).findFirst().orElseThrow();
        require(!depot.completed&&depot.built(v)==0,"house planned alongside completely unbuilt depot");
        require(v.state.orders.stream().anyMatch(o->o.work==house.origin&&o.purpose.startsWith("bed_")),"new house requests beds immediately");
        pass("housing_and_bed_orders_start_before_any_depot_completion");

        var initial=v.town.request(r);require(initial==null,"no free building without real items");
        // Pay every bootstrap block through the production placement path.
        for(var p:depot.blocks)if(TownPlanner.warehousePhase(depot,p)==0)paidPlace(v,r,p);
        require(v.town.warehousePhase(depot)==1&&!depot.completed,"lit base works while walls and roof remain missing");
        BlockPos chest=BlockPos.of(depot.origin).offset(2,1,4),table=BlockPos.of(depot.origin).offset(4,1,4);
        require(v.chests().stream().anyMatch(c->c.getBlockPos().equals(chest))&&v.index.tables.contains(table),"early chest and table registered for production use");
        give(r.bot(),Items.DIRT,5);
        var inventory=(ChestBlockEntity)v.level.getBlockEntity(chest);
        require(Supplies.transfer(r.bot(),inventory,true,s->s.is(Items.DIRT),5)==5&&inventory.countItem(Items.DIRT)==5,"unfinished depot accepts actual cargo");
        pass("paid_lit_bootstrap_chest_and_table_function_without_roof_or_decor");

        resetBag(r.bot());give(r.bot(),Items.OAK_PLANKS,64);
        for(var road:List.copyOf(v.state.buildings))if(road.kind.equals("road"))for(var p:road.blocks)paidPlace(v,r,p);
        resetBag(r.bot());give(r.bot(),Items.OAK_PLANKS,64);
        var choice=v.town.request(r);
        require(choice!=null&&house.blocks.contains(choice),"builder chooses housing before depot walls");
        pass("housing_construction_precedes_depot_workroom");

        var repair=new TownBuilding("native_house",origin.offset(6,0,0));repair.completed=true;
        repair.add(origin.offset(6,0,0),Blocks.OAK_PLANKS.defaultBlockState());v.state.buildings.add(repair);v.town.invalidate();
        require(v.town.request(r,true)==repair.blocks.getFirst(),"housing repair precedes housing and warehouse construction");
        paidPlace(v,r,repair.blocks.getFirst());
        pass("paid_home_repair_precedes_parallel_projects");

        // The fixture supplies materials and positions the worker: verify intent and actual payment,
        // without folding unresolved delivery/pathfinding acceptance into this scheduler regression.
        for(var p:house.blocks)paidPlace(v,r,p);
        require(house.built(v)==house.blocks.size()&&!depot.completed&&v.town.warehousePhase(depot)==1,"complete physical two-bed house precedes warehouse walls");
        v.town.syncOrders(v.level.getGameTime());
        require(v.state.orders.stream().noneMatch(o->o.work==house.origin&&o.purpose.startsWith("bed_")),"installed beds release their orders");
        pass("paid_two_bed_house_finishes_while_depot_remains_incomplete");

        for(var p:depot.blocks)if(TownPlanner.warehousePhase(depot,p)==1)paidPlace(v,r,p);
        require(v.town.warehousePhase(depot)==2,"workroom precedes roof");
        for(var p:depot.blocks)if(TownPlanner.warehousePhase(depot,p)==2)paidPlace(v,r,p);
        require(v.town.warehousePhase(depot)==3&&!depot.completed,"decor and extra chests remain optional final stage");
        var restored=VillageRuntime.GSON.fromJson(VillageRuntime.GSON.toJson(v.state),VillageState.class);restored.validate();
        var saved=restored.buildings.stream().filter(b->b.kind.equals("warehouse")).findFirst().orElseThrow();
        require(saved.blocks.size()==depot.blocks.size()&&v.town.warehousePhase(saved)==3,"stages survive metadata reload without progress flags");
        pass("paid_workroom_then_roof_and_legacy_compatible_stage_reload");
    }
    private static void paidPlace(Village v,Resident r,TownBuilding.Plan p){
        if(p.matches(v))return;
        r.body().snapTo(Vec3.atBottomCenterOf(p.position().north(2)),0,0);r.body().setDeltaMovement(Vec3.ZERO);
        resetBag(r.bot());give(r.bot(),p.block().getBlock().asItem(),Construction.cost(p.step()));
        require(Construction.place(v,r.bot(),p.step())&&p.matches(v)&&r.bot().inventory().count(p.block().getBlock().asItem())==0,"actual paid placement "+p.position()+" "+p.block());
    }
}
