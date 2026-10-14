package com.qavideo.controller;

import com.qavideo.common.ApiResponse;
import com.qavideo.dto.UploadChunkResponse;
import com.qavideo.dto.UploadCompleteResponse;
import com.qavideo.dto.UploadInitRequest;
import com.qavideo.dto.UploadInitResponse;
import com.qavideo.dto.UploadSessionDto;
import com.qavideo.service.UploadService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Resumable chunked upload API. See {@link UploadService} for the protocol.
 */
@RestController
@RequestMapping("/api/uploads")
public class UploadController {

    private final UploadService uploadService;

    public UploadController(UploadService uploadService) {
        this.uploadService = uploadService;
    }

    /** Creates a session, or resumes one when {@code uploadId} is supplied. */
    @PostMapping("/init")
    public ApiResponse<UploadInitResponse> init(@Valid @RequestBody UploadInitRequest request) {
        return ApiResponse.ok(uploadService.init(request));
    }

    /** Breakpoint-resume query: which chunks does the server already hold? */
    @GetMapping("/{uploadId}")
    public ApiResponse<UploadSessionDto> session(@PathVariable String uploadId) {
        return ApiResponse.ok(uploadService.session(uploadId));
    }

    /** Uploads one chunk. Re-sending the same index is safe (idempotent overwrite). */
    @PostMapping("/{uploadId}/chunks/{index}")
    public ApiResponse<UploadChunkResponse> uploadChunk(@PathVariable String uploadId,
                                                        @PathVariable int index,
                                                        @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(uploadService.saveChunk(uploadId, index, file));
    }

    /** Merge + sha256 + dedup + register + enqueue parse. */
    @PostMapping("/{uploadId}/complete")
    public ApiResponse<UploadCompleteResponse> complete(@PathVariable String uploadId) {
        return ApiResponse.ok(uploadService.complete(uploadId));
    }

    @DeleteMapping("/{uploadId}")
    public ApiResponse<Void> cancel(@PathVariable String uploadId) {
        uploadService.cancel(uploadId);
        return ApiResponse.ok();
    }
}
