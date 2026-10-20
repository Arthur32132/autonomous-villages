package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.BlockPos;

/**
 * Block targets no route reached lately, per village: a chest, a workbench or a cell of a building.
 * Everyone skips a remembered target for about a game hour and then tries again; a target that fails
 * again within a day stays out twice as long (up to {@link #MAX_STRIKES} doublings), so a block nobody
 * can stand at stops a house for a day at most while the rest of it goes up. A shared store block is
 * hidden from the village only once {@code quorum} different residents failed at it (one trapped
 * resident fails everywhere); the reporter itself skips it right away. Pure logic, no world access.
 */
final class UnreachableMemory {
    /** How long one failure keeps a target out, in game ticks (about one game hour). */
    static final int TICKS=1000;
    /** Doublings of the span for failures in a row; six give 32 000 ticks, a little over a day. */
    static final int MAX_STRIKES=6;
    /** Failures this far apart still count as a row: a night with everyone asleep lies between two tries at one cell. */
    static final int STREAK=24000;
    /** Failures in a row a cell needs before a site made of such cells counts as blocked for the planner. */
    static final int BLOCKED_STRIKES=2;
    /** Targets remembered at most; the oldest marks go first when more are added. */
    static final int LIMIT=256;
    /** Route endings that say nothing about the target: the search ran out of time or chunks, or the watchdog cancelled it. */
    private static final Set<String> TRANSIENT=Set.of("planning_timeout","chunk_loading_timeout","search_queue_full","unloaded_frontier","dimension_unavailable","dimension_change_rejected","cancelled");
    /** Skipped until {@code until}; {@code strikes} failures in a row; {@code by} the residents that failed there in this row. */
    record Mark(long until,int strikes,Set<UUID> by){}
    private final Map<Long,Mark> marks=new LinkedHashMap<>();
    private final int ticks,limit;
    UnreachableMemory(){this(TICKS,LIMIT);}
    UnreachableMemory(int ticks,int limit){this.ticks=Math.max(1,ticks);this.limit=Math.max(1,limit);}

    /** A route to this target failed now; {@code by} is the resident that failed, or null. */
    void remember(BlockPos p,long now,UUID by){remember(p.asLong(),now,by);}
    void remember(long pos,long now,UUID by){
        var old=marks.remove(pos);
        // A failure within twice the old span, or within a day, of the last one counts as a failure in a row.
        boolean row=old!=null&&now-(old.until()-span(old.strikes()))<Math.max(2*span(old.strikes()),STREAK);
        int strikes=row?Math.min(old.strikes()+1,MAX_STRIKES):1;
        var reporters=row?new HashSet<>(old.by()):new HashSet<UUID>();if(by!=null)reporters.add(by);
        marks.put(pos,new Mark(now+span(strikes),strikes,reporters));
        if(marks.size()>limit)evict(now);
    }
    /** Remembered after a single report: building cells, where one failure means the next cell is tried instead. */
    boolean isUnreachable(BlockPos p,long now){return isUnreachable(p.asLong(),now);}
    boolean isUnreachable(long pos,long now){var m=marks.get(pos);return m!=null&&m.until()>now;}
    /** Remembered by at least {@code quorum} different residents: shared store blocks everyone would lose. */
    boolean isUnreachable(BlockPos p,long now,int quorum){var m=marks.get(p.asLong());return m!=null&&m.until()>now&&m.by().size()>=quorum;}
    /** This resident itself failed at the target while the mark lives; it does not walk there again meanwhile. */
    boolean reported(BlockPos p,long now,UUID by){var m=marks.get(p.asLong());return m!=null&&m.until()>now&&by!=null&&m.by().contains(by);}
    /**
     * Every cell (at least one) is remembered right now after {@link #BLOCKED_STRIKES} failures in a row:
     * the site has been tried, waited out and tried again, so the planner treats it as blocked and moves on.
     */
    boolean allBlocked(Collection<BlockPos> cells,long now){
        if(cells.isEmpty())return false;
        for(var p:cells){var m=marks.get(p.asLong());if(m==null||m.until()<=now||m.strikes()<BLOCKED_STRIKES)return false;}
        return true;
    }
    /** Someone reached the target after all: the mark and its strikes go. */
    void forget(BlockPos p){marks.remove(p.asLong());}
    /** A resident turned out to be stuck itself; what it alone reported says nothing about the targets. */
    void forgetBy(UUID by){if(by!=null)marks.values().removeIf(m->m.by().remove(by)&&m.by().isEmpty());}
    /** Targets currently skipped by at least their reporter. */
    int count(long now){int n=0;for(var m:marks.values())if(m.until()>now)n++;return n;}
    int size(){return marks.size();}
    int strikes(BlockPos p){var m=marks.get(p.asLong());return m==null?0:m.strikes();}
    long span(int strikes){return (long)ticks<<Math.max(0,Math.min(MAX_STRIKES,strikes)-1);}
    private void evict(long now){
        var it=marks.values().iterator();
        while(it.hasNext()&&marks.size()>limit)if(it.next().until()<=now)it.remove();
        it=marks.values().iterator();
        while(it.hasNext()&&marks.size()>limit){it.next();it.remove();}
    }
    /** True for a route ending that means the target itself cannot be reached from where the resident stands. */
    static boolean blockedRoute(String reason){return reason==null||!TRANSIENT.contains(reason);}
}
