package com.qavideo.service.asr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qavideo.config.AppProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the ASR request/response handling against a local fake endpoint, so no real
 * API key is needed and the assertions are about what we actually send:
 * the <b>video file itself</b> (Whisper demuxes the audio server-side), the model field,
 * the bearer token, and a real multipart Content-Length.
 */
class OpenAiCompatibleAsrTranscriberTest {

    @Test
    void uploadsTheVideoFileAsIsAndParsesTheTranscript() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> contentLength = new AtomicReference<>();
        AtomicReference<byte[]> body = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/audio/transcriptions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            contentLength.set(exchange.getRequestHeaders().getFirst("Content-Length"));
            body.set(exchange.getRequestBody().readAllBytes());
            respond(exchange, 200, "{\"text\":\"这是真实字幕内容\",\"language\":\"zh\"}");
        });
        server.start();

        Path media = Files.createTempFile("qavideo-asr-test", ".mp4");
        try {
            byte[] mediaBytes = "FAKE-VIDEO-BYTES-0123456789".getBytes(StandardCharsets.UTF_8);
            Files.write(media, mediaBytes);

            AsrResult result = transcriber(server, "test-key")
                    .transcribe(new TranscriptionRequest(media, "demo.mp4", 120));

            assertThat(result.text()).isEqualTo("这是真实字幕内容");
            assertThat(result.language()).isEqualTo("zh");
            assertThat(result.provider()).isEqualTo("openai");

            assertThat(authorization.get()).isEqualTo("Bearer test-key");
            assertThat(contentType.get()).startsWith("multipart/form-data; boundary=");

            String multipart = new String(body.get(), StandardCharsets.ISO_8859_1);
            assertThat(multipart)
                    .as("必须原样上传视频文件，由 ASR 引擎自己解出音轨（不需要先分离音频）")
                    .contains("FAKE-VIDEO-BYTES-0123456789");
            assertThat(multipart).contains("name=\"file\"; filename=\"qavideo-media.mp4\"");
            assertThat(multipart).contains("Content-Type: video/mp4");
            assertThat(multipart).contains("name=\"model\"").contains("whisper-1");
            assertThat(multipart).contains("name=\"language\"").contains("zh");
            assertThat(multipart).endsWith("--\r\n");

            assertThat(contentLength.get())
                    .as("multipart 必须带 Content-Length（流式拼装但长度已知），否则网关会拒绝")
                    .isEqualTo(String.valueOf(body.get().length));
        } finally {
            server.stop(0);
            Files.deleteIfExists(media);
        }
    }

    @Test
    void reportsTheUpstreamErrorBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/audio/transcriptions", exchange ->
                respond(exchange, 401, "{\"error\":{\"message\":\"Incorrect API key provided\"}}"));
        server.start();

        Path media = Files.createTempFile("qavideo-asr-test", ".mp4");
        try {
            Files.write(media, "bytes".getBytes(StandardCharsets.UTF_8));
            assertThatThrownBy(() -> transcriber(server, "bad-key")
                    .transcribe(new TranscriptionRequest(media, "demo.mp4", null)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("401")
                    .hasMessageContaining("Incorrect API key");
        } finally {
            server.stop(0);
            Files.deleteIfExists(media);
        }
    }

    @Test
    void refusesToCallWithoutAnApiKey() throws Exception {
        AppProperties properties = new AppProperties();
        properties.getAsr().setProvider("openai");
        properties.getAsr().setApiKey("");

        Path media = Files.createTempFile("qavideo-asr-test", ".mp4");
        try {
            assertThatThrownBy(() -> new OpenAiCompatibleAsrTranscriber(properties, new ObjectMapper())
                    .transcribe(new TranscriptionRequest(media, "demo.mp4", null)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("QVIDEO_ASR_API_KEY");
        } finally {
            Files.deleteIfExists(media);
        }
    }

    private OpenAiCompatibleAsrTranscriber transcriber(HttpServer server, String apiKey) {
        AppProperties properties = new AppProperties();
        properties.getAsr().setProvider("openai");
        properties.getAsr().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        properties.getAsr().setApiKey(apiKey);
        properties.getAsr().setModel("whisper-1");
        properties.getAsr().setLanguage("zh");
        return new OpenAiCompatibleAsrTranscriber(properties, new ObjectMapper());
    }

    private void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }
}
