package com.qavideo.storage;

import com.qavideo.dto.UploadSessionDto;

import java.time.Instant;
import java.util.Optional;

/**
 * Persistence boundary for resumable-upload state.
 *
 * <p>Two implementations ship with the project:
 * <ul>
 *   <li>{@link RedisUploadStateStore} - production, shared across instances, TTL based.</li>
 *   <li>{@link MemoryUploadStateStore} - standalone profile, no middleware needed.</li>
 * </ul>
 */
public interface UploadStateStore {

    UploadSession create(UploadSession template);

    Optional<UploadSession> find(String uploadId);

    /**
     * Records that one chunk landed.
     *
     * @return the updated session, or empty when the session expired/was cancelled
     */
    Optional<UploadSession> addChunk(String uploadId, int index);

    void remove(String uploadId);

    /** true when the state is shared between application instances. */
    boolean isDistributed();

    default UploadSessionDto toDto(UploadSession session) {
        return new UploadSessionDto(
                session.getUploadId(),
                session.getFileName(),
                session.getFileSize(),
                session.getChunkSize(),
                session.getTotalChunks(),
                session.getUploadedChunks().stream().toList(),
                session.receivedCount(),
                session.progressPercent(),
                session.getCreatedAt(),
                session.getExpiresAt());
    }

    /** Housekeeping hook; Redis relies on key TTL instead. */
    default void cleanupExpired(Instant now) {
        // no-op by default
    }
}
