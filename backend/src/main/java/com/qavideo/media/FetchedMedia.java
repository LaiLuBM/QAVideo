package com.qavideo.media;

import java.nio.file.Path;

/**
 * A media file pulled from the network, parked in the upload temp area until the
 * pipeline moves it into content-addressed storage.
 */
public record FetchedMedia(Path tempFile, String contentType, long size, String resolvedUrl, String extension) {
}
