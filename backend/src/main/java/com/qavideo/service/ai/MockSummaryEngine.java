package com.qavideo.service.ai;

import com.qavideo.dto.ChapterDto;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Offline fallback summarizer.
 *
 * <p>Used when no DeepSeek key is configured (or the API call fails), so the whole
 * import -> parse -> summary flow stays demonstrable without network access. It is a
 * classic extractive summarizer: term-frequency scoring of sentences, plus keyword and
 * chapter extraction. It is explicitly reported as {@code mock=true} in the API and UI.
 */
@Component
public class MockSummaryEngine implements SummaryEngine {

    private static final Set<String> STOPWORDS = Set.of(
            "的", "了", "是", "在", "我", "有", "和", "就", "不", "人", "都", "一", "一个", "上", "也",
            "很", "到", "说", "要", "去", "你", "会", "着", "没有", "看", "好", "自己", "这", "那",
            "我们", "他们", "可以", "这个", "那个", "因为", "所以", "但是", "如果", "然后", "就是",
            "the", "a", "an", "and", "or", "of", "to", "in", "is", "are", "was", "were", "it", "this",
            "that", "for", "on", "with", "as", "be", "by", "at", "from", "we", "you", "they", "i");

    private static final int MAX_SENTENCES_IN_SUMMARY = 5;
    private static final int MAX_KEYWORDS = 8;
    private static final int MAX_HIGHLIGHTS = 4;
    private static final int MAX_CHAPTERS = 6;

    @Override
    public SummaryPayload summarize(SummaryRequest request) {
        String transcript = request.transcript() == null ? "" : request.transcript().trim();
        if (transcript.isEmpty()) {
            return new SummaryPayload(
                    "未获取到可用字幕文本，无法生成摘要。请为视频提供字幕文件（.srt/.vtt/.txt）或配置语音转写服务后重试。",
                    List.of(), List.of(), List.of(), "未知", "未知", provider(), "local-extractive", true, null, null);
        }

        List<String> sentences = splitSentences(transcript);
        Map<String, Integer> frequency = termFrequency(sentences);

        List<String> summarySentences = topSentences(sentences, frequency, MAX_SENTENCES_IN_SUMMARY);
        String summary = String.join("", summarySentences);
        if (summary.length() > 480) {
            summary = summary.substring(0, 480) + "…";
        }

        List<String> keywords = frequency.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .filter(term -> term.length() >= 2)
                .limit(MAX_KEYWORDS)
                .toList();

        List<String> highlights = topSentences(sentences, frequency, MAX_HIGHLIGHTS).stream()
                .map(String::trim)
                .toList();

        List<ChapterDto> chapters = buildChapters(sentences, frequency, request.durationSeconds());

        return new SummaryPayload(
                summary.isBlank() ? "内容过短，无法生成有效摘要。" : summary,
                keywords,
                highlights,
                chapters,
                "中性",
                "通用受众",
                provider(),
                "local-extractive",
                true,
                null,
                null);
    }

    @Override
    public String provider() {
        return "local-fallback";
    }

    @Override
    public boolean mock() {
        return true;
    }

    private List<String> splitSentences(String text) {
        String normalised = text.replaceAll("[ \t]+", " ").replaceAll("\r\n?", "\n");
        List<String> result = new ArrayList<>();
        for (String line : normalised.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            // strip SRT indexes / timestamps that may have slipped in
            trimmed = trimmed.replaceAll("^\\d+$", "").replaceAll("\\d{2}:\\d{2}:\\d{2}[,.]\\d{3}\\s*-->\\s*\\S+", "").trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            for (String piece : trimmed.split("(?<=[。！？!?;；.])")) {
                String sentence = piece.trim();
                if (sentence.length() >= 2) {
                    result.add(sentence);
                }
            }
        }
        return result;
    }

    /** Counts terms; CJK text is scored with character bigrams because it has no spaces. */
    private Map<String, Integer> termFrequency(List<String> sentences) {
        Map<String, Integer> frequency = new HashMap<>();
        for (String sentence : sentences) {
            for (String token : tokens(sentence)) {
                frequency.merge(token, 1, Integer::sum);
            }
        }
        return frequency;
    }

    private List<String> tokens(String sentence) {
        List<String> tokens = new ArrayList<>();
        String lower = sentence.toLowerCase(Locale.ROOT);
        for (String chunk : lower.split("[^\\p{IsHan}\\p{Alnum}]+")) {
            if (chunk.isEmpty()) {
                continue;
            }
            if (chunk.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)) {
                if (chunk.length() == 1) {
                    continue;
                }
                for (int i = 0; i + 1 < chunk.length(); i++) {
                    String bigram = chunk.substring(i, i + 2);
                    if (!STOPWORDS.contains(bigram)) {
                        tokens.add(bigram);
                    }
                }
            } else if (chunk.length() > 1 && !STOPWORDS.contains(chunk)) {
                tokens.add(chunk);
            }
        }
        return tokens;
    }

    private List<String> topSentences(List<String> sentences, Map<String, Integer> frequency, int limit) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (int i = 0; i < sentences.size(); i++) {
            String sentence = sentences.get(i);
            List<String> tokens = tokens(sentence);
            if (tokens.isEmpty()) {
                continue;
            }
            double score = tokens.stream().mapToInt(t -> frequency.getOrDefault(t, 0)).sum() / Math.sqrt(tokens.size());
            // very short sentences are rarely informative
            if (sentence.length() < 8) {
                score *= 0.5;
            }
            // a small positional bonus for the opening sentences
            if (i < 2) {
                score *= 1.15;
            }
            scores.put(sentence, score);
        }
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(limit)
                .map(Map.Entry::getKey)
                .distinct()
                .sorted(Comparator.comparingInt(sentences::indexOf))
                .toList();
    }

    private List<ChapterDto> buildChapters(List<String> sentences, Map<String, Integer> frequency, Integer durationSeconds) {
        List<ChapterDto> chapters = new ArrayList<>();
        if (sentences.isEmpty()) {
            return chapters;
        }
        int groups = Math.min(MAX_CHAPTERS, Math.max(1, sentences.size() / 3));
        int per = Math.max(1, (int) Math.ceil(sentences.size() / (double) groups));
        for (int g = 0; g < groups; g++) {
            int from = g * per;
            if (from >= sentences.size()) {
                break;
            }
            int to = Math.min(sentences.size(), from + per);
            List<String> slice = sentences.subList(from, to);
            Map<String, Integer> localFrequency = new HashMap<>();
            for (String sentence : slice) {
                for (String token : tokens(sentence)) {
                    localFrequency.merge(token, 1, Integer::sum);
                }
            }
            String title = localFrequency.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse("第 " + (g + 1) + " 部分");
            chapters.add(new ChapterDto(
                    estimateTime(g, groups, durationSeconds),
                    title,
                    slice.get(0).length() > 80 ? slice.get(0).substring(0, 80) + "…" : slice.get(0)));
        }
        return chapters;
    }

    private String estimateTime(int index, int total, Integer durationSeconds) {
        if (durationSeconds == null || durationSeconds <= 0) {
            return "--:--";
        }
        int seconds = (int) Math.round(durationSeconds * (index / (double) total));
        return String.format("%02d:%02d", seconds / 60, seconds % 60);
    }
}
