package dev.autonomousvillages;

import java.util.*;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;

/** One active production dependency: shears, a miner's pick, then a guard's next armor piece. */
final class IronSupply {
    record Need(Resident recipient,String purpose,int ingots){ }
    /** Candidates only. SettlementNeeds orders and schedules the entire set. */
    static List<Need> requests(Village v){
        var needs=new ArrayList<Need>();
        var caretaker=v.farm.caretaker();
        boolean wool=v.state.buildings.stream().flatMap(b->b.blocks.stream())
                .anyMatch(p->p.block().getBlock() instanceof net.minecraft.world.level.block.BedBlock&&!p.matches(v));
        if(wool&&caretaker!=null&&Supplies.find(caretaker.bot(),s->s.is(Items.SHEARS)&&Supplies.usable(s))<0)
            needs.add(new Need(caretaker,"iron_shears",2));
        var workers=v.workers.values().stream().filter(r->r.bot()!=null&&r.bot().state()==dev.navigationai.api.BotState.ACTIVE
                &&r.body().isAlive()&&!r.body().isNoAi()&&!r.body().isBaby()&&!v.expeditions.owns(r.id))
                .sorted(Comparator.comparing(r->r.id.toString())).toList();
        for(var r:workers)if(v.hasRole(r.id,ResidentRoles.Role.MINER)&&!VillageWorkshop.hasPickFor(r.bot(),Blocks.DIAMOND_ORE.defaultBlockState()))
            needs.add(new Need(r,"iron_pickaxe",3));
        var irrigator=ManagedLand.irrigator(v);
        if(irrigator!=null&&!v.state.land.fields.isEmpty()&&v.state.land.fields.stream().anyMatch(f->!v.level.getBlockState(net.minecraft.core.BlockPos.of(f.water)).is(Blocks.WATER))
                &&irrigator.bot().inventory().count(Items.BUCKET)+irrigator.bot().inventory().count(Items.WATER_BUCKET)==0)
            needs.add(new Need(irrigator,"iron_bucket",3));
        if(v.state.buildings.stream().noneMatch(b->b.kind.equals("warehouse")&&v.town.warehousePhase(b)>=1))return needs;
        EquipmentSlot[] slots={EquipmentSlot.OFFHAND,EquipmentSlot.CHEST,EquipmentSlot.HEAD,EquipmentSlot.LEGS,EquipmentSlot.FEET};
        Item[] items={Items.SHIELD,Items.IRON_CHESTPLATE,Items.IRON_HELMET,Items.IRON_LEGGINGS,Items.IRON_BOOTS};
        int[] costs={1,8,5,7,4};
        for(var r:workers)if(v.guard(r.id))for(int i=0;i<slots.length;i++){
            final Item item=items[i];
            if(!Supplies.usable(r.body().getItemBySlot(slots[i]))&&Supplies.find(r.bot(),s->s.is(item)&&Supplies.usable(s))<0)
                needs.add(new Need(r,"iron_"+slots[i].getName(),costs[i]));
        }
        return needs;
    }
    static Need next(Village v){
        return v.needs.needs().stream().filter(n->n.material()==Orders.Material.IRON&&v.needs.state(n).state()!=SettlementNeeds.State.DONE)
                .map(n->{var r=v.workers.values().stream().filter(worker->worker.id.toString().equals(n.executor())).findFirst().orElse(null);
                    return r==null?null:new Need(r,n.purpose().split("@",2)[0],n.required());}).filter(Objects::nonNull).findFirst().orElse(null);
    }
    static int rawNeeded(Village v){
        int target=v.needs.needs().stream().filter(n->n.material()==Orders.Material.IRON).mapToInt(SettlementNeeds.Need::required).sum();if(target==0)return 0;
        int cooking=0;
        for(long p:v.state.furnaces)if(v.level.getBlockEntity(net.minecraft.core.BlockPos.of(p)) instanceof net.minecraft.world.level.block.entity.FurnaceBlockEntity f&&!f.isLocked())
            cooking+=(f.getItem(0).is(Items.RAW_IRON)?f.getItem(0).getCount():0)+(f.getItem(2).is(Items.IRON_INGOT)?f.getItem(2).getCount():0);
        return Math.max(0,target-v.count(s->s.is(Items.IRON_INGOT)||s.is(Items.RAW_IRON))-cooking);
    }
    static boolean maySpend(Village v,Resident r,String purpose){
        return v.needs.needs().stream().anyMatch(n->n.produces().equals(purpose)&&n.executor().equals(r.id.toString())
                &&v.needs.state(n).state()==SettlementNeeds.State.READY);
    }
    static Item output(String purpose){
        return switch(purpose){
            case "iron_shears"->Items.SHEARS;case "iron_pickaxe"->Items.IRON_PICKAXE;case "iron_bucket"->Items.BUCKET;
            case "iron_offhand"->Items.SHIELD;case "iron_chest"->Items.IRON_CHESTPLATE;case "iron_head"->Items.IRON_HELMET;
            case "iron_legs"->Items.IRON_LEGGINGS;case "iron_feet"->Items.IRON_BOOTS;default->Items.AIR;
        };
    }
    static boolean accepts(Village v,Resident r,ItemStack out){
        return v.needs.needs().stream().anyMatch(n->n.purpose().startsWith("craft_iron_")&&n.executor().equals(r.id.toString())
                &&out.is(output(n.produces()))&&v.needs.state(n).state()==SettlementNeeds.State.READY);
    }
    /** Compatibility entry point for explicit scenario synchronization. */
    static void sync(Village v,long now){SettlementNeeds.sync(v,now);}
}
