package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import java.util.*;
import org.junit.jupiter.api.Test;

class MasteryTest {
    private static final UUID IVAN=new UUID(1,1),MARIA=new UUID(2,2),GLEB=new UUID(3,3);

    @Test void jobKindsMapToAFewCrafts(){
        assertEquals("wood",Mastery.craft("chop").key());
        assertEquals("stone",Mastery.craft("quarry").key());
        assertEquals("ore",Mastery.craft("shaft").key());
        for(String k:List.of("build","place","path","light","lantern"))assertEquals("build",Mastery.craft(k).key(),k);
        for(String k:List.of("farm","compost","herd","shear"))assertEquals("farm",Mastery.craft(k).key(),k);
        for(String k:List.of("craft","smelt"))assertEquals("craft",Mastery.craft(k).key(),k);
        assertEquals("guard",Mastery.craft("patrol").key());
        // Chores and rest are nobody's craft.
        for(String k:Arrays.asList("fetch","deposit","home","shelter","escape","hazard","gift","grave","flowers","nameplate","unknown",null))assertNull(Mastery.craft(k),k);
        assertTrue(Mastery.CRAFTS.size()>=6&&Mastery.CRAFTS.size()<=8);
        assertEquals(Mastery.CRAFTS.size(),Mastery.CRAFTS.stream().map(Mastery.Craft::key).distinct().count());
    }

    @Test void ranksFollowOneThreeAndSevenWorkingDays(){
        assertEquals(0,Mastery.rank(0));
        assertEquals(0,Mastery.rank(Mastery.WORK_DAY-1));
        assertEquals(Mastery.APPRENTICE,Mastery.rank(Mastery.WORK_DAY));
        assertEquals(Mastery.APPRENTICE,Mastery.rank(3*Mastery.WORK_DAY-1));
        assertEquals(Mastery.JOURNEYMAN,Mastery.rank(3*Mastery.WORK_DAY));
        assertEquals(Mastery.JOURNEYMAN,Mastery.rank(7*Mastery.WORK_DAY-1));
        assertEquals(Mastery.MASTER,Mastery.rank(7*Mastery.WORK_DAY));
        assertEquals(Mastery.MASTER,Mastery.rank(Mastery.CAP));
        assertTrue(Mastery.CAP>=7*Mastery.WORK_DAY);
    }

    @Test void titlesAreRussianAndTheChronicleUsesTheInstrumental(){
        var wood=Mastery.byKey("wood");var stone=Mastery.byKey("stone");var farm=Mastery.byKey("farm");var guard=Mastery.byKey("guard");
        assertEquals("мастер-лесоруб",Mastery.title(wood,Mastery.MASTER,false));
        assertEquals("подмастерье-каменщик",Mastery.title(stone,Mastery.JOURNEYMAN,false));
        assertEquals("ученик-фермер",Mastery.title(farm,Mastery.APPRENTICE,false));
        assertEquals("ученица-фермер",Mastery.title(farm,Mastery.APPRENTICE,true));
        assertEquals("мастером-лесорубом",Mastery.instrumental(wood,Mastery.MASTER,false));
        assertEquals("подмастерьем-каменщиком",Mastery.instrumental(stone,Mastery.JOURNEYMAN,false));
        assertEquals("стражем",guard.instrumental());
        assertEquals("строителем",Mastery.byKey("build").instrumental());
        assertEquals("ремесленником",Mastery.byKey("craft").instrumental());
        assertEquals("Иван стал мастером-лесорубом",Mastery.promotion("Иван",wood,Mastery.MASTER));
        assertEquals("Мария стала ученицей-фермером",Mastery.promotion("Мария",farm,Mastery.APPRENTICE));
        assertEquals("Анна стала мастером-стражем",Mastery.promotion("Анна",guard,Mastery.MASTER));
        assertEquals("Илья стал подмастерьем-рудокопом",Mastery.promotion("Илья",Mastery.byKey("ore"),Mastery.JOURNEYMAN));
    }

