package com.qavideo.service.ai;

/**
 * Everything the agent needs to know about one video.
 */
public record SummaryRequest(
        Long videoId,
        String title,
        String sourceUrl,
        Integer durationSeconds,
        String transcript
) {
}
