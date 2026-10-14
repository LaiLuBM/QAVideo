package com.qavideo.service;

import com.qavideo.common.BizException;
import com.qavideo.common.PageResult;
import com.qavideo.domain.ParseTask;
import com.qavideo.domain.Video;
import com.qavideo.domain.enums.SourceType;
import com.qavideo.domain.enums.VideoStatus;
import com.qavideo.dto.ImportUrlRequest;
import com.qavideo.dto.TaskDto;
import com.qavideo.dto.VideoBriefDto;
import com.qavideo.dto.VideoDetailDto;
import com.qavideo.dto.VideoImportResponse;
import com.qavideo.dto.VideoMapper;
import com.qavideo.media.UrlNormalizer;
import com.qavideo.mq.ParseTaskDispatcher;
import com.qavideo.mq.ParseTaskMessage;
import com.qavideo.repository.VideoRepository;
import com.qavideo.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

/**
 * Video library: import, dedup, query, delete, re-parse.
 *
 * <p>Dedup is enforced twice: a lookup in {@code video.dedup_key} answers the common
 * case cheaply, and the unique index catches the race between two concurrent imports
 * (the loser re-reads the winner's row and reports {@code duplicated=true} instead of
 * failing).
 */
@Service
public class VideoService {

    private static final Logger log = LoggerFactory.getLogger(VideoService.class);

    private final VideoRepository videoRepository;
    private final ParseTaskService taskService;
    private final ParseTaskDispatcher dispatcher;
    private final VideoMapper videoMapper;
    private final StorageService storageService;
    private final TransactionTemplate transactionTemplate;

    public VideoService(VideoRepository videoRepository,
                        ParseTaskService taskService,
                        ParseTaskDispatcher dispatcher,
                        VideoMapper videoMapper,
                        StorageService storageService,
                        TransactionTemplate transactionTemplate) {
        this.videoRepository = videoRepository;
        this.taskService = taskService;
        this.dispatcher = dispatcher;
        this.videoMapper = videoMapper;
        this.storageService = storageService;
        this.transactionTemplate = transactionTemplate;
    }

    // ------------------------------------------------------------------ import

    /** Import a link; returns the existing row when the same link was imported before. */
    public VideoImportResponse importByUrl(ImportUrlRequest request) {
        String url = request.url() == null ? "" : request.url().trim();
        if (!UrlNormalizer.isHttpUrl(url)) {
            throw BizException.badRequest("仅支持 http/https 链接导入");
        }
        String dedupKey = UrlNormalizer.dedupKey(url);

        Optional<Video> existing = videoRepository.findByDedupKey(dedupKey);
        if (existing.isPresent()) {
            log.info("duplicate link import, reusing video {} ({})", existing.get().getId(), UrlNormalizer.normalize(url));
            return existingVideoResponse(existing.get(), request.autoParseOrDefault());
        }

        Video video = new Video();
        video.setTitle(resolveTitle(request.title(), url, null));
        video.setSourceType(SourceType.URL);
        video.setSourceUrl(url);
        video.setDedupKey(dedupKey);
        video.setContentHash(UrlNormalizer.contentHash(url));
        video.setStatus(VideoStatus.IMPORTED);

        Video saved = insertIgnoringDuplicate(video);
        if (saved == null) {
            // lost the race: the other request already created this row
            return videoRepository.findByDedupKey(dedupKey)
                    .map(v -> existingVideoResponse(v, request.autoParseOrDefault()))
                    .orElseThrow(() -> BizException.internal("链接导入冲突，请重试"));
        }

        TaskDto task = request.autoParseOrDefault() ? enqueueParse(saved) : null;
        log.info("imported link {} as video {}", url, saved.getId());
        return new VideoImportResponse(detailOf(saved), false, task);
    }

    /**
     * Register an already-stored local file (used by the chunked upload flow).
     *
     * @param video     row to insert; dedupKey/contentHash must already be filled in
     * @param autoParse whether to queue parsing immediately
     */
    public VideoImportResponse registerLocalVideo(Video video, boolean autoParse) {
        Optional<Video> existing = videoRepository.findByDedupKey(video.getDedupKey());
        if (existing.isPresent()) {
            log.info("duplicate upload, reusing video {}", existing.get().getId());
            return existingVideoResponse(existing.get(), autoParse);
        }
        Video saved = insertIgnoringDuplicate(video);
        if (saved == null) {
            return videoRepository.findByDedupKey(video.getDedupKey())
                    .map(v -> existingVideoResponse(v, autoParse))
                    .orElseThrow(() -> BizException.internal("文件登记冲突，请重试"));
        }
        TaskDto task = autoParse ? enqueueParse(saved) : null;
        return new VideoImportResponse(detailOf(saved), false, task);
    }

