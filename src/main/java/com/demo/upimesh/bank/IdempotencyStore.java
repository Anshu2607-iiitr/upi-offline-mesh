package com.demo.upimesh.bank;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers which packets the bank has already acted on. {@code putIfAbsent} is atomic, so
 * when three bridges deliver the same packet at the same instant exactly one wins the claim.
 *
 * In production this would be Redis {@code SET NX} or a unique index in the ledger DB.
 */
@Component
public class IdempotencyStore {

    private final ConcurrentHashMap<String, Instant> claimed = new ConcurrentHashMap<>();

    /** @return true for the first caller with this key, false for every caller after that */
    public boolean claim(String key, Instant now) {
        return claimed.putIfAbsent(key, now) == null;
    }

    /** Undo a claim whose settlement failed before anything was committed. */
    public void release(String key) {
        claimed.remove(key);
    }

    public int evictClaimedBefore(Instant cutoff) {
        int before = claimed.size();
        claimed.values().removeIf(at -> at.isBefore(cutoff));
        return before - claimed.size();
    }

    public int size() {
        return claimed.size();
    }

    public void clear() {
        claimed.clear();
    }
}
