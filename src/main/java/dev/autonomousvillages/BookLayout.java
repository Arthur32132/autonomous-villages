package dev.autonomousvillages;

import java.util.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Text laid out on written-book pages: 14 lines of 114 pixels. Widths are estimated on the safe side
 * (a Cyrillic letter as 7 pixels, a wide one as 8), so the client never wraps a line once more and
 * nothing falls off the bottom of a page. What does not fit goes on the next page under the same
 * heading; a list cut short says how many entries are left out.
 */
final class BookLayout {
    private BookLayout(){}
    static final int LINES=14,WIDTH=114;
    private static final String WIDE="ЖШЩЫЮМФжшщыюмф";
    /**
     * One block of a page (a resident, a stack of the store), kept together on one page. Literal text is
     * wrapped here; a component the reader's client fills in (an item name in their language) is given
     * a fixed number of lines instead.
     */
    record Entry(Component text,int lines,List<String> literal){}

    /** Pixels a character takes in the book font, rounded up. */
    static int width(int c){
        if(c==' ')return 4;
        if(c=='i'||c=='!'||c=='.'||c==','||c==':'||c==';'||c=='|'||c=='\'')return 2;
        if(c=='l'||c=='`')return 3;
        if(c=='I'||c=='t'||c=='['||c==']'||c=='('||c==')'||c=='"'||c=='*')return 5;
        if(c=='@'||c=='~')return 7;
        if(c<128)return 6;
        if(c>=0x400&&c<0x500)return WIDE.indexOf(c)>=0?8:7;
        return 9;
    }
    static int width(String s){int w=0;for(int i=0;i<s.length();){int c=s.codePointAt(i);w+=width(c);i+=Character.charCount(c);}return w;}

    /** Book lines of a text: broken at spaces, a word wider than the page cut; explicit line breaks kept. */
    static List<String> wrap(String text){
        var out=new ArrayList<String>();
        for(String paragraph:text.split("\n",-1)){
            var line=new StringBuilder();int used=0;
            for(String word:paragraph.split(" ")){
                if(word.isEmpty())continue;
                int w=width(word);
                if(used>0&&used+width(' ')+w<=WIDTH){line.append(' ').append(word);used+=width(' ')+w;continue;}
                if(used>0){out.add(line.toString());line.setLength(0);used=0;}
                for(int i=0;i<word.length();){
                    int c=word.codePointAt(i),cw=width(c);
                    if(used>0&&used+cw>WIDTH){out.add(line.toString());line.setLength(0);used=0;}
                    line.appendCodePoint(c);used+=cw;i+=Character.charCount(c);
                }
            }
            out.add(line.toString());
        }
        return out;
    }
    static Entry entry(String text){var lines=wrap(text);return new Entry(Component.literal(String.join("\n",lines)),lines.size(),lines);}
    static Entry entry(Component text,int lines){return new Entry(text,Math.max(1,lines),null);}
    /** The last line of a list cut short. */
    static String more(int n){return "…и ещё "+n;}

    /**
     * Entry indices on each page under a heading of {@code header} lines: entries stay whole, a page never
     * holds more than LINES lines. Past {@code maxPages} the last page ends early, leaving one line for
     * {@link #more}; the caller counts what was left out.
     */
    static List<List<Integer>> layout(int header,int[] heights,int maxPages){
        int room=Math.max(1,LINES-header);
        var pages=new ArrayList<List<Integer>>();var page=new ArrayList<Integer>();int used=0;
        for(int i=0;i<heights.length;i++){
            int h=Math.clamp(heights[i],1,room);
            if(!page.isEmpty()&&used+h>room){pages.add(page);page=new ArrayList<>();used=0;}
            page.add(i);used+=h;
        }
        if(!page.isEmpty()||pages.isEmpty())pages.add(page);
        if(pages.size()<=Math.max(1,maxPages))return pages;
        var kept=new ArrayList<>(pages.subList(0,Math.max(1,maxPages)));
        var last=new ArrayList<>(kept.getLast());
        int lastUsed=0;for(int i:last)lastUsed+=Math.clamp(heights[i],1,room);
        while(!last.isEmpty()&&lastUsed+1>room)lastUsed-=Math.clamp(heights[last.removeLast()],1,room);
        kept.set(kept.size()-1,last);
        return kept;
    }

    /** Pages under a heading repeated on each; nothing is dropped without a closing "…и ещё N". */
    static List<Component> pages(String title,List<Entry> entries,int maxPages){
        var head=wrap(title);head.add("");
        int room=Math.max(1,LINES-head.size());
        // A literal entry taller than a page continues on the next one.
        var parts=new ArrayList<Entry>();var owner=new ArrayList<Integer>();
        for(int e=0;e<entries.size();e++){
            var entry=entries.get(e);
            if(entry.literal()==null||entry.lines()<=room){parts.add(entry);owner.add(e);continue;}
            for(int i=0;i<entry.literal().size();i+=room){
                var chunk=entry.literal().subList(i,Math.min(entry.literal().size(),i+room));
                parts.add(new Entry(Component.literal(String.join("\n",chunk)),chunk.size(),chunk));owner.add(e);
            }
        }
        int[] heights=parts.stream().mapToInt(Entry::lines).toArray();
        var layout=layout(head.size(),heights,maxPages);
        // An entry counts as shown only when all of it is on the pages.
        int[] missing=new int[entries.size()];for(int o:owner)missing[o]++;
        for(var page:layout)for(int i:page)missing[owner.get(i)]--;
        int left=0;for(int m:missing)if(m>0)left++;
        var out=new ArrayList<Component>();
        for(int p=0;p<layout.size();p++){
            MutableComponent page=Component.literal(String.join("\n",head));
            for(int i:layout.get(p))page.append("\n").append(parts.get(i).text());
            if(p==layout.size()-1&&left>0)page.append("\n"+more(left));
            out.add(page);
        }
        return out;
    }
}
