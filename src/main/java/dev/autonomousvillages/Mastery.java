package dev.autonomousvillages;

import java.util.*;

/**
 * Craft mastery as plain data: which craft a job belongs to, the ranks a resident earns by long work at
 * one craft (ученик, подмастерье, мастер) and their Russian forms, nominative for the name label and
 * instrumental for the chronicle ("Иван стал мастером-лесорубом").
 */
final class Mastery {
    private Mastery(){}
    /** A working day: the daylight shift from waking (hour 1000) to the evening rest (11500), less breaks. */
    static final long WORK_DAY=10_000;
    static final long SAMPLE=100;
    static final int APPRENTICE=1,JOURNEYMAN=2,MASTER=3;
    /** Work needed for each rank: one, three and seven working days at the same craft. */
    static final long[] THRESHOLDS={WORK_DAY,3*WORK_DAY,7*WORK_DAY};
    /** Work beyond this is not counted (a master stays a master), so the saved numbers stay short. */
    static final long CAP=30*WORK_DAY;
    /** Residents the book follows at most; a newcomer beyond this waits for a place. */
    static final int MAX_RESIDENTS=64;

    record Craft(String key,String noun,String instrumental,Set<String> jobs){}
    static final List<Craft> CRAFTS=List.of(
            new Craft("wood","лесоруб","лесорубом",Set.of("chop")),
            new Craft("stone","каменщик","каменщиком",Set.of("quarry")),
            new Craft("ore","рудокоп","рудокопом",Set.of("shaft")),
            new Craft("build","строитель","строителем",Set.of("build","place","path","light","lantern")),
            new Craft("farm","фермер","фермером",Set.of("farm","compost","herd","shear")),
            new Craft("craft","ремесленник","ремесленником",Set.of("craft","smelt")),
            new Craft("guard","страж","стражем",Set.of("patrol")));
    private static final Map<String,Craft> BY_JOB=new HashMap<>(),BY_KEY=new HashMap<>();
    static{for(Craft c:CRAFTS){BY_KEY.put(c.key(),c);for(String j:c.jobs())BY_JOB.put(j,c);}}
    /** The craft a job kind counts toward, or null for chores (fetching, carrying, going home...). */
    static Craft craft(String jobKind){return jobKind==null?null:BY_JOB.get(jobKind);}
    static Craft byKey(String key){return key==null?null:BY_KEY.get(key);}
    static int rank(long ticks){int r=0;for(long t:THRESHOLDS)if(ticks>=t)r++;return r;}

    private static final String[][] RANK={{"ученик","ученица"},{"подмастерье","подмастерье"},{"мастер","мастер"}};
    private static final String[][] RANK_INSTRUMENTAL={{"учеником","ученицей"},{"подмастерьем","подмастерьем"},{"мастером","мастером"}};
    /** "мастер-лесоруб", "ученица-фермер". */
    static String title(Craft c,int rank,boolean feminine){return RANK[rank-1][feminine?1:0]+"-"+c.noun();}
    /** "мастером-лесорубом", "ученицей-фермером". */
    static String instrumental(Craft c,int rank,boolean feminine){return RANK_INSTRUMENTAL[rank-1][feminine?1:0]+"-"+c.instrumental();}
    /** The chronicle line: "Иван стал мастером-лесорубом", "Мария стала ученицей-фермером". */
    static String promotion(String name,Craft c,int rank){boolean f=feminine(name);return name+(f?" стала ":" стал ")+instrumental(c,rank,f);}
    /** The label's role with the title: "мастер-лесоруб" when it names the same craft, else "строитель, мастер-лесоруб". */
    static String withTitle(String role,String title){
        if(title==null||title.isEmpty())return role;
        if(role==null||role.isEmpty()||title.endsWith("-"+role))return title;
        return role+", "+title;
    }

