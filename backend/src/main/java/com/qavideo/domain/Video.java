package com.qavideo.domain;

import com.qavideo.domain.enums.SourceType;
import com.qavideo.domain.enums.VideoStatus;
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
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * One piece of source material (link or uploaded file) plus its AI output.
 *
 * <p>Duplicate protection lives on {@link #dedupKey}: {@code url:<sha256(normalized url)>}
 * for links and {@code file:<sha256(bytes)>} for uploads. The unique constraint is the
 * final arbiter, so two concurrent imports cannot create two rows.
 */
@Entity
@Table(
        name = "video",
        uniqueConstraints = @UniqueConstraint(name = "uk_video_dedup_key", columnNames = "dedup_key"),
        indexes = {
                @Index(name = "idx_video_created_at", columnList = "created_at"),
                @Index(name = "idx_video_status", columnList = "status"),
                @Index(name = "idx_video_content_hash", columnList = "content_hash")
        })
public class Video {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "title", length = 512)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 16)
    private SourceType sourceType;

    /** Original link, when the video was imported by URL. */
    @Column(name = "source_url", length = 1024)
    private String sourceUrl;

    /** Relative path of the stored media file, when one exists. */
    @Column(name = "storage_path", length = 1024)
    private String storagePath;

    @Column(name = "file_name", length = 512)
    private String fileName;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "content_type", length = 128)
    private String contentType;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "cover_url", length = 1024)
    private String coverUrl;

    /** Unique dedup key; see class javadoc. */
    @Column(name = "dedup_key", nullable = false, length = 128)
    private String dedupKey;

    /** sha256 of the media bytes, or of the normalized URL when nothing was downloaded. */
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private VideoStatus status = VideoStatus.IMPORTED;

    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(name = "summary")
    private String summary;

    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(name = "transcript")
    private String transcript;

    /** Comma separated keywords extracted by the agent. */
    @Column(name = "keywords", length = 512)
    private String keywords;

    /** JSON array of highlight lines. */
    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(name = "highlights")
    private String highlights;

    /** JSON array of {start,title,summary} chapter entries. */
    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(name = "chapters")
    private String chapters;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "last_parsed_at")
    private LocalDateTime lastParsedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public SourceType getSourceType() {
        return sourceType;
    }

    public void setSourceType(SourceType sourceType) {
        this.sourceType = sourceType;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public void setStoragePath(String storagePath) {
        this.storagePath = storagePath;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public Integer getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(Integer durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public String getCoverUrl() {
        return coverUrl;
    }

    public void setCoverUrl(String coverUrl) {
        this.coverUrl = coverUrl;
    }

    public String getDedupKey() {
        return dedupKey;
    }

    public void setDedupKey(String dedupKey) {
        this.dedupKey = dedupKey;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public VideoStatus getStatus() {
        return status;
    }

    public void setStatus(VideoStatus status) {
        this.status = status;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getTranscript() {
        return transcript;
    }

    public void setTranscript(String transcript) {
        this.transcript = transcript;
    }

    public String getKeywords() {
        return keywords;
    }

    public void setKeywords(String keywords) {
        this.keywords = keywords;
    }

    public String getHighlights() {
        return highlights;
    }

    public void setHighlights(String highlights) {
        this.highlights = highlights;
    }

    public String getChapters() {
        return chapters;
    }

    public void setChapters(String chapters) {
        this.chapters = chapters;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public LocalDateTime getLastParsedAt() {
        return lastParsedAt;
    }

    public void setLastParsedAt(LocalDateTime lastParsedAt) {
        this.lastParsedAt = lastParsedAt;
    }
}
