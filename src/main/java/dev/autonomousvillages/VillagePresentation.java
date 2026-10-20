package dev.autonomousvillages;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.*;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;

/** Vanilla networked names/boss bar, real hand animations and chest block events; no client mod needed. */
final class VillagePresentation {
    private final Village v;
    private final ServerBossEvent bar;
    private final Map<Long,Long> opening=new HashMap<>();
    /** Where a resident is putting up the notice board, with the tick its job was last seen. */
    private final Map<Long,Long> boardSpots=new HashMap<>();
    private long nextDisplayNanos,nextNameLog;
    VillagePresentation(Village v){this.v=v;bar=new ServerBossEvent(UUID.randomUUID(),Component.literal(v.state.name),BossEvent.BossBarColor.GREEN,BossEvent.BossBarOverlay.PROGRESS);}
    void tick(){
        long now=v.level.getGameTime();
        for(long p:List.copyOf(opening.keySet()))if(opening.get(p)<=now){
            BlockPos pos=BlockPos.of(p);v.level.blockEvent(pos,v.level.getBlockState(pos).getBlock(),1,0);opening.remove(p);
        }
        v.chronicle.flushChat();
        watchBoard(now);
        if(now%200==100)updateBoard();
        if(now%20!=0)return;
        long realNow=System.nanoTime();if(realNow<nextDisplayNanos)return;
        nextDisplayNanos=realNow+500_000_000L;
        refreshViewers();
        var current=v.town.active();
        bar.setName(Component.literal(v.state.name+" • "+(v.level.isDarkOutside()?"ночной отдых • ":"")+v.town.stage+" • жителей "+v.state.residents.size()+" • еда "+Food.meals(v.stock)));
        bar.setProgress(current==null?1f:Math.clamp((float)v.town.built(current)/Math.max(1,v.town.size(current)),0f,1f));
        for(Worker w:v.workers.values()){
            if(w.bot()==null||!(w.bot().entity() instanceof Villager resident))continue;
            try{label(w,resident);}
            catch(RuntimeException broken){if(now>=nextNameLog){nextNameLog=now+1200;AutonomousVillagesMod.LOG.warn("Cannot name resident {}: {}",w.id,broken.toString());}}
        }
    }
    /** The bar goes to players near the village; one who left, died or logged out is dropped at once, never kept. */
    private void refreshViewers(){
        var online=v.runtime.server.getPlayerList().getPlayers();
        for(ServerPlayer player:List.copyOf(bar.getPlayers()))
            if(player.hasDisconnected()||player.isRemoved()||!player.isAlive()||!online.contains(player))bar.removePlayer(player);
        var area=VillageDiscovery.area(v.state,96);
        for(ServerPlayer player:online){
            boolean near=player.isAlive()&&!player.isRemoved()&&player.level()==v.level&&area.contains(player.position());
            if(near)bar.addPlayer(player);else bar.removePlayer(player);
        }
    }
    /** A name a player gave (a name tag, before or after the claim) stays exactly as given; other residents show the village's label. */
    private void label(Worker w,Villager resident){
        Component shown=resident.getCustomName();
        String text=shown==null?null:shown.getString();
        var record=v.state.residentNames.computeIfAbsent(w.id,id->{var n=new VillageState.ResidentName();n.visible=resident.isCustomNameVisible();
            if(text!=null&&!generatedLabel(text,generatedName(id),null))n.json=encode(shown);return n;});
        String player=playerName(record);
        if(text!=null&&!generatedLabel(text,generatedName(w.id),player)){
            // A name tag given since the last look becomes the resident's name, and the one restored on release.
            if(!text.equals(player))record.json=encode(shown);
            return;
        }
        if(player!=null&&text!=null){resident.setCustomName(decode(record.json));return;}
        if(player!=null)record.json="";
        resident.setCustomName(Component.literal(personName(w.id)+", "+MasteryFeature.withTitle(v,w.id,v.role(w.id))+" • "+w.status));resident.setCustomNameVisible(true);
    }
    private static final String[] NAMES={"Иван","Пётр","Мария","Анна","Борис","Глеб","Дарья","Елена","Фёдор","Злата","Игорь","Ксения",
            "Лев","Мирон","Ника","Остап","Полина","Роман","Савва","Тимофей","Ульяна","Фрол","Степан","Вера","Любава","Милана","Добрыня",
            "Тихон","Агафья","Ефим","Прохор","Василиса","Ярослава","Демид","Есения","Захар","Илья","Матвей","Нина","Олеся"};
    /** A name tag the player gave wins; otherwise every resident gets a stable name from its UUID. */
    String personName(UUID id){
        String player=playerName(v.state.residentNames.get(id));
        return player!=null?player:generatedName(id);
    }
    static String generatedName(UUID id){return NAMES[Math.floorMod(id.getLeastSignificantBits()^id.getMostSignificantBits(),NAMES.length)];}
    /** The village's own label ("Иван, строитель • рубит"), also the one an older version wrote over a player's name. */
    static boolean generatedLabel(String shown,String generated,String player){
        return shown.contains(" • ")&&(shown.startsWith(generated+", ")||player!=null&&!player.isEmpty()&&shown.startsWith(player+", "));
    }
    private static String playerName(VillageState.ResidentName record){
        if(record==null||record.untouched||record.json==null||record.json.isEmpty())return null;
        try{return decode(record.json).getString();}catch(RuntimeException broken){return null;}
    }
    private static Component decode(String json){return ComponentSerialization.CODEC.parse(JsonOps.INSTANCE,JsonParser.parseString(json)).getOrThrow();}
    private static String encode(Component name){
        try{return ComponentSerialization.CODEC.encodeStart(JsonOps.INSTANCE,name).getOrThrow().toString();}
        catch(RuntimeException broken){return ComponentSerialization.CODEC.encodeStart(JsonOps.INSTANCE,Component.literal(name.getString())).getOrThrow().toString();}
    }
    static final Map<String,String> NEEDS=Map.ofEntries(Map.entry("planks","доски"),Map.entry("logs","брёвна"),Map.entry("cobblestone","булыжник"),
            Map.entry("stairs","ступени"),Map.entry("slab","плиты"),Map.entry("door","двери"),Map.entry("bed","кровати"),Map.entry("torch","факелы"),
            Map.entry("fence","заборы"),Map.entry("chest","сундук"),Map.entry("crafting_table","верстак"),Map.entry("furnace","печь"));
    /** About the characters a vanilla sign line shows (90 px of a 6 px font); longer text is cut with an ellipsis. */
    static final int SIGN_LINE=15;
    static String clip(String line){return line==null?"":line.length()<=SIGN_LINE?line:line.substring(0,SIGN_LINE-1).stripTrailing()+"…";}
    /** The board's four lines: the village's name over three lines of news, each clipped to what a sign holds. */
    static List<String> boardLines(String village,List<String> lines){
        var out=new ArrayList<String>(4);out.add(clip(village));
        for(int i=0;i<3;i++)out.add(clip(i<lines.size()?lines.get(i):""));
        return out;
    }
    /** Headings the board has shown, by which a board taken over by an older version is recognised. */
    static final Set<String> BOARD_HEADINGS=Set.of("стройке нужно:","нужно на стройку:","всё построено","ЗАКАЗ игроку:");
    static boolean oldBoard(String top,String heading,String village){return (top.equals(village)||top.equals(clip(village)))&&BOARD_HEADINGS.contains(heading);}
    /** Remembers where residents put up a sign for the board; the sign found there just after is the village's own. */
    private void watchBoard(long now){
        if(v.state.noticeBoard!=null){boardSpots.clear();return;}
        for(Worker w:v.workers.values())if(w.job instanceof PlaceJob p&&p.state.is(net.minecraft.tags.BlockTags.STANDING_SIGNS))boardSpots.put(p.spot.asLong(),now);
        if(!boardSpots.isEmpty())adoptBoard();
    }
    /** A sign standing on such a spot within a second of its job becomes the board; Village.noticeBoard() asks too, so planners see it at once. */
    void adoptBoard(){
        long now=v.level.getGameTime();
        boardSpots.values().removeIf(seen->now-seen>20);
        for(long spot:boardSpots.keySet()){
            BlockPos p=BlockPos.of(spot);
            if(v.state.contains(p)&&v.level.hasChunkAt(p)&&v.level.getBlockEntity(p) instanceof SignBlockEntity){v.state.noticeBoard=spot;boardSpots.clear();return;}
        }
    }
    /** A board an older version put up and wrote, before boards were remembered: by the bell, not a building's sign or a nameplate. */
    private void adoptOldBoard(){
        BlockPos hub=v.meetingPoint();
        for(BlockPos q:BlockPos.betweenClosed(hub.offset(-6,-2,-6),hub.offset(6,2,6))){
            if(!v.state.contains(q)||!v.level.hasChunkAt(q)||!(v.level.getBlockEntity(q) instanceof SignBlockEntity sign)||v.state.nameplates.containsKey(q.asLong())||v.town.protects(q))continue;
            var text=sign.getFrontText();
            if(oldBoard(text.getMessage(0,false).getString(),text.getMessage(1,false).getString(),v.state.name)){v.state.noticeBoard=q.asLong();return;}
        }
    }
    /** Rewrites the notice board when what it shows differs from what the sign at its position says. */
    private void updateBoard(){
        if(v.state.noticeBoard==null)adoptOldBoard();
        BlockPos p=v.noticeBoard();
        if(p==null||!v.level.hasChunkAt(p)||!(v.level.getBlockEntity(p) instanceof SignBlockEntity sign))return;
        var project=v.town.active();
        var lines=new ArrayList<String>();
        if(v.state.request!=null&&AutonomousVillagesMod.CONFIG.requests)lines.addAll(VillageRequests.board(v.state.request));
        else if(project==null)lines.add("всё построено");
        else{
            lines.add("стройке нужно:");
            v.town.missing(project).entrySet().stream().sorted(Map.Entry.<String,Integer>comparingByValue().reversed()).limit(2)
                    .forEach(e->lines.add(NEEDS.getOrDefault(e.getKey(),e.getKey())+" "+e.getValue()));
        }
        var shown=boardLines(v.state.name,lines);
        var text=sign.getFrontText();
        boolean same=true;
        for(int i=0;i<4;i++)same&=text.getMessage(i,false).getString().equals(shown.get(i));
        if(same)return;
        for(int i=0;i<4;i++)text=text.setMessage(i,Component.literal(shown.get(i)));
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
    /** On pause, release and server stop: a broken record or body never stops the others or the server. */
    void close(){
        try{bar.removeAllPlayers();}catch(RuntimeException broken){AutonomousVillagesMod.LOG.warn("Cannot clear the bar of {}: {}",v.state.name,broken.toString());}
        for(var entry:List.copyOf(v.state.residentNames.entrySet())){
            try{if(entry.getKey()!=null&&v.level.getEntity(entry.getKey()) instanceof Villager resident)restoreName(resident,entry.getValue());}
            catch(RuntimeException broken){AutonomousVillagesMod.LOG.warn("Cannot restore the name of {}: {}",entry.getKey(),broken.toString());}
        }
    }
    /** Gives the body back the name it had before the village (or the name tag given since); never throws, also from chunk load. */
    static void restoreName(Villager resident,VillageState.ResidentName original){
        if(resident==null||original==null||original.untouched)return;
        Component name=null;
        try{if(original.json!=null&&!original.json.isEmpty())name=decode(original.json);}
        catch(RuntimeException broken){AutonomousVillagesMod.LOG.warn("Unreadable saved name of {}, cleared: {}",resident.getUUID(),broken.toString());}
        try{resident.setCustomName(name);resident.setCustomNameVisible(original.visible);}
        catch(RuntimeException broken){AutonomousVillagesMod.LOG.warn("Cannot restore the name of {}: {}",resident.getUUID(),broken.toString());}
    }
}
