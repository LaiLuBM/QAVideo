package com.qavideo.common;

import java.util.List;

/**
 * Paged payload used by the video library endpoint.
 */
public record PageResult<T>(List<T> items, long total, int page, int size, int totalPages) {

    public static <T> PageResult<T> of(List<T> items, long total, int page, int size) {
        int totalPages = size <= 0 ? 0 : (int) Math.ceil((double) total / size);
        return new PageResult<>(items, total, page, size, totalPages);
    }
}
