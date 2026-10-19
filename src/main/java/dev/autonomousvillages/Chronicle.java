package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/** What the village has done: running totals and the latest notable events, kept in the save. */
final class Chronicle {
    static final class Data {
        public Map<String,Long> totals=new TreeMap<>();
        public List<String> events=new ArrayList<>();
        void sanitize(){if(totals==null)totals=new TreeMap<>();if(events==null)events=new ArrayList<>();while(events.size()>60)events.removeFirst();}
    }
    private final Village v;
    Chronicle(Village v){this.v=v;}
    Data data(){if(v.state.chronicle==null)v.state.chronicle=new Data();return v.state.chronicle;}
    void add(String key,long n){data().totals.merge(key,n,Long::sum);}
    long total(String key){return data().totals.getOrDefault(key,0L);}
    String day(){return "день "+(v.level.getGameTime()/24000+1);}
    void event(String message){
        var events=data().events;events.add(day()+": "+message);while(events.size()>60)events.removeFirst();
        AutonomousVillagesMod.LOG.info("SETTLEMENT {}: {}",v.state.name,message);
    }
    /** An event worth telling: recorded, and shown in chat to players near the village. */
    void announce(String message){
        event(message);
        tell(message);
        if(v.lifeEvents!=null)v.lifeEvents.herald();
    }
    /** Shown in chat to players near the village, but not kept among the events. */
    void tell(String message){
        var text=net.minecraft.network.chat.Component.literal("["+v.state.name+"] ").withStyle(net.minecraft.ChatFormatting.GOLD)
                .append(net.minecraft.network.chat.Component.literal(message).withStyle(net.minecraft.ChatFormatting.YELLOW));
        var area=VillageDiscovery.area(v.state,64);
        for(var player:v.runtime.server.getPlayerList().getPlayers())
            if(player.level()==v.level&&area.contains(player.position()))player.sendSystemMessage(text);
    }
    void crafted(ItemStack output){add("crafted",output.getCount());add("crafted:"+BuiltInRegistries.ITEM.getKey(output.getItem()).getPath(),output.getCount());}
    void placed(){add("placed",1);}
    void treeFelled(int logs){add("trees",1);add("logs",logs);}
    void mined(){add("mined",1);}
    void harvested(){add("harvested",1);}
    void deposited(int items){add("deposited",items);}
    void lit(){add("torches",1);}
    void born(String name){add("births",1);announce("Родился "+name+"!");}
    void died(String who,String cause){add("deaths",1);announce("Погиб "+who+": "+cause);}
    /** The village's own book on a lectern: title page with the totals, then the latest events, newest first. */
    static final String BOOK_AUTHOR="жители";
    net.minecraft.world.item.ItemStack book(){
        var pages=new ArrayList<net.minecraft.server.network.Filterable<net.minecraft.network.chat.Component>>();
        String title=v.state.name+"\n"+day()+"\n\nЖителей "+v.state.residents.size()+"\nРождений "+total("births")+"\nПотерь "+total("deaths")
                +"\nДеревьев "+total("trees")+"\nБлоков "+total("placed")+"\nФакелов "+total("torches");
        pages.add(net.minecraft.server.network.Filterable.passThrough(net.minecraft.network.chat.Component.literal(title)));
        var events=data().events;var page=new StringBuilder();int lines=0;
        for(int i=events.size()-1;i>=0&&pages.size()<40;i--){
            String e=events.get(i);int needed=1+e.length()/18;
            if(lines+needed>13){pages.add(net.minecraft.server.network.Filterable.passThrough(net.minecraft.network.chat.Component.literal(page.toString())));page.setLength(0);lines=0;}
            page.append(e).append('\n');lines+=needed;
        }
        if(!page.isEmpty())pages.add(net.minecraft.server.network.Filterable.passThrough(net.minecraft.network.chat.Component.literal(page.toString())));
        var stack=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WRITTEN_BOOK);
        String name=("Летопись "+v.state.name);if(name.length()>32)name=name.substring(0,32);
        stack.set(net.minecraft.core.component.DataComponents.WRITTEN_BOOK_CONTENT,new net.minecraft.world.item.component.WrittenBookContent(
                net.minecraft.server.network.Filterable.passThrough(name),BOOK_AUTHOR,0,pages,true));
        return stack;
    }
    /** A book the village wrote itself (and may rewrite), never a player's. */
    static boolean ownBook(net.minecraft.world.item.ItemStack stack){
        return stack.is(net.minecraft.world.item.Items.WRITTEN_BOOK)&&ownBook(stack.get(net.minecraft.core.component.DataComponents.WRITTEN_BOOK_CONTENT));
    }
    static boolean ownBook(net.minecraft.world.item.component.WrittenBookContent content){
        return content!=null&&BOOK_AUTHOR.equals(content.author())&&content.title().raw().startsWith("Летопись ");
    }
    String render(int page){
        var lines=new ArrayList<String>();
        lines.add(v.state.name+" — летопись ("+day()+")");
        lines.add("Срублено деревьев "+total("trees")+" ("+total("logs")+" брёвен), добыто блоков "+total("mined")+", собрано урожая "+total("harvested"));
        lines.add("Поставлено блоков "+total("placed")+", факелов "+total("torches")+", изготовлено предметов "+total("crafted")+", сдано на склад "+total("deposited"));
        lines.add("Рождений "+total("births")+", потерь "+total("deaths"));
        var events=data().events;int from=Math.max(0,events.size()-page*10),to=Math.max(0,events.size()-(page-1)*10);
        for(int i=to-1;i>=from;i--)lines.add("• "+events.get(i));
        return String.join("\n",lines);
    }
}
