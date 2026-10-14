package com.qavideo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/videos/import-url}.
 */
public record ImportUrlRequest(
        @NotBlank(message = "视频链接不能为空")
        @Size(max = 1024, message = "链接过长")
        String url,

        @Size(max = 512, message = "标题过长")
        String title,

        /** null/true -> enqueue the parse job right away. */
        Boolean autoParse
) {
    public boolean autoParseOrDefault() {
        return autoParse == null || autoParse;
    }
}
