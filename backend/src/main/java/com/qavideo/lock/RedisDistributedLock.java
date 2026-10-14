package com.qavideo.lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Redis SET NX + TTL lock. Release is a compare-and-delete Lua script, so a process
 * that overran its TTL cannot unlock a lock another process now owns.
 */
@Component
@ConditionalOnProperty(prefix = "qavideo.redis", name = "enabled", havingValue = "true")
public class RedisDistributedLock implements DistributedLock {

    private static final Logger log = LoggerFactory.getLogger(RedisDistributedLock.class);

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;

    public RedisDistributedLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Optional<String> tryLock(String key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redis.opsForValue().setIfAbsent(key, token, ttl);
        if (Boolean.TRUE.equals(acquired)) {
            log.debug("lock {} acquired for {}s", key, ttl.toSeconds());
            return Optional.of(token);
        }
        return Optional.empty();
    }

    @Override
    public void unlock(String key, String token) {
        try {
            redis.execute(RELEASE_SCRIPT, List.of(key), token);
        } catch (Exception ex) {
            log.warn("cannot release lock {}: {}", key, ex.getMessage());
        }
    }
}
