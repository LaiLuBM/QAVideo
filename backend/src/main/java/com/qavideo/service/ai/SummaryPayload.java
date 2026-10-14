package com.qavideo.service.ai;

import com.qavideo.dto.ChapterDto;

import java.util.List;

/**
 * Normalised agent output; the same shape whether it came from DeepSeek or from the
 * offline fallback summarizer.
 */
public record SummaryPayload(
        String summary,
        List<String> keywords,
        List<String> highlights,
        List<ChapterDto> chapters,
        String sentiment,
        String targetAudience,
        String provider,
        String model,
        boolean mock,
        Integer promptTokens,
        Integer completionTokens
) {
    public static SummaryPayload empty(String provider, String model) {
        return new SummaryPayload(null, List.of(), List.of(), List.of(), null, null, provider, model, false, null, null);
    }
}
