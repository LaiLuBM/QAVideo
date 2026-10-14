package com.qavideo.dto;

import java.time.Instant;
import java.util.List;

/** State of an in-flight resumable upload; used by {@code GET /api/uploads/{id}}. */
public record UploadSessionDto(
        String uploadId,
        String fileName,
        long fileSize,
        long chunkSize,
        int totalChunks,
        List<Integer> uploadedChunks,
        int receivedChunks,
        int progress,
        Instant createdAt,
        Instant expiresAt
) {
}
