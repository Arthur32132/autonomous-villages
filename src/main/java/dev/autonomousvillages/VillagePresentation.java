package dev.autonomousvillages;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.*;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/** Vanilla networked names/boss bar, real hand animations and chest block events; no client mod needed. */
final class VillagePresentation {
    private final Village v;
    private final ServerBossEvent bar;
    private final Map<Long,Long> opening=new HashMap<>();
    private long nextDisplayNanos;
    VillagePresentation(Village v){this.v=v;bar=new ServerBossEvent(UUID.randomUUID(),Component.literal(v.state.name),BossEvent.BossBarColor.GREEN,BossEvent.BossBarOverlay.PROGRESS);}
    void tick(){
        long now=v.level.getGameTime();
        for(long p:List.copyOf(opening.keySet()))if(opening.get(p)<=now){
            BlockPos pos=BlockPos.of(p);v.level.blockEvent(pos,v.level.getBlockState(pos).getBlock(),1,0);opening.remove(p);
        }
        if(now%20!=0)return;
        long realNow=System.nanoTime();if(realNow<nextDisplayNanos)return;
        nextDisplayNanos=realNow+500_000_000L;
        for(var player:v.runtime.server.getPlayerList().getPlayers()){
            boolean near=player.level()==v.level&&VillageDiscovery.area(v.state,96).contains(player.position());
            if(near)bar.addPlayer(player);else bar.removePlayer(player);
        }
        var current=v.state.buildings.stream().filter(b->b.built(v)<b.blocks.size()).findFirst().orElse(null);
        bar.setName(Component.literal(v.state.name+" • "+(v.level.isDarkOutside()?"ночной отдых • ":"")+v.town.stage+" • еда "+v.count(Supplies::food)));
        bar.setProgress(current==null?1f:(float)current.built(v)/Math.max(1,current.blocks.size()));
        for(Resident r:v.workers.values()){
            if(r.bot()==null||!(r.bot().entity() instanceof Villager resident))continue;
            v.state.residentNames.computeIfAbsent(r.id,id->{var n=new VillageState.ResidentName();n.visible=resident.isCustomNameVisible();
                if(resident.getCustomName()!=null)n.json=ComponentSerialization.CODEC.encodeStart(JsonOps.INSTANCE,resident.getCustomName()).getOrThrow().toString();return n;});
            String text=v.role(r.id)+" • "+r.status;
            if(r.job!=null&&Set.of("deliver","collect_order","supply").contains(r.job)&&!v.porter(r.id))text+=" • временная помощь";
            if(r.job!=null&&r.target!=null&&r.job.equals("wood")&&r.treeBase!=null)text+=" • осталось брёвен "+v.state.treeWork.getOrDefault(r.treeBase.asLong(),List.of()).size();
            resident.setCustomName(Component.literal(text));resident.setCustomNameVisible(true);
        }
    }
    void chest(ChestBlockEntity chest){
        BlockPos p=chest.getBlockPos();if(!opening.containsKey(p.asLong())){
            v.level.blockEvent(p,v.level.getBlockState(p).getBlock(),1,1);
            v.level.playSound(null,p,net.minecraft.sounds.SoundEvents.CHEST_OPEN,net.minecraft.sounds.SoundSource.BLOCKS,.4f,1f);
        }
        opening.put(p.asLong(),v.level.getGameTime()+25);
    }
    void close(){bar.removeAllPlayers();
        for(var entry:v.state.residentNames.entrySet())if(v.level.getEntity(entry.getKey()) instanceof Villager resident){
            restoreName(resident,entry.getValue());
        }
    }
    static void restoreName(Villager resident,VillageState.ResidentName original){
        if(original==null||original.untouched)return;
        resident.setCustomName(original.json.isEmpty()?null:ComponentSerialization.CODEC.parse(JsonOps.INSTANCE,JsonParser.parseString(original.json)).getOrThrow());
        resident.setCustomNameVisible(original.visible);
    }
}
