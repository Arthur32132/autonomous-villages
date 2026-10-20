package dev.autonomousvillages;

import com.google.gson.*;
import java.util.*;
import java.util.function.*;

/**
 * Dozens of call sites ask for a save after every gift, death, chest or shaft segment; they only
 * mark the state dirty and the runtime serialises at most once per interval, off the server thread.
 */
final class SaveCoalescer {
    private final long interval;
    private boolean dirty;
    private long nextAllowed;
    SaveCoalescer(long interval){this.interval=interval;}
    void dirty(){dirty=true;}
    boolean isDirty(){return dirty;}
    /** True when a snapshot is due now: the dirty flag is consumed and the next one waits the interval. */
    boolean take(long now){
        if(!dirty||now<nextAllowed)return false;
        dirty=false;nextAllowed=now+interval;return true;
    }
    /** A synchronous save wrote everything; nothing is pending. */
    void clear(){dirty=false;}

    /**
     * Serialises each village on its own: one whose state fails validation falls back to its last
     * valid snapshot (or is left out when it never had one) instead of blocking the save of every other.
     */
    static final class Partition {
        private final Map<String,JsonElement> lastGood=new HashMap<>();
        private final Set<String> warned=new HashSet<>();
        <T> JsonArray encode(Collection<T> items,Function<T,String> key,Function<T,JsonElement> encoder,BiConsumer<String,RuntimeException> warn){
            var keys=new HashSet<String>();var out=new JsonArray();
            for(T item:items){
                String k=key.apply(item);keys.add(k);
                try{JsonElement json=encoder.apply(item);lastGood.put(k,json);warned.remove(k);out.add(json);}
                catch(RuntimeException error){
                    if(warned.add(k))warn.accept(k,error);
                    JsonElement previous=lastGood.get(k);if(previous!=null)out.add(previous);
                }
            }
            lastGood.keySet().retainAll(keys);warned.retainAll(keys);
            return out;
        }
    }
}
