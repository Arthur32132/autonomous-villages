package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.*;

/** Stable slot selection: keep the best working tool and a role-specific spare, not every tool. */
final class PersonalKits {
    static String family(ItemStack s){
        if(s.is(ItemTags.PICKAXES))return "pick";
        if(s.is(ItemTags.AXES))return "axe";
        if(s.is(ItemTags.HOES))return "hoe";
        if(s.is(ItemTags.SWORDS))return "sword";
        if(s.is(Items.SHEARS))return "shears";
        if(s.is(Items.SHIELD))return "shield";
        if(s.has(DataComponents.EQUIPPABLE))return "equipment:"+s.getItem();
        return "";
    }
    static int tools(ResidentRoles.Role role,String family,boolean shepherd){
        return switch(family){
            case "pick" -> role==ResidentRoles.Role.MINER?2:1;
            case "axe" -> role==ResidentRoles.Role.LOGGER?2:1;
            case "hoe" -> role==ResidentRoles.Role.FARMER?2:0;
            case "shears" -> shepherd?2:0;
            case "sword" -> role==ResidentRoles.Role.GUARD?2:1;
            case "shield" -> role==ResidentRoles.Role.GUARD?1:0;
            default -> family.startsWith("equipment:")?1:0;
        };
    }
    static Set<Integer> kept(List<ItemStack> bag,ResidentRoles.Role role,boolean shepherd){
        return kept(bag,role,shepherd,Map.of());
    }
    static Set<Integer> kept(List<ItemStack> bag,ResidentRoles.Role role,boolean shepherd,Map<Orders.Material,Integer> cargoKits){
        Set<Integer> kept=new HashSet<>();
        Map<String,List<Integer>> families=new LinkedHashMap<>();
        for(int i=0;i<bag.size();i++){
            var s=bag.get(i);if(s.isEmpty())continue;
            if(s.has(DataComponents.CUSTOM_NAME))kept.add(i);
            String family=family(s);
            if(!family.isEmpty()&&Supplies.usable(s))families.computeIfAbsent(family,k->new ArrayList<>()).add(i);
        }
        for(var e:families.entrySet()){
            var slots=e.getValue();
            slots.sort(Comparator.<Integer>comparingInt(i->bag.get(i).isDamageableItem()?bag.get(i).getMaxDamage()-bag.get(i).getDamageValue():Integer.MAX_VALUE).reversed().thenComparingInt(i->i));
            int named=(int)slots.stream().filter(kept::contains).count();
            int left=Math.max(0,tools(role,e.getKey(),shepherd)-named);
            for(int i:slots)if(!kept.contains(i)&&left>0){kept.add(i);left--;}
        }
        // A carried tool does not enlarge the personal kit. Retain the best tools
        // up to the saved pre-pickup quantity; named items always stay protected.
        for(var entry:cargoKits.entrySet()){
            var material=entry.getKey();var slots=new ArrayList<Integer>();int named=0;
            for(int i=0;i<bag.size();i++){
                var s=bag.get(i);
                if(family(s).isEmpty()||!material.accepts.test(s))continue;
                if(s.has(DataComponents.CUSTOM_NAME)){named+=s.getCount();continue;}
                kept.remove(i);
                if(Supplies.usable(s))slots.add(i);
            }
            slots.sort(Comparator.<Integer>comparingInt(i->bag.get(i).isDamageableItem()?bag.get(i).getMaxDamage()-bag.get(i).getDamageValue():Integer.MAX_VALUE).reversed().thenComparingInt(i->i));
            int left=Math.max(0,entry.getValue()-named);
            for(int i:slots)if(left>0){kept.add(i);left-=bag.get(i).getCount();}
        }
        return kept;
    }
}
