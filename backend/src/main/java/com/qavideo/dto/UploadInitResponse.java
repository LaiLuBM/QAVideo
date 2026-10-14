package com.qavideo.dto;

import java.util.List;

/**
 * Answer of {@code POST /api/uploads/init}: everything the client needs to start or
 * resume a chunked transfer.
 *
 * @param resumed         true when an existing session was reused (breakpoint resume)
 * @param duplicate       non-null when the supplied contentHash already exists in the library
 * @param uploadedChunks  0-based indexes the server already holds; the client skips these
 */
public record UploadInitResponse(
        String uploadId,
        String fileName,
        long fileSize,
        long chunkSize,
        int totalChunks,
        List<Integer> uploadedChunks,
        boolean resumed,
        VideoImportResponse duplicate
) {
}
