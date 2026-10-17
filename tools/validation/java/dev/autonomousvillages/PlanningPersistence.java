package dev.autonomousvillages;

import java.util.*;
import java.nio.file.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.storage.LevelResource;
import static dev.autonomousvillages.VillageSelfTest.*;

/** Compare real saved metadata and native inventories after a process restart. */
final class PlanningPersistence {
    static final class Expected {
        VillageState state;
        Map<UUID,List<String>> bags=new LinkedHashMap<>();
    }
    static List<String> bag(UUID id){
        return v.runtime.bots.find(id).orElseThrow().inventory().snapshot().slots().stream()
            .map(s->s.isEmpty()?"empty":BuiltInRegistries.ITEM.getKey(s.getItem())+":"+s.getCount()+":"+s.getDamageValue()).toList();
    }
    static Path path(){return server.getWorldPath(LevelResource.ROOT).resolve("validation-systems-expected.json");}
    static void capture() throws java.io.IOException{
        v.state.active=false;v.workers.values().forEach(r->{r.cancel();r.bot().stopAll();});v.runtime.save();cmd("save-all flush");
        var expected=new Expected();expected.state=v.state;for(var id:v.state.residents)expected.bags.put(id,bag(id));
        Files.writeString(path(),VillageRuntime.GSON.toJson(expected));pass("systems_snapshot_saved_with_actual_native_bags_and_durability");
    }
    static boolean restore() throws java.io.IOException{
        if(age==1){v=AutonomousVillagesMod.runtime(server).get("Test");require(!v.state.active,"paused state restored");v.ticket(true);restartWaitStarted=System.nanoTime();}
        if(age<120)return false;
        var expected=VillageRuntime.GSON.fromJson(Files.readString(path()),Expected.class);
        if(expected.bags.keySet().stream().anyMatch(id->v.runtime.bots.find(id).isEmpty())){
            require(System.nanoTime()-restartWaitStarted<30_000_000_000L,"native bodies/bags unavailable after restart");return false;
        }
        var a=expected.state;var b=v.state;
        require(a.residents.equals(b.residents),"complete native membership restored");
        for(var id:expected.bags.keySet())require(expected.bags.get(id).equals(bag(id)),"native bag counts and tool durability restored exactly once: "+id);
        require(VillageRuntime.GSON.toJson(a.residentRoles).equals(VillageRuntime.GSON.toJson(b.residentRoles))&&VillageRuntime.GSON.toJson(a.land).equals(VillageRuntime.GSON.toJson(b.land))
            &&VillageRuntime.GSON.toJson(a.policy).equals(VillageRuntime.GSON.toJson(b.policy))&&a.workshopOutput.equals(b.workshopOutput)&&a.buildings.size()==b.buildings.size(),"roles, managed land, policies, output and building plans restored from disk");
        require(server.getPlayerList().getPlayerCount()==0,"no fake players after restart");pass("actual_process_restart_preserves_roles_land_policy_output_and_native_inventory");return true;
    }
}
