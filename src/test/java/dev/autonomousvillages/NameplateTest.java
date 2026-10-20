package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.entity.SignText;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class NameplateTest {
    @BeforeAll static void bootstrapRegistries(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}

    @Test void aPlateNamesWhoLivesInTheHouse(){
        assertArrayEquals(new String[]{"Дом","ждёт жильцов","",""},NameplateJob.lines(List.of()));
        assertArrayEquals(new String[]{"Здесь живёт","Полина","",""},NameplateJob.lines(List.of("Полина")));
        assertArrayEquals(new String[]{"Здесь живут","Анна","Глеб","Ника"},NameplateJob.lines(List.of("Анна","Глеб","Ника")));
        assertArrayEquals(new String[]{"Здесь живут","Анна","Глеб","Ника и ещё 3"},NameplateJob.lines(List.of("Анна","Глеб","Ника","Олеся","Роман","Фрол")));
    }
    @Test void everyLineFitsTheSign(){
        // "Валентина и ещё 2" is wider than the sign: the count goes alone, and it counts her too.
        assertArrayEquals(new String[]{"Здесь живут","Анна","Глеб","и ещё 3"},NameplateJob.lines(List.of("Анна","Глеб","Валентина","Олеся","Роман")));
        String longName="Константин Константинопольский";
        String fitted=NameplateJob.fit(longName);
        assertTrue(fitted.endsWith("…"));assertTrue(longName.startsWith(fitted.substring(0,fitted.length()-1)));
        assertTrue(NameplateJob.width(fitted)<=NameplateJob.SIGN_WIDTH);
        assertEquals("Полина",NameplateJob.fit("Полина"));
        var many=new ArrayList<String>();for(int i=0;i<14;i++)many.add(i%2==0?"Александра":longName);
        for(var names:List.of(many,List.of(longName),List.of("Анна","Глеб","Ника","Олеся")))
            for(String line:NameplateJob.lines(names))assertTrue(NameplateJob.width(line)<=NameplateJob.SIGN_WIDTH,line);
        for(String fixed:new String[]{"Здесь живут","Здесь живёт","ждёт жильцов","Ника и ещё 3"})assertTrue(NameplateJob.width(fixed)<=NameplateJob.SIGN_WIDTH,fixed);
        // Conservative for Cyrillic: thirteen letters never pass for a fitting line.
        assertTrue(NameplateJob.width("ЖжЖжЖжЖжЖжЖжЖ")>NameplateJob.SIGN_WIDTH);
    }
    @Test void aPlateShowingTheSameNamesIsLeftAlone(){
        var lines=NameplateJob.lines(List.of("Анна","Глеб"));
        SignText text=new SignText();
        for(int i=0;i<4;i++)text=text.setMessage(i,Component.literal(lines[i]));
        assertTrue(NameplateJob.unchanged(lines,text));
        SignText same=new SignText();for(int i=0;i<4;i++)same=same.setMessage(i,Component.literal(lines[i]));
        // What the old check compared: two texts with the same lines are never equal objects.
        assertNotEquals(text,same);
        assertTrue(NameplateJob.unchanged(lines,same));
        assertFalse(NameplateJob.unchanged(NameplateJob.lines(List.of("Анна","Глеб","Ника")),text));
        assertFalse(NameplateJob.unchanged(lines,new SignText()));
    }
}
