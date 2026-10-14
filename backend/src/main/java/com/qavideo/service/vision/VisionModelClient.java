package com.qavideo.service.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * Describes images with an OpenAI-compatible vision model.
 *
 * <p>Uses {@code POST {base-url}/chat/completions} with the multi-modal content array
 * ({@code text} + {@code image_url}). Images are inlined as {@code data:image/jpeg;base64}
 * so no public URL is required. Because the same request shape is used by OpenAI,
 * 通义千问 (兼容模式), 智谱 GLM-4V and 硅基流动, switching provider is configuration only.
 */
@Component
public class VisionModelClient {

    private static final Logger log = LoggerFactory.getLogger(VisionModelClient.class);

    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public VisionModelClient(AppProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    /**
     * @param images JPEG/PNG files to describe, in chronological order
     * @param prompt instruction for the model
     * @return the model's text answer
     */
    public String describe(List<Path> images, String prompt) {
        AppProperties.Vision vision = properties.getVision();
        if (!vision.hasApiKey()) {
            throw new IllegalStateException("视觉模型未配置 QVIDEO_VISION_API_KEY");
        }
        if (images.isEmpty()) {
            return "";
        }

        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", vision.getModel());
        root.put("temperature", 0.2);
        root.put("max_tokens", 1500);

        ArrayNode messages = root.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        ArrayNode content = message.putArray("content");

        ObjectNode textPart = content.addObject();
        textPart.put("type", "text");
        textPart.put("text", prompt);

        for (Path image : images) {
            ObjectNode imagePart = content.addObject();
            imagePart.put("type", "image_url");
            ObjectNode url = imagePart.putObject("image_url");
            url.put("url", toDataUrl(image));
        }

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(endpoint(vision.getBaseUrl()))
                    .timeout(vision.getTimeout())
                    .header("Authorization", "Bearer " + vision.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(root), StandardCharsets.UTF_8))
                    .build();
        } catch (Exception ex) {
            throw new IllegalStateException("无法构造视觉模型请求: " + ex.getMessage(), ex);
        }

        log.info("vision request: model={} images={}", vision.getModel(), images.size());

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new IllegalStateException("视觉模型调用失败: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("视觉模型调用被中断", ex);
        }

        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("视觉模型返回 HTTP " + response.statusCode()
                    + ": " + abbreviate(response.body()) + hintFor(response.statusCode(), vision.getBaseUrl()));
        }

        try {
            JsonNode node = objectMapper.readTree(response.body());
            JsonNode choices = node.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new IllegalStateException("视觉模型响应缺少 choices: " + abbreviate(response.body()));
            }
            JsonNode messageNode = choices.get(0).path("message");
            String text = messageNode.path("content").asText("");
            if (text.isBlank()) {
                // some providers return an array of content parts
                JsonNode contentNode = messageNode.path("content");
                if (contentNode.isArray()) {
                    StringBuilder sb = new StringBuilder();
                    for (JsonNode part : contentNode) {
                        sb.append(part.path("text").asText(""));
                    }
                    text = sb.toString();
                }
            }
            return text.trim();
        } catch (IOException ex) {
            throw new IllegalStateException("视觉模型响应解析失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * Turns the two mistakes everybody makes into an actionable hint:
     * a wrong model name (404) and a wrong/expired key (401).
     */
    private String hintFor(int statusCode, String baseUrl) {
        String modelsUrl = endpoint(baseUrl).toString().replace("/chat/completions", "/models");
        if (statusCode == 404) {
            return "（模型名可能已下线或不适用于该账号；用 GET " + modelsUrl
                    + " 可以列出账号当前可用的模型名，填到 QVIDEO_VISION_MODEL）";
        }
        if (statusCode == 401 || statusCode == 403) {
            return "（密钥无效或无权限，请检查 QVIDEO_VISION_API_KEY）";
        }
        return "";
    }

    private URI endpoint(String baseUrl) {        String base = baseUrl == null || baseUrl.isBlank() ? "https://api.openai.com/v1" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + "/chat/completions");
    }

    private String toDataUrl(Path image) {
        try {
            byte[] bytes = Files.readAllBytes(image);
            String mime = image.getFileName().toString().toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg";
            return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (IOException ex) {
            throw new IllegalStateException("无法读取画面文件 " + image + ": " + ex.getMessage(), ex);
        }
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "null";
        }
        return value.length() <= 300 ? value : value.substring(0, 300) + "...";
    }
}
