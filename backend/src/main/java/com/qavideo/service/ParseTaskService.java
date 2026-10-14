package com.qavideo.service;

import com.qavideo.common.BizException;
import com.qavideo.domain.ParseTask;
import com.qavideo.domain.Video;
import com.qavideo.domain.enums.TaskStatus;
import com.qavideo.domain.enums.VideoStatus;
import com.qavideo.dto.VideoMapper;
import com.qavideo.repository.ParseTaskRepository;
import com.qavideo.repository.VideoRepository;
import com.qavideo.service.ai.SummaryPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns the parse-task state machine in MySQL: PENDING -> RUNNING -> SUCCESS/FAILED,
 * plus the progress column the front-end polls.
 *
 * <p>Progress is intentionally persisted rather than kept in Redis: it is tiny, it must
 * survive a restart, and the UI can then use a plain HTTP poll with no sticky routing.
 */
@Service
public class ParseTaskService {

    private static final Logger log = LoggerFactory.getLogger(ParseTaskService.class);

    private final ParseTaskRepository taskRepository;
    private final VideoRepository videoRepository;
    private final VideoMapper videoMapper;

    public ParseTaskService(ParseTaskRepository taskRepository,
                            VideoRepository videoRepository,
                            VideoMapper videoMapper) {
        this.taskRepository = taskRepository;
        this.videoRepository = videoRepository;
        this.videoMapper = videoMapper;
    }

    @Transactional
    public ParseTask createQueued(Long videoId) {
        ParseTask task = new ParseTask();
        task.setVideoId(videoId);
        task.setRequestId(UUID.randomUUID().toString());
        task.setStatus(TaskStatus.PENDING);
        task.setProgress(0);
        task.setStage("QUEUED");
        ParseTask saved = taskRepository.save(task);

        videoRepository.findById(videoId).ifPresent(video -> {
            video.setStatus(VideoStatus.PARSING);
            videoRepository.save(video);
        });
        log.debug("parse task {} queued for video {}", saved.getId(), videoId);
        return saved;
    }

    /**
     * @return the task, or empty when it already finished (idempotent redelivery)
     */
    @Transactional
    public Optional<ParseTask> markRunning(Long taskId) {
        ParseTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            return Optional.empty();
        }
        if (task.getStatus() == TaskStatus.SUCCESS) {
            log.info("parse task {} already succeeded, skipping redelivery", taskId);
            return Optional.empty();
        }
        if (task.getStatus() == TaskStatus.RUNNING) {
            log.warn("parse task {} is already RUNNING, skipping concurrent execution", taskId);
            return Optional.empty();
        }
        task.setStatus(TaskStatus.RUNNING);
        task.setStage("ACQUIRE");
        task.setProgress(1);
        task.setStartedAt(LocalDateTime.now());
        task.setErrorMessage(null);
        return Optional.of(taskRepository.save(task));
    }

    @Transactional
    public void updateProgress(Long taskId, String stage, int progress) {
        taskRepository.findById(taskId).ifPresent(task -> {
            task.setStage(stage);
            task.setProgress(Math.min(99, Math.max(0, progress)));
            taskRepository.save(task);
        });
    }

    @Transactional
    public void markSucceeded(Long taskId, SummaryPayload payload, String transcript, Integer durationSeconds) {
        ParseTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> BizException.notFound("解析任务不存在: " + taskId));

        task.setStatus(TaskStatus.SUCCESS);
        task.setStage("DONE");
        task.setProgress(100);
        task.setModel(payload.model());
        task.setMockAi(payload.mock());
        task.setPromptTokens(payload.promptTokens());
        task.setCompletionTokens(payload.completionTokens());
        task.setTranscriptChars(transcript == null ? 0 : transcript.length());
        LocalDateTime finished = LocalDateTime.now();
        task.setFinishedAt(finished);
        if (task.getStartedAt() != null) {
            task.setDurationMs(Duration.between(task.getStartedAt(), finished).toMillis());
        }
        taskRepository.save(task);

        Video video = videoRepository.findById(task.getVideoId())
                .orElseThrow(() -> BizException.notFound("视频不存在: " + task.getVideoId()));
        video.setSummary(payload.summary());
        video.setTranscript(transcript);
        video.setKeywords(videoMapper.writeStringList(payload.keywords()));
        video.setHighlights(videoMapper.writeJson(payload.highlights()));
        video.setChapters(videoMapper.writeJson(payload.chapters()));
        if (durationSeconds != null) {
            video.setDurationSeconds(durationSeconds);
        }
        video.setStatus(VideoStatus.PARSED);
        video.setLastParsedAt(finished);
        videoRepository.save(video);
        log.info("parse task {} succeeded for video {} (mock={}, model={})",
                taskId, video.getId(), payload.mock(), payload.model());
    }

    @Transactional
    public void markFailed(Long taskId, String message) {
        taskRepository.findById(taskId).ifPresent(task -> {
            task.setStatus(TaskStatus.FAILED);
            task.setStage("FAILED");
            task.setErrorMessage(truncate(message));
            LocalDateTime finished = LocalDateTime.now();
            task.setFinishedAt(finished);
            if (task.getStartedAt() != null) {
                task.setDurationMs(Duration.between(task.getStartedAt(), finished).toMillis());
            }
            taskRepository.save(task);
            videoRepository.findById(task.getVideoId()).ifPresent(video -> {
                video.setStatus(VideoStatus.FAILED);
                videoRepository.save(video);
            });
            log.warn("parse task {} failed: {}", taskId, message);
        });
    }

    @Transactional(readOnly = true)
    public Optional<ParseTask> find(Long taskId) {
        return taskRepository.findById(taskId);
    }

    @Transactional(readOnly = true)
    public Optional<ParseTask> findLatestForVideo(Long videoId) {
        return taskRepository.findFirstByVideoIdOrderByIdDesc(videoId);
    }

    @Transactional(readOnly = true)
    public List<ParseTask> historyForVideo(Long videoId) {
        return taskRepository.findByVideoIdOrderByIdDesc(videoId);
    }

    @Transactional(readOnly = true)
    public boolean hasActiveTask(Long videoId) {
        return taskRepository.countActiveByVideoId(videoId) > 0;
    }

    private String truncate(String message) {
        if (message == null) {
            return "未知错误";
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
