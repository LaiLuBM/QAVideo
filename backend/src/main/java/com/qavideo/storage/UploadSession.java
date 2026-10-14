package com.qavideo.storage;

import java.time.Instant;
import java.util.Set;
import java.util.TreeSet;

/**
 * In-flight resumable upload session. Held in Redis (or memory for the standalone
 * profile) rather than MySQL: it is short-lived, high-churn state.
 */
public class UploadSession {

    private String uploadId;
    private String fileName;
    private long fileSize;
    private long chunkSize;
    private int totalChunks;
    private String contentType;
    private String title;
    private boolean autoParse = true;
    private Instant createdAt;
    private Instant expiresAt;
    private final Set<Integer> uploadedChunks = new TreeSet<>();

    public String getUploadId() {
        return uploadId;
    }

    public void setUploadId(String uploadId) {
        this.uploadId = uploadId;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    public long getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(long chunkSize) {
        this.chunkSize = chunkSize;
    }

    public int getTotalChunks() {
        return totalChunks;
    }

    public void setTotalChunks(int totalChunks) {
        this.totalChunks = totalChunks;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public boolean isAutoParse() {
        return autoParse;
    }

    public void setAutoParse(boolean autoParse) {
        this.autoParse = autoParse;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Set<Integer> getUploadedChunks() {
        return uploadedChunks;
    }

    public int receivedCount() {
        return uploadedChunks.size();
    }

    public int progressPercent() {
        if (totalChunks <= 0) {
            return 0;
        }
        return (int) Math.floor(receivedCount() * 100.0 / totalChunks);
    }

    public boolean isComplete() {
        return totalChunks > 0 && receivedCount() >= totalChunks;
    }

    public void setUploadedChunks(Set<Integer> chunks) {
        uploadedChunks.clear();
        if (chunks != null) {
            uploadedChunks.addAll(chunks);
        }
    }
}
