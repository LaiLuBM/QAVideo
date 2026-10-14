package com.qavideo.service;

import com.qavideo.config.AppProperties;
import com.qavideo.domain.ParseTask;
import com.qavideo.domain.Video;
import com.qavideo.media.FetchedMedia;
import com.qavideo.media.MediaFetcher;
import com.qavideo.repository.ParseTaskRepository;
import com.qavideo.repository.VideoRepository;
import com.qavideo.service.ai.AiSummaryService;
import com.qavideo.service.ai.SummaryPayload;
import com.qavideo.service.ai.SummaryRequest;
import com.qavideo.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The parsing pipeline, executed off the request thread (RocketMQ consumer or local pool).
 *
 * <pre>
 *   ACQUIRE      URL source? try to pull the media file (direct media URLs only)
 *   TRANSCRIBE   sidecar .srt/.vtt/.txt, else demo placeholder (ASR plug point)
 *   SUMMARIZE    LangChain4j agent -> DeepSeek
 *   PERSIST      summary/keywords/chapters/transcript -> MySQL
 * </pre>
 *
 * <p>Any failure is recorded on the task and the video, so the UI can show what went
 * wrong instead of hanging on "解析中".
 */
@Service
public class VideoParsePipeline {

    private static final Logger log = LoggerFactory.getLogger(VideoParsePipeline.class);

    private final ParseTaskRepository taskRepository;
    private final VideoRepository videoRepository;
    private final ParseTaskService taskService;
    private final TranscriptService transcriptService;
    private final AiSummaryService aiSummaryService;
    private final MediaFetcher mediaFetcher;
    private final StorageService storageService;
    private final AppProperties properties;

    public VideoParsePipeline(ParseTaskRepository taskRepository,
                              VideoRepository videoRepository,
                              ParseTaskService taskService,
                              TranscriptService transcriptService,
                              AiSummaryService aiSummaryService,
                              MediaFetcher mediaFetcher,
                              StorageService storageService,
                              AppProperties properties) {
        this.taskRepository = taskRepository;
        this.videoRepository = videoRepository;
        this.taskService = taskService;
        this.transcriptService = transcriptService;
        this.aiSummaryService = aiSummaryService;
        this.mediaFetcher = mediaFetcher;
        this.storageService = storageService;
        this.properties = properties;
    }

    public void execute(Long taskId) {
        Optional<ParseTask> claimed = taskService.markRunning(taskId);
        if (claimed.isEmpty()) {
            return;
        }
        ParseTask task = claimed.get();
        try {
            Video video = videoRepository.findById(task.getVideoId())
                    .orElseThrow(() -> new IllegalStateException("视频记录不存在: " + task.getVideoId()));

            taskService.updateProgress(taskId, "ACQUIRE", 10);
            video = acquireMedia(video);

            taskService.updateProgress(taskId, "TRANSCRIBE", 35);
            TranscriptService.ContentMaterial material = transcriptService.extract(video);
            String transcript = material.text();
            Integer durationSeconds = material.durationSeconds() != null
                    ? material.durationSeconds()
                    : video.getDurationSeconds();
            log.debug("video {} material kind={} chars={} duration={}s",
                    video.getId(), material.kind(), transcript == null ? 0 : transcript.length(), durationSeconds);

            taskService.updateProgress(taskId, "SUMMARIZE", 55);
            SummaryPayload payload = aiSummaryService.summarize(new SummaryRequest(
                    video.getId(),
                    video.getTitle(),
                    video.getSourceUrl(),
                    durationSeconds,
                    transcript));

            taskService.updateProgress(taskId, "PERSIST", 95);
            taskService.markSucceeded(taskId, payload, transcript, durationSeconds);
        } catch (Exception ex) {
            log.error("parse task {} failed", taskId, ex);
            taskService.markFailed(taskId, rootMessage(ex));
        }
    }

    /**
     * For URL sources, pull the media file when the URL points straight at a media
     * resource. Page URLs (YouTube/Bilibili/...) stay link-only - see {@link MediaFetcher}.
     */
    private Video acquireMedia(Video video) {
        if (video.getStoragePath() != null || video.getSourceUrl() == null) {
            return video;
        }
        if (!properties.getMedia().isDownloadEnabled()) {
            log.info("video {}: link-only mode (media download disabled)", video.getId());
            return video;
        }
        Optional<FetchedMedia> fetched = mediaFetcher.fetch(video.getSourceUrl(), "video" + video.getId());
        if (fetched.isEmpty()) {
            log.info("video {}: source URL is not a direct media file, summarising link metadata only", video.getId());
            return video;
        }

        FetchedMedia media = fetched.get();
        try {
            String sha256 = storageService.sha256(media.tempFile());
            Path target = storageService.targetPath(sha256, media.extension());
            storageService.moveIntoPlace(media.tempFile(), target);

            video.setContentHash(sha256);
            video.setStoragePath(storageService.relativePath(target));
            video.setFileSize(media.size());
            if (media.contentType() != null && !media.contentType().isBlank()) {
                video.setContentType(media.contentType());
            }
            // dedupKey deliberately stays "url:..." - the library identity is the link,
            // so re-importing the same link still resolves to this row.
            return videoRepository.save(video);
        } catch (Exception ex) {
            storageService.deleteQuietly(media.tempFile());
            throw new IllegalStateException("保存下载的媒体文件失败: " + ex.getMessage(), ex);
        }
    }

    private String rootMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
