package dev.autonomousvillages;

import java.util.*;

/** Exclusive jobs expire if a worker disappears; ownership is checked on release. */
final class Reservations {
    private record Lease(UUID worker, long expires) { }
    private final Map<Long, Lease> claims = new HashMap<>();
    boolean acquire(long key, UUID worker, long now) {
        Lease prior = claims.get(key);
        if (prior != null && prior.expires > now && !prior.worker.equals(worker)) return false;
        claims.put(key, new Lease(worker, now + 1200)); return true;
    }
    boolean available(long key, UUID worker, long now) {
        Lease lease = claims.get(key);
        return lease == null || lease.expires <= now || lease.worker.equals(worker);
    }
    void release(long key, UUID worker) { claims.computeIfPresent(key, (k, v) -> v.worker.equals(worker) ? null : v); }
    void release(UUID worker) { claims.values().removeIf(lease -> lease.worker.equals(worker)); }
}
