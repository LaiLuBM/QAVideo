package com.qavideo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qavideo.common.Hashing;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end check of the contract the Vue front-end depends on:
 * init -> resume -> upload chunks -> complete -> async parse -> summary -> dedup.
 *
 * <p>Runs on the {@code standalone} profile (H2 + in-memory upload state + local task
 * executor + offline summarizer), so no external middleware is required in CI.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("standalone")
@TestPropertySource(properties = {
        "qavideo.storage.root=./target/test-data/storage",
        "qavideo.storage.temp-root=./target/test-data/uploads"
})
class UploadFlowIntegrationTest {

    private static final int CHUNK_SIZE = 1024;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void resumableUploadIsParsedAndDeduplicated() throws Exception {
        byte[] data = new byte[CHUNK_SIZE * 3 + 100];
        new Random(42).nextBytes(data);
        String contentHash = Hashing.sha256Hex(data);

        // ---------- 1) init -------------------------------------------------
        JsonNode init = postJson("/api/uploads/init", body(
                "fileName", "integration-demo.mp4",
                "fileSize", data.length,
                "contentType", "video/mp4",
                "chunkSize", CHUNK_SIZE,
                "title", "集成测试视频",
                "autoParse", true)).path("data");

        String uploadId = init.path("uploadId").asText();
        int totalChunks = init.path("totalChunks").asInt();
        assertThat(uploadId).isNotBlank();
        assertThat(totalChunks).isEqualTo(4);
        assertThat(init.path("uploadedChunks")).isEmpty();

        // ---------- 2) upload one chunk, then resume ------------------------
        uploadChunk(uploadId, 0, data, 0);

        JsonNode resumed = postJson("/api/uploads/init", body(
                "fileName", "integration-demo.mp4",
                "fileSize", data.length,
                "chunkSize", CHUNK_SIZE,
                "uploadId", uploadId)).path("data");

        assertThat(resumed.path("resumed").asBoolean()).isTrue();
        assertThat(resumed.path("uploadId").asText()).isEqualTo(uploadId);
        assertThat(resumed.path("uploadedChunks")).hasSize(1);
        assertThat(resumed.path("uploadedChunks").get(0).asInt()).isZero();

        for (int index = 1; index < totalChunks; index++) {
            uploadChunk(uploadId, index, data, index);
        }

        JsonNode session = getJson("/api/uploads/" + uploadId).path("data");
        assertThat(session.path("progress").asInt()).isEqualTo(100);

        // ---------- 3) complete -> video row + parse task --------------------
        JsonNode complete = postJson("/api/uploads/" + uploadId + "/complete", null).path("data");
        assertThat(complete.path("contentHash").asText()).isEqualTo(contentHash);
        assertThat(complete.path("mergedChunks").asInt()).isEqualTo(totalChunks);

        JsonNode result = complete.path("result");
        assertThat(result.path("duplicated").asBoolean()).isFalse();
        long videoId = result.path("video").path("id").asLong();
        long taskId = result.path("task").path("id").asLong();
        assertThat(videoId).isPositive();
        assertThat(taskId).isPositive();

        // ---------- 4) wait for the async pipeline ---------------------------
        JsonNode task = awaitTask(taskId);
        assertThat(task.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(task.path("progress").asInt()).isEqualTo(100);
        assertThat(task.path("mockAi").asBoolean()).isTrue();

        JsonNode video = getJson("/api/videos/" + videoId).path("data");
        assertThat(video.path("status").asText()).isEqualTo("PARSED");
        assertThat(video.path("summary").asText()).isNotBlank();
        assertThat(video.path("transcript").asText()).isNotBlank();
        assertThat(video.path("keywords")).isNotEmpty();
        assertThat(video.path("sourceType").asText()).isEqualTo("LOCAL");

        // ---------- 5) the same file is rejected as duplicate ----------------
        JsonNode duplicate = postJson("/api/uploads/init", body(
                "fileName", "integration-demo.mp4",
                "fileSize", data.length,
                "chunkSize", CHUNK_SIZE,
                "contentHash", contentHash)).path("data");

        assertThat(duplicate.path("duplicate").isMissingNode()).isFalse();
        assertThat(duplicate.path("duplicate").path("duplicated").asBoolean()).isTrue();
        assertThat(duplicate.path("duplicate").path("video").path("id").asLong()).isEqualTo(videoId);

        // and a full re-upload resolves to the very same row
        JsonNode secondInit = postJson("/api/uploads/init", body(
                "fileName", "integration-demo-copy.mp4",
                "fileSize", data.length,
                "chunkSize", CHUNK_SIZE)).path("data");
        String secondUploadId = secondInit.path("uploadId").asText();
        for (int index = 0; index < secondInit.path("totalChunks").asInt(); index++) {
            uploadChunk(secondUploadId, index, data, index);
        }
        JsonNode secondComplete = postJson("/api/uploads/" + secondUploadId + "/complete", null).path("data");
        assertThat(secondComplete.path("result").path("duplicated").asBoolean()).isTrue();
        assertThat(secondComplete.path("result").path("video").path("id").asLong()).isEqualTo(videoId);

        // ---------- 6) library listing contains it ---------------------------
        JsonNode page = getJson("/api/videos?page=0&size=10").path("data");
        assertThat(page.path("total").asLong()).isGreaterThanOrEqualTo(1);

        JsonNode status = getJson("/api/system/status").path("data");
        assertThat(status.path("aiMockMode").asBoolean()).isTrue();
        assertThat(status.path("extras").path("uploadStateStore").asText()).isEqualTo("memory");
        assertThat(status.path("extras").path("mqTransport").asText()).isEqualTo("local-executor");
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Regression: {@code <video>} playback always issues Range requests, and the endpoint
     * used to answer those with HTTP 500
     * ("No converter for ResourceRegion with preset Content-Type 'video/mp4'").
     */
    @Test
    void streamServesRangeRequestsSoThePlayerCanSeek() throws Exception {
        byte[] data = new byte[CHUNK_SIZE * 2 + 7];
        new Random(7).nextBytes(data);

        JsonNode init = postJson("/api/uploads/init", body(
                "fileName", "range-test.mp4",
                "fileSize", data.length,
                "contentType", "video/mp4",
                "chunkSize", CHUNK_SIZE,
                "autoParse", false)).path("data");

        String uploadId = init.path("uploadId").asText();
        int totalChunks = init.path("totalChunks").asInt();
        for (int index = 0; index < totalChunks; index++) {
            uploadChunk(uploadId, index, data, index);
        }

        long videoId = postJson("/api/uploads/" + uploadId + "/complete", null)
                .path("data").path("result").path("video").path("id").asLong();
        String url = "/api/videos/" + videoId + "/stream";

        ResponseEntity<byte[]> full = rest.exchange(url, HttpMethod.GET, HttpEntity.EMPTY, byte[].class);
        assertThat(full.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(full.getBody()).isEqualTo(data);
        assertThat(full.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
        assertThat(full.getHeaders().getContentType()).isEqualTo(MediaType.parseMediaType("video/mp4"));

        HttpHeaders rangeHeaders = new HttpHeaders();
        rangeHeaders.setRange(List.of(HttpRange.createByteRange(100, 199)));
        ResponseEntity<byte[]> partial =
                rest.exchange(url, HttpMethod.GET, new HttpEntity<>(rangeHeaders), byte[].class);

        assertThat(partial.getStatusCode())
                .as("Range 请求必须返回 206，否则浏览器无法播放/拖动进度条")
                .isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(partial.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE))
                .isEqualTo("bytes 100-199/" + data.length);
        assertThat(partial.getHeaders().getContentType()).isEqualTo(MediaType.parseMediaType("video/mp4"));
        assertThat(partial.getBody()).isEqualTo(Arrays.copyOfRange(data, 100, 200));
    }

    private JsonNode awaitTask(long taskId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000L;
        JsonNode last = null;
        while (System.currentTimeMillis() < deadline) {
            last = getJson("/api/tasks/" + taskId).path("data");
            String status = last.path("status").asText();
            if ("SUCCESS".equals(status) || "FAILED".equals(status)) {
                return last;
            }
            Thread.sleep(200L);
        }
        throw new AssertionError("解析任务在 30 秒内未结束: " + last);
    }

    private void uploadChunk(String uploadId, int index, byte[] data, int chunkIndex) {
        int from = chunkIndex * CHUNK_SIZE;
        int to = Math.min(data.length, from + CHUNK_SIZE);
        byte[] slice = Arrays.copyOfRange(data, from, to);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(slice) {
            @Override
            public String getFilename() {
                return "chunk.part";
            }
        });

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<String> response = rest.postForEntity(
                "/api/uploads/" + uploadId + "/chunks/" + index,
                new HttpEntity<>(body, headers),
                String.class);

        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("chunk %s upload failed: %s", index, response.getBody())
                .isTrue();
    }

    private JsonNode postJson(String url, Map<String, Object> payload) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Object> entity = new HttpEntity<>(payload, headers);

        ResponseEntity<String> response = payload == null
                ? rest.postForEntity(url, null, String.class)
                : rest.postForEntity(url, entity, String.class);

        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("POST %s failed: %s", url, response.getBody())
                .isTrue();
        return objectMapper.readTree(response.getBody());
    }

    private JsonNode getJson(String url) {
        ResponseEntity<String> response = rest.getForEntity(url, String.class);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("GET %s failed: %s", url, response.getBody())
                .isTrue();
        try {
            return objectMapper.readTree(response.getBody());
        } catch (Exception ex) {
            throw new IllegalStateException("cannot parse response of " + url, ex);
        }
    }

    private static Map<String, Object> body(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
