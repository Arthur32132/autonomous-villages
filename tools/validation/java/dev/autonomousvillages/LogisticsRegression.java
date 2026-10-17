package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;
import static dev.autonomousvillages.VillageSelfTest.*;

/** Bounded real-inventory transactions; navigation and colony autonomy are separate. */
final class LogisticsRegression {
    static void run(Village v,BlockPos origin){
        v.state.active=false;v.expeditions.stop();v.state.expedition=null;
        v.state.orders.clear();v.state.storage.clear();v.state.buildings.clear();v.state.land.fields.clear();
        v.state.cargoKits.clear();
        while(v.workers.size()<3){var body=spawn(origin);body.setNoAi(false);v.state.residents.add(body.getUUID());v.sync();}
        var people=new ArrayList<>(v.workers.values());var donor=people.get(0);var recipient=people.get(1);var porter=people.get(2);
        for(var r:people){
            r.cancel();resetBag(r.bot());r.fresh=false;r.body().setNoAi(false);r.body().setHealth(20);r.body().stopSleeping();
            r.body().getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.JOB_SITE);
            r.body().snapTo(Vec3.atBottomCenterOf(origin),0,0);
            v.state.residentRoles.put(r.id,new ResidentRoles.Assignment(r.id,ResidentRoles.Role.PORTER,"none","logistics fixture"));
            give(r.bot(),Items.STONE_PICKAXE,1);give(r.bot(),Items.STONE_AXE,1);
        }
        give(donor.bot(),Items.OAK_PLANKS,14);
        var o=order(v,recipient,Orders.Material.PLANKS,6);
        recipient.assign("workshop",origin);
        porter.collectOrder(o,donor,recipient.id);
        require(porter.agreement!=null&&porter.agreement.quantity==6,"capacity accepted before donor pickup");
        porter.work(porter.bot());
        require("deliver".equals(porter.job)&&donor.bot().inventory().count(Items.OAK_PLANKS)==8,"pickup leaves personal kit");
        porter.work(porter.bot());
        require(o.delivered==0&&recipient.job!=null,"busy recipient is not interrupted");
        recipient.work(recipient.bot());
        require(DeliveryRuns.hold(recipient),"recipient waits at completed action boundary");
        porter.work(porter.bot());
        require(o.delivered==6&&recipient.bot().inventory().count(Items.OAK_PLANKS)==6&&porter.bot().inventory().count(Items.OAK_PLANKS)==0,"actual six-item handoff");
        pass("logistics_capacity_busy_boundary_and_two_real_handoffs");

        resetBag(recipient.bot());resetBag(porter.bot());donor.cancel();give(donor.bot(),Items.OAK_PLANKS,6);
        o=order(v,recipient,Orders.Material.PLANKS,6);porter.collectOrder(o,donor,recipient.id);porter.work(porter.bot());
        v.state.orders.clear();porter.work(porter.bot());
        require(porter.job==null&&porter.bot().inventory().count(Items.OAK_PLANKS)==6,"cancel preserves real cargo");
        var restored=VillageRuntime.GSON.fromJson(VillageRuntime.GSON.toJson(v.state),VillageState.class);
        require(restored.cargoKits.get(porter.id).get(Orders.Material.PLANKS)==0,"cargo baseline survives save");
        o=order(v,recipient,Orders.Material.PLANKS,6);porter.deliver(o,recipient.id);porter.work(porter.bot());
        require(o.delivered==6,"orphan cargo reassigned without becoming porter kit");
        pass("logistics_cancel_save_and_reassign_preserve_cargo");

        resetBag(recipient.bot());resetBag(porter.bot());
        for(int i=0;i<recipient.bot().inventory().snapshot().slots().size();i++)give(recipient.bot(),Items.DIRT,64);
        give(donor.bot(),Items.OAK_PLANKS,6);o=order(v,recipient,Orders.Material.PLANKS,6);
        int held=donor.bot().inventory().count(Items.OAK_PLANKS);
        porter.collectOrder(o,donor,recipient.id);
        require(porter.agreement==null&&donor.bot().inventory().count(Items.OAK_PLANKS)==held,"full recipient rejects before pickup");
        pass("logistics_full_recipient_does_not_take_donor_cargo");

        resetBag(recipient.bot());resetBag(porter.bot());v.state.orders.clear();
        BlockPos p=origin.east(),q=origin.west();
        v.level.setBlock(p,Blocks.CHEST.defaultBlockState(),3);v.level.setBlock(q,Blocks.CHEST.defaultBlockState(),3);
        v.state.storage.add(p.asLong());v.state.storage.add(q.asLong());
        var source=(ChestBlockEntity)v.level.getBlockEntity(p);var destination=(ChestBlockEntity)v.level.getBlockEntity(q);
        source.clearContent();destination.clearContent();source.setItem(0,new ItemStack(Items.COAL,20));
        require(DeliveryRuns.fromChest(v,porter,null,null,destination,Orders.Material.FUEL,8),"chest capacity and free surplus agreed");
        porter.work(porter.bot());require("deliver_chest".equals(porter.job)&&source.countItem(Items.COAL)==12,"physical pickup from source");
        porter.work(porter.bot());
        require(destination.countItem(Items.COAL)==8&&porter.bot().inventory().count(Items.COAL)==0,"physical delivery to second chest");
        pass("logistics_chest_to_chest_conserves_twenty_fuel");

