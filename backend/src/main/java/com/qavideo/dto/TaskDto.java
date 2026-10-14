package com.qavideo.dto;

import com.qavideo.domain.ParseTask;

import java.time.LocalDateTime;

/** Progress view of one parse job; the front-end polls this while parsing. */
public record TaskDto(
        Long id,
        Long videoId,
        String status,
        Integer progress,
        String stage,
        String errorMessage,
        String model,
        boolean mockAi,
        Integer promptTokens,
        Integer completionTokens,
        Integer transcriptChars,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        Long durationMs
) {
    public static TaskDto from(ParseTask task) {
        if (task == null) {
            return null;
        }
        return new TaskDto(
                task.getId(),
                task.getVideoId(),
                task.getStatus() == null ? null : task.getStatus().name(),
                task.getProgress(),
                task.getStage(),
                task.getErrorMessage(),
                task.getModel(),
                task.isMockAi(),
                task.getPromptTokens(),
                task.getCompletionTokens(),
                task.getTranscriptChars(),
                task.getCreatedAt(),
                task.getStartedAt(),
                task.getFinishedAt(),
                task.getDurationMs());
    }
}
