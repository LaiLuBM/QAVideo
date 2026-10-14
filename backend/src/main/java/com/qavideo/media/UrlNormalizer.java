package com.qavideo.media;

import com.qavideo.common.Hashing;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.TreeSet;

/**
 * URL canonicalisation for duplicate detection.
 *
 * <p>Two links that differ only by tracking parameters, fragment, case in the host or
 * parameter order are the same video and must map to the same dedup key.
 */
public final class UrlNormalizer {

    private static final String[] TRACKING_PARAM_PREFIXES = {"utm_", "spm", "from_", "share_", "vd_source"};
    private static final String[] TRACKING_PARAMS = {
            "fbclid", "gclid", "msclkid", "ref", "refer", "referer", "share_source",
            "share_medium", "share_plat", "share_tag", "timestamp", "unique_k", "buvid"
    };

    private UrlNormalizer() {
    }

    /** @return canonical form, or the trimmed input when it cannot be parsed. */
    public static String normalize(String rawUrl) {
        if (rawUrl == null) {
            return "";
        }
        String trimmed = rawUrl.trim();
        try {
            URI uri = new URI(trimmed);
            String scheme = uri.getScheme() == null ? "https" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            String path = uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath();
            if (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            String query = canonicalQuery(uri.getRawQuery());
            StringBuilder sb = new StringBuilder();
            sb.append(scheme).append("://").append(host);
            if (port > 0 && port != defaultPort(scheme)) {
                sb.append(':').append(port);
            }
            sb.append(path);
            if (!query.isEmpty()) {
                sb.append('?').append(query);
            }
            return sb.toString();
        } catch (URISyntaxException ex) {
            return trimmed;
        }
    }

    /** Dedup key for a link: {@code url:<sha256(normalized)>}. */
    public static String dedupKey(String rawUrl) {
        return "url:" + Hashing.sha256Hex(normalize(rawUrl));
    }

    public static String contentHash(String rawUrl) {
        return Hashing.sha256Hex(normalize(rawUrl));
    }

    public static boolean isHttpUrl(String rawUrl) {
        if (rawUrl == null) {
            return false;
        }
        String lower = rawUrl.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static String canonicalQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return "";
        }
        TreeSet<String> kept = new TreeSet<>();
        for (String pair : rawQuery.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            String key = pair.contains("=") ? pair.substring(0, pair.indexOf('=')) : pair;
            String lowerKey = key.toLowerCase(Locale.ROOT);
            if (isTracking(lowerKey)) {
                continue;
            }
            kept.add(decode(pair));
        }
        return String.join("&", kept);
    }

    private static boolean isTracking(String lowerKey) {
        if (Arrays.stream(TRACKING_PARAMS).anyMatch(lowerKey::equals)) {
            return true;
        }
        return Arrays.stream(TRACKING_PARAM_PREFIXES).anyMatch(lowerKey::startsWith);
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return value;
        }
    }

    private static int defaultPort(String scheme) {
        return "http".equals(scheme) ? 80 : 443;
    }
}
