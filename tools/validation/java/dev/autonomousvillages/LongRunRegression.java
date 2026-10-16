package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.*;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.Vec3;

/** Targeted regressions for the failures observed in the 48,001-tick generated village. */
final class LongRunRegression {
    @SuppressWarnings("unchecked")
    private static Map<String,Long> delays(Resident resident){
        try{var field=Resident.class.getDeclaredField("delays");field.setAccessible(true);return (Map<String,Long>)field.get(resident);}
        catch(ReflectiveOperationException e){throw new AssertionError("fixture scheduling state",e);}
    }
    static void run(Village v, Resident r, BlockPos origin){
        var bot=r.bot();var level=v.level;var body=r.body();
        Vec3 previous=body.position();boolean noAi=body.isNoAi();float health=body.getHealth();
        var nativeFood=Supplies.copy(body.getInventory());
        var previousDelays=new HashMap<>(delays(r));
        var home=body.getBrain().getMemory(MemoryModuleType.HOME);
        var buildings=List.copyOf(v.state.buildings);var tables=Set.copyOf(v.index.tables);
        var previousExpedition=v.state.expedition;long clock=level.getOverworldClockTime();
        BlockPos shared=origin.offset(12,0,-5),near=origin.offset(1,0,-3),rest=origin.offset(-5,0,0);
        var sharedState=level.getBlockState(shared);var nearState=level.getBlockState(near);
        try{
            r.cancel();delays(r).clear();body.setNoAi(false);body.setHealth(20);body.getInventory().clearContent();
            body.snapTo(Vec3.atBottomCenterOf(origin),0,0);body.setDeltaMovement(Vec3.ZERO);
            v.state.buildings.clear();v.index.tables.clear();
            var project=new TownBuilding("warehouse",origin.offset(3,0,0));
            project.add(origin.offset(3,0,0),Blocks.CHEST.defaultBlockState());v.state.buildings.add(project);v.town.invalidate();
            level.setBlock(shared,Blocks.CRAFTING_TABLE.defaultBlockState(),3);v.index.tables.add(shared);
            VillageSelfTest.resetBag(bot);VillageSelfTest.give(bot,Items.OAK_PLANKS,16);VillageSelfTest.give(bot,Items.STICK,4);
            VillageSelfTest.give(bot,Items.WHEAT,3); // A real three-wide recipe requires a bench for every possible UUID-derived role.
            VillageSelfTest.give(bot,Items.WOODEN_AXE,1);VillageSelfTest.give(bot,Items.WOODEN_PICKAXE,1);VillageSelfTest.give(bot,Items.WOODEN_SWORD,1);
            r.cooldown=0;r.prepareCrafting(bot,v.steward(r.id));
            VillageSelfTest.require("table".equals(r.job)&&shared.equals(r.target)&&bot.inventory().count(Items.CRAFTING_TABLE)==0
                    &&bot.inventory().count(Items.OAK_PLANKS)==16,"reuse existing workbench 12 blocks away without crafting a duplicate; job="+r.job);
            VillageSelfTest.pass("longrun_existing_shared_workbench_reused_before_portable_recipe");
            r.cancel();v.index.tables.clear();
            VillageSelfTest.give(bot,Items.OAK_PLANKS,8); // Generic surplus must not turn a workbench request into a storage journey.
            r.prepareCrafting(bot,v.steward(r.id));
            VillageSelfTest.require(bot.inventory().count(Items.CRAFTING_TABLE)==1&&!"storage".equals(r.job),
                    "without a shared workbench in storage, craft the paid portable table instead of unloading unrelated surplus; job="+r.job);
            VillageSelfTest.pass("longrun_missing_shared_workbench_does_not_dispatch_unrelated_storage");
            r.cancel();v.state.buildings.clear();v.town.invalidate();v.index.tables.clear();
            level.setBlock(near,Blocks.CRAFTING_TABLE.defaultBlockState(),3);v.index.tables.add(near);
            VillageSelfTest.resetBag(bot);VillageSelfTest.give(bot,Items.WHEAT,3);VillageSelfTest.give(bot,Items.OAK_LOG,1);
            VillageSelfTest.require(VillageWorkshop.make(bot,v,false)&&bot.inventory().count(Items.BREAD)==1
                    &&bot.inventory().count(Items.WHEAT)==0&&bot.inventory().count(Items.OAK_LOG)==1,"food crafted before optional timber and equipment with real recipe conservation");
            VillageSelfTest.pass("longrun_bread_recipe_precedes_optional_wood_processing");
            VillageSelfTest.require(r.prepareCrafting(bot,v.steward(r.id))&&bot.inventory().count(Items.OAK_LOG)==0
                    &&bot.inventory().count(Items.OAK_PLANKS)==4
                    &&bot.activities().status()==dev.navigationai.api.ActivityStatus.RUNNING
                    &&((dev.navigationai.runtime.VillagerAccess)body).navigationAiController().controlsBody(),
                    "paid crafting holds actual body control during the existing twenty-tick recipe interval instead of native wandering away");
            VillageSelfTest.pass("paid_crafting_retains_body_control_for_bounded_recipe_interval");r.cancel();
            VillageSelfTest.resetBag(bot);body.getInventory().clearContent();body.getInventory().addItem(new ItemStack(Items.BREAD,4));
            body.getBrain().setMemory(MemoryModuleType.HOME,GlobalPos.of(level.dimension(),rest));
            body.snapTo(Vec3.atBottomCenterOf(origin.offset(12,0,0)),0,0);body.setDeltaMovement(Vec3.ZERO);body.setHealth(16);
            r.cooldown=0;r.tick();
            VillageSelfTest.require("home".equals(r.job)&&rest.equals(r.target),"injured supplied resident walks home before accepting more distant jobs");
            r.cancel();body.snapTo(Vec3.atBottomCenterOf(rest),0,0);body.setDeltaMovement(Vec3.ZERO);r.lastRestHeal=r.now()-400;r.tick();
            VillageSelfTest.require(body.getHealth()==17&&body.getInventory().countItem(Items.BREAD)==3,"rest healing consumes exactly one actual bread and restores one health");
            VillageSelfTest.pass("longrun_injured_resident_prioritizes_home_and_paid_recovery");
            r.cancel();delays(r).clear();body.setHealth(20);body.snapTo(Vec3.atBottomCenterOf(origin.offset(12,0,0)),0,0);body.setDeltaMovement(Vec3.ZERO);
            VillageSelfTest.cmd("time set 11550");r.tick();
            VillageSelfTest.require("home".equals(r.job)&&rest.equals(r.target),"healthy remote worker returns to the real home before darkness instead of accepting another forest trip");
            VillageSelfTest.pass("healthy_remote_worker_returns_before_dark_without_interrupting_an_atomic_block");r.cancel();
            v.state.buildings.add(project);v.town.invalidate();
            VillageSelfTest.require(v.town.timberBatch()==24,"small unfinished project does not send builders for a full64-plank forestry batch");
            VillageSelfTest.pass("longrun_small_project_reduces_logging_batch_without_forgetting_tree");
            r.cancel();VillageSelfTest.give(bot,Items.COBBLESTONE,3);
            var party=new ExpeditionState();party.phase="rally";party.team.add(r.id);party.target=origin.asLong();party.started=r.now();party.phaseSince=r.now();v.state.expedition=party;
            VillageSelfTest.cmd("time set 14000");v.expeditions.tick();
            VillageSelfTest.require(v.state.expedition==null&&bot.inventory().count(Items.COBBLESTONE)==3,
                    "party still rallying after nightfall does not depart or consume/duplicate carried cargo");
            VillageSelfTest.pass("longrun_nightfall_cancels_rally_with_actual_cargo_preserved");
            guardReceivesMaterials(v,origin);
            minerMaintainsPick(v,origin);
            surfaceFarm(v,r,origin);
            laterProjectMaterials(v,origin);
            builderAndFarmerPriorities(v,origin);
            ReviewRegression.run(v,r,origin);
        }finally{
            v.state.expedition=previousExpedition;VillageSelfTest.cmd("time set "+clock);
            r.cancel();VillageSelfTest.resetBag(bot);body.getInventory().clearContent();
            for(int i=0;i<nativeFood.size();i++)body.getInventory().setItem(i,nativeFood.get(i));
            if(home.isPresent())body.getBrain().setMemory(MemoryModuleType.HOME,home.get());else body.getBrain().eraseMemory(MemoryModuleType.HOME);
            body.setHealth(health);body.setNoAi(noAi);body.snapTo(previous,0,0);body.setDeltaMovement(Vec3.ZERO);
            v.state.buildings.clear();v.state.buildings.addAll(buildings);v.index.tables.clear();v.index.tables.addAll(tables);v.town.invalidate();
            level.setBlock(shared,sharedState,3);level.setBlock(near,nearState,3);r.needsWorkbench=false;
            delays(r).clear();delays(r).putAll(previousDelays);
        }
    }
    private static void laterProjectMaterials(Village v,BlockPos origin){
        var builder=v.workers.values().stream().filter(r->v.builder(r.id)&&!v.steward(r.id)).findFirst().orElseThrow();
        var b=builder.bot();var previous=builder.body().position();var bag=Supplies.copy(b.inventory().snapshot().slots());var previousDelays=new HashMap<>(delays(builder));
        var storage=Set.copyOf(v.state.storage);var tables=Set.copyOf(v.index.tables);var beds=List.copyOf(v.index.beds);var crops=Set.copyOf(v.index.crops);var buildings=List.copyOf(v.state.buildings);
        BlockPos chestPos=origin.offset(2,0,-2),tablePos=origin.offset(-2,0,-2),repairPos=origin.east(3),bedPos=origin.south(3),torchPos=origin.east(6);
        var saved=new LinkedHashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
        for(BlockPos p:List.of(chestPos,tablePos,repairPos,bedPos,bedPos.south(),torchPos))saved.put(p,v.level.getBlockState(p));
        try{
            builder.cancel();delays(builder).clear();VillageSelfTest.resetBag(b);builder.body().snapTo(Vec3.atBottomCenterOf(origin),0,0);builder.body().setDeltaMovement(Vec3.ZERO);
            for(Item item:new Item[]{Items.STONE_PICKAXE,Items.WOODEN_AXE,Items.STONE_SWORD})VillageSelfTest.give(b,item,1);VillageSelfTest.give(b,Items.STICK,4);
            v.level.setBlock(chestPos,Blocks.CHEST.defaultBlockState(),3);v.level.setBlock(tablePos,Blocks.CRAFTING_TABLE.defaultBlockState(),3);
            for(BlockPos p:List.of(repairPos,bedPos,bedPos.south(),torchPos))v.level.setBlock(p,Blocks.AIR.defaultBlockState(),3);
            v.state.storage.clear();v.state.storage.add(chestPos.asLong());v.index.tables.clear();v.index.tables.add(tablePos);v.index.crops.clear();
            var chest=(net.minecraft.world.level.block.entity.ChestBlockEntity)v.level.getBlockEntity(chestPos);
            chest.setItem(0,new ItemStack(Items.COBBLESTONE,16));chest.setItem(1,new ItemStack(Items.WOOL.brown(),6));chest.setItem(2,new ItemStack(Items.OAK_PLANKS,6));
            var blocked=new TownBuilding("native_house",torchPos);blocked.tier=1;blocked.add(torchPos,Blocks.TORCH.defaultBlockState());
            var repair=new TownBuilding("native_house",repairPos);repair.tier=1;repair.add(repairPos,Blocks.DYED_TERRACOTTA.white().defaultBlockState());
            var home=new TownBuilding("house",bedPos);home.add(bedPos,Blocks.BED.white().defaultBlockState().setValue(BedBlock.FACING,net.minecraft.core.Direction.SOUTH));
            v.state.buildings.clear();v.state.buildings.add(blocked);v.state.buildings.add(repair);v.state.buildings.add(home);v.town.invalidate();
            VillageSelfTest.require(builder.depositOrFetch(b,false)&&b.inventory().count(Items.COBBLESTONE)==16&&b.inventory().count(Items.WOOL.brown())==6,
                    "unavailable first-project torch must not hide existing stone and colored wool for later repair and housing projects; bag="+b.inventory().snapshot().slots().stream().filter(s->!s.isEmpty()).toList());
            var selected=v.town.request(builder,true);VillageSelfTest.require(selected==repair.blocks.getFirst(),"next usable repair selected while the earlier torch has no coal");
            VillageSelfTest.require(Construction.place(v,b,Construction.available(b,selected.step()))&&selected.matches(v)&&b.inventory().count(Items.COBBLESTONE)==15,
                    "later terracotta repair physically spends one actual stone substitute");
            VillageSelfTest.pass("blocked_first_upgrade_does_not_hide_real_materials_for_later_paid_repair");
            VillageSelfTest.require(VillageWorkshop.make(b,v,false)&&b.inventory().count(Items.BED.brown())==1&&b.inventory().count(Items.WOOL.brown())==3&&b.inventory().count(Items.OAK_PLANKS)==3,
                    "later housing request crafts one real colored bed from exactly three wool and three boards despite blocked earlier torch");
            selected=v.town.request(builder,true);VillageSelfTest.require(selected==home.blocks.getFirst(),"new paid bed remains reachable in the ordered project queue");
            VillageSelfTest.require(Construction.place(v,b,Construction.available(b,selected.step()))&&selected.matches(v)&&b.inventory().count(Items.BED.brown())==0&&v.level.getBlockState(torchPos).isAir(),
                    "both halves of the paid housing bed are placed without inventing fuel for the earlier upgrade");
            VillageSelfTest.pass("later_housing_uses_existing_colored_wool_and_places_paid_bed_behind_blocked_upgrade");
        }finally{
            builder.cancel();VillageSelfTest.resetBag(b);for(var stack:bag)if(!stack.isEmpty())b.inventory().insert(stack);builder.body().snapTo(previous,0,0);builder.body().setDeltaMovement(Vec3.ZERO);
            var chest=v.level.getBlockEntity(chestPos);if(chest instanceof net.minecraft.world.level.block.entity.ChestBlockEntity box)box.clearContent();saved.forEach((p,s)->v.level.setBlock(p,s,3));
            v.state.storage.clear();v.state.storage.addAll(storage);v.index.tables.clear();v.index.tables.addAll(tables);v.index.beds=beds;v.index.crops.clear();v.index.crops.addAll(crops);v.state.buildings.clear();v.state.buildings.addAll(buildings);v.town.invalidate();delays(builder).clear();delays(builder).putAll(previousDelays);
        }
    }
    private static void builderAndFarmerPriorities(Village v,BlockPos origin){
        var builder=v.workers.values().stream().filter(r->v.builder(r.id)&&!v.steward(r.id)).findFirst().orElseThrow();
        var farmer=v.workers.values().stream().filter(r->v.roleIndex(r.id)%6==2).findFirst().orElseThrow();
        var selected=List.of(builder,farmer);var bags=new LinkedHashMap<Resident,List<ItemStack>>();
        for(var member:v.workers.values())bags.put(member,Supplies.copy(member.bot().inventory().snapshot().slots()));
        var positions=new LinkedHashMap<Resident,Vec3>();var noAi=new HashMap<Resident,Boolean>();var health=new HashMap<Resident,Float>();
        var nativeFood=new HashMap<Resident,List<ItemStack>>();var oldDelays=new HashMap<Resident,Map<String,Long>>();var cooldowns=new HashMap<Resident,Long>();
        for(var member:selected){positions.put(member,member.body().position());noAi.put(member,member.body().isNoAi());health.put(member,member.body().getHealth());nativeFood.put(member,Supplies.copy(member.body().getInventory()));oldDelays.put(member,new HashMap<>(delays(member)));cooldowns.put(member,member.cooldown);}
        var buildings=List.copyOf(v.state.buildings);var crops=Set.copyOf(v.index.crops);var storage=Set.copyOf(v.state.storage);long clock=v.level.getOverworldClockTime();
        BlockPos start=origin.offset(-8,0,8),stair=start.east(3),crop=start.west(2);
        var saved=new LinkedHashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();for(BlockPos p:List.of(stair,crop,crop.below()))saved.put(p,v.level.getBlockState(p));
        try{
            for(var member:v.workers.values())VillageSelfTest.resetBag(member.bot());
            for(var member:selected){member.cancel();delays(member).clear();member.cooldown=0;member.body().setNoAi(false);member.body().stopSleeping();member.body().setHealth(20);member.body().getInventory().clearContent();member.body().snapTo(Vec3.atBottomCenterOf(start),0,0);member.body().setDeltaMovement(Vec3.ZERO);
                for(Item item:new Item[]{Items.STONE_PICKAXE,Items.WOODEN_AXE,Items.STONE_SWORD})VillageSelfTest.give(member.bot(),item,1);}
            VillageSelfTest.give(builder.bot(),Items.OAK_STAIRS,1);VillageSelfTest.give(farmer.bot(),Items.WOODEN_HOE,1);
            v.level.setBlock(stair,Blocks.AIR.defaultBlockState(),3);v.level.setBlock(crop.below(),Blocks.FARMLAND.defaultBlockState(),3);v.level.setBlock(crop,Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,7),3);
            v.state.storage.clear();v.state.buildings.clear();var project=new TownBuilding("warehouse",stair);project.add(stair,Blocks.OAK_STAIRS.defaultBlockState());v.state.buildings.add(project);v.town.invalidate();v.index.crops.clear();v.index.crops.add(crop);
            VillageSelfTest.cmd("time set 1000");VillageSelfTest.require(v.count(Supplies::food)==0,"real shared food shortage in the role-priority fixture");
            builder.tick();VillageSelfTest.require("build".equals(builder.job)&&stair.equals(builder.target),"equipped builder must use its ready paid project rather than taking the farmer's ripe crop; job="+builder.job+" target="+builder.target);
            builder.work(builder.bot());VillageSelfTest.require(v.level.getBlockState(stair).is(Blocks.OAK_STAIRS)&&builder.bot().inventory().count(Items.OAK_STAIRS)==0,
                    "builder physically places its actual carried stair before optional crop assistance");
            VillageSelfTest.pass("builder_places_ready_paid_project_before_optional_crop_assistance");
            farmer.tick();VillageSelfTest.require("harvest".equals(farmer.job)&&crop.equals(farmer.target)&&((CropBlock)Blocks.WHEAT).isMaxAge(v.level.getBlockState(crop)),
                    "dedicated farmer still claims the actual ripe crop under the identical food shortage");
            VillageSelfTest.pass("dedicated_farmer_keeps_early_harvest_priority_under_shared_food_shortage");
        }finally{
            for(var member:selected){member.cancel();member.body().getInventory().clearContent();var food=nativeFood.get(member);for(int i=0;i<food.size();i++)member.body().getInventory().setItem(i,food.get(i));member.body().setNoAi(noAi.get(member));member.body().setHealth(health.get(member));member.body().snapTo(positions.get(member),0,0);member.body().setDeltaMovement(Vec3.ZERO);member.cooldown=cooldowns.get(member);delays(member).clear();delays(member).putAll(oldDelays.get(member));}
            for(var entry:bags.entrySet()){VillageSelfTest.resetBag(entry.getKey().bot());for(var stack:entry.getValue())if(!stack.isEmpty())entry.getKey().bot().inventory().insert(stack);}
            saved.forEach((p,s)->v.level.setBlock(p,s,3));v.state.buildings.clear();v.state.buildings.addAll(buildings);v.index.crops.clear();v.index.crops.addAll(crops);v.state.storage.clear();v.state.storage.addAll(storage);v.town.invalidate();VillageSelfTest.cmd("time set "+clock);
        }
    }
    private static void surfaceFarm(Village v,Resident r,BlockPos origin){
        var saved=new LinkedHashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
        var soil=Set.copyOf(v.index.soil);var sites=Set.copyOf(v.index.farmSites);
        try{
            r.cancel();VillageSelfTest.resetBag(r.bot());delays(r).clear();v.index.soil.clear();v.index.farmSites.clear();
            r.body().snapTo(Vec3.atBottomCenterOf(origin),0,0);r.body().setDeltaMovement(Vec3.ZERO);
            // Equal nearby water sources: the western one is underground, beneath a real roof.
            for(int x=-12;x<=-4;x++)for(int z=-11;z<=-3;z++){
                BlockPos roof=origin.offset(x,3,z);saved.put(roof,v.level.getBlockState(roof));v.level.setBlock(roof,Blocks.STONE.defaultBlockState(),3);
            }
            for(int x:new int[]{-8,8}){BlockPos water=origin.offset(x,-1,-7);saved.put(water,v.level.getBlockState(water));v.level.setBlock(water,Blocks.WATER.defaultBlockState(),3);}
            VillageSelfTest.give(r.bot(),Items.WOODEN_HOE,1);VillageSelfTest.give(r.bot(),Items.WHEAT_SEEDS,1);
            VillageSelfTest.require(r.farmExpand(r.bot())&&"hoe".equals(r.job)&&r.target.getX()>origin.getX(),
                    "farmer rejects the roofed water bank and chooses actual open-air soil instead of a cave destination; target="+r.target);
            BlockPos target=r.target;saved.put(target.below(),v.level.getBlockState(target.below()));
            r.body().snapTo(Vec3.atBottomCenterOf(target.east()),0,0);r.body().setDeltaMovement(Vec3.ZERO);r.work(r.bot());
            VillageSelfTest.require(v.level.getBlockState(target.below()).is(Blocks.FARMLAND)
                    &&r.bot().inventory().snapshot().slots().stream().anyMatch(s->s.is(Items.WOODEN_HOE)&&s.getDamageValue()==1),
                    "surface plot is physically tilled with one actual hoe durability spent");
            VillageSelfTest.pass("farmer_rejects_cave_water_and_physically_tills_open_air_bank");
        }finally{
            r.cancel();saved.forEach((p,s)->v.level.setBlock(p,s,3));v.index.soil.clear();v.index.soil.addAll(soil);v.index.farmSites.clear();v.index.farmSites.addAll(sites);
        }
    }
    private static void minerMaintainsPick(Village v,BlockPos origin){
        var miner=v.workers.values().stream().filter(r->v.roleIndex(r.id)%6==4).findFirst().orElseThrow();
        var b=miner.bot();var previous=miner.body().position();var bag=Supplies.copy(b.inventory().snapshot().slots());var previousDelays=new HashMap<>(delays(miner));
        var storage=Set.copyOf(v.state.storage);var tables=Set.copyOf(v.index.tables);
        var previousParty=v.state.expedition;long previousClock=v.level.getOverworldClockTime();
        BlockPos chestPos=origin.offset(2,0,0),tablePos=origin.offset(0,0,2);
        var chestState=v.level.getBlockState(chestPos);var tableState=v.level.getBlockState(tablePos);
        try{
            miner.cancel();delays(miner).clear();VillageSelfTest.resetBag(b);miner.body().snapTo(Vec3.atBottomCenterOf(origin),0,0);miner.body().setDeltaMovement(Vec3.ZERO);
            v.level.setBlock(chestPos,Blocks.CHEST.defaultBlockState(),3);v.level.setBlock(tablePos,Blocks.CRAFTING_TABLE.defaultBlockState(),3);
            v.state.storage.clear();v.state.storage.add(chestPos.asLong());v.index.tables.clear();v.index.tables.add(tablePos);
            var chest=(net.minecraft.world.level.block.entity.ChestBlockEntity)v.level.getBlockEntity(chestPos);chest.setItem(0,new ItemStack(Items.COBBLESTONE,6));
            var worn=new ItemStack(Items.STONE_PICKAXE);worn.setDamageValue(worn.getMaxDamage()-60);b.inventory().insert(worn);
            VillageSelfTest.give(b,Items.WOODEN_AXE,1);VillageSelfTest.give(b,Items.WOODEN_SWORD,1);VillageSelfTest.give(b,Items.STICK,4);VillageSelfTest.give(b,Items.OAK_PLANKS,16);
            VillageSelfTest.cmd("time set 1000");
            var party=new ExpeditionState();party.phase="prepare";party.team.add(miner.id);party.target=origin.asLong();party.started=party.phaseSince=v.level.getGameTime();v.state.expedition=party;
            v.expeditions.tick();
            VillageSelfTest.require(party.phase.equals("prepare")&&v.expeditions.preparingSurveyPick(miner.id),"available real replacement materials keep the mining party in preparation while its usable pick is too worn for the planned survey");
            VillageSelfTest.require(miner.depositOrFetch(b,false)&&b.inventory().count(Items.COBBLESTONE)==3&&chest.countItem(Items.COBBLESTONE)==3,"worn but still usable survey pick reserves exactly three actual stone for a replacement");
            VillageSelfTest.require(VillageWorkshop.make(b,v,false)&&VillageWorkshop.hasSurveyPick(b)&&b.inventory().count(Items.STONE_PICKAXE)==2&&b.inventory().count(Items.COBBLESTONE)==0,
                    "replacement survey pick costs three real stone and retains the usable sixty-durability spare");
            VillageSelfTest.pass("miner_fetches_and_spends_real_stone_for_survey_pick_before_old_one_breaks");
            v.expeditions.tick();
            VillageSelfTest.require(party.phase.equals("rally")&&!v.expeditions.preparingSurveyPick(miner.id),"only the actual paid replacement releases the prepared mining party to rally");
            VillageSelfTest.pass("mining_party_waits_for_actual_survey_pick_replacement_before_rally");
            var before=Supplies.copy(b.inventory().snapshot().slots());var next=Supplies.copy(before);
            for(var stack:next)if(stack.is(Items.STONE_PICKAXE))stack.setDamageValue(stack.getMaxDamage()-60);
            VillageSelfTest.require(Supplies.replace(b,before,next),"fixture wears both actual picks before vanilla repair recipe");
            VillageSelfTest.require(VillageWorkshop.make(b,v,false)&&b.inventory().count(Items.STONE_PICKAXE)==1&&VillageWorkshop.hasSurveyPick(b)
                    &&chest.countItem(Items.COBBLESTONE)==3,"two worn usable picks combine via actual vanilla repair without spending any extra stone");
            VillageSelfTest.pass("miner_repairs_two_worn_survey_picks_instead_of_accumulating_spares");
        }finally{
            v.expeditions.stop();v.state.expedition=previousParty;VillageSelfTest.cmd("time set "+previousClock);
            miner.cancel();VillageSelfTest.resetBag(b);for(var stack:bag)if(!stack.isEmpty())b.inventory().insert(stack);miner.body().snapTo(previous,0,0);miner.body().setDeltaMovement(Vec3.ZERO);
            var chest=(net.minecraft.world.level.block.entity.ChestBlockEntity)v.level.getBlockEntity(chestPos);if(chest!=null)chest.clearContent();v.level.setBlock(chestPos,chestState,3);v.level.setBlock(tablePos,tableState,3);
            v.state.storage.clear();v.state.storage.addAll(storage);v.index.tables.clear();v.index.tables.addAll(tables);delays(miner).clear();delays(miner).putAll(previousDelays);
        }
    }
    private static void guardReceivesMaterials(Village v,BlockPos origin){
        var guard=v.workers.values().stream().filter(r->!v.steward(r.id)&&(v.roleIndex(r.id)%6==5||v.workers.size()<3)).findFirst().orElseThrow();
        var b=guard.bot();var previous=guard.body().position();var bag=Supplies.copy(b.inventory().snapshot().slots());
        var previousDelays=new HashMap<>(delays(guard));
        var storage=Set.copyOf(v.state.storage);var tables=Set.copyOf(v.index.tables);
        BlockPos chestPos=origin.offset(2,0,0),tablePos=origin.offset(0,0,2);
        var chestState=v.level.getBlockState(chestPos);var tableState=v.level.getBlockState(tablePos);
        try{
            guard.cancel();delays(guard).clear();VillageSelfTest.resetBag(b);guard.body().snapTo(Vec3.atBottomCenterOf(origin),0,0);guard.body().setDeltaMovement(Vec3.ZERO);
            v.level.setBlock(chestPos,Blocks.CHEST.defaultBlockState(),3);v.level.setBlock(tablePos,Blocks.CRAFTING_TABLE.defaultBlockState(),3);
            v.state.storage.clear();v.state.storage.add(chestPos.asLong());v.index.tables.clear();v.index.tables.add(tablePos);
            var chest=(net.minecraft.world.level.block.entity.ChestBlockEntity)v.level.getBlockEntity(chestPos);chest.setItem(0,new ItemStack(Items.COBBLESTONE,5));
            VillageSelfTest.give(b,Items.WOODEN_PICKAXE,1);VillageSelfTest.give(b,Items.WOODEN_AXE,1);VillageSelfTest.give(b,Items.WOODEN_SWORD,1);VillageSelfTest.give(b,Items.STICK,4);
            VillageSelfTest.require(guard.depositOrFetch(b,false)&&b.inventory().count(Items.COBBLESTONE)==5&&chest.countItem(Items.COBBLESTONE)==0,
                    "non-steward can physically fetch stone for both the protective sword and replacement pick; workers="+v.workers.size()+" role="+v.roleIndex(guard.id)+" job="+guard.job+" bag="+b.inventory().snapshot().slots()+" chest="+chest.countItem(Items.COBBLESTONE));
            VillageSelfTest.require(VillageWorkshop.make(b,v,false)&&VillageWorkshop.hasDefensiveSword(b)&&b.inventory().count(Items.COBBLESTONE)==3,
                    "guard's stronger sword costs exactly two real stone");
            VillageSelfTest.require(VillageWorkshop.make(b,v,false)&&b.inventory().count(Items.STONE_PICKAXE)==1&&b.inventory().count(Items.COBBLESTONE)==0,
                    "same five fetched stone pay for sword and stronger pick without losing original wooden tools");
            VillageSelfTest.pass("guard_fetches_and_spends_actual_stone_for_defensive_sword_and_pick");
        }finally{
            guard.cancel();VillageSelfTest.resetBag(b);for(var stack:bag)if(!stack.isEmpty())b.inventory().insert(stack);
            guard.body().snapTo(previous,0,0);guard.body().setDeltaMovement(Vec3.ZERO);
            var chest=(net.minecraft.world.level.block.entity.ChestBlockEntity)v.level.getBlockEntity(chestPos);if(chest!=null)chest.clearContent();
            v.level.setBlock(chestPos,chestState,3);v.level.setBlock(tablePos,tableState,3);
            v.state.storage.clear();v.state.storage.addAll(storage);v.index.tables.clear();v.index.tables.addAll(tables);
            delays(guard).clear();delays(guard).putAll(previousDelays);
        }
    }
}
