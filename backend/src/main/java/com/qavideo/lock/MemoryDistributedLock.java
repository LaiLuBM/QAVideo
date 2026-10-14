package com.qavideo.lock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Single-JVM lock used by the {@code standalone} profile. */
@Component
@ConditionalOnProperty(prefix = "qavideo.redis", name = "enabled", havingValue = "false", matchIfMissing = true)
public class MemoryDistributedLock implements DistributedLock {

    private record Holder(String token, Instant expiresAt) {
    }

    private final Map<String, Holder> holders = new ConcurrentHashMap<>();

    @Override
    public Optional<String> tryLock(String key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        Holder candidate = new Holder(token, Instant.now().plus(ttl));
        Holder existing = holders.compute(key, (k, current) -> {
            if (current == null || current.expiresAt().isBefore(Instant.now())) {
                return candidate;
            }
            return current;
        });
        return candidate.equals(existing) ? Optional.of(token) : Optional.empty();
    }

    @Override
    public void unlock(String key, String token) {
        holders.computeIfPresent(key, (k, current) -> current.token().equals(token) ? null : current);
    }
}
