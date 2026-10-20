package dev.autonomousvillages;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.*;

/**
 * Hub for the self-contained village features added on top of the core (campfire evenings, orchard,
 * heraldry, storm safety, mastery, livestock). Each feature lives in its own class with its own switch
 * in {@link ModConfig} and its own JSON slot in {@link VillageState#features}, so adding one never
 * touches the planner, the tick loop or the save format of the others.
 *
 * Rules for a feature: it is ticked from {@link LifeEvents#tick} (every server tick, so gate on time
 * yourself); {@link Feature#job} is offered to every idle resident right after the core safety work
 * and before the role's own work, so it must limit itself (time windows, cooldowns, a cap on how many
 * residents do it at once) and return null almost always. A feature that throws is logged at most once
 * a minute and skipped; it never stops the others or the village.
 */
final class Features {
    interface Feature {
        /** Stable key: config switch name and the slot in {@link VillageState#features}. */
        String key();
        boolean enabled();
        default void tick(Village v,long time){}
        /** A job for this idle resident, or null; role is null for a small crew where everyone does everything. */
        default Job job(Village v,Worker w,ResidentRoles.Role role){return null;}
    }

    static final List<Feature> ALL=List.of(
            new CampfireFeature(),
            new OrchardFeature(),
            new HeraldryFeature(),
            new StormFeature(),
            new MasteryFeature(),
            new LivestockFeature());

    private static final Gson GSON=new GsonBuilder().disableHtmlEscaping().create();
    private static final Map<String,Long> lastError=new HashMap<>();
    /** One feature's saved JSON may not exceed this; a larger one is dropped instead of bloating the save. */
    static final int MAX_STATE_CHARS=32_768;

    private Features(){}

    static void tick(Village v,long time){
        for(var f:ALL){
            if(!f.enabled())continue;
            try{f.tick(v,time);}catch(RuntimeException e){report(v,f,time,e);}
        }
    }

    static Job job(Village v,Worker w,ResidentRoles.Role role,long time){
        for(var f:ALL){
            if(!f.enabled())continue;
            try{Job j=f.job(v,w,role);if(j!=null)return j;}catch(RuntimeException e){report(v,f,time,e);}
        }
        return null;
    }

    /** The feature's saved state, or a fresh instance when there is none or it does not parse. */
    static <T> T load(Village v,String key,Class<T> type,java.util.function.Supplier<T> fresh){
        String json=v.state.features==null?null:v.state.features.get(key);
        if(json!=null)try{T t=GSON.fromJson(json,type);if(t!=null)return t;}catch(RuntimeException ignored){}
        return fresh.get();
    }

    /** Stores the feature's state; the periodic save writes it with the rest of the village. */
    static void store(Village v,String key,Object state){
        if(v.state.features==null)v.state.features=new LinkedHashMap<>();
        String json=GSON.toJson(state);
        if(json.length()>MAX_STATE_CHARS){AutonomousVillagesMod.LOG.warn("Feature {} state too large ({} chars), dropped",key,json.length());v.state.features.remove(key);return;}
        v.state.features.put(key,json);
    }

    static boolean throttled(Map<String,Long> last,String key,long time,long every){
        Long at=last.get(key);
        if(at!=null&&time-at<every&&time>=at)return true;
        last.put(key,time);return false;
    }

    private static void report(Village v,Feature f,long time,RuntimeException e){
        if(throttled(lastError,v.state.name+"/"+f.key(),time,1200))return;
        AutonomousVillagesMod.LOG.error("Village {} feature {} failed",v.state.name,f.key(),e);
    }
}
