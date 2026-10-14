package com.qavideo.dto;

/** Answer of one chunk upload. */
public record UploadChunkResponse(
        String uploadId,
        int index,
        int receivedChunks,
        int totalChunks,
        int progress,
        boolean completed
) {
}
