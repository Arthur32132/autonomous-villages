package dev.autonomousvillages;

import java.util.*;
import java.util.function.Supplier;

/**
 * Runs one phase of a tick or one event handler so a throwing feature never takes the rest down:
 * the exception is swallowed and reported at most once per interval per phase.
 */
final class TickGuard {
    interface Report{void failed(String phase,RuntimeException error);}
    private final long interval;
    private final Report report;
    private final Map<String,Long> nextReport=new HashMap<>();
    TickGuard(long interval,Report report){this.interval=interval;this.report=report;}
    /** True when the phase completed. */
    boolean run(String phase,long now,Runnable body){
        try{body.run();return true;}
        catch(RuntimeException error){failed(phase,now,error);return false;}
    }
    /** The body's value, or the fallback when it threw. */
    <T> T run(String phase,long now,Supplier<T> body,T fallback){
        try{return body.get();}
        catch(RuntimeException error){failed(phase,now,error);return fallback;}
    }
    private void failed(String phase,long now,RuntimeException error){
        Long next=nextReport.get(phase);
        if(next!=null&&now<next)return;
        nextReport.put(phase,now+interval);report.failed(phase,error);
    }
}
