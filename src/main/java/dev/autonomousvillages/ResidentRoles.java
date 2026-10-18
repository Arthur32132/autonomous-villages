package dev.autonomousvillages;

import java.util.*;

/** Persistent specializations. Jobs and loaded controller order are deliberately absent here. */
final class ResidentRoles {
    enum Role {
        STEWARD("завхоз"),BUILDER("строитель"),FARMER("фермер"),LOGGER("лесоруб"),
        MINER("шахтёр"),GUARD("сторож");
        final String label;
        Role(String label){this.label=label;}
    }
    static final class Assignment {
        Role role;
        String profession,reason;
        int schedule;
        Assignment() { }
        Assignment(UUID id,Role role,String profession,String reason){
            this.role=role;this.profession=profession;this.reason=reason;
            schedule=Math.floorMod(id.hashCode(),24);
        }
    }
    static Role preferred(String profession){
        return switch(profession){
            case "farmer","fisherman","butcher","shepherd"->Role.FARMER;
            case "mason"->Role.BUILDER;
            case "toolsmith","weaponsmith"->Role.MINER;
            case "armorer"->Role.GUARD;
            case "librarian","cleric","cartographer"->Role.STEWARD;
            case "fletcher"->Role.LOGGER;
            default->null;
        };
    }
    /** Fill vacant specialties first; never rebalance existing adults on birth, unload or death. */
    static boolean assign(Map<UUID,Assignment> assigned,Map<UUID,String> adults){
        var newcomers=adults.keySet().stream().filter(id->!assigned.containsKey(id))
                .sorted(Comparator.<UUID>comparingInt(id->preferred(adults.get(id))==null?1:0)
                        .thenComparing(UUID::toString)).toList();
        for(UUID id:newcomers){
            var counts=new EnumMap<Role,Integer>(Role.class);
            for(var a:assigned.values())counts.merge(a.role,1,Integer::sum);
            int minimum=Arrays.stream(Role.values()).mapToInt(r->counts.getOrDefault(r,0)).min().orElse(0);
            Role nativeRole=preferred(adults.get(id));
            Role role=nativeRole!=null&&counts.getOrDefault(nativeRole,0)==minimum?nativeRole:
                    Arrays.stream(Role.values()).filter(r->counts.getOrDefault(r,0)==minimum).findFirst().orElseThrow();
            assigned.put(id,new Assignment(id,role,adults.get(id),role==nativeRole?
                    "родная профессия: "+adults.get(id):"нужен специалист: "+role.label));
        }
        return !newcomers.isEmpty();
    }
    static boolean homeCoverage(Map<UUID,Role> available,Set<UUID> away){
        var home=available.entrySet().stream().filter(e->!away.contains(e.getKey())).map(Map.Entry::getValue).toList();
        // Three independent adults must remain: food, repairs and watch. A steward can
        // cover repairs, but one generalist is never counted as three people.
        return home.contains(Role.FARMER)&&home.contains(Role.GUARD)
                &&(home.contains(Role.BUILDER)||home.contains(Role.STEWARD));
    }
}
