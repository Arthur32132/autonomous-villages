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
        if(now%200==100)updateBoard();
        if(now%20!=0)return;
        long realNow=System.nanoTime();if(realNow<nextDisplayNanos)return;
        nextDisplayNanos=realNow+500_000_000L;
        for(var player:v.runtime.server.getPlayerList().getPlayers()){
            boolean near=player.level()==v.level&&VillageDiscovery.area(v.state,96).contains(player.position());
            if(near)bar.addPlayer(player);else bar.removePlayer(player);
        }
        var current=v.town.active();
        bar.setName(Component.literal(v.state.name+" • "+(v.level.isDarkOutside()?"ночной отдых • ":"")+v.town.stage+" • жителей "+v.state.residents.size()+" • еда "+v.stock.count(Supplies::food)));
        bar.setProgress(current==null?1f:Math.clamp((float)v.town.built(current)/Math.max(1,v.town.size(current)),0f,1f));
        for(Worker w:v.workers.values()){
            if(w.bot()==null||!(w.bot().entity() instanceof Villager resident))continue;
            v.state.residentNames.computeIfAbsent(w.id,id->{var n=new VillageState.ResidentName();n.visible=resident.isCustomNameVisible();
                if(resident.getCustomName()!=null)n.json=ComponentSerialization.CODEC.encodeStart(JsonOps.INSTANCE,resident.getCustomName()).getOrThrow().toString();return n;});
            resident.setCustomName(Component.literal(personName(w.id)+", "+v.role(w.id)+" • "+w.status));resident.setCustomNameVisible(true);
        }
    }
    private static final String[] NAMES={"Иван","Пётр","Мария","Анна","Борис","Глеб","Дарья","Елена","Фёдор","Злата","Игорь","Ксения",
            "Лев","Мирон","Ника","Остап","Полина","Роман","Савва","Тимофей","Ульяна","Фрол","Степан","Вера","Любава","Милана","Добрыня",
            "Тихон","Агафья","Ефим","Прохор","Василиса","Ярослава","Демид","Есения","Захар","Илья","Матвей","Нина","Олеся"};
    /** A name tag the player gave wins; otherwise every resident gets a stable name from its UUID. */
    String personName(UUID id){
        var original=v.state.residentNames.get(id);
        if(original!=null&&!original.untouched&&!original.json.isEmpty()){
            try{return ComponentSerialization.CODEC.parse(JsonOps.INSTANCE,JsonParser.parseString(original.json)).getOrThrow().getString();}
            catch(RuntimeException ignored){}
        }
        return NAMES[Math.floorMod(id.getLeastSignificantBits()^id.getMostSignificantBits(),NAMES.length)];
    }
    static final Map<String,String> NEEDS=Map.ofEntries(Map.entry("planks","доски"),Map.entry("logs","брёвна"),Map.entry("cobblestone","булыжник"),
            Map.entry("stairs","ступени"),Map.entry("slab","плиты"),Map.entry("door","двери"),Map.entry("bed","кровати"),Map.entry("torch","факелы"),
            Map.entry("fence","заборы"),Map.entry("chest","сундук"),Map.entry("crafting_table","верстак"),Map.entry("furnace","печь"));
    private String boardText="";
    /** Rewrites the notice board when the list of missing materials changes. */
    private void updateBoard(){
        BlockPos p=v.noticeBoard();
        if(p==null||!(v.level.getBlockEntity(p) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign))return;
        var project=v.town.active();
        var lines=new ArrayList<String>();
        if(project==null)lines.add("всё построено");
        else{
            lines.add("нужно на стройку:");
            v.town.missing(project).entrySet().stream().sorted(Map.Entry.<String,Integer>comparingByValue().reversed()).limit(2)
                    .forEach(e->lines.add(NEEDS.getOrDefault(e.getKey(),e.getKey())+" "+e.getValue()));
        }
        while(lines.size()<3)lines.add("");
        String joined=String.join("|",lines);
        if(joined.equals(boardText))return;
        boardText=joined;
        var text=sign.getFrontText().setMessage(0,Component.literal(v.state.name));
        for(int i=0;i<3;i++)text=text.setMessage(i+1,Component.literal(lines.get(i)));
        sign.setText(text,true);sign.setChanged();
        v.level.sendBlockUpdated(p,v.level.getBlockState(p),v.level.getBlockState(p),3);
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
