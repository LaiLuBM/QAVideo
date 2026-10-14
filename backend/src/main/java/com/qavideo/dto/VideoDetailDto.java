package com.qavideo.dto;

import java.time.LocalDateTime;
import java.util.List;

/** Full detail view: summary, transcript, chapters and the latest task. */
public record VideoDetailDto(
        Long id,
        String title,
        String sourceType,
        String sourceUrl,
        String fileName,
        Long fileSize,
        String contentType,
        Integer durationSeconds,
        String coverUrl,
        String status,
        String contentHash,
        String summary,
        String transcript,
        List<String> keywords,
        List<String> highlights,
        List<ChapterDto> chapters,
        String playUrl,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime lastParsedAt,
        TaskDto task,
        List<TaskDto> recentTasks
) {
}
