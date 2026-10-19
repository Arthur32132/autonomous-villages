package dev.autonomousvillages;

import java.util.*;

/**
 * At sunrise the players around the village hear what it did since the last morning: new blocks,
 * felled trees, harvest, torches, births and losses, and how far the current building has come.
 */
final class MorningNews {
    private MorningNews(){}

    /** A count with the Russian noun form it takes: 1 блок, 2 блока, 5 блоков, 11 блоков, 21 блок. */
    static String plural(long n,String one,String few,String many){
        long m=Math.abs(n)%100,d=m%10;
        String form=m>=11&&m<=14?many:d==1?one:d>=2&&d<=4?few:many;
        return n+" "+form;
    }

    /** The message from the chronicle totals of the last morning and of now. */
    static String text(String day,int residents,int residentsBefore,Map<String,Long> before,Map<String,Long> now,String building){
        var sb=new StringBuilder("Утро, ").append(day).append(". Жителей ").append(residents);
        if(residentsBefore>=0&&residents!=residentsBefore)sb.append(" (").append(residents>residentsBefore?"+":"").append(residents-residentsBefore).append(')');
        sb.append('.');
        var items=new ArrayList<String>();
        long placed=delta(before,now,"placed"),trees=delta(before,now,"trees"),harvest=delta(before,now,"harvested");
        long torches=delta(before,now,"torches"),births=delta(before,now,"births"),deaths=delta(before,now,"deaths");
        if(placed>0)items.add("+"+plural(placed,"блок","блока","блоков")+" в постройках");
        if(trees>0)items.add(plural(trees,"дерево","дерева","деревьев"));
        if(harvest>0)items.add("урожай с "+plural(harvest,"грядки","грядок","грядок"));
        if(torches>0)items.add(plural(torches,"факел","факела","факелов"));
        if(births>0)items.add(plural(births,"рождение","рождения","рождений"));
        if(deaths>0)items.add(plural(deaths,"потеря","потери","потерь"));
        sb.append(items.isEmpty()?" За сутки тихо.":" За сутки: "+String.join(", ",items)+".");
        if(building!=null)sb.append(" Стройка: ").append(building).append('.');
        return sb.toString();
    }
    private static long delta(Map<String,Long> before,Map<String,Long> now,String key){return now.getOrDefault(key,0L)-before.getOrDefault(key,0L);}

    /** Once a day, in the first hours after sunrise. */
    static void tick(Village v){
        if(!AutonomousVillagesMod.CONFIG.morningNews)return;
        long clock=v.level.getOverworldClockTime(),day=Math.floorDiv(clock,24000);
        var totals=v.chronicle.data().totals;
        // The first news comes the morning after the claim; until then the village only takes stock.
        if(v.state.newsTotals!=null){
            if(Math.floorMod(clock,24000)>=2000||v.state.newsDay==day)return;
            var current=v.town.active();
            String building=current==null?null:Projects.label(current)+" — "+v.town.built(current)+" из "+v.town.size(current);
            String news=text("день "+(day+1),v.state.residents.size(),v.state.newsResidents,v.state.newsTotals,totals,building);
            v.chronicle.tell(news);
            AutonomousVillagesMod.LOG.info("SETTLEMENT {} news: {}",v.state.name,news);
        }
        v.state.newsDay=day;v.state.newsTotals=new TreeMap<>(totals);v.state.newsResidents=v.state.residents.size();
    }
}
