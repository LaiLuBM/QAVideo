package com.qavideo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * All tunables for QAVideo, bound from the {@code qavideo.*} block of application.yml.
 */
@ConfigurationProperties(prefix = "qavideo")
public class AppProperties {

    private final Storage storage = new Storage();
    private final Upload upload = new Upload();
    private final Redis redis = new Redis();
    private final Mq mq = new Mq();
    private final Ai ai = new Ai();
    private final Asr asr = new Asr();
    private final Vision vision = new Vision();
    private final Transcript transcript = new Transcript();
    private final Media media = new Media();
    private final Cors cors = new Cors();

    public Storage getStorage() {
        return storage;
    }

    public Upload getUpload() {
        return upload;
    }

    public Redis getRedis() {
        return redis;
    }

    public Mq getMq() {
        return mq;
    }

    public Ai getAi() {
        return ai;
    }

    public Asr getAsr() {
        return asr;
    }

    public Vision getVision() {
        return vision;
    }

    public Transcript getTranscript() {
        return transcript;
    }

    public Media getMedia() {
        return media;
    }

    public Cors getCors() {
        return cors;
    }

    public static class Storage {
        /** Directory holding merged, content-addressed video files. */
        private String root = "./data/storage";
        /** Directory holding per-session chunk files for resumable uploads. */
        private String tempRoot = "./data/uploads";

        public String getRoot() {
            return root;
        }

        public void setRoot(String root) {
            this.root = root;
        }

        public String getTempRoot() {
            return tempRoot;
        }

        public void setTempRoot(String tempRoot) {
            this.tempRoot = tempRoot;
        }
    }

    public static class Upload {
        /** Size of one chunk in bytes; the client is told this value by /uploads/init. */
        private long chunkSize = 5 * 1024 * 1024L;
        private long maxFileSize = 2L * 1024 * 1024 * 1024;
        /** How long an unfinished upload session survives. */
        private Duration sessionTtl = Duration.ofHours(24);
        private String cleanupCron = "0 0 * * * *";

        public long getChunkSize() {
            return chunkSize;
        }

        public void setChunkSize(long chunkSize) {
            this.chunkSize = chunkSize;
        }

        public long getMaxFileSize() {
            return maxFileSize;
        }

        public void setMaxFileSize(long maxFileSize) {
            this.maxFileSize = maxFileSize;
        }

        public Duration getSessionTtl() {
            return sessionTtl;
        }

        public void setSessionTtl(Duration sessionTtl) {
            this.sessionTtl = sessionTtl;
        }

        public String getCleanupCron() {
            return cleanupCron;
        }

        public void setCleanupCron(String cleanupCron) {
            this.cleanupCron = cleanupCron;
        }
    }

    public static class Redis {
        /** true -> upload state lives in Redis (multi-instance safe). */
        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Mq {
        /** true -> parse jobs go through RocketMQ; false -> in-process async executor. */
        private boolean enabled = false;
        private String topic = "qavideo-parse-topic";
        private String consumerGroup = "qavideo-parse-consumer";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getTopic() {
            return topic;
        }

        public void setTopic(String topic) {
            this.topic = topic;
        }

        public String getConsumerGroup() {
            return consumerGroup;
        }

        public void setConsumerGroup(String consumerGroup) {
            this.consumerGroup = consumerGroup;
        }
    }

    public static class Ai {
        private String provider = "deepseek";
        private String baseUrl = "https://api.deepseek.com/v1";
        /** Reserved DeepSeek API field; empty -> local fallback summarizer. */
        private String apiKey = "";
        private String model = "deepseek-chat";
        private Double temperature = 0.3;
        private Duration timeout = Duration.ofSeconds(120);
        private int maxRetries = 2;
        private boolean logRequests = false;
        private boolean logResponses = false;
        private boolean mockWhenNoKey = true;
        private int maxTranscriptChars = 16000;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public Double getTemperature() {
            return temperature;
        }

