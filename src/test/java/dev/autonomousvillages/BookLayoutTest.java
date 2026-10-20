package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BookLayoutTest {
    @BeforeAll static void bootstrapRegistries(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}

    private static List<String> lines(Component page){return List.of(page.getString().split("\n",-1));}

    @Test void cyrillicIsCountedWiderThanAscii(){
        assertEquals(6,BookLayout.width('a'));
        assertEquals(7,BookLayout.width('а'));
        assertEquals(8,BookLayout.width('Ж'));
        assertTrue(BookLayout.width('i')<BookLayout.width('a'));
        // About 16 ordinary Cyrillic letters to a line, 19 ASCII ones, 14 of the widest.
        assertEquals(1,BookLayout.wrap("оооооооооооооооо").size());
        assertEquals(2,BookLayout.wrap("ооооооооооооооооо").size());
        assertEquals(1,BookLayout.wrap("aaaaaaaaaaaaaaaaaaa").size());
        assertEquals(2,BookLayout.wrap("aaaaaaaaaaaaaaaaaaaa").size());
        assertEquals(1,BookLayout.wrap("ЖЖЖЖЖЖЖЖЖЖЖЖЖЖ").size());
        assertEquals(2,BookLayout.wrap("ЖЖЖЖЖЖЖЖЖЖЖЖЖЖЖ").size());
    }

    @Test void wrappingBreaksAtSpacesAndKeepsEveryWord(){
        String text="Иван Петрович, строитель: несёт доски на стройку дома у колокола";
        var wrapped=BookLayout.wrap(text);
        assertTrue(wrapped.size()>1);
        for(String line:wrapped)assertTrue(BookLayout.width(line)<=BookLayout.WIDTH,line);
        assertEquals(text,String.join(" ",wrapped));
        // Explicit breaks and blank lines stay; a word wider than a page is cut, not lost.
        assertEquals(List.of("Склад","","пусто"),BookLayout.wrap("Склад\n\nпусто"));
        String word="ж".repeat(50);
        var cut=BookLayout.wrap(word);
        assertTrue(cut.size()>=3);
        for(String line:cut)assertTrue(BookLayout.width(line)<=BookLayout.WIDTH);
        assertEquals(word,String.join("",cut));
    }

    @Test void pagesNeverExceedFourteenLines(){
        var heights=new int[50];
        for(int i=0;i<heights.length;i++)heights[i]=1+i%3;
        var layout=BookLayout.layout(2,heights,100);
        var placed=new ArrayList<Integer>();
        for(var page:layout){
            int used=2;for(int i:page)used+=heights[i];
            assertTrue(used<=BookLayout.LINES,"page of "+used+" lines");
            placed.addAll(page);
        }
        var all=new ArrayList<Integer>();for(int i=0;i<heights.length;i++)all.add(i);
        assertEquals(all,placed);
    }

    @Test void aLongListOfResidentsContinuesOverPagesAndDropsNobody(){
        var entries=new ArrayList<BookLayout.Entry>();
        for(int i=0;i<45;i++)entries.add(BookLayout.entry("Житель"+i+" Ольховский, строитель: "+PlayerGoods.shorten("несёт брёвна на склад у колокола деревни",28)));
        var pages=BookLayout.pages("Жители",entries,40);
        assertTrue(pages.size()>4);
        var text=new StringBuilder();
        for(var page:pages){
            var lines=lines(page);
            assertTrue(lines.size()<=BookLayout.LINES,"page of "+lines.size()+" lines");
            assertEquals("Жители",lines.getFirst());
            for(String line:lines)assertTrue(BookLayout.width(line)<=BookLayout.WIDTH,line);
            text.append(page.getString()).append('\n');
        }
        String flat=text.toString().replace('\n',' ');
        for(int i=0;i<45;i++)assertTrue(flat.contains("Житель"+i+" "),"resident "+i);
        assertFalse(text.toString().contains("ещё"));
    }

    @Test void aListCutShortSaysHowManyAreLeftOut(){
        var entries=new ArrayList<BookLayout.Entry>();
        for(int i=0;i<30;i++)entries.add(BookLayout.entry("даритель"+i+": "+(100-i)));
        var pages=BookLayout.pages("Дарители",entries,1);
        assertEquals(1,pages.size());
        var lines=lines(pages.getFirst());
        assertTrue(lines.size()<=BookLayout.LINES);
        int shown=(int)lines.stream().filter(l->l.startsWith("даритель")).count();
        assertEquals(BookLayout.more(30-shown),lines.getLast());
        assertEquals(BookLayout.LINES,lines.size());
    }

    @Test void anEntryTallerThanAPageContinuesOnTheNext(){
        var tall=new StringBuilder();
        for(int i=0;i<30;i++)tall.append("строка ").append(i).append('\n');
        var pages=BookLayout.pages("Стройка",List.of(BookLayout.entry(tall.toString().strip())),5);
        assertEquals(3,pages.size());
        var all=new StringBuilder();
        for(var page:pages){assertTrue(lines(page).size()<=BookLayout.LINES);all.append(page.getString()).append('\n');}
        for(int i=0;i<30;i++)assertTrue(all.toString().contains("строка "+i+"\n"),"line "+i);
        assertFalse(all.toString().contains("ещё"));
    }

    @Test void anItemNameInTheReadersLanguageIsGivenTwoLines(){
        var entries=new ArrayList<BookLayout.Entry>();
        for(int i=0;i<12;i++)entries.add(BookLayout.entry(Component.literal(i+" ").append(Component.translatable("item.minecraft.oak_planks")),PlayerGoods.ITEM_LINES));
        // A heading and a blank line, then six two-line items a page.
        var pages=BookLayout.pages("Склад (2 сунд.)",entries,3);
        assertEquals(2,pages.size());
        for(var page:pages)assertEquals(1+1+6,lines(page).size());
    }
}
