package com.qavideo.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qavideo.domain.ParseTask;
import com.qavideo.domain.Video;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Entity -> DTO mapping. Keeps the JSON columns (keywords / highlights / chapters)
 * as raw strings in MySQL and turns them into structures only on the way out.
 */
@Component
public class VideoMapper {

    private static final Logger log = LoggerFactory.getLogger(VideoMapper.class);
    private static final int PREVIEW_CHARS = 160;

    private final ObjectMapper objectMapper;

    public VideoMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public VideoBriefDto toBrief(Video video, ParseTask task) {
        return new VideoBriefDto(
                video.getId(),
                video.getTitle(),
                video.getSourceType() == null ? null : video.getSourceType().name(),
                video.getSourceUrl(),
                video.getFileName(),
                video.getFileSize(),
                video.getDurationSeconds(),
                video.getCoverUrl(),
                video.getStatus() == null ? null : video.getStatus().name(),
                preview(video.getSummary()),
                readStringList(video.getKeywords()),
                video.getCreatedAt(),
                video.getUpdatedAt(),
                video.getLastParsedAt(),
                TaskDto.from(task));
    }

    public VideoDetailDto toDetail(Video video, ParseTask latest, List<ParseTask> recent) {
        return new VideoDetailDto(
                video.getId(),
                video.getTitle(),
                video.getSourceType() == null ? null : video.getSourceType().name(),
                video.getSourceUrl(),
                video.getFileName(),
                video.getFileSize(),
                video.getContentType(),
                video.getDurationSeconds(),
                video.getCoverUrl(),
                video.getStatus() == null ? null : video.getStatus().name(),
                video.getContentHash(),
                video.getSummary(),
                video.getTranscript(),
                readStringList(video.getKeywords()),
                readStringList(video.getHighlights()),
                readList(video.getChapters(), new TypeReference<List<ChapterDto>>() {
                }),
                video.getStoragePath() == null ? null : "/api/videos/" + video.getId() + "/stream",
                video.getCreatedAt(),
                video.getUpdatedAt(),
                video.getLastParsedAt(),
                TaskDto.from(latest),
                recent == null ? List.of() : recent.stream().map(TaskDto::from).toList());
    }

    /** Serializes a List&lt;String&gt; for the keywords column. */
    public String writeStringList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception ex) {
            log.warn("cannot serialize string list", ex);
            return null;
        }
    }

    public String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            log.warn("cannot serialize value", ex);
            return null;
        }
    }

    public List<String> readStringList(String json) {
        return readList(json, new TypeReference<List<String>>() {
        });
    }

    private <T> T readList(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception ex) {
            log.warn("cannot parse json column: {}", json);
            return null;
        }
    }

    private String preview(String text) {
        if (text == null) {
            return null;
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= PREVIEW_CHARS ? flat : flat.substring(0, PREVIEW_CHARS) + "...";
    }
}
