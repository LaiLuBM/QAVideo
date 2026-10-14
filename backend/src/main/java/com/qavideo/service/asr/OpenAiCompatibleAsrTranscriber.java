package com.qavideo.service.asr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qavideo.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

/**
 * Uploads the media file to an OpenAI-compatible transcription endpoint.
 *
 * <p>The file is sent <b>as-is</b>: Whisper's API accepts {@code mp4/mpeg/mp4a/m4a/wav/webm}
 * and extracts the audio track server-side, so there is no manual "split the audio out of
 * the video" step.
 *
 * <p>The body is assembled with {@link HttpRequest.BodyPublishers#concat} so the file is
 * streamed from disk (no full copy in heap) while still reporting a {@code Content-Length},
 * which most gateways require for multipart uploads.
 */
@Component
public class OpenAiCompatibleAsrTranscriber implements AsrTranscriber {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleAsrTranscriber.class);

    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenAiCompatibleAsrTranscriber(AppProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    @Override
    public String provider() {
        return "openai";
    }

    @Override
    public AsrResult transcribe(TranscriptionRequest request) {
        AppProperties.Asr asr = properties.getAsr();
        if (!asr.hasApiKey()) {
            throw new IllegalStateException("ASR provider=openai 但未配置 QVIDEO_ASR_API_KEY");
        }

        Path media = request.mediaFile();
        String extension = extensionOf(request.fileName());
        String boundary = "----QAVideoBoundary" + UUID.randomUUID().toString().replace("-", "");
        String contentType = guessContentType(extension);

        String preamble = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"qavideo-media" + extension + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "\r\n";

        StringBuilder epilogue = new StringBuilder("\r\n");
        epilogue.append(part(boundary, "model", asr.getModel()));
        if (asr.getLanguage() != null && !asr.getLanguage().isBlank()) {
            epilogue.append(part(boundary, "language", asr.getLanguage()));
        }
        epilogue.append(part(boundary, "response_format", "json"));
        epilogue.append("--").append(boundary).append("--\r\n");

        HttpRequest.BodyPublisher body;
        try {
            // streamed from disk (no heap copy of a 25 MB+ file) while still reporting Content-Length
            body = HttpRequest.BodyPublishers.concat(
                    HttpRequest.BodyPublishers.ofByteArray(preamble.getBytes(StandardCharsets.UTF_8)),
                    HttpRequest.BodyPublishers.ofFile(media),
                    HttpRequest.BodyPublishers.ofByteArray(epilogue.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (IOException ex) {
            throw new IllegalStateException("无法读取媒体文件 " + media + ": " + ex.getMessage(), ex);
        }

        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint(asr.getBaseUrl()))
                .timeout(asr.getTimeout())
                .header("Authorization", "Bearer " + asr.getApiKey())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(body)
                .build();

        log.info("ASR request: provider=openai model={} file={} bytes={}",
                asr.getModel(), request.fileName(), sizeOf(media));

        HttpResponse<String> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new IllegalStateException("ASR 调用失败: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ASR 调用被中断", ex);
        }

        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("ASR 返回 HTTP " + response.statusCode() + ": " + abbreviate(response.body()));
        }

        try {
            JsonNode node = objectMapper.readTree(response.body());
            String text = node.path("text").asText("");
            if (text.isBlank()) {
                throw new IllegalStateException("ASR 响应里没有 text 字段: " + abbreviate(response.body()));
            }
            String language = node.path("language").asText(asr.getLanguage());
            log.info("ASR done: provider=openai chars={} language={}", text.length(), language);
            return new AsrResult(text.trim(), language, provider());
        } catch (IOException ex) {
            throw new IllegalStateException("ASR 响应解析失败: " + ex.getMessage(), ex);
        }
    }

    private URI endpoint(String baseUrl) {
        String base = baseUrl == null || baseUrl.isBlank() ? "https://api.openai.com/v1" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + "/audio/transcriptions");
    }

    private String part(String boundary, String name, String value) {
        return "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n"
                + "\r\n"
                + value + "\r\n";
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return ".mp4";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return ".mp4";
        }
        String ext = fileName.substring(dot).toLowerCase(Locale.ROOT);
        return ext.length() > 8 ? ".mp4" : ext;
    }

    private static String guessContentType(String extension) {
        return switch (extension) {
            case ".mp4", ".m4v" -> "video/mp4";
            case ".webm" -> "video/webm";
            case ".mov" -> "video/quicktime";
            case ".mkv" -> "video/x-matroska";
            case ".mp3" -> "audio/mpeg";
            case ".m4a" -> "audio/mp4";
            case ".wav" -> "audio/wav";
            case ".flac" -> "audio/flac";
            case ".ogg", ".opus" -> "audio/ogg";
            default -> "application/octet-stream";
        };
    }

    private static long sizeOf(Path file) {
        try {
            return java.nio.file.Files.size(file);
        } catch (IOException ex) {
            return -1L;
        }
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "null";
        }
        return value.length() <= 300 ? value : value.substring(0, 300) + "...";
    }
}
