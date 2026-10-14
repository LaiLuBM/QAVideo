package com.qavideo.service.asr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qavideo.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Selection rules for the ASR layer: misconfiguration must degrade to a clearly explained
 * placeholder rather than silently producing a fake summary.
 */
class AsrServiceTest {

    private AsrService service(AppProperties properties) {
        List<AsrTranscriber> transcribers = List.of(
                new OpenAiCompatibleAsrTranscriber(properties, new ObjectMapper()),
                new CommandAsrTranscriber(properties));
        return new AsrService(properties, transcribers);
    }

    @Test
    void disabledByDefault() {
        AppProperties properties = new AppProperties();
        AsrService service = service(properties);

        assertThat(service.enabled()).isFalse();
        assertThat(service.unavailableReason()).contains("provider=none");
    }

    @Test
    void openAiProviderNeedsAnApiKey() {
        AppProperties properties = new AppProperties();
        properties.getAsr().setProvider("openai");
        properties.getAsr().setApiKey("");
        AsrService service = service(properties);

        assertThat(service.enabled()).isFalse();
        assertThat(service.unavailableReason()).contains("QVIDEO_ASR_API_KEY");

        properties.getAsr().setApiKey("sk-test");
        assertThat(service(properties).enabled()).isTrue();
    }

    @Test
    void commandProviderNeedsATemplate() {
        AppProperties properties = new AppProperties();
        properties.getAsr().setProvider("command");
        AsrService service = service(properties);

        assertThat(service.enabled()).isFalse();
        assertThat(service.unavailableReason()).contains("qavideo.asr.command");

        properties.getAsr().setCommand("whisper --output_dir {outDir} {file}");
        assertThat(service(properties).enabled()).isTrue();
    }

    @Test
    void unknownProviderIsReportedNotIgnored() {
        AppProperties properties = new AppProperties();
        properties.getAsr().setProvider("aliyun");
        AsrService service = service(properties);

        assertThat(service.enabled()).isFalse();
        assertThat(service.unavailableReason()).contains("未知的 asr.provider=aliyun");
    }
}
