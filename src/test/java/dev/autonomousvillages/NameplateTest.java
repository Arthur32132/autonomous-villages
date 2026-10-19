package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class NameplateTest {
    @Test void aPlateNamesWhoLivesInTheHouse(){
        assertArrayEquals(new String[]{"Дом","ждёт жильцов","",""},NameplateJob.lines(List.of()));
        assertArrayEquals(new String[]{"Здесь живёт","Полина","",""},NameplateJob.lines(List.of("Полина")));
        assertArrayEquals(new String[]{"Здесь живут","Анна","Глеб","Ника"},NameplateJob.lines(List.of("Анна","Глеб","Ника")));
        assertArrayEquals(new String[]{"Здесь живут","Анна","Глеб","Ника и ещё 3"},NameplateJob.lines(List.of("Анна","Глеб","Ника","Олеся","Роман","Фрол")));
    }
}
