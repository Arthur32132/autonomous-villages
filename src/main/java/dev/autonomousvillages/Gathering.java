package dev.autonomousvillages;

import java.util.*;

/** Small rules shared by the gathering jobs, kept free of the world so they can be tested. */
final class Gathering {
    private Gathering(){}
    /** Vanilla drops cannot be picked up for 10 ticks: right after the last break or shear there is nothing to collect yet. */
    static final int COLLECT_WINDOW=20;
    /** True while collecting goes on: drops are left to walk to, or the last break is too recent for its drops to be collectable. */
    static boolean keepCollecting(boolean dropsLeft,long now,long lastBreak,long deadline){return now<deadline&&(dropsLeft||now-lastBreak<COLLECT_WINDOW);}

    /** Failed routes per animal: one that failed {@code limit} times is passed over for the rest of the job. */
    static final class Strikes<K> {
        private final Map<K,Integer> counts=new HashMap<>();
        private final int limit;
        Strikes(int limit){this.limit=limit;}
        /** Counts one failure; true once the key is out. */
        boolean strike(K key){return counts.merge(key,1,Integer::sum)>=limit;}
        boolean out(K key){return counts.getOrDefault(key,0)>=limit;}
    }
}
