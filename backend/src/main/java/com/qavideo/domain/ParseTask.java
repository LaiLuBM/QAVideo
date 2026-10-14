package com.qavideo.domain;

import com.qavideo.domain.enums.TaskStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * An asynchronous parse job. Created synchronously by the REST layer (so the HTTP
 * response is fast), executed by a RocketMQ consumer or the local executor.
 *
 * <p>{@link #requestId} makes redelivery safe: a consumer that receives the same
 * message twice sees a finished task and skips the work.
 */
@Entity
@Table(
        name = "parse_task",
        indexes = {
                @Index(name = "idx_task_video_id", columnList = "video_id"),
                @Index(name = "idx_task_status", columnList = "status"),
                @Index(name = "idx_task_request_id", columnList = "request_id")
        })
public class ParseTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "video_id", nullable = false)
    private Long videoId;

    /** Idempotency key shared with the MQ message. */
    @Column(name = "request_id", nullable = false, length = 64)
    private String requestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TaskStatus status = TaskStatus.PENDING;

    /** 0-100, updated as the pipeline advances, so the UI can show progress. */
    @Column(name = "progress", nullable = false)
    private Integer progress = 0;

    /** Current stage, e.g. ACQUIRE / TRANSCRIBE / SUMMARIZE / PERSIST. */
    @Column(name = "stage", length = 32)
    private String stage;

    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "model", length = 128)
    private String model;

    /** true when the local fallback summarizer produced the result. */
    @Column(name = "mock_ai", nullable = false)
    private boolean mockAi = false;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "transcript_chars")
    private Integer transcriptChars;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    void onUpdate() {
        // no-op hook kept for symmetry with Video
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getVideoId() {
        return videoId;
    }

    public void setVideoId(Long videoId) {
        this.videoId = videoId;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public Integer getProgress() {
        return progress;
    }

    public void setProgress(Integer progress) {
        this.progress = progress;
    }

    public String getStage() {
        return stage;
    }

    public void setStage(String stage) {
        this.stage = stage;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public boolean isMockAi() {
        return mockAi;
    }

    public void setMockAi(boolean mockAi) {
        this.mockAi = mockAi;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Integer promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Integer completionTokens) {
        this.completionTokens = completionTokens;
    }

    public Integer getTranscriptChars() {
        return transcriptChars;
    }

    public void setTranscriptChars(Integer transcriptChars) {
        this.transcriptChars = transcriptChars;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }
}
