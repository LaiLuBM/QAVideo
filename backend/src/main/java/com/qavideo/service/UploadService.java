package com.qavideo.service;

import com.qavideo.common.BizException;
import com.qavideo.config.AppProperties;
import com.qavideo.domain.Video;
import com.qavideo.domain.enums.SourceType;
import com.qavideo.domain.enums.VideoStatus;
import com.qavideo.dto.UploadChunkResponse;
import com.qavideo.dto.UploadCompleteResponse;
import com.qavideo.dto.UploadInitRequest;
import com.qavideo.dto.UploadInitResponse;
import com.qavideo.dto.UploadSessionDto;
import com.qavideo.dto.VideoImportResponse;
import com.qavideo.storage.StorageService;
import com.qavideo.storage.UploadSession;
import com.qavideo.storage.UploadStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * Chunked, resumable uploads.
 *
 * <p>Protocol:
 * <ol>
 *   <li>{@code POST /api/uploads/init} - create or resume a session. The client stores
 *       the returned {@code uploadId} (localStorage) so a page reload, a network drop or
 *       a closed laptop can pick up where it stopped.</li>
 *   <li>{@code POST /api/uploads/{id}/chunks/{index}} - one chunk per request, so each
 *       HTTP call stays small and can be retried independently.</li>
 *   <li>{@code POST /api/uploads/{id}/complete} - merge, hash, dedup, register, parse.</li>
 * </ol>
 *
 * <p>The chunk inventory lives in Redis (or memory) rather than in the browser, which is
 * what makes the resume trustworthy: it reflects bytes the server actually persisted.
 */
@Service
public class UploadService {

    private static final Logger log = LoggerFactory.getLogger(UploadService.class);

    private final UploadStateStore stateStore;
    private final StorageService storageService;
    private final VideoService videoService;
    private final AppProperties properties;

    public UploadService(UploadStateStore stateStore,
                         StorageService storageService,
                         VideoService videoService,
                         AppProperties properties) {
        this.stateStore = stateStore;
        this.storageService = storageService;
        this.videoService = videoService;
        this.properties = properties;
    }

    // ------------------------------------------------------------------- init

    public UploadInitResponse init(UploadInitRequest request) {
        long chunkSize = request.chunkSize() != null && request.chunkSize() > 0
                ? request.chunkSize()
                : properties.getUpload().getChunkSize();
        long maxFileSize = properties.getUpload().getMaxFileSize();
        if (request.fileSize() > maxFileSize) {
            throw BizException.badRequest("文件大小超过上限: " + maxFileSize + " 字节");
        }

        // 1) duplicate short-circuit: the browser already knows the sha256 -> no upload at all
        if (request.contentHash() != null && !request.contentHash().isBlank()) {
            String dedupKey = "file:" + request.contentHash().trim().toLowerCase(Locale.ROOT);
            Optional<Video> existing = videoService.findByDedupKey(dedupKey);
            if (existing.isPresent()) {
                log.info("upload short-circuited as duplicate: {}", dedupKey);
                return new UploadInitResponse(
                        null,
                        request.fileName(),
                        request.fileSize(),
                        chunkSize,
                        totalChunksOf(request.fileSize(), chunkSize),
                        List.of(),
                        false,
                        videoService.existingVideoResponse(existing.get(), request.autoParseOrDefault()));
            }
        }

        // 2) resume an existing session when the client still has its id
        if (request.uploadId() != null && !request.uploadId().isBlank()) {
            Optional<UploadSession> resumed = stateStore.find(request.uploadId().trim());
            if (resumed.isPresent()) {
                return new UploadInitResponse(
                        resumed.get().getUploadId(),
                        resumed.get().getFileName(),
                        resumed.get().getFileSize(),
                        resumed.get().getChunkSize(),
                        resumed.get().getTotalChunks(),
                        resumed.get().getUploadedChunks().stream().toList(),
                        true,
                        null);
            }
            log.info("upload session {} not found, starting a new one", request.uploadId());
        }

        // 3) brand new session
        UploadSession session = new UploadSession();
        session.setUploadId(UUID.randomUUID().toString().replace("-", ""));
        session.setFileName(request.fileName());
        session.setFileSize(request.fileSize());
        session.setChunkSize(chunkSize);
        session.setTotalChunks(totalChunksOf(request.fileSize(), chunkSize));
        session.setContentType(request.contentType());
        session.setTitle(request.title());
        session.setAutoParse(request.autoParseOrDefault());
        session.setCreatedAt(Instant.now());
        session.setExpiresAt(Instant.now().plus(properties.getUpload().getSessionTtl()));
        stateStore.create(session);

        // 4) reconcile with chunk files already on disk: if Redis was flushed but the
        //    temp directory survived, the client is told those chunks are still there.
        List<Integer> onDisk = storageService.chunkIndexesOnDisk(session.getUploadId());
        for (Integer index : onDisk) {
            stateStore.addChunk(session.getUploadId(), index);
        }
        if (!onDisk.isEmpty()) {
            log.info("reconciled {} chunk(s) from disk for session {}", onDisk.size(), session.getUploadId());
        }

        boolean resumed = !onDisk.isEmpty();
        UploadSession stored = stateStore.find(session.getUploadId()).orElse(session);
        return new UploadInitResponse(
                stored.getUploadId(),
                stored.getFileName(),
                stored.getFileSize(),
                stored.getChunkSize(),
                stored.getTotalChunks(),
                stored.getUploadedChunks().stream().toList(),
                resumed,
                null);
    }

