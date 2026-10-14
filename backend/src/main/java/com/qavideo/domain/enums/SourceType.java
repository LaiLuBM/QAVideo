package com.qavideo.domain.enums;

/** How the video entered the platform. */
public enum SourceType {
    /** Imported from a link (page URL or direct media URL). */
    URL,
    /** Uploaded from the local disk, chunk by chunk. */
    LOCAL
}
