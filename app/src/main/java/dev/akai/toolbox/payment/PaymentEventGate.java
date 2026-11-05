package dev.akai.toolbox.payment;

import java.util.HashMap;
import java.util.Map;

/** Rejects stale or reordered signals independently for each verified application UID. */
public final class PaymentEventGate {
    private static final long MAX_AGE = 5_000_000_000L;
    private final Map<Integer, Long> last = new HashMap<>();

    public synchronized boolean accept(int uid, long eventAt, long now) {
        Long previous = last.get(uid);
        if (eventAt <= 0 || eventAt > now || now - eventAt > MAX_AGE
                || (previous != null && eventAt <= previous)) return false;
        last.put(uid, eventAt);
        return true;
    }
}
