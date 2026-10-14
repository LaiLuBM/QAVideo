package com.qavideo.lock;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Minimal cross-instance lock.
 *
 * <p>Used to avoid scheduling two parse jobs for the same video at the same moment.
 * It is an optimisation, not the source of truth: the unique index on
 * {@code video.dedup_key} is what actually guarantees "no duplicates".
 */
public interface DistributedLock {

    /**
     * @return an ownership token on success, empty when somebody else holds the lock
     */
    Optional<String> tryLock(String key, Duration ttl);

    /** Releases the lock only if {@code token} still owns it. */
    void unlock(String key, String token);

    /** Convenience wrapper: runs the action under the lock, or returns empty if busy. */
    default <T> Optional<T> withLock(String key, Duration ttl, Supplier<T> action) {
        return tryLock(key, ttl).map(token -> {
            try {
                return action.get();
            } finally {
                unlock(key, token);
            }
        });
    }
}