    private static final Set<String> MALE_A=Set.of("илья","никита","савва","добрыня","фома","кузьма","лука","данила","гаврила","миша","саша","ваня",
            "петя","коля","дима","вова","лёша","алёша","серёжа","паша","яша","федя","гриша","стёпа","тёма","сеня","лёва","женя","гоша","luca","joshua","nikita","ilya","sasha","misha");
    private static final Set<String> FEMALE_SOFT=Set.of("любовь","нинель","адель","ассоль","эсфирь");
    /** A Russian first name ending in -а/-я is a woman's, but for the men's names that do too; anything else (or Latin) is a man's. */
    static boolean feminine(String name){
        if(name==null)return false;
        String first=name.strip().split("\\s+",2)[0].toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}]+$","");
        if(first.isEmpty()||MALE_A.contains(first))return false;
        if(FEMALE_SOFT.contains(first))return true;
        char last=first.charAt(first.length()-1);
        return last=='а'||last=='я'||last=='a';
    }

    record Promotion(UUID id,Craft craft,int rank){}

    /** Saved per village: work per resident and craft, and the rank already announced (each rank is earned once). */
    static final class Book {
        public Map<String,Map<String,Long>> work=new LinkedHashMap<>();
        public Map<String,Map<String,Integer>> ranks=new LinkedHashMap<>();
        /** Something changed since the last save; never saved itself. */
        transient boolean dirty;

        void sanitize(){
            if(work==null)work=new LinkedHashMap<>();
            if(ranks==null)ranks=new LinkedHashMap<>();
            work.entrySet().removeIf(e->e.getKey()==null||e.getValue()==null);
            for(var m:work.values()){m.entrySet().removeIf(e->byKey(e.getKey())==null||e.getValue()==null||e.getValue()<=0);m.replaceAll((k,t)->Math.min(CAP,t));}
            work.values().removeIf(Map::isEmpty);
            while(work.size()>MAX_RESIDENTS)work.remove(work.keySet().iterator().next());
            ranks.keySet().retainAll(work.keySet());
            ranks.entrySet().removeIf(e->e.getValue()==null);
            for(var m:ranks.values()){m.entrySet().removeIf(e->byKey(e.getKey())==null||e.getValue()==null||e.getValue()<APPRENTICE);m.replaceAll((k,r)->Math.min(MASTER,r));}
            ranks.values().removeIf(Map::isEmpty);
        }

        /**
         * One sample: each resident's job kind (absent while it has none) counts {@code ticks} toward its craft;
         * residents who left are forgotten. Returns the ranks reached now, at most one per resident and craft.
         */
        List<Promotion> sample(Map<UUID,String> jobs,Collection<UUID> residents,long ticks){
            Set<String> present=new HashSet<>();for(UUID id:residents)present.add(id.toString());
            int before=work.size()+ranks.size();
            work.keySet().retainAll(present);ranks.keySet().retainAll(present);
            if(work.size()+ranks.size()!=before)dirty=true;
            var out=new ArrayList<Promotion>();
            for(var e:jobs.entrySet()){
                Craft c=craft(e.getValue());String id=e.getKey()==null?null:e.getKey().toString();
                if(c==null||id==null||!present.contains(id)||!work.containsKey(id)&&work.size()>=MAX_RESIDENTS)continue;
                var m=work.computeIfAbsent(id,k->new LinkedHashMap<>());
                long t=Math.min(CAP,m.getOrDefault(c.key(),0L)+ticks);m.put(c.key(),t);dirty=true;
                int r=rank(t);
                if(r>earned(e.getKey(),c)){ranks.computeIfAbsent(id,k->new LinkedHashMap<>()).put(c.key(),r);out.add(new Promotion(e.getKey(),c,r));}
            }
            return out;
        }
        int earned(UUID id,Craft c){var m=ranks.get(id.toString());Integer r=m==null?null:m.get(c.key());return r==null?0:r;}
        long worked(UUID id,Craft c){var m=work.get(id.toString());Long t=m==null?null:m.get(c.key());return t==null?0:t;}
        /** The resident's best title: highest rank, then most work, then the earlier craft; null without one. */
        Promotion best(UUID id){
            var m=ranks.get(id.toString());if(m==null)return null;
            Promotion top=null;long topWork=-1;
            for(Craft c:CRAFTS){
                int r=earned(id,c);if(r<APPRENTICE)continue;
                long t=worked(id,c);
                if(top==null||r>top.rank()||r==top.rank()&&t>topWork){top=new Promotion(id,c,r);topWork=t;}
            }
            return top;
        }
    }
}