    @Test void womenAreToldApartByTheirFirstName(){
        for(String n:List.of("Мария","Анна","Дарья","Елена","Злата","Ксения","Ника","Полина","Ульяна","Вера","Любава","Милана","Агафья","Василиса","Ярослава","Есения","Нина","Олеся","Любовь","Анна Петровна"))
            assertTrue(Mastery.feminine(n),n);
        for(String n:Arrays.asList("Иван","Пётр","Борис","Глеб","Фёдор","Игорь","Лев","Мирон","Остап","Роман","Савва","Тимофей","Фрол","Степан","Добрыня","Тихон","Ефим","Прохор","Демид","Захар","Илья","Матвей","Steve","",null))
            assertFalse(Mastery.feminine(n),String.valueOf(n));
    }

    @Test void theLabelShowsTheTitleBesideOrInPlaceOfTheRole(){
        assertEquals("строитель",Mastery.withTitle("строитель",null));
        assertEquals("мастер-лесоруб",Mastery.withTitle("лесоруб","мастер-лесоруб"));
        assertEquals("строитель, ученик-фермер",Mastery.withTitle("строитель","ученик-фермер"));
    }

    @Test void aRankIsEarnedOnceAndOnlyByWorkAtTheSameCraft(){
        var book=new Mastery.Book();var residents=Set.of(IVAN,MARIA);
        long samples=Mastery.WORK_DAY/Mastery.SAMPLE;
        List<Mastery.Promotion> all=new ArrayList<>();
        // Ivan chops; Maria does chores and a bit of everything, never a full day at one craft.
        for(long i=0;i<samples;i++)all.addAll(book.sample(Map.of(IVAN,"chop",MARIA,i%2==0?"fetch":i%4==1?"farm":"build"),residents,Mastery.SAMPLE));
        assertEquals(List.of(new Mastery.Promotion(IVAN,Mastery.byKey("wood"),Mastery.APPRENTICE)),all);
        assertTrue(book.dirty);
        assertNull(book.best(MARIA));
        assertEquals("ученик-лесоруб",Mastery.title(book.best(IVAN).craft(),book.best(IVAN).rank(),false));
        // Further work at the same rank says nothing more.
        for(int i=0;i<10;i++)assertTrue(book.sample(Map.of(IVAN,"chop"),residents,Mastery.SAMPLE).isEmpty());
        // Each threshold is announced exactly once, up to master, and nothing after it.
        all.clear();
        for(long i=0;i<3*Mastery.CAP/Mastery.SAMPLE;i++)all.addAll(book.sample(Map.of(IVAN,"chop"),residents,Mastery.SAMPLE));
        assertEquals(List.of(Mastery.JOURNEYMAN,Mastery.MASTER),all.stream().map(Mastery.Promotion::rank).toList());
        assertEquals(Mastery.CAP,book.worked(IVAN,Mastery.byKey("wood")));
        assertEquals(Mastery.MASTER,book.best(IVAN).rank());
    }

    @Test void theBestTitleIsTheHighestRankThenTheMostWork(){
        var book=new Mastery.Book();var residents=Set.of(IVAN);
        for(long i=0;i<3*Mastery.WORK_DAY/Mastery.SAMPLE;i++)book.sample(Map.of(IVAN,"quarry"),residents,Mastery.SAMPLE);
        for(long i=0;i<2*Mastery.WORK_DAY/Mastery.SAMPLE;i++)book.sample(Map.of(IVAN,"farm"),residents,Mastery.SAMPLE);
        assertEquals("stone",book.best(IVAN).craft().key());
        assertEquals(Mastery.JOURNEYMAN,book.best(IVAN).rank());
        var other=new Mastery.Book();
        for(long i=0;i<Mastery.WORK_DAY/Mastery.SAMPLE;i++)other.sample(Map.of(IVAN,"farm"),residents,Mastery.SAMPLE);
        for(long i=0;i<2*Mastery.WORK_DAY/Mastery.SAMPLE;i++)other.sample(Map.of(IVAN,"patrol"),residents,Mastery.SAMPLE);
        assertEquals("guard",other.best(IVAN).craft().key());
    }

