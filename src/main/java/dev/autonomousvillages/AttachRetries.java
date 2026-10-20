package dev.autonomousvillages;

import java.util.*;

/**
 * Residents the library refused to attach (duplicate UUID, foreign controller, broken saved state):
 * each is tried again after the interval and warned about once per streak, never freezing the village.
 */
final class AttachRetries {
    private final long interval;
    private final Map<UUID,Long> retryAt=new HashMap<>();
    private final Set<UUID> warned=new HashSet<>();
    AttachRetries(long interval){this.interval=interval;}
    boolean waiting(UUID id,long now){Long at=retryAt.get(id);return at!=null&&now<at;}
    /** Records a failure; true when this is the first of a streak and deserves a warning. */
    boolean failed(UUID id,long now){retryAt.put(id,now+interval);return warned.add(id);}
    void succeeded(UUID id){retryAt.remove(id);warned.remove(id);}
    void retain(Collection<UUID> residents){retryAt.keySet().retainAll(residents);warned.retainAll(residents);}
    int size(){return retryAt.size();}
}
