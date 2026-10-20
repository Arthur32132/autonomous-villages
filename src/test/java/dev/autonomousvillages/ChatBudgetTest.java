package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ChatBudgetTest {
    @Test void aQuietVillageSpeaksAtOnceThenWaitsItsTurn(){
        var chat=new Chronicle.ChatBudget();
        assertNull(chat.poll(0));
        chat.offer("Родился Иван!");
        assertEquals("Родился Иван!",chat.poll(1000));
        chat.offer("Поставили: сундук");
        assertNull(chat.poll(1001));
        assertNull(chat.poll(1000+Chronicle.ChatBudget.GAP-1));
        assertEquals("Поставили: сундук",chat.poll(1000+Chronicle.ChatBudget.GAP));
        assertNull(chat.poll(5000));
    }

    @Test void aBurstBecomesOneLineAndTheRestIsOnlyCounted(){
        var chat=new Chronicle.ChatBudget();
        chat.offer("первое");assertEquals("первое",chat.poll(0));
        for(String m:new String[]{"а","б","в","г","д"," ",null})chat.offer(m);
        assertNull(chat.poll(50));
        assertEquals("а • б • в • и ещё 2 новости в летописи",chat.poll(100));
        assertNull(chat.poll(150));
        // Twenty announcements in one tick still make a single line per budget period.
        for(int i=0;i<20;i++)chat.offer("событие "+i);
        var sent=new java.util.ArrayList<String>();
        for(long t=200;t<200+Chronicle.ChatBudget.GAP;t++){String line=chat.poll(t);if(line!=null)sent.add(line);}
        assertEquals(java.util.List.of("событие 0 • событие 1 • событие 2 • и ещё 17 новостей в летописи"),sent);
    }

    @Test void theChronicleAndTheMorningNewsCountDaysAlike(){
        assertEquals("день 1",Chronicle.day(0));
        assertEquals("день 1",Chronicle.day(23_999));
        assertEquals("день 2",Chronicle.day(24_000));
        assertEquals("день 11",Chronicle.day(240_500));
    }
}
