package dev.autonomousvillages;

import java.util.*;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;

/** Refillable physical input batches for a primary builder, served by the existing couriers. */
final class ConstructionSupply {
    static String prefix(Resident r){
        var bytes=java.nio.ByteBuffer.allocate(16).putLong(r.id.getMostSignificantBits()).putLong(r.id.getLeastSignificantBits()).array();
        return "b_"+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)+"_";
    }
    static boolean sync(Village v,long now){
        boolean changed=false;
        for(var r:v.workers.values()){
            if(!v.hasRole(r.id,ResidentRoles.Role.BUILDER)||r.bot()==null||!r.body().isAlive()||r.body().isNoAi())continue;
            String prefix=prefix(r);var plans=v.town.wantedPlans(r);
            // Beds already have their exact plank order; a second bulk batch would
            // compete with that order for the same physical handoff.
            var wood=plans.stream().filter(p->wood(p.block())&&!(p.block().getBlock() instanceof BedBlock)).findFirst().orElse(null);
            long work=wood==null?v.state.center().asLong():v.state.buildings.stream().filter(b->b.blocks.contains(wood)).mapToLong(b->b.origin).findFirst().orElse(v.state.center().asLong());
            int planks=r.bot().inventory().snapshot().slots().stream().filter(s->s.is(ItemTags.PLANKS)).mapToInt(ItemStack::getCount).sum();
            int logs=Supplies.count(r.bot().inventory().snapshot().slots(),s->s.is(ItemTags.LOGS_THAT_BURN));
            var demand=new ArrayList<Orders.Demand>();
            if(wood!=null){
                // One conversion route per batch. An active carrier keeps his original
                // material; otherwise prefer existing boards, then burnable logs.
                var moving=v.state.orders.stream().filter(o->o.purpose.startsWith(prefix)&&busy(o)
                        &&(o.kind()==Orders.Material.LOGS||o.kind()==Orders.Material.PLANKS)).findFirst().orElse(null);
                boolean useLogs=moving!=null?moving.kind()==Orders.Material.LOGS:
                        v.count(s->s.is(ItemTags.PLANKS))-planks<=0&&v.count(s->s.is(ItemTags.LOGS_THAT_BURN))-logs>0;
                if(useLogs){int target=Math.max(0,(32-planks+3)/4);if(target>0||moving!=null)demand.add(new Orders.Demand(Orders.Material.LOGS,prefix+"logs",target));}
                else {int target=Math.max(0,32-4*logs);if(target>0||moving!=null)demand.add(new Orders.Demand(Orders.Material.PLANKS,prefix+"planks",target));}
            }
            int stone=TownCrafting.ingredientReserve(r,new ItemStack(Items.COBBLESTONE));
            if(stone>0)demand.add(new Orders.Demand(Orders.Material.COBBLESTONE,prefix+"stone",stone));
            // Stop an obsolete delivery without deleting its physical cargo.
            for(var order:v.state.orders)if(order.purpose.startsWith(prefix)&&busy(order)
                    &&(order.work!=work||demand.stream().noneMatch(d->d.purpose().equals(order.purpose)))){
                if(order.quantity!=0||order.delivered!=0){order.quantity=0;order.delivered=0;changed=true;}
            }
            changed|=v.state.orders.removeIf(o->o.purpose.startsWith(prefix)&&!busy(o)&&(o.work!=work||demand.stream().noneMatch(d->d.purpose().equals(o.purpose))));
            for(var need:demand){
                var active=v.state.orders.stream().filter(o->o.purpose.equals(need.purpose())).findFirst().orElse(null);
                if(active!=null&&busy(active)){
                    // Keep material/work/recipient stable, but reduce the current stock
                    // target if crafting or another source has already met the need.
                    int target=active.work==work?need.quantity():0;
                    if(active.quantity!=target){active.quantity=target;active.delivered=Math.min(active.delivered,target);changed=true;}
                    continue;
                }
                changed|=Orders.applyScoped(v.state.orders,work,List.of(need),r.id.toString(),v.meetingPoint().asLong(),now,need.purpose());
            }
        }
        changed|=v.state.orders.removeIf(o->o.purpose.startsWith("b_")&&!busy(o)&&v.workers.values().stream().noneMatch(r->v.hasRole(r.id,ResidentRoles.Role.BUILDER)&&o.purpose.startsWith(prefix(r))));
        return changed;
    }
    private static boolean busy(Orders.Order order){return order.transit||!order.carrier.isEmpty();}
    private static boolean wood(net.minecraft.world.level.block.state.BlockState s){
        return s.is(BlockTags.PLANKS)||s.is(BlockTags.LOGS)||s.is(BlockTags.WOODEN_STAIRS)||s.is(BlockTags.WOODEN_DOORS)||s.is(BlockTags.WOODEN_FENCES)
                ||s.is(Blocks.CHEST)||s.is(Blocks.CRAFTING_TABLE)||s.getBlock() instanceof BedBlock||s.getBlock() instanceof FenceGateBlock;
    }
    static boolean awaiting(Resident r){
        return r.v.state.orders.stream().anyMatch(o->o.purpose.startsWith(prefix(r))&&DeliveryRuns.canServe(r.v,o));
    }
}
