package dev.autonomousvillages;

import java.util.List;
import net.minecraft.world.item.ItemStack;

/** Takes a tool or material from the store into the worker's own backpack. */
final class FetchJob extends Job {
    private final Fetch fetch;
    private final Need need;
    FetchJob(Worker w,Need need){super(w,"берёт со склада: "+need.label());this.need=need;this.fetch=new Fetch(List.of(need));}
    @Override String kind(){return "fetch";}
    @Override boolean keeps(ItemStack s){return need.test().test(s);}
    @Override boolean tick(){
        return switch(fetch.tick()){
            // Part of the amount is still useful: seeds for a few cells, a stack of torches.
            case FAILED->w.count(need.test())>0?false:fail((fetch.hidden?"не добраться до склада: ":"нет на складе: ")+fetch.missing);
            case MOVING->true;
            default->false;
        };
    }
    @Override void stop(){fetch.cancel();}
}