    // ------------------------------------------------------------------ state

    public UploadSessionDto session(String uploadId) {
        UploadSession session = requireSession(uploadId);
        return stateStore.toDto(session);
    }

    public void cancel(String uploadId) {
        stateStore.remove(uploadId);
        storageService.deleteRecursively(storageService.getTempRoot().resolve(uploadId));
        log.info("upload session {} cancelled", uploadId);
    }

    // ----------------------------------------------------------------- chunks

    public UploadChunkResponse saveChunk(String uploadId, int index, MultipartFile file) {
        UploadSession session = requireSession(uploadId);
        if (index < 0 || index >= session.getTotalChunks()) {
            throw BizException.badRequest("分片下标越界: " + index + " (共 " + session.getTotalChunks() + " 片)");
        }
        if (file == null || file.isEmpty()) {
            throw BizException.badRequest("分片内容为空");
        }
        if (file.getSize() > session.getChunkSize()) {
            throw BizException.badRequest("分片大小 " + file.getSize() + " 超过约定值 " + session.getChunkSize());
        }

        Path target = storageService.chunkFile(uploadId, index);
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            throw BizException.internal("写入分片失败: " + ex.getMessage());
        }

        UploadSession updated = stateStore.addChunk(uploadId, index)
                .orElseThrow(() -> BizException.notFound("上传会话已过期，请重新初始化"));

        return new UploadChunkResponse(
                uploadId,
                index,
                updated.receivedCount(),
                updated.getTotalChunks(),
                updated.progressPercent(),
                updated.isComplete());
    }

    // ---------------------------------------------------------------- complete

    public UploadCompleteResponse complete(String uploadId) {
        UploadSession session = requireSession(uploadId);

        List<Integer> onDisk = storageService.chunkIndexesOnDisk(uploadId);
        if (onDisk.size() < session.getTotalChunks()) {
            List<Integer> missing = IntStream.range(0, session.getTotalChunks())
                    .filter(i -> !onDisk.contains(i))
                    .boxed()
                    .toList();
            throw BizException.conflict("还有 " + missing.size() + " 个分片未上传: " + abbreviate(missing));
        }

        Path merged = storageService.mergedTempFile(uploadId);
        String sha256;
        try {
            sha256 = storageService.mergeChunks(uploadId, session.getTotalChunks(), merged);
        } catch (IOException ex) {
            throw BizException.internal("合并分片失败: " + ex.getMessage());
        }

        long mergedSize;
        try {
            mergedSize = Files.size(merged);
        } catch (IOException ex) {
            throw BizException.internal("无法读取合并后的文件: " + ex.getMessage());
        }
        if (session.getFileSize() > 0 && mergedSize != session.getFileSize()) {
            storageService.deleteQuietly(merged);
            throw BizException.badRequest(
                    "合并后大小 " + mergedSize + " 与声明的 " + session.getFileSize() + " 不一致，请重新上传");
        }

        String dedupKey = "file:" + sha256;
        Optional<Video> existing = videoService.findByDedupKey(dedupKey);
        if (existing.isPresent()) {
            cleanup(uploadId);
            log.info("merged upload {} is a duplicate of video {}", uploadId, existing.get().getId());
            return new UploadCompleteResponse(
                    uploadId, sha256, session.getTotalChunks(),
                    videoService.existingVideoResponse(existing.get(), session.isAutoParse()));
        }

        String extension = StorageService.extensionOf(session.getFileName());
        Path target = storageService.targetPath(sha256, extension);
        try {
            storageService.moveIntoPlace(merged, target);
        } catch (IOException ex) {
            throw BizException.internal("保存文件失败: " + ex.getMessage());
        }

        Video video = new Video();
        video.setTitle(videoService.titleFor(session.getTitle(), session.getFileName()));
        video.setSourceType(SourceType.LOCAL);
        video.setFileName(session.getFileName());
        video.setFileSize(mergedSize);
        video.setContentType(session.getContentType());
        video.setStoragePath(storageService.relativePath(target));
        video.setContentHash(sha256);
        video.setDedupKey(dedupKey);
        video.setStatus(VideoStatus.IMPORTED);

        VideoImportResponse result = videoService.registerLocalVideo(video, session.isAutoParse());
        cleanup(uploadId);
        log.info("upload {} completed: {} bytes, sha256={}, video={}", uploadId, mergedSize, sha256, video.getId());
        return new UploadCompleteResponse(uploadId, sha256, session.getTotalChunks(), result);
    }

    // ----------------------------------------------------------------- helpers

    private void cleanup(String uploadId) {
        stateStore.remove(uploadId);
        storageService.deleteRecursively(storageService.getTempRoot().resolve(uploadId));
    }

    private UploadSession requireSession(String uploadId) {
        return stateStore.find(uploadId)
                .orElseThrow(() -> BizException.notFound("上传会话不存在或已过期: " + uploadId));
    }

    private int totalChunksOf(long fileSize, long chunkSize) {
        if (chunkSize <= 0) {
            throw BizException.internal("分片大小配置非法");
        }
        return (int) Math.max(1, (fileSize + chunkSize - 1) / chunkSize);
    }

    private String abbreviate(List<Integer> indexes) {
        String text = indexes.stream().limit(12).map(String::valueOf).toList().toString();
        return indexes.size() > 12 ? text + " ..." : text;
    }
}
