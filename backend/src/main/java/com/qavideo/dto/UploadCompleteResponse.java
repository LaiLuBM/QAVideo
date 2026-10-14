package com.qavideo.dto;

/**
 * Answer of {@code POST /api/uploads/{id}/complete}: the merged file was hashed,
 * deduplicated, stored and (optionally) queued for parsing.
 *
 * @param uploadId     the session that was consumed
 * @param contentHash  sha256 of the merged file (also the dedup key material)
 * @param mergedChunks how many chunk files were merged
 * @param result       video row + duplicate flag + parse task
 */
public record UploadCompleteResponse(
        String uploadId,
        String contentHash,
        int mergedChunks,
        VideoImportResponse result
) {
}
