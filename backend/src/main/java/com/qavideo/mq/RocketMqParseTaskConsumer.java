package com.qavideo.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qavideo.config.AppProperties;
import com.qavideo.service.VideoParsePipeline;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Consumes parse jobs from RocketMQ and runs the pipeline.
 *
 * <p>Why not just {@code @RocketMQMessageListener}? Because the annotation-driven listener
 * starts during context refresh and <em>fails the whole application</em> when the broker
 * is not reachable yet. A platform is expected to come up even if middleware restarts, so
 * this consumer owns its lifecycle:
 * <ul>
 *   <li>{@link #start()} returns immediately and connects on a daemon thread,</li>
 *   <li>connection failures are retried with capped backoff,</li>
 *   <li>jobs published meanwhile stay in MySQL as {@code PENDING} and are consumed as soon
 *       as the subscription is live.</li>
 * </ul>
 *
 * <p>Acknowledgement policy: failures inside {@link VideoParsePipeline} are recorded on the
 * task row and the message is ACKed, so a permanently broken input cannot spin the queue.
 */
@Component
@ConditionalOnProperty(prefix = "qavideo.mq", name = "enabled", havingValue = "true")
public class RocketMqParseTaskConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RocketMqParseTaskConsumer.class);
    private static final long MAX_BACKOFF_SECONDS = 60L;

    private final VideoParsePipeline pipeline;
    private final ObjectMapper objectMapper;
    private final AppProperties properties;
    private final String nameServer;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private volatile DefaultMQPushConsumer consumer;
    private Thread starterThread;

    public RocketMqParseTaskConsumer(VideoParsePipeline pipeline,
                                     ObjectMapper objectMapper,
                                     AppProperties properties,
                                     @Value("${rocketmq.name-server:127.0.0.1:9876}") String nameServer) {
        this.pipeline = pipeline;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.nameServer = nameServer;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        starterThread = new Thread(this::connectWithRetry, "qavideo-mq-consumer-starter");
        starterThread.setDaemon(true);
        starterThread.start();
    }

    private void connectWithRetry() {
        int attempt = 0;
        while (running.get()) {
            attempt += 1;
            try {
                DefaultMQPushConsumer created = buildConsumer();
                created.start();
                this.consumer = created;
                this.connected.set(true);
                log.info("RocketMQ consumer connected: namesrv={} topic={} group={} attempt={}",
                        nameServer, properties.getMq().getTopic(), properties.getMq().getConsumerGroup(), attempt);
                return;
            } catch (Exception ex) {
                long backoff = Math.min(MAX_BACKOFF_SECONDS, 5L * attempt);
                log.warn("RocketMQ consumer not connected yet (attempt {}): {}; retrying in {}s",
                        attempt, ex.getMessage(), backoff);
                sleepSeconds(backoff);
            }
        }
    }

    private DefaultMQPushConsumer buildConsumer() throws Exception {
        DefaultMQPushConsumer created = new DefaultMQPushConsumer(properties.getMq().getConsumerGroup());
        created.setNamesrvAddr(nameServer);
        created.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        created.subscribe(properties.getMq().getTopic(), "*");
        created.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
            for (MessageExt message : messages) {
                handle(new String(message.getBody(), StandardCharsets.UTF_8));
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        return created;
    }

    private void handle(String payload) {
        try {
            ParseTaskMessage message = objectMapper.readValue(payload, ParseTaskMessage.class);
            log.info("consumed parse task {} (requestId={})", message.taskId(), message.requestId());
            pipeline.execute(message.taskId());
        } catch (Exception ex) {
            log.error("cannot consume parse message: {}", payload, ex);
        }
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        connected.set(false);
        if (starterThread != null) {
            starterThread.interrupt();
        }
        DefaultMQPushConsumer current = consumer;
        if (current != null) {
            try {
                current.shutdown();
            } catch (Exception ex) {
                log.warn("RocketMQ consumer shutdown failed: {}", ex.getMessage());
            }
        }
        log.info("RocketMQ consumer stopped");
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    /** true once the subscription is actually live (surfaced by /api/system/status). */
    public boolean isConnected() {
        return connected.get();
    }

    private void sleepSeconds(long seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
