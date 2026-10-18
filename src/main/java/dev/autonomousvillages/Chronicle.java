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
    void crafted(ItemStack output){add("crafted",output.getCount());add("crafted:"+BuiltInRegistries.ITEM.getKey(output.getItem()).getPath(),output.getCount());}
    void placed(){add("placed",1);}
    void treeFelled(int logs){add("trees",1);add("logs",logs);}
    void mined(){add("mined",1);}
    void harvested(){add("harvested",1);}
    void deposited(int items){add("deposited",items);}
    void lit(){add("torches",1);}
    void born(){add("births",1);event("Родился новый житель!");}
    void died(String cause){add("deaths",1);event("Погиб житель: "+cause);}
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
