package com.qavideo.service.vision;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies what we actually send to a vision model: an OpenAI-compatible
 * {@code /chat/completions} request whose content array carries the text prompt plus one
 * {@code data:image/jpeg;base64,...} part per frame - i.e. the model "sees" the frames.
 */
class VisionModelClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void sendsFramesAsBase64ImagePartsAndReadsTheAnswer() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, """
                    {"choices":[{"message":{"role":"assistant",
                     "content":"画面1：游戏中角色在屋顶跳跃\\n画面2：屏幕右上角显示比分 5:3"}}]}
                    """);
        });
        server.start();

        Path frameOne = Files.createTempFile("frame", ".jpg");
        Path frameTwo = Files.createTempFile("frame", ".jpg");
        try {
            Files.write(frameOne, "JPEG-ONE".getBytes(StandardCharsets.UTF_8));
            Files.write(frameTwo, "JPEG-TWO".getBytes(StandardCharsets.UTF_8));

            String answer = client(server, "vlm-key")
                    .describe(List.of(frameOne, frameTwo), "请描述这些画面");

            assertThat(answer).contains("画面1").contains("比分 5:3");
            assertThat(authorization.get()).isEqualTo("Bearer vlm-key");

            JsonNode request = MAPPER.readTree(requestBody.get());
            assertThat(request.path("model").asText()).isEqualTo("qwen-vl-plus");

            JsonNode content = request.path("messages").get(0).path("content");
            assertThat(content.isArray()).isTrue();
            assertThat(content.get(0).path("type").asText()).isEqualTo("text");
            assertThat(content.get(0).path("text").asText()).isEqualTo("请描述这些画面");

            for (int index = 1; index <= 2; index++) {
                JsonNode imagePart = content.get(index);
                assertThat(imagePart.path("type").asText()).isEqualTo("image_url");
                assertThat(imagePart.path("image_url").path("url").asText())
                        .as("画面必须以 base64 data URL 内联，不能只发文件名")
                        .startsWith("data:image/jpeg;base64,");
            }
            assertThat(new String(java.util.Base64.getDecoder().decode(
                    content.get(1).path("image_url").path("url").asText().split(",")[1]), StandardCharsets.UTF_8))
                    .isEqualTo("JPEG-ONE");
        } finally {
            server.stop(0);
            Files.deleteIfExists(frameOne);
            Files.deleteIfExists(frameTwo);
        }
    }

    @Test
    void surfacesProviderErrors() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange ->
                respond(exchange, 400, "{\"error\":{\"message\":\"model does not support images\"}}"));
        server.start();

        Path frame = Files.createTempFile("frame", ".jpg");
        try {
            Files.write(frame, "JPEG".getBytes(StandardCharsets.UTF_8));
            assertThatThrownBy(() -> client(server, "key").describe(List.of(frame), "描述"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("400")
                    .hasMessageContaining("does not support images");
        } finally {
            server.stop(0);
            Files.deleteIfExists(frame);
        }
    }

    private VisionModelClient client(HttpServer server, String apiKey) {
        AppProperties properties = new AppProperties();
        properties.getVision().setProvider("openai");
        properties.getVision().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        properties.getVision().setApiKey(apiKey);
        properties.getVision().setModel("qwen-vl-plus");
        return new VisionModelClient(properties, new ObjectMapper());
    }

    private void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }
}
