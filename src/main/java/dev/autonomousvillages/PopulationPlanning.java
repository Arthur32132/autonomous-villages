package dev.autonomousvillages;

import java.util.*;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.*;

/** Native reproduction is retained; controlled food surplus makes its prerequisites explicit. */
final class PopulationPlanning {
    static void tick(Village v){
        if(v.level.getGameTime()%100!=0)return;
        reassignOrders(v);
        boolean grow=SettlementEconomy.canGrow(v);
        for(var r:v.workers.values())if(r.canExchange()){
            var body=r.body();
            if(!grow){
                // Two bread-equivalents remain for nutrition; remove only breeding surplus
                // to the real backpack. Native offspring are never deleted or aborted.
                var food=body.getInventory();
                int left=2;
                for(int slot=0;slot<food.getContainerSize();slot++){
                    var s=food.getItem(slot);if(!Supplies.food(s))continue;int unit=s.is(Items.BREAD)?1:4;
                    int keep=Math.min(s.getCount(),left*unit);left=Math.max(0,left-(keep+unit-1)/unit);
                    int extra=s.getCount()-keep;if(extra<=0)continue;
                    var rest=r.bot().inventory().insert(s.copyWithCount(extra));int moved=extra-rest.getCount();if(moved>0){s.shrink(moved);food.setChanged();}
                }
            }
        }
        if(!grow||v.level.isDarkOutside()||Math.floorMod(v.level.getOverworldClockTime(),24000)<9000||Math.floorMod(v.level.getOverworldClockTime(),24000)>11500)return;
        // Give an eligible pair actual surplus bread from a real nearby chest. Vanilla Brain
        // chooses the partner and consumes food; this code never spawns or ages a child.
        var pair=v.workers.values().stream().filter(r->r.bot()!=null&&r.body().isAlive()&&!r.body().isBaby()&&!r.body().isTrading()&&!v.expeditions.owns(r.id)
                &&r.combat==null&&r.body().getInventory().countItem(Items.BREAD)<6).limit(2).toList();
        if(pair.size()!=2)return;
        for(var r:pair)for(var chest:v.chests())if(r.body().blockPosition().distSqr(chest.getBlockPos())<16){
            int needed=6-r.body().getInventory().countItem(Items.BREAD);
            if(chest.countItem(Items.BREAD)<needed)continue;
            var remaining=r.body().getInventory().addItem(new ItemStack(Items.BREAD,needed));int moved=needed-remaining.getCount();
            for(int slot=0;slot<chest.getContainerSize()&&moved>0;slot++)if(chest.getItem(slot).is(Items.BREAD)){
                int n=Math.min(moved,chest.getItem(slot).getCount());chest.removeItem(slot,n);moved-=n;
            }
            chest.setChanged();break;
        }
    }
    static boolean mayReplace(ResidentRoles.Role missing,ResidentRoles.Role current,int count){
        if(current==null||current==missing)return false;
        if(count>1)return true;
        return Set.of(ResidentRoles.Role.FARMER,ResidentRoles.Role.BUILDER,ResidentRoles.Role.STEWARD,ResidentRoles.Role.GUARD).contains(missing)
                &&Set.of(ResidentRoles.Role.LOGGER,ResidentRoles.Role.MINER,ResidentRoles.Role.PORTER).contains(current);
    }
    static void reassignOrders(Village v){
        for(var order:v.state.orders)if(order.open()&&!order.returnCargo()&&order.recipient.isEmpty()){
            var successor=v.workers.values().stream().filter(r->r.bot()!=null&&r.body().isAlive()&&!r.body().isNoAi()&&!v.expeditions.owns(r.id))
                    .filter(r->order.purpose.equals("bed_shears")?v.farm.caretaker()==r:order.purpose.startsWith("iron_")?IronSupply.next(v)!=null&&IronSupply.next(v).recipient()==r:v.builder(r.id))
                    .sorted(Comparator.comparing(r->r.id.toString())).findFirst().orElse(null);
            if(successor!=null){order.recipient=successor.id.toString();v.event("Заказ "+order.purpose+" передан преемнику "+successor.id);v.runtime.save();}
        }
    }
    static void recoverVacancies(Village v){
        // Only an actual vacancy (confirmed death), never an unloaded resident, is filled.
        var required=new ArrayList<>(List.of(ResidentRoles.Role.FARMER,ResidentRoles.Role.BUILDER,ResidentRoles.Role.STEWARD,ResidentRoles.Role.GUARD,ResidentRoles.Role.MINER,ResidentRoles.Role.LOGGER));
        if(!v.state.orders.isEmpty())required.add(ResidentRoles.Role.PORTER);
        for(var missing:required){
            if(!v.state.vacantRoles.contains(missing))continue;
            if(v.state.residentRoles.values().stream().anyMatch(a->a.role==missing||missing==ResidentRoles.Role.BUILDER&&a.role==ResidentRoles.Role.STEWARD)){v.state.vacantRoles.remove(missing);continue;}
            var replacement=v.workers.values().stream().filter(r->r.bot()!=null&&r.body().isAlive()&&!r.body().isBaby()&&!r.body().isNoAi()&&!r.body().isTrading()&&r.body().getHealth()>r.body().getMaxHealth()*.6&&r.job==null&&r.combat==null&&!v.expeditions.owns(r.id))
                    .filter(r->{var role=v.primaryRole(r.id);return mayReplace(missing,role,(int)v.state.residentRoles.values().stream().filter(a->a.role==role).count());})
                    .sorted(Comparator.comparing(r->r.id.toString())).findFirst().orElse(null);
            if(replacement==null)continue;var assignment=v.state.residentRoles.get(replacement.id);
            assignment.role=missing;v.state.vacantRoles.remove(missing);assignment.reason="замена погибшего специалиста: "+missing.label;
            v.event("Назначение изменено: "+assignment.reason);v.runtime.save();
        }
    }
}
