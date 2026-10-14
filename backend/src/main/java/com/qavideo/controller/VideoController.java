package com.qavideo.controller;

import com.qavideo.common.ApiResponse;
import com.qavideo.common.BizException;
import com.qavideo.common.PageResult;
import com.qavideo.domain.Video;
import com.qavideo.domain.enums.VideoStatus;
import com.qavideo.dto.ImportUrlRequest;
import com.qavideo.dto.TaskDto;
import com.qavideo.dto.VideoBriefDto;
import com.qavideo.dto.VideoDetailDto;
import com.qavideo.dto.VideoImportResponse;
import com.qavideo.service.ParseTaskService;
import com.qavideo.service.VideoService;
import com.qavideo.storage.RangeInputStream;
import com.qavideo.storage.StorageService;
import jakarta.validation.Valid;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

@RestController
@RequestMapping("/api/videos")
public class VideoController {

    private final VideoService videoService;
    private final ParseTaskService taskService;
    private final StorageService storageService;

    public VideoController(VideoService videoService,
                           ParseTaskService taskService,
                           StorageService storageService) {
        this.videoService = videoService;
        this.taskService = taskService;
        this.storageService = storageService;
    }

    /**
     * Import a link. Returns immediately with a PENDING task unless {@code autoParse=false};
     * the heavy work happens asynchronously (RocketMQ / worker pool).
     */
    @PostMapping("/import-url")
    public ApiResponse<VideoImportResponse> importUrl(@Valid @RequestBody ImportUrlRequest request) {
        return ApiResponse.ok(videoService.importByUrl(request));
    }

    /** Video library, newest first. */
    @GetMapping
    public ApiResponse<PageResult<VideoBriefDto>> list(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "12") int size,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "keyword", required = false) String keyword) {
        VideoStatus videoStatus = parseStatus(status);
        return ApiResponse.ok(videoService.list(videoStatus, keyword, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<VideoDetailDto> detail(@PathVariable Long id) {
        return ApiResponse.ok(videoService.detail(id));
    }

    @GetMapping("/{id}/tasks")
    public ApiResponse<List<TaskDto>> tasks(@PathVariable Long id) {
        videoService.requireVideo(id);
        return ApiResponse.ok(taskService.historyForVideo(id).stream().map(TaskDto::from).toList());
    }

    /** Re-run the AI analysis (e.g. after .srt was added, or with a different model). */
    @PostMapping("/{id}/parse")
    public ApiResponse<VideoImportResponse> reparse(@PathVariable Long id) {
        return ApiResponse.ok(videoService.reparse(id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        videoService.delete(id);
        return ApiResponse.ok();
    }

    /**
     * Streams the stored media file. Supports HTTP Range so the HTML5 player can seek.
     *
     * <p>Range responses are streamed through {@link com.qavideo.storage.RangeInputStream}
     * rather than through Spring's {@code ResourceRegion}: the latter's converter only
     * accepts {@code application/octet-stream}, so pairing it with a real video MIME type
     * made every seeking request fail with HTTP 500.
     */
    @GetMapping("/{id}/stream")
    public ResponseEntity<Resource> stream(@PathVariable Long id,
                                           @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader)
            throws IOException {
        Video video = videoService.requireVideo(id);
        if (video.getStoragePath() == null) {
            throw BizException.notFound("该视频没有任何本地媒体文件（链接导入且未下载）");
        }
        Resource resource = storageService.asResource(video.getStoragePath());
        long length = resource.contentLength();
        MediaType mediaType = resolveMediaType(video, resource);

        if (rangeHeader == null || rangeHeader.isBlank()) {
            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .contentLength(length)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .body(resource);
        }

        List<HttpRange> ranges = HttpRange.parseRanges(rangeHeader);
        HttpRange range = ranges.get(0);
        long start = range.getRangeStart(length);
        long end = range.getRangeEnd(length);
        long regionLength = end - start + 1;

        Path file = storageService.resolveRelative(video.getStoragePath());
        InputStream region = RangeInputStream.of(file, start, regionLength);

        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .contentType(mediaType)
                .contentLength(regionLength)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + length)
                .body(new InputStreamResource(region));
    }

    private MediaType resolveMediaType(Video video, Resource resource) {
        if (video.getContentType() != null && !video.getContentType().isBlank()) {
            try {
                return MediaType.parseMediaType(video.getContentType());
            } catch (Exception ignored) {
                // fall through to extension based detection
            }
        }
        return MediaTypeFactory.getMediaType(resource).orElse(MediaType.APPLICATION_OCTET_STREAM);
    }

    private VideoStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return VideoStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw BizException.badRequest("未知的状态: " + status);
        }
    }
}
