package com.qavideo.media;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UrlNormalizerTest {

    @Test
    void removesTrackingParametersAndFragment() {
        String normalized = UrlNormalizer.normalize(
                "https://www.YouTube.com/watch?v=abc123&utm_source=wechat&spm=a1b2&fbclid=xyz#t=30");
        assertThat(normalized).isEqualTo("https://www.youtube.com/watch?v=abc123");
    }

    @Test
    void isCaseInsensitiveForSchemeAndHost() {
        assertThat(UrlNormalizer.normalize("HTTP://Example.COM/Path"))
                .isEqualTo(UrlNormalizer.normalize("http://example.com/Path"));
    }

    @Test
    void ignoresParameterOrderAndTrailingSlash() {
        String a = UrlNormalizer.normalize("https://v.qq.com/x/cover/a1b2.html/?cid=1&vid=2");
        String b = UrlNormalizer.normalize("https://v.qq.com/x/cover/a1b2.html?vid=2&cid=1");
        assertThat(a).isEqualTo(b).isEqualTo("https://v.qq.com/x/cover/a1b2.html?cid=1&vid=2");
    }

    @Test
    void sameVideoDifferentTrackingYieldsSameDedupKey() {
        String first = UrlNormalizer.dedupKey("https://www.bilibili.com/video/BV1xx411c7mD?spm_id_from=333.999#reply");
        String second = UrlNormalizer.dedupKey("https://www.bilibili.com/video/BV1xx411c7mD");
        assertThat(first).isEqualTo(second).startsWith("url:");
    }

    @Test
    void detectsHttpUrlsOnly() {
        assertThat(UrlNormalizer.isHttpUrl("https://example.com/a.mp4")).isTrue();
        assertThat(UrlNormalizer.isHttpUrl("ftp://example.com/a.mp4")).isFalse();
        assertThat(UrlNormalizer.isHttpUrl("D:\\videos\\a.mp4")).isFalse();
        assertThat(UrlNormalizer.isHttpUrl(null)).isFalse();
    }
}
