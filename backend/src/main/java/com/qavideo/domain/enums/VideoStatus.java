package com.qavideo.domain.enums;

/** Lifecycle of a video row. */
public enum VideoStatus {
    /** Row exists, nothing parsed yet. */
    IMPORTED,
    /** A parse task is running. */
    PARSING,
    /** Summary + transcript available. */
    PARSED,
    /** Last parse attempt failed; errorMessage on the task explains why. */
    FAILED
}