    /**
     * @return the persisted row, or {@code null} when the unique dedup key was taken
     *         by a concurrent request
     */
    private Video insertIgnoringDuplicate(Video video) {
        try {
            return transactionTemplate.execute(status -> videoRepository.saveAndFlush(video));
        } catch (DataIntegrityViolationException ex) {
            log.info("dedup key {} already exists, falling back to the existing row", video.getDedupKey());
            return null;
        }
    }

    /** Builds the "already in the library" response, optionally queueing a (re)parse. */
    public VideoImportResponse existingVideoResponse(Video video, boolean autoParse) {
        TaskDto task = null;
        if (autoParse && video.getStatus() != VideoStatus.PARSED && !taskService.hasActiveTask(video.getId())) {
            task = enqueueParse(video);
        }
        return new VideoImportResponse(detailOf(video), true, task);
    }

    private TaskDto enqueueParse(Video video) {
        ParseTask task = taskService.createQueued(video.getId());
        ParseTaskMessage message = new ParseTaskMessage(
                task.getId(), video.getId(), task.getRequestId(), System.currentTimeMillis());
        dispatchAfterCommit(message);
        return TaskDto.from(task);
    }

    /**
     * Dispatch only once the enclosing transaction has committed - otherwise the
     * consumer could start before the job row is visible.
     */
    private void dispatchAfterCommit(ParseTaskMessage message) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safeDispatch(message);
                }
            });
        } else {
            safeDispatch(message);
        }
    }

    private void safeDispatch(ParseTaskMessage message) {
        try {
            dispatcher.dispatch(message);
        } catch (Exception ex) {
            log.error("cannot dispatch parse task {}", message.taskId(), ex);
            taskService.markFailed(message.taskId(), "任务投递失败: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ queries

    public PageResult<VideoBriefDto> list(VideoStatus status, String keyword, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        Page<Video> result = videoRepository.search(
                status,
                keyword == null || keyword.isBlank() ? null : keyword.trim(),
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt")));

        List<VideoBriefDto> items = result.getContent().stream()
                .map(video -> videoMapper.toBrief(video, taskService.findLatestForVideo(video.getId()).orElse(null)))
                .toList();
        return PageResult.of(items, result.getTotalElements(), safePage, safeSize);
    }

    public VideoDetailDto detail(Long id) {
        Video video = requireVideo(id);
        return detailOf(video);
    }

    private VideoDetailDto detailOf(Video video) {
        List<ParseTask> history = taskService.historyForVideo(video.getId());
        ParseTask latest = history.isEmpty() ? null : history.get(0);
        return videoMapper.toDetail(video, latest, history);
    }

    public Video requireVideo(Long id) {
        return videoRepository.findById(id).orElseThrow(() -> BizException.notFound("视频不存在: " + id));
    }

    public Optional<Video> findByDedupKey(String dedupKey) {
        return videoRepository.findByDedupKey(dedupKey);
    }

    public long count() {
        return videoRepository.count();
    }

    // ------------------------------------------------------------------ actions

    public VideoImportResponse reparse(Long id) {
        Video video = requireVideo(id);
        if (taskService.hasActiveTask(id)) {
            throw BizException.conflict("该视频已有正在执行的解析任务");
        }
        TaskDto task = enqueueParse(video);
        return new VideoImportResponse(detailOf(video), false, task);
    }

    public void delete(Long id) {
        Video video = requireVideo(id);
        String storagePath = video.getStoragePath();
        videoRepository.delete(video);
        if (storagePath != null) {
            storageService.deleteQuietly(storageService.resolveRelative(storagePath));
        }
        log.info("deleted video {} (file={})", id, storagePath);
    }

    private String resolveTitle(String providedTitle, String url, String fileName) {
        if (providedTitle != null && !providedTitle.isBlank()) {
            return providedTitle.trim();
        }
        if (fileName != null && !fileName.isBlank()) {
            int dot = fileName.lastIndexOf('.');
            return dot > 0 ? fileName.substring(0, dot) : fileName;
        }
        String path = url;
        int slash = path.lastIndexOf('/');
        if (slash >= 0 && slash < path.length() - 1) {
            path = path.substring(slash + 1);
        }
        int query = path.indexOf('?');
        if (query > 0) {
            path = path.substring(0, query);
        }
        return path.isBlank() ? "未命名视频" : path;
    }

    /** Exposed for UploadService so the title rule stays in one place. */
    public String titleFor(String providedTitle, String fileName) {
        return resolveTitle(providedTitle, null, fileName);
    }
}
