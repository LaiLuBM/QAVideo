package com.qavideo.dto;

/**
 * Result of importing a link or finishing an upload.
 *
 * @param duplicated true when the same content was already in the library, in which
 *                   case {@code video} is the existing row and no new file was stored.
 */
public record VideoImportResponse(VideoDetailDto video, boolean duplicated, TaskDto task) {
}
