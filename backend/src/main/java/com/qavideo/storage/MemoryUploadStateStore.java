package com.qavideo.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process fallback used by the {@code standalone} profile (no Redis available).
 * Enables resumable uploads on a single instance; switch to Redis for a cluster.
 */
@Component
@ConditionalOnProperty(prefix = "qavideo.redis", name = "enabled", havingValue = "false", matchIfMissing = true)
public class MemoryUploadStateStore implements UploadStateStore {

    private final Map<String, UploadSession> sessions = new ConcurrentHashMap<>();

    @Override
    public UploadSession create(UploadSession session) {
        sessions.put(session.getUploadId(), copy(session));
        return session;
    }

    @Override
    public Optional<UploadSession> find(String uploadId) {
        UploadSession session = sessions.get(uploadId);
        if (session == null) {
            return Optional.empty();
        }
        if (session.getExpiresAt() != null && session.getExpiresAt().isBefore(Instant.now())) {
            sessions.remove(uploadId);
            return Optional.empty();
        }
        return Optional.of(copy(session));
    }

    @Override
    public synchronized Optional<UploadSession> addChunk(String uploadId, int index) {
        UploadSession session = sessions.get(uploadId);
        if (session == null) {
            return Optional.empty();
        }
        session.getUploadedChunks().add(index);
        return Optional.of(copy(session));
    }

    @Override
    public void remove(String uploadId) {
        sessions.remove(uploadId);
    }

    @Override
    public boolean isDistributed() {
        return false;
    }

    @Override
    public void cleanupExpired(Instant now) {
        sessions.entrySet().removeIf(e -> e.getValue().getExpiresAt() != null
                && e.getValue().getExpiresAt().isBefore(now));
    }

    private UploadSession copy(UploadSession source) {
        UploadSession target = new UploadSession();
        target.setUploadId(source.getUploadId());
        target.setFileName(source.getFileName());
        target.setFileSize(source.getFileSize());
        target.setChunkSize(source.getChunkSize());
        target.setTotalChunks(source.getTotalChunks());
        target.setContentType(source.getContentType());
        target.setTitle(source.getTitle());
        target.setAutoParse(source.isAutoParse());
        target.setCreatedAt(source.getCreatedAt());
        target.setExpiresAt(source.getExpiresAt());
        target.setUploadedChunks(source.getUploadedChunks());
        return target;
    }
}
