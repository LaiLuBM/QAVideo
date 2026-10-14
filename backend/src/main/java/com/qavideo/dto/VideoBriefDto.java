package com.qavideo.dto;

import java.time.LocalDateTime;
import java.util.List;

/** Library row. Deliberately excludes the full transcript to keep list responses small. */
public record VideoBriefDto(
        Long id,
        String title,
        String sourceType,
        String sourceUrl,
        String fileName,
        Long fileSize,
        Integer durationSeconds,
        String coverUrl,
        String status,
        String summaryPreview,
        List<String> keywords,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime lastParsedAt,
        TaskDto task
) {
}
