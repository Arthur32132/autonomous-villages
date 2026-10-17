package dev.autonomousvillages;

import dev.navigationai.api.*;
import dev.navigationai.runtime.VillagerAccess;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.level.storage.*;
import net.minecraft.world.phys.*;

/** Physical regressions for the complete review, always in the disposable server fixture. */
final class ReviewRegression {
    static void run(Village v,Resident r,BlockPos origin){
        var b=r.bot();var bag=Supplies.copy(b.inventory().snapshot().slots());
        Vec3 previousPosition=r.body().position();r.body().snapTo(Vec3.atBottomCenterOf(origin),0,0);r.body().setDeltaMovement(Vec3.ZERO);
        var buildings=List.copyOf(v.state.buildings);var storage=Set.copyOf(v.state.storage);var party=v.state.expedition;
        BlockPos chestPos=origin.offset(2,0,-3),slabPos=origin.offset(2,0,1);
        var chestState=v.level.getBlockState(chestPos);var slabState=v.level.getBlockState(slabPos);
        try{
            r.cancel();VillageSelfTest.resetBag(b);v.state.buildings.clear();v.town.invalidate();
            v.level.setBlock(chestPos,Blocks.CHEST.defaultBlockState(),3);var chest=(ChestBlockEntity)v.level.getBlockEntity(chestPos);
            for(int i=0;i<chest.getContainerSize();i++)chest.setItem(i,new ItemStack(Items.DIAMOND,64));
            chest.setItem(0,new ItemStack(Items.COBBLESTONE,60));VillageSelfTest.give(b,Items.COBBLESTONE,61);
            VillageSelfTest.require(Supplies.transfer(b,chest,true,s->s.is(Items.COBBLESTONE),61)==4&&chest.countItem(Items.COBBLESTONE)==64&&b.inventory().count(Items.COBBLESTONE)==57,"partial real chest accepts exactly four of61 stones without duplication");
            VillageSelfTest.require(Supplies.transfer(b,chest,true,s->s.is(Items.COBBLESTONE),57)==0&&b.inventory().count(Items.COBBLESTONE)==57,"full chest never debits unaccepted cargo");
            VillageSelfTest.pass("review_partial_chest_deposit_conserves_every_actual_item");
            VillageSelfTest.resetBag(b);v.level.setBlock(slabPos,Blocks.AIR.defaultBlockState(),3);
            var doubleSlab=new Construction.Step(slabPos,Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE,SlabType.DOUBLE),Items.OAK_SLAB);
            VillageSelfTest.give(b,Items.OAK_SLAB,1);VillageSelfTest.require(!Construction.place(v,b,doubleSlab)&&v.level.getBlockState(slabPos).isAir()&&b.inventory().count(Items.OAK_SLAB)==1,"one slab cannot buy a double slab");
            VillageSelfTest.give(b,Items.OAK_SLAB,1);VillageSelfTest.require(Construction.place(v,b,doubleSlab)&&b.inventory().count(Items.OAK_SLAB)==0&&v.level.getBlockState(slabPos).getValue(SlabBlock.TYPE)==SlabType.DOUBLE,"two actual slabs buy exactly one double slab");
            var stair=new Construction.Step(slabPos,Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING,Direction.NORTH),Items.OAK_STAIRS);
            VillageSelfTest.require(!Construction.matches(stair,stair.state().setValue(StairBlock.FACING,Direction.EAST))&&!Construction.matches(stair,stair.state().setValue(StairBlock.HALF,Half.TOP)),"wrong facing and half must require repair");
            VillageSelfTest.require(!Construction.matches(doubleSlab,Blocks.OAK_SLAB.defaultBlockState()),"single slab does not satisfy double slab intent");
            var door=new Construction.Step(slabPos,Blocks.OAK_DOOR.defaultBlockState(),Items.OAK_DOOR);
            VillageSelfTest.require(Construction.matches(door,door.state().setValue(DoorBlock.OPEN,true)),"native open state does not create perpetual door repairs");
            var chestPlan=new Construction.Step(slabPos,Blocks.CHEST.defaultBlockState(),Items.CHEST);
            VillageSelfTest.require(Construction.matches(chestPlan,chestPlan.state().setValue(ChestBlock.TYPE,ChestType.LEFT)),"dynamic chest connection does not create perpetual repairs");
            VillageSelfTest.pass("review_structural_geometry_and_double_slab_payment");
            var otherState=new VillageState("ReviewNeighbour",UUID.randomUUID(),origin.offset(100,0,0),24);
            var other=new Village(v.runtime,otherState);var wall=new TownBuilding("native_house",slabPos);wall.add(slabPos,Blocks.OAK_PLANKS.defaultBlockState());wall.add(chestPos,Blocks.OAK_DOOR.defaultBlockState());other.state.buildings.add(wall);
            v.runtime.villages.put("reviewneighbour",other);
            try{VillageSelfTest.require(!v.runtime.mayBreak(r.id,slabPos)&&!v.runtime.mayBreak(r.id,chestPos.above()),"foreign wall and upper door half protected even outside owner's footprint");}finally{v.runtime.villages.remove("reviewneighbour");}
            VillageSelfTest.pass("review_all_settlements_and_both_door_halves_are_protected");
            var e=new ExpeditionState();e.team.add(r.id);e.phase="return";e.target=origin.asLong();e.started=e.phaseSince=r.now();v.state.expedition=e;v.workers.remove(r.id);
            try{v.expeditions.tick();VillageSelfTest.require(v.state.expedition==e&&e.team.equals(List.of(r.id)),"unavailable live party member survives the planner tick");}finally{v.workers.put(r.id,r);v.state.expedition=party;}
            VillageSelfTest.pass("review_unloaded_party_member_is_not_treated_as_dead");
            var legacy=new VillageState("Legacy",UUID.randomUUID(),origin,24);legacy.bounds=new VillageState.Bounds(origin.getX()-24,origin.getZ()-24,origin.getX()+24,origin.getZ()+24);
            var overhang=new TownBuilding("house",origin.offset(0,0,-24));overhang.add(origin.offset(0,0,-25),Blocks.OAK_STAIRS.defaultBlockState());legacy.buildings.add(overhang);legacy.recoverProjectBounds();legacy.validate();
            VillageSelfTest.require(legacy.contains(origin.offset(0,0,-25)),"known legacy porch overhang recovers without discarding its project");VillageSelfTest.pass("review_legacy_border_project_recovers_and_validates");
            deferredRelease(v,origin.offset(96,0,0));
            nightTargets(v,r,origin);
        }finally{
            r.cancel();VillageSelfTest.resetBag(b);for(var s:bag)if(!s.isEmpty())b.inventory().insert(s);
            r.body().snapTo(previousPosition,0,0);r.body().setDeltaMovement(Vec3.ZERO);
            if(v.level.getBlockEntity(chestPos) instanceof ChestBlockEntity chest)chest.clearContent();
            v.level.setBlock(chestPos,chestState,3);v.level.setBlock(slabPos,slabState,3);
            v.state.storage.clear();v.state.storage.addAll(storage);v.state.buildings.clear();v.state.buildings.addAll(buildings);v.town.invalidate();v.state.expedition=party;
        }
    }
    private static void nightTargets(Village v,Resident r,BlockPos origin){
        var helper=v.workers.values().stream().filter(other->other!=r).findFirst().orElseThrow();
        var previous=v.state.expedition;var homes=v.index.beds;var buildings=List.copyOf(v.state.buildings);
        var memory=r.body().getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.HOME);
        Vec3 position=r.body().position();boolean noAi=r.body().isNoAi();long clock=v.level.getOverworldClockTime();
        BlockPos broken=origin.offset(4,0,2),safe=origin.offset(-4,0,2),hole=broken.east(2);
        var states=new LinkedHashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
        for(BlockPos p:List.of(broken,broken.south(),safe,safe.south(),hole))states.put(p,v.level.getBlockState(p));
        try{
            r.cancel();helper.cancel();v.state.expedition=null;VillageSelfTest.cmd("time set 14000");
            var bed=Blocks.BED.white().defaultBlockState().setValue(BedBlock.FACING,Direction.SOUTH);
            for(BlockPos p:List.of(broken,safe)){v.level.setBlock(p,bed,18);v.level.setBlock(p.south(),bed.setValue(BedBlock.PART,BedPart.HEAD),18);}
            var unsafe=new TownBuilding("native_house",broken);unsafe.add(broken,bed);unsafe.add(hole,Blocks.OAK_PLANKS.defaultBlockState());
            var intact=new TownBuilding("native_house",safe);intact.add(safe,bed);v.level.setBlock(hole,Blocks.AIR.defaultBlockState(),3);
            v.state.buildings.clear();v.state.buildings.add(unsafe);v.state.buildings.add(intact);v.town.invalidate();v.index.beds=List.of(broken.south(),safe.south());
            r.body().getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.HOME,GlobalPos.of(v.level.dimension(),broken.south()));
            VillageSelfTest.require(!v.town.shelterIntact(broken.south())&&v.town.shelterIntact(safe.south())&&r.restHome().equals(safe.south()),"damaged original room yields to an intact alternative shelter");
            r.assign("home",safe.south());helper.assign("home",safe.south());
            VillageSelfTest.require("home".equals(r.job)&&"home".equals(helper.job),"two live residents may approach the same shelter without an exclusive bed lease");
            VillageSelfTest.pass("review_night_shelter_destinations_are_shared_and_damaged_room_is_avoided");
            VillageSelfTest.give(r.bot(),Items.STONE_PICKAXE,1);r.lastRecovery=r.now()-1000;
            r.fail("маршрут: unreachable");VillageSelfTest.require(!"escape".equals(r.job),"failed night doorway does not start destructive terrain recovery near homes");
            helper.cancel();r.cancel();r.body().setNoAi(false);r.body().snapTo(Vec3.atBottomCenterOf(safe.south().east()),0,0);r.body().setDeltaMovement(Vec3.ZERO);
            r.tick();VillageSelfTest.require(r.bot().activities().status()==ActivityStatus.RUNNING&&r.job==null,"resident holds the intact alternate room instead of native Brain wandering back to its damaged HOME");
            VillageSelfTest.pass("review_night_alternate_shelter_holds_body_without_digging_away");
            r.cancel();var party=new ExpeditionState();party.purpose="wool";party.phase="work";party.team.add(r.id);party.target=safe.asLong();party.started=r.now();party.phaseSince=r.now()-2000;v.state.expedition=party;r.collectingUntil=r.now()+200;
            v.expeditions.tick();VillageSelfTest.require(party.phase.equals("work"),"scheduled night return must finish real wool pickup first");
            r.collectingUntil=0;v.expeditions.tick();VillageSelfTest.require(party.phase.equals("return"),"party returns immediately after the atomic collection finishes");
            VillageSelfTest.pass("review_wool_collection_finishes_before_scheduled_night_return");
        }finally{
            r.cancel();helper.cancel();r.body().setNoAi(noAi);r.body().snapTo(position,0,0);r.body().setDeltaMovement(Vec3.ZERO);
            if(memory.isPresent())r.body().getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.HOME,memory.orElseThrow());else r.body().getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.HOME);
            v.state.expedition=previous;v.index.beds=homes;v.state.buildings.clear();v.state.buildings.addAll(buildings);v.town.invalidate();states.forEach((p,s)->v.level.setBlock(p,s,3));VillageSelfTest.cmd("time set "+clock);
        }
    }
    private static void deferredRelease(Village v,BlockPos at){
        VillageSelfTest.require(v.level.isPositionEntityTicking(at),"release fixture chunk has finished entity loading before the round trip");
        Villager body=EntityTypes.VILLAGER.create(v.level,EntitySpawnReason.COMMAND);body.setNoAi(true);body.snapTo(Vec3.atBottomCenterOf(at),0,0);body.setCustomName(Component.literal("Original name"));VillageSelfTest.require(v.level.addFreshEntity(body),"release fixture body inserted");
        var handle=v.runtime.bots.attach(body);VillageSelfTest.give(handle,Items.WOODEN_AXE,1);VillageSelfTest.give(handle,Items.DIAMOND,17);
        var state=new VillageState("ReviewRelease",UUID.randomUUID(),at,24);state.residents.add(body.getUUID());var name=new VillageState.ResidentName();name.json=net.minecraft.network.chat.ComponentSerialization.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE,body.getCustomName()).getOrThrow().toString();state.residentNames.put(body.getUUID(),name);
        var released=new Village(v.runtime,state);v.runtime.villages.put("reviewrelease",released);
        var output=TagValueOutput.createWithContext(ProblemReporter.DISCARDING,v.level.registryAccess());body.saveWithoutId(output);var nbt=output.buildResult();UUID id=body.getUUID();
        body.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK);v.runtime.change("ReviewRelease","release");
        VillageSelfTest.require(v.runtime.pendingReleases.containsKey(id),"unloaded release persisted until body returns");
        try{var file=v.runtime.server.getWorldPath(LevelResource.ROOT).resolve("autonomous_villages.json");var saved=VillageRuntime.GSON.fromJson(java.nio.file.Files.readString(file),VillageRuntime.Save.class);VillageSelfTest.require(saved.pendingReleases.containsKey(id),"deferred release survives a metadata round trip");}catch(java.io.IOException error){throw new IllegalStateException(error);}
        Villager loaded=EntityTypes.VILLAGER.create(v.level,EntitySpawnReason.COMMAND);loaded.load(TagValueInput.create(ProblemReporter.DISCARDING,v.level.registryAccess(),nbt));VillageSelfTest.require(v.level.addFreshEntity(loaded),"released fixture body reinserted");v.runtime.residentLoaded(loaded);
        VillageSelfTest.require(v.runtime.bots.find(id).isEmpty()&&!v.runtime.pendingReleases.containsKey(id)&&((VillagerAccess)loaded).navigationAiSavedState()==null&&loaded.getName().getString().equals("Original name"),"reloaded released villager has no resurrected controller and keeps original name");
        var drops=v.level.getEntitiesOfClass(ItemEntity.class,new AABB(at).inflate(4),item->item.getItem().is(Items.DIAMOND));int returned=drops.stream().mapToInt(item->item.getItem().getCount()).sum();VillageSelfTest.require(returned==17,"all17 backpack diamonds returned exactly once on deferred release; actual="+returned);drops.forEach(Entity::discard);loaded.discard();
        VillageSelfTest.pass("review_unloaded_release_persists_and_returns_backpack_once_on_load");
        VillageSelfTest.releaseReviewFixture();
    }
}
