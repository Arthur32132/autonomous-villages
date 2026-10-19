package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MorningNewsTest {
    @Test void countsTakeTheRussianNounForm(){
        assertEquals("1 блок",MorningNews.plural(1,"блок","блока","блоков"));
        assertEquals("3 блока",MorningNews.plural(3,"блок","блока","блоков"));
        assertEquals("5 блоков",MorningNews.plural(5,"блок","блока","блоков"));
        assertEquals("11 блоков",MorningNews.plural(11,"блок","блока","блоков"));
        assertEquals("14 блоков",MorningNews.plural(14,"блок","блока","блоков"));
        assertEquals("21 блок",MorningNews.plural(21,"блок","блока","блоков"));
        assertEquals("112 блоков",MorningNews.plural(112,"блок","блока","блоков"));
        assertEquals("122 блока",MorningNews.plural(122,"блок","блока","блоков"));
    }
    @Test void newsTellsWhatChangedSinceTheLastMorning(){
        String news=MorningNews.text("день 3",7,6,Map.of("placed",100L,"trees",4L,"births",1L),
                Map.of("placed",141L,"trees",7L,"births",2L,"deaths",1L,"harvested",12L),"дом «plains_small_house_1» — 45 из 83");
        assertEquals("Утро, день 3. Жителей 7 (+1). За сутки: +41 блок в постройках, 3 дерева, урожай с 12 грядок, 1 рождение, 1 потеря."
                +" Стройка: дом «plains_small_house_1» — 45 из 83.",news);
    }
    @Test void aQuietDayIsSaidSo(){
        assertEquals("Утро, день 2. Жителей 6. За сутки тихо.",MorningNews.text("день 2",6,6,Map.of("placed",5L),Map.of("placed",5L),null));
    }
}
