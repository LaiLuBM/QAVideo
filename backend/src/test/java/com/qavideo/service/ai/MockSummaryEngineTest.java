package com.qavideo.service.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MockSummaryEngineTest {

    private final MockSummaryEngine engine = new MockSummaryEngine();

    @Test
    void summarisesChineseTranscript() {
        String transcript = """
                今天我们介绍 Spring Boot 的异步任务处理。异步任务可以显著降低接口响应时间。
                第一种方式是把任务投递到消息队列，例如 RocketMQ，让消费者在后台执行。
                第二种方式是在应用内部使用线程池，实现简单但是无法跨实例扩展。
                消息队列还有一个好处是削峰填谷，流量高峰时任务会排队而不是压垮服务。
                最后我们演示了如何用 Redis 保存断点续传的状态。
                """;

        SummaryPayload payload = engine.summarize(
                new SummaryRequest(1L, "异步任务实践", "https://example.com/video/1", 600, transcript));

        assertThat(payload.mock()).isTrue();
        assertThat(payload.summary()).isNotBlank();
        assertThat(payload.keywords()).isNotEmpty();
        assertThat(payload.highlights()).isNotEmpty();
        assertThat(payload.chapters()).isNotEmpty();
        assertThat(payload.chapters().get(0).start()).isEqualTo("00:00");
    }

    @Test
    void handlesEmptyTranscriptGracefully() {
        SummaryPayload payload = engine.summarize(
                new SummaryRequest(2L, "空视频", null, null, "   "));

        assertThat(payload.summary()).contains("无法生成摘要");
        assertThat(payload.keywords()).isEmpty();
    }

    @Test
    void stripsSrtArtifacts() {
        String srt = """
                1
                00:00:00,000 --> 00:00:02,000
                欢迎观看本期视频

                2
                00:00:02,000 --> 00:00:04,000
                今天我们聊聊消息队列削峰填谷
                """;

        SummaryPayload payload = engine.summarize(new SummaryRequest(3L, "srt", null, 120, srt));

        assertThat(payload.summary()).doesNotContain("-->").doesNotContain("00:00:00");
    }
}
