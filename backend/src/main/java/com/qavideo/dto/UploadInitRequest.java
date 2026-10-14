package com.qavideo.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/uploads/init}.
 *
 * <p>Re-send the same {@code uploadId} (the front-end keeps it in localStorage) to
 * resume an interrupted transfer: the server answers with the chunks it already has.
 * Supplying {@code contentHash} lets the server short-circuit a duplicate before any
 * byte is transferred.
 */
public record UploadInitRequest(
        @NotBlank(message = "文件名不能为空")
        @Size(max = 512, message = "文件名过长")
        String fileName,

        @Min(value = 1, message = "文件大小必须大于 0")
        long fileSize,

        @Size(max = 128)
        String contentType,

        /** Optional; server decides when omitted. */
        Long chunkSize,

        /** Optional; present -> resume that session. */
        String uploadId,

        @Size(max = 512)
        String title,

        /** Optional sha256 of the whole file (the browser can compute it for big files). */
        @Size(max = 64)
        String contentHash,

        Boolean autoParse
) {
    public boolean autoParseOrDefault() {
        return autoParse == null || autoParse;
    }
}
