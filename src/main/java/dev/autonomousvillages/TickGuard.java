package dev.autonomousvillages;

import java.util.*;
import java.util.function.*;

/**
 * Runs one phase of a tick or one event handler so a throwing feature never takes the rest down:
 * the exception is swallowed and reported at most once per interval per phase. The clock is read
 * only when something failed, so a guarded hot path costs nothing but the try.
 */
final class TickGuard {
    interface Report{void failed(String phase,RuntimeException error);}
    private final long interval;
    private final LongSupplier clock;
    private final Report report;
    private final Map<String,Long> nextReport=new HashMap<>();
    TickGuard(long interval,LongSupplier clock,Report report){this.interval=interval;this.clock=clock;this.report=report;}
    /** True when the phase completed. */
    boolean run(String phase,Runnable body){
        try{body.run();return true;}
        catch(RuntimeException error){failed(phase,error);return false;}
    }
    /** The body's value, or the fallback when it threw. */
    <T> T run(String phase,Supplier<T> body,T fallback){
        try{return body.get();}
        catch(RuntimeException error){failed(phase,error);return fallback;}
    }
    /** Reports a failure caught by the caller's own try, rate-limited like the others. */
    void failed(String phase,RuntimeException error){
        long now=clock.getAsLong();Long next=nextReport.get(phase);
        if(next!=null&&now<next)return;
        nextReport.put(phase,now+interval);report.failed(phase,error);
    }
}
