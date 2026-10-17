package dev.autonomousvillages;

import java.util.*;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;

/** One active production dependency: shears, a miner's pick, then a guard's next armor piece. */
final class IronSupply {
    record Need(Resident recipient,String purpose,int ingots){ }
    static Need next(Village v){
        var caretaker=v.farm.caretaker();
        if(v.farm.woolRequested()&&caretaker!=null&&Supplies.find(caretaker.bot(),s->s.is(Items.SHEARS)&&Supplies.usable(s))<0)
            return new Need(caretaker,"iron_shears",2);
        var workers=v.workers.values().stream().filter(r->r.bot()!=null&&r.bot().state()==dev.navigationai.api.BotState.ACTIVE&&r.body().isAlive()&&!r.body().isNoAi())
                .sorted(Comparator.comparing(r->r.id.toString())).toList();
        for(var r:workers)if(v.hasRole(r.id,ResidentRoles.Role.MINER)&&!VillageWorkshop.hasPickFor(r.bot(),Blocks.DIAMOND_ORE.defaultBlockState()))
            return new Need(r,"iron_pickaxe",3);
        var irrigator=ManagedLand.irrigator(v);
        if(irrigator!=null&&!v.state.land.fields.isEmpty()&&v.state.land.fields.stream().anyMatch(f->!v.level.getBlockState(net.minecraft.core.BlockPos.of(f.water)).is(Blocks.WATER))
                &&irrigator.bot().inventory().count(Items.BUCKET)+irrigator.bot().inventory().count(Items.WATER_BUCKET)==0)return new Need(irrigator,"iron_bucket",3);
        if(v.state.buildings.stream().noneMatch(b->b.kind.equals("warehouse")&&v.town.warehousePhase(b)>=1))return null;
        EquipmentSlot[] slots={EquipmentSlot.OFFHAND,EquipmentSlot.CHEST,EquipmentSlot.HEAD,EquipmentSlot.LEGS,EquipmentSlot.FEET};
        Item[] items={Items.SHIELD,Items.IRON_CHESTPLATE,Items.IRON_HELMET,Items.IRON_LEGGINGS,Items.IRON_BOOTS};
        int[] costs={1,8,5,7,4};
        for(var r:workers)if(v.guard(r.id))for(int i=0;i<slots.length;i++){
            final Item item=items[i];
            if(!Supplies.usable(r.body().getItemBySlot(slots[i]))&&Supplies.find(r.bot(),s->s.is(item)&&Supplies.usable(s))<0)
                return new Need(r,"iron_"+slots[i].getName(),costs[i]);
        }
        return null;
    }
    static int rawNeeded(Village v){
        var need=next(v);if(need==null)return 0;
        int cooking=0;
        for(long p:v.state.furnaces)if(v.level.getBlockEntity(net.minecraft.core.BlockPos.of(p)) instanceof net.minecraft.world.level.block.entity.FurnaceBlockEntity f&&!f.isLocked())
            cooking+=(f.getItem(0).is(Items.RAW_IRON)?f.getItem(0).getCount():0)+(f.getItem(2).is(Items.IRON_INGOT)?f.getItem(2).getCount():0);
        return Math.max(0,need.ingots-v.count(s->s.is(Items.IRON_INGOT)||s.is(Items.RAW_IRON))-cooking);
    }
    static boolean maySpend(Village v,Resident r,String purpose){var n=next(v);return n==null||n.recipient==r&&n.purpose.equals(purpose);}
    static boolean accepts(Village v,Resident r,ItemStack out){
        var n=next(v);if(n==null)return true;if(n.recipient!=r)return false;
        return switch(n.purpose){
            case "iron_shears"->out.is(Items.SHEARS);
            case "iron_pickaxe"->out.is(Items.IRON_PICKAXE);
            case "iron_bucket"->out.is(Items.BUCKET);
            case "iron_offhand"->out.is(Items.SHIELD);
            case "iron_chest"->out.is(Items.IRON_CHESTPLATE);
            case "iron_head"->out.is(Items.IRON_HELMET);
            case "iron_legs"->out.is(Items.IRON_LEGGINGS);
            case "iron_feet"->out.is(Items.IRON_BOOTS);
            default->false;
        };
    }
    static void sync(Village v,long now){
        var need=next(v);boolean changed;
        if(need==null)changed=Orders.clear(v.state.orders,"iron_");
        else changed=Orders.applyScoped(v.state.orders,v.state.center().asLong(),List.of(new Orders.Demand(Orders.Material.IRON,need.purpose,need.ingots)),
                need.recipient.id.toString(),v.meetingPoint().asLong(),now,"iron_");
        changed|=Orders.refreshStock(v);
        if(changed)v.runtime.save();
    }
}