        v.rejected.clear();source.setItem(0,new ItemStack(Items.COAL,20));
        require(DeliveryRuns.fromChest(v,porter,null,null,destination,Orders.Material.FUEL,8),"second chest run");
        porter.work(porter.bot());v.level.setBlock(q,Blocks.AIR.defaultBlockState(),3);porter.work(porter.bot());
        require(porter.agreement==null&&porter.bot().inventory().count(Items.COAL)==8&&source.countItem(Items.COAL)==12,"destroyed destination leaves cargo in bag");
        pass("logistics_unavailable_chest_preserves_in_transit_cargo");

        var tools=new ArrayList<ItemStack>();
        tools.add(new ItemStack(Items.STONE_PICKAXE));tools.add(new ItemStack(Items.STONE_PICKAXE));tools.add(new ItemStack(Items.STONE_PICKAXE));
        tools.get(0).setDamageValue(80);tools.get(1).setDamageValue(40);
        require(PersonalKits.kept(tools,ResidentRoles.Role.MINER,false).equals(Set.of(1,2)),"miner keeps best working and spare tool");
        require(PersonalKits.kept(tools,ResidentRoles.Role.PORTER,false).equals(Set.of(2)),"porter retains one emergency pick");
        pass("logistics_role_kit_exposes_only_real_tool_surplus");

        // A changed addressee invalidates the old promise and permits a new real handoff.
        // Isolate the carried pool: a separate chest reserve otherwise has first allocation.
        source.clearContent();
        porter.cancel();o=order(v,recipient,Orders.Material.FUEL,4);porter.deliver(o,recipient.id);
        require("deliver".equals(porter.job),"carried fuel assigned before changing recipient");
        o.recipient=donor.id.toString();porter.work(porter.bot());
        require(porter.agreement==null&&porter.bot().inventory().count(Items.COAL)==8,"changed recipient preserves load");
        porter.deliver(o,donor.id);porter.work(porter.bot());
        require(donor.bot().inventory().count(Items.COAL)==4&&porter.bot().inventory().count(Items.COAL)==4,"changed recipient receives physical cargo");
        pass("logistics_changed_recipient_reassigns_actual_cargo");

        // Replenishment is scheduled without a project order; seed reserve is bounded.
        porter.cancel();v.state.orders.clear();v.rejected.clear();
        v.level.setBlock(q,Blocks.CHEST.defaultBlockState(),3);v.state.storage.add(q.asLong());
        destination=(ChestBlockEntity)v.level.getBlockEntity(q);destination.clearContent();
        source.clearContent();source.setItem(0,new ItemStack(Items.WHEAT_SEEDS,32));
        var field=new ManagedLand.Field();field.seedChest=q.asLong();field.produceChest=p.asLong();
        field.water=origin.asLong();field.entry=origin.asLong();v.state.land.fields.add(field);
        DeliveryRuns.restock(v);
        var scheduled=v.workers.values().stream().filter(r->r.agreement!=null).findFirst().orElseThrow();
        require(scheduled.agreement.destination==destination&&scheduled.agreement.quantity==16,"regular field supply planned");
        scheduled.work(scheduled.bot());scheduled.work(scheduled.bot());
        require(destination.countItem(Items.WHEAT_SEEDS)==16&&source.countItem(Items.WHEAT_SEEDS)==16,"regular replenishment keeps physical total");
        pass("logistics_regular_field_chest_replenishment");
        v.state.land.fields.clear();

        porter.cancel();o=order(v,recipient,Orders.Material.FUEL,4);porter.deliver(o,recipient.id);
        recipient.body().kill(v.level);v.sync();
        if(porter.job!=null)porter.work(porter.bot());
        require(porter.agreement==null&&porter.bot().inventory().count(Items.COAL)==4,"recipient death preserves carrier cargo");
        pass("logistics_recipient_death_releases_agreement_without_item_loss");
    }
    private static Orders.Order order(Village v,Resident recipient,Orders.Material material,int n){
        v.state.orders.clear();var o=new Orders.Order();o.material=material.name();o.purpose="logistics_fixture";o.quantity=n;
        o.recipient=recipient.id.toString();o.work=recipient.body().blockPosition().asLong();o.delivery=o.work;
        v.state.orders.add(o);return o;
    }
}