        public void setTemperature(Double temperature) {
            this.temperature = temperature;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public boolean isLogRequests() {
            return logRequests;
        }

        public void setLogRequests(boolean logRequests) {
            this.logRequests = logRequests;
        }

        public boolean isLogResponses() {
            return logResponses;
        }

        public void setLogResponses(boolean logResponses) {
            this.logResponses = logResponses;
        }

        public boolean isMockWhenNoKey() {
            return mockWhenNoKey;
        }

        public void setMockWhenNoKey(boolean mockWhenNoKey) {
            this.mockWhenNoKey = mockWhenNoKey;
        }

        public int getMaxTranscriptChars() {
            return maxTranscriptChars;
        }

        public void setMaxTranscriptChars(int maxTranscriptChars) {
            this.maxTranscriptChars = maxTranscriptChars;
        }

        public boolean hasApiKey() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    /**
     * Speech-to-text settings.
     *
     * <p>The agent (DeepSeek) is text-only, so real summaries need a transcript. ASR engines
     * demux the audio themselves, so the video file can be handed over as-is - no manual
     * "separate the audio track" step. See {@code service/asr}.
     */
    public static class Asr {

        /** none | openai (OpenAI-compatible HTTP endpoint) | command (local CLI, e.g. whisper). */
        private String provider = "none";
        private String baseUrl = "https://api.openai.com/v1";
        private String apiKey = "";
        private String model = "whisper-1";
        private String language = "zh";
        private Duration timeout = Duration.ofMinutes(10);
        /** Cloud endpoints cap upload size (OpenAI: 25 MB); larger files need the local CLI. */
        private long maxFileSize = 25L * 1024 * 1024;
        /** Expected transcript format produced by the command provider. */
        private String outputFormat = "srt";
        /** Command template for the local provider; supports {file}, {outDir}, {language}. */
        private String command = "";

        public boolean isEnabled() {
            return provider != null && !provider.isBlank() && !"none".equalsIgnoreCase(provider);
        }

        public boolean hasApiKey() {
            return apiKey != null && !apiKey.isBlank();
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getLanguage() {
            return language;
        }

        public void setLanguage(String language) {
            this.language = language;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public long getMaxFileSize() {
            return maxFileSize;
        }

        public void setMaxFileSize(long maxFileSize) {
            this.maxFileSize = maxFileSize;
        }

        public String getOutputFormat() {
            return outputFormat;
        }

        public void setOutputFormat(String outputFormat) {
            this.outputFormat = outputFormat;
        }

        public String getCommand() {
            return command;
        }

        public void setCommand(String command) {
            this.command = command;
        }
    }

    /**
     * Visual understanding settings (frames -> vision model).
     *
     * <p>Needed for videos without speech (game highlights, sports, scenery): there is no
     * audio worth transcribing, and DeepSeek itself cannot see images. Frames are extracted
     * with ffmpeg and described by an OpenAI-compatible vision model such as
     * GPT-4o, 通义千问 qwen-vl, 智谱 GLM-4V or any VLM behind 硅基流动.
     */
    public static class Vision {

        /** none | openai (OpenAI-compatible /chat/completions with image_url). */
        private String provider = "none";
        private String baseUrl = "https://api.openai.com/v1";
        private String apiKey = "";
        private String model = "gpt-4o-mini";
        /** Hard cap on how many frames are extracted (each frame costs one image in the request). */
        private int maxFrames = 8;
        /** 0 = derive from the probed duration so the frames are spread over the whole video. */
        private int frameIntervalSeconds = 0;
        /** Downscale width before base64 encoding: keeps requests small and cheap. */
        private int imageMaxWidth = 640;
        /** How many frames are described in one vision request. */
        private int batchSize = 4;
        private Duration timeout = Duration.ofMinutes(3);
        private String ffmpegPath = "ffmpeg";
        private String ffprobePath = "ffprobe";
        /** Optional prompt override for the vision model. */
        private String prompt = "";

        public boolean isEnabled() {
            return provider != null && !provider.isBlank() && !"none".equalsIgnoreCase(provider);
        }

        public boolean hasApiKey() {
            return apiKey != null && !apiKey.isBlank();
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getMaxFrames() {
            return maxFrames;
        }

        public void setMaxFrames(int maxFrames) {
            this.maxFrames = maxFrames;
        }

        public int getFrameIntervalSeconds() {
            return frameIntervalSeconds;
        }

        public void setFrameIntervalSeconds(int frameIntervalSeconds) {
            this.frameIntervalSeconds = frameIntervalSeconds;
        }

        public int getImageMaxWidth() {
            return imageMaxWidth;
        }

        public void setImageMaxWidth(int imageMaxWidth) {
            this.imageMaxWidth = imageMaxWidth;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public String getFfmpegPath() {
            return ffmpegPath;
        }

        public void setFfmpegPath(String ffmpegPath) {
            this.ffmpegPath = ffmpegPath;
        }

        public String getFfprobePath() {
            return ffprobePath;
        }

        public void setFfprobePath(String ffprobePath) {
            this.ffprobePath = ffprobePath;
        }

        public String getPrompt() {
            return prompt;
        }

        public void setPrompt(String prompt) {
            this.prompt = prompt;
        }
    }

    public static class Transcript {
        private boolean sidecarEnabled = true;
        private boolean demoEnabled = true;

        public boolean isSidecarEnabled() {
            return sidecarEnabled;
        }

        public void setSidecarEnabled(boolean sidecarEnabled) {
            this.sidecarEnabled = sidecarEnabled;
        }

        public boolean isDemoEnabled() {
            return demoEnabled;
        }

        public void setDemoEnabled(boolean demoEnabled) {
            this.demoEnabled = demoEnabled;
        }
    }

    public static class Media {
        private boolean downloadEnabled = true;
        private long maxDownloadSize = 1024L * 1024 * 1024;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration readTimeout = Duration.ofSeconds(60);
        private String userAgent = "Mozilla/5.0 (compatible; QAVideo/1.0)";

        public boolean isDownloadEnabled() {
            return downloadEnabled;
        }

        public void setDownloadEnabled(boolean downloadEnabled) {
            this.downloadEnabled = downloadEnabled;
        }

        public long getMaxDownloadSize() {
            return maxDownloadSize;
        }

        public void setMaxDownloadSize(long maxDownloadSize) {
            this.maxDownloadSize = maxDownloadSize;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }

        public String getUserAgent() {
            return userAgent;
        }

        public void setUserAgent(String userAgent) {
            this.userAgent = userAgent;
        }
    }

    public static class Cors {
        private String allowedOrigins = "http://localhost:5173,http://127.0.0.1:5173";

        public String getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(String allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }
    }
}
