package com.qavideo.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Redis-backed resumable-upload state (default production mode).
 *
 * <p>Layout - every key carries the session TTL so abandoned uploads disappear by
 * themselves:
 * <pre>
 *   qavideo:upload:{id}          HASH  fileName/fileSize/chunkSize/totalChunks/...
 *   qavideo:upload:{id}:chunks   SET   0-based indexes already received
 * </pre>
 * Using a SET makes {@code SADD} atomic, so several parallel chunk requests cannot
 * lose each other's progress the way a read-modify-write on a JSON blob would.
 */
@Component
@ConditionalOnProperty(prefix = "qavideo.redis", name = "enabled", havingValue = "true")
public class RedisUploadStateStore implements UploadStateStore {

    private static final Logger log = LoggerFactory.getLogger(RedisUploadStateStore.class);
    private static final String META_PREFIX = "qavideo:upload:";
    private static final String CHUNK_PREFIX = "qavideo:upload:chunks:";

    private final StringRedisTemplate redis;

    public RedisUploadStateStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public UploadSession create(UploadSession session) {
        String id = session.getUploadId();
        Map<String, String> meta = new HashMap<>();
        meta.put("uploadId", id);
        meta.put("fileName", nullSafe(session.getFileName()));
        meta.put("fileSize", String.valueOf(session.getFileSize()));
        meta.put("chunkSize", String.valueOf(session.getChunkSize()));
        meta.put("totalChunks", String.valueOf(session.getTotalChunks()));
        meta.put("contentType", nullSafe(session.getContentType()));
        meta.put("title", nullSafe(session.getTitle()));
        meta.put("autoParse", String.valueOf(session.isAutoParse()));
        meta.put("createdAt", String.valueOf(session.getCreatedAt().toEpochMilli()));
        meta.put("expiresAt", String.valueOf(session.getExpiresAt().toEpochMilli()));

        Duration ttl = ttlOf(session);
        redis.opsForHash().putAll(metaKey(id), meta);
        redis.expire(metaKey(id), ttl);
        redis.expire(chunkKey(id), ttl);
        log.debug("upload session {} created in redis (ttl={}s)", id, ttl.toSeconds());
        return session;
    }

    @Override
    public Optional<UploadSession> find(String uploadId) {
        Map<Object, Object> meta = redis.opsForHash().entries(metaKey(uploadId));
        if (meta == null || meta.isEmpty()) {
            return Optional.empty();
        }
        UploadSession session = new UploadSession();
        session.setUploadId(uploadId);
        session.setFileName(str(meta.get("fileName")));
        session.setFileSize(longOf(meta.get("fileSize")));
        session.setChunkSize(longOf(meta.get("chunkSize")));
        session.setTotalChunks((int) longOf(meta.get("totalChunks")));
        session.setContentType(str(meta.get("contentType")));
        session.setTitle(str(meta.get("title")));
        session.setAutoParse(Boolean.parseBoolean(str(meta.get("autoParse"))));
        session.setCreatedAt(Instant.ofEpochMilli(longOf(meta.get("createdAt"))));
        session.setExpiresAt(Instant.ofEpochMilli(longOf(meta.get("expiresAt"))));

        Set<String> members = redis.opsForSet().members(chunkKey(uploadId));
        Set<Integer> chunks = new HashSet<>();
        if (members != null) {
            for (String m : members) {
                try {
                    chunks.add(Integer.parseInt(m));
                } catch (NumberFormatException ignored) {
                    // skip damaged member
                }
            }
        }
        session.setUploadedChunks(chunks);
        return Optional.of(session);
    }

    @Override
    public Optional<UploadSession> addChunk(String uploadId, int index) {
        Long added = redis.opsForSet().add(chunkKey(uploadId), String.valueOf(index));
        if (added == null) {
            return Optional.empty();
        }
        // refresh the TTL of both keys so a slow but active upload never expires mid-flight
        redis.expire(metaKey(uploadId), ttlOf(null));
        redis.expire(chunkKey(uploadId), ttlOf(null));
        return find(uploadId);
    }

    @Override
    public void remove(String uploadId) {
        redis.delete(metaKey(uploadId));
        redis.delete(chunkKey(uploadId));
    }

    @Override
    public boolean isDistributed() {
        return true;
    }

    private String metaKey(String id) {
        return META_PREFIX + id;
    }

    private String chunkKey(String id) {
        return CHUNK_PREFIX + id;
    }

    private Duration ttlOf(UploadSession session) {
        if (session == null || session.getExpiresAt() == null || session.getCreatedAt() == null) {
            return Duration.ofHours(24);
        }
        Duration ttl = Duration.between(Instant.now(), session.getExpiresAt());
        return ttl.isNegative() || ttl.isZero() ? Duration.ofMinutes(30) : ttl;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private static long longOf(Object value) {
        try {
            return value == null ? 0L : Long.parseLong(value.toString());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }
}