    @Test void residentsWhoLeftAreForgotten(){
        var book=new Mastery.Book();
        for(long i=0;i<Mastery.WORK_DAY/Mastery.SAMPLE;i++)book.sample(Map.of(IVAN,"chop",GLEB,"farm"),Set.of(IVAN,GLEB),Mastery.SAMPLE);
        assertNotNull(book.best(GLEB));
        book.dirty=false;
        assertTrue(book.sample(Map.of(),Set.of(IVAN),Mastery.SAMPLE).isEmpty());
        assertTrue(book.dirty,"pruning is saved");
        assertEquals(Set.of(IVAN.toString()),book.work.keySet());
        assertEquals(Set.of(IVAN.toString()),book.ranks.keySet());
        assertNull(book.best(GLEB));
        // A job reported for someone who is not a resident is not counted.
        book.sample(Map.of(GLEB,"chop"),Set.of(IVAN),Mastery.SAMPLE);
        assertFalse(book.work.containsKey(GLEB.toString()));
        // A quiet night with nobody working changes nothing and needs no save.
        book.dirty=false;
        book.sample(Map.of(),Set.of(IVAN),Mastery.SAMPLE);
        assertFalse(book.dirty);
    }

    @Test void theSavedBookStaysBoundedAndSurvivesARoundTrip(){
        var book=new Mastery.Book();var residents=new LinkedHashSet<UUID>();
        for(int i=0;i<Mastery.MAX_RESIDENTS+40;i++)residents.add(UUID.randomUUID());
        var kinds=Mastery.CRAFTS.stream().map(c->c.jobs().iterator().next()).toList();
        // Everyone works every craft far past master: the worst case the book can reach.
        for(long s=0;s<Mastery.CAP/Mastery.SAMPLE+5;s++){
            var jobs=new HashMap<UUID,String>();int n=0;
            for(UUID id:residents)jobs.put(id,kinds.get((int)((n+++s)%kinds.size())));
            book.sample(jobs,residents,Mastery.SAMPLE*kinds.size());
        }
        assertEquals(Mastery.MAX_RESIDENTS,book.work.size());
        assertTrue(book.ranks.size()<=Mastery.MAX_RESIDENTS);
        String json=new Gson().toJson(book);
        assertTrue(json.length()<Features.MAX_STATE_CHARS/2,"saved book is "+json.length()+" chars");
        assertFalse(json.contains("dirty"));
        var back=new Gson().fromJson(json,Mastery.Book.class);back.sanitize();
        assertEquals(book.work,back.work);
        assertEquals(book.ranks,back.ranks);
        UUID someone=UUID.fromString(book.work.keySet().iterator().next());
        assertEquals(Mastery.MASTER,back.best(someone).rank());
    }

    @Test void aDamagedSaveIsCleanedUp(){
        var book=new Gson().fromJson("{\"work\":{\""+IVAN+"\":{\"wood\":99999999,\"nonsense\":5,\"farm\":-3},\""+GLEB+"\":{}},"
                +"\"ranks\":{\""+IVAN+"\":{\"wood\":9,\"farm\":0},\""+MARIA+"\":{\"wood\":2}}}",Mastery.Book.class);
        book.sanitize();
        assertEquals(Map.of(IVAN.toString(),Map.of("wood",Mastery.CAP)),book.work);
        assertEquals(Map.of(IVAN.toString(),Map.of("wood",Mastery.MASTER)),book.ranks);
        var empty=new Gson().fromJson("{\"work\":null}",Mastery.Book.class);empty.sanitize();
        assertNotNull(empty.work);assertNotNull(empty.ranks);
        assertTrue(empty.sample(Map.of(IVAN,"chop"),Set.of(IVAN),Mastery.SAMPLE).isEmpty());
    }

    @Test void theFeatureIsRegisteredUnderItsKey(){
        assertTrue(Features.ALL.stream().anyMatch(f->f instanceof MasteryFeature&&f.key().equals("mastery")));
    }
}
