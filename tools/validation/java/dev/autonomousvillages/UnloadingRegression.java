package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Two real bodies share a one-wide depot aisle; an empty helper must yield outside. */
final class UnloadingRegression {
    static Village v;static Resident carrier,helper;static BlockPos chest,rally;
    static final Map<BlockPos,BlockState> saved=new LinkedHashMap<>();
    static Set<Long> storage;static List<TownBuilding> buildings;
    static Map<UUID,Long> deposits,minerals;
    static List<ItemStack> carrierBag,helperBag;static Vec3 helperPos;static boolean helperNoAi;
    static ExpeditionState previous;static long started;static boolean yielded;
    static void begin(Village village,Resident worker,BlockPos origin){
        v=village;carrier=worker;helper=v.workers.values().stream().filter(r->r!=worker).findFirst().orElseThrow();
        carrier.cancel();helper.cancel();v.expeditions.stop();previous=v.state.expedition;
        storage=Set.copyOf(v.state.storage);buildings=List.copyOf(v.state.buildings);
        deposits=new HashMap<>(v.state.deposits);minerals=new HashMap<>(v.state.mineralDeposits);
        carrierBag=Supplies.copy(carrier.bot().inventory().snapshot().slots());helperBag=Supplies.copy(helper.bot().inventory().snapshot().slots());
        helperPos=helper.body().position();helperNoAi=helper.body().isNoAi();helper.body().setNoAi(false);
        VillageSelfTest.resetBag(carrier.bot());VillageSelfTest.resetBag(helper.bot());
        for(Item item:new Item[]{Items.STONE_PICKAXE,Items.WOODEN_AXE,Items.STONE_SWORD})VillageSelfTest.give(carrier.bot(),item,1);
        VillageSelfTest.give(carrier.bot(),Items.COBBLESTONE,64);
        chest=origin.offset(12,0,6);rally=origin.offset(12,0,14);saved.clear();
        var depot=new TownBuilding("warehouse",rally.offset(-3,-1,2));depot.completed=true;
        for(BlockPos q:BlockPos.betweenClosed(origin.offset(10,-1,4),origin.offset(14,3,16))){
            BlockPos p=q.immutable();saved.put(p,v.level.getBlockState(p));
            int x=p.getX()-origin.getX(),z=p.getZ()-origin.getZ(),y=p.getY()-origin.getY();
            boolean room=x>=11&&x<=13&&z>=5&&z<=10;
            BlockState state=y==-1?(room?Blocks.OAK_PLANKS:Blocks.GRASS_BLOCK).defaultBlockState():room&&(y==2||(y==0||y==1)&&(x==11||x==13||z==5))?Blocks.OAK_PLANKS.defaultBlockState():Blocks.AIR.defaultBlockState();
            v.level.setBlock(p,state,3);if(room&&!state.isAir())depot.add(p,state);
        }
        v.level.setBlock(chest,Blocks.CHEST.defaultBlockState(),3);depot.add(chest,Blocks.CHEST.defaultBlockState());
        v.state.buildings.clear();v.state.buildings.add(depot);v.town.invalidate();v.state.storage.clear();v.state.storage.add(chest.asLong());
        carrier.body().snapTo(Vec3.atBottomCenterOf(origin.offset(10,0,12)),0,0);carrier.body().setDeltaMovement(Vec3.ZERO);
        helper.body().snapTo(Vec3.atBottomCenterOf(chest.south(2)),0,0);helper.body().setDeltaMovement(Vec3.ZERO);
        var party=new ExpeditionState();party.phase="unload";party.team.add(carrier.id);party.team.add(helper.id);party.target=chest.asLong();party.started=party.phaseSince=v.level.getGameTime();v.state.expedition=party;
        VillageSelfTest.require(v.meetingPoint().equals(rally)&&carrier.hasSurplus()&&!helper.hasSurplus(),"actual cargo and empty helper start at a single-aisle depot with an exterior rally point");
        started=v.level.getGameTime();yielded=false;
    }
    static boolean tick(BlockPos origin){
        v.expeditions.work(helper);v.expeditions.work(carrier);
        long elapsed=v.level.getGameTime()-started;
        if(!yielded&&elapsed>=180){
            VillageSelfTest.require(helper.body().position().distanceToSqr(Vec3.atBottomCenterOf(rally))<=1,"empty unloaded helper reaches the exterior waiting spot instead of stopping near the aisle entrance; pos="+helper.body().position()+" status="+helper.status);
            VillageSelfTest.pass("unloaded_party_helper_physically_waits_outside_single_width_depot_aisle");yielded=true;
        }
        var box=(ChestBlockEntity)v.level.getBlockEntity(chest);
        VillageSelfTest.require(elapsed<1000,"depot queue must deliver through the real aisle; carrier="+carrier.body().position()+" job="+carrier.job+" failure="+carrier.lastFailure+" helper="+helper.body().position()+" helperJob="+helper.job+" helperStatus="+helper.status+" stored="+box.countItem(Items.COBBLESTONE));
        if(!yielded||box.countItem(Items.COBBLESTONE)==0||carrier.hasSurplus())return false;
        VillageSelfTest.require(box.countItem(Items.COBBLESTONE)+carrier.bot().inventory().count(Items.COBBLESTONE)+helper.bot().inventory().count(Items.COBBLESTONE)==64,"queued physical chest delivery conserves every one of the64 actual stones");
        VillageSelfTest.require(carrier.body().getHealth()==20&&helper.body().getHealth()==20,"queue preserves both native bodies");
        VillageSelfTest.pass("queued_party_physically_unloads_real_cargo_after_helper_yields_without_duplication");
        carrier.cancel();helper.cancel();v.expeditions.stop();v.state.expedition=previous;
        carrier.body().snapTo(Vec3.atBottomCenterOf(origin),0,0);carrier.body().setDeltaMovement(Vec3.ZERO);helper.body().snapTo(helperPos,0,0);helper.body().setDeltaMovement(Vec3.ZERO);helper.body().setNoAi(helperNoAi);
        VillageSelfTest.resetBag(carrier.bot());VillageSelfTest.resetBag(helper.bot());
        for(var item:carrierBag)if(!item.isEmpty())carrier.bot().inventory().insert(item);for(var item:helperBag)if(!item.isEmpty())helper.bot().inventory().insert(item);
        box.clearContent();saved.forEach((p,s)->v.level.setBlock(p,s,3));saved.clear();
        v.state.storage.clear();v.state.storage.addAll(storage);v.state.buildings.clear();v.state.buildings.addAll(buildings);v.town.invalidate();
        v.state.deposits.clear();v.state.deposits.putAll(deposits);v.state.mineralDeposits.clear();v.state.mineralDeposits.putAll(minerals);
        return true;
    }
}
