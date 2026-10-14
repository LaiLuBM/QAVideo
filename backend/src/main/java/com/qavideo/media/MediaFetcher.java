package com.qavideo.media;

import com.qavideo.config.AppProperties;
import com.qavideo.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Downloads direct media URLs so their audio can be transcribed.
 *
 * <p>Scope is deliberately narrow: only URLs that <em>are</em> the media file
 * ({@code .mp4}, {@code .webm}, ...) or that answer with a {@code video/*} /
 * {@code audio/*} content type are fetched. Page URLs (YouTube / Bilibili / 抖音) are
 * not scraped here - the pipeline summarises those from link metadata instead. Wiring a
 * real extractor (yt-dlp / 官方开放平台) is a matter of returning a file from
 * {@link #fetch}; nothing else in the pipeline changes.
 */
@Component
public class MediaFetcher {

    private static final Logger log = LoggerFactory.getLogger(MediaFetcher.class);

    private static final Set<String> MEDIA_EXTENSIONS = Set.of(
            ".mp4", ".m4v", ".webm", ".mov", ".mkv", ".avi", ".flv", ".wmv",
            ".mp3", ".m4a", ".aac", ".wav", ".flac", ".ogg", ".opus");

    /** HLS/DASH manifests are not a single file - not handled by this fetcher. */
    private static final Set<String> MANIFEST_EXTENSIONS = Set.of(".m3u8", ".mpd", ".ts");

    private final AppProperties properties;
    private final StorageService storageService;
    private final HttpClient httpClient;

    public MediaFetcher(AppProperties properties, StorageService storageService) {
        this.properties = properties;
        this.storageService = storageService;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(properties.getMedia().getConnectTimeout())
                .build();
    }

    public boolean isDirectMediaUrl(String url) {
        if (url == null) {
            return false;
        }
        String path = pathOf(url);
        if (MANIFEST_EXTENSIONS.stream().anyMatch(path::endsWith)) {
            return false;
        }
        return MEDIA_EXTENSIONS.stream().anyMatch(path::endsWith);
    }

    /**
     * Streams the URL into the upload temp directory.
     *
     * @return the downloaded file, or empty when the URL is not a direct media link
     */
    public Optional<FetchedMedia> fetch(String url, String key) {
        if (!properties.getMedia().isDownloadEnabled() || !isDirectMediaUrl(url)) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException ex) {
            return Optional.empty();
        }

        String extension = storageService.extensionOf(pathOf(url));
        Path target = storageService.getTempRoot().resolve("remote-" + key + "-" + System.nanoTime() + extension)
                .normalize();

        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(properties.getMedia().getReadTimeout())
                    .header("User-Agent", properties.getMedia().getUserAgent())
                    .header("Accept", "*/*")
                    .GET()
                    .build();

            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                closeQuietly(response.body());
                log.warn("download of {} failed with HTTP {}", url, response.statusCode());
                return Optional.empty();
            }

            String contentType = response.headers().firstValue("content-type").orElse("");
            long declared = response.headers().firstValueAsLong("content-length").orElse(-1L);
            long max = properties.getMedia().getMaxDownloadSize();
            if (declared > max) {
                closeQuietly(response.body());
                log.warn("download of {} rejected: {} bytes exceeds limit {}", url, declared, max);
                return Optional.empty();
            }

            long written;
            try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(target)) {
                written = copyWithLimit(in, out, max);
            }
            if (written < 0) {
                storageService.deleteQuietly(target);
                log.warn("download of {} aborted: exceeded {} bytes", url, max);
                return Optional.empty();
            }
            log.info("downloaded {} ({} bytes, {}) to {}", url, written, contentType, target.getFileName());
            return Optional.of(new FetchedMedia(target, contentType, written, url, extension));
        } catch (IOException ex) {
            storageService.deleteQuietly(target);
            log.warn("download of {} failed: {}", url, ex.getMessage());
            return Optional.empty();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            storageService.deleteQuietly(target);
            return Optional.empty();
        }
    }

    /** @return bytes copied, or -1 when the limit was hit */
    private long copyWithLimit(InputStream in, OutputStream out, long limit) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0L;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > limit) {
                return -1L;
            }
            out.write(buffer, 0, read);
        }
        return total;
    }

    private String pathOf(String url) {
        int q = url.indexOf('?');
        String withoutQuery = q >= 0 ? url.substring(0, q) : url;
        int hash = withoutQuery.indexOf('#');
        if (hash >= 0) {
            withoutQuery = withoutQuery.substring(0, hash);
        }
        int slash = withoutQuery.lastIndexOf('/');
        String last = slash >= 0 ? withoutQuery.substring(slash + 1) : withoutQuery;
        return last.toLowerCase(Locale.ROOT);
    }

    private void closeQuietly(InputStream stream) {
        try {
            if (stream != null) {
                stream.close();
            }
        } catch (IOException ignored) {
            // nothing useful to do
        }
    }
}
