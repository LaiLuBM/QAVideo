package com.qavideo.common;

import org.springframework.http.HttpStatus;

/**
 * Business failure carrying an application error code and the HTTP status to answer with.
 */
public class BizException extends RuntimeException {

    private final int code;
    private final HttpStatus status;

    public BizException(int code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public int getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static BizException badRequest(String message) {
        return new BizException(40000, message, HttpStatus.BAD_REQUEST);
    }

    public static BizException notFound(String message) {
        return new BizException(40400, message, HttpStatus.NOT_FOUND);
    }

    public static BizException conflict(String message) {
        return new BizException(40900, message, HttpStatus.CONFLICT);
    }

    public static BizException upstream(String message) {
        return new BizException(50200, message, HttpStatus.BAD_GATEWAY);
    }

    public static BizException internal(String message) {
        return new BizException(50000, message, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
