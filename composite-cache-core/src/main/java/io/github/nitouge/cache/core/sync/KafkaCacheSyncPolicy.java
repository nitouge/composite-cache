package io.github.nitouge.cache.core.sync;

import io.github.nitouge.cache.core.config.CacheConfig;
import io.github.nitouge.cache.core.sync.listener.CacheSyncMessage;
import io.github.nitouge.cache.core.util.SyncMessageSerializer;
import io.github.nitouge.cache.core.util.RandomUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;

import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 Kafka 的缓存同步策略
 *
 * <h3>核心功能</h3>
 * <ul>
 *   <li>基于 Kafka 消息队列实现分布式缓存同步</li>
 *   <li>支持同步/异步消息发布</li>
 *   <li>自动生成消费者组 ID，实现消息订阅模式</li>
 *   <li>支持自定义 Kafka 配置</li>
 * </ul>
 *
 * <h3>优势</h3>
 * <ul>
 *   <li>消息持久化：Kafka 保证消息不丢失</li>
 *   <li>高吞吐量：适合大规模分布式场景</li>
 *   <li>消息顺序：保证分区内消息顺序</li>
 * </ul>
 *
 */
@Slf4j
public class KafkaCacheSyncPolicy extends AbstractCacheSyncPolicy<Void> {

    private final AtomicBoolean started = new AtomicBoolean(false);
    private KafkaProducer<String, String> producer;
    private KafkaConsumer<String, String> consumer;
    private Thread consumerThread;

    @Override
    public void run() {
        if (!started.compareAndSet(false, true)) {
            log.warn("KafkaCacheSyncPolicy already started, skip initialization");
            return;
        }
        
        try {
            CacheConfig cacheConfig = this.getCacheConfig();
            CacheConfig.CacheSyncPolicyConfig cacheSyncPolicy = cacheConfig.getCacheSyncPolicy();

            // 生成 Consumer 的 groupId
            generateConsumerGroupId(cacheSyncPolicy);

            // 初始化 Producer 和 Consumer
            producer = new KafkaProducer<>(buildProducerProps(cacheSyncPolicy.getProps()));
            consumer = new KafkaConsumer<>(buildConsumerProps(cacheSyncPolicy.getProps()));
            
            // 启动消费者线程
            startConsumerThread(cacheSyncPolicy.getTopic());
            
            log.info("KafkaCacheSyncPolicy started successfully, topic={}", cacheSyncPolicy.getTopic());
        } catch (Exception e) {
            started.set(false);
            log.error("Failed to start KafkaCacheSyncPolicy", e);
            throw new RuntimeException("Failed to start KafkaCacheSyncPolicy", e);
        }
    }
    
    /**
     * 启动消费者线程
     */
    private void startConsumerThread(String topic) {
        consumerThread = new Thread(() -> {
            try {
                consumer.subscribe(Collections.singletonList(topic));
                log.info("Kafka consumer subscribed to topic: {}", topic);
                
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(3));
                        
                        if (records.count() > 0) {
                            log.debug("Polled {} messages from topic: {}", records.count(), topic);
                        }
                        
                        for (ConsumerRecord<String, String> record : records) {
                            processMessage(record);
                        }
                        
                        consumer.commitSync();
                    } catch (Exception e) {
                        log.error("Error processing Kafka messages", e);
                    }
                }
            } catch (Exception e) {
                log.error("Kafka consumer thread error", e);
            } finally {
                log.info("Kafka consumer thread stopped");
            }
        }, "kafka-cache-sync-consumer");
        
        consumerThread.setDaemon(true);
        consumerThread.start();
    }
    
    /**
     * 处理单条消息
     */
    private void processMessage(ConsumerRecord<String, String> record) {
        try {
            log.debug("Processing message: offset={}, key={}, value={}", 
                    record.offset(), record.key(), record.value());
            
            CacheSyncMessage message = SyncMessageSerializer.toObject(record.value(), CacheSyncMessage.class);
            this.getCacheMessageListener().onMessage(message);
        } catch (Exception e) {
            log.error("Failed to process message: {}", record.value(), e);
        }
    }

    @Override
    public void publish(CacheSyncMessage message) {
        if (message == null) {
            log.warn("Cache sync message is null, skip publish");
            return;
        }
        
        CacheConfig.CacheSyncPolicyConfig cacheSyncPolicy = this.getCacheConfig().getCacheSyncPolicy();
        String topic = cacheSyncPolicy.getTopic();
        
        try {
            String messageJson = SyncMessageSerializer.toJson(message);
            ProducerRecord<String, String> record = new ProducerRecord<>(topic, messageJson);
            
            if (cacheSyncPolicy.isAsync()) {
                publishAsync(record, messageJson);
            } else {
                publishSync(record, topic);
            }
        } catch (Exception e) {
            log.error("Failed to publish cache sync message, message={}", message, e);
        }
    }
    
    /**
     * 异步发布消息
     */
    private void publishAsync(ProducerRecord<String, String> record, String messageJson) {
        producer.send(record, (metadata, exception) -> {
            if (exception == null) {
                log.debug("Message sent successfully: partition={}, offset={}",
                        metadata.partition(), metadata.offset());
            } else {
                log.error("Failed to send message asynchronously: {}", messageJson, exception);
            }
        });
        log.debug("Cache sync message sent asynchronously");
    }
    
    /**
     * 同步发布消息
     */
    private void publishSync(ProducerRecord<String, String> record, String topic) {
        try {
            RecordMetadata metadata = producer.send(record).get();
            log.debug("Cache sync message sent synchronously: topic={}, partition={}, offset={}",
                    topic, metadata.partition(), metadata.offset());
        } catch (Exception e) {
            log.error("Failed to send message synchronously", e);
        }
    }

    @Override
    public void close() {
        if (started.compareAndSet(true, false)) {
            try {
                // 停止消费者线程
                if (consumerThread != null && consumerThread.isAlive()) {
                    consumerThread.interrupt();
                    consumerThread.join(5000);
                }

                // 关闭消费者
                if (consumer != null) {
                    consumer.close();
                    log.info("Kafka consumer closed");
                }

                // 关闭生产者
                if (producer != null) {
                    producer.close();
                    log.info("Kafka producer closed");
                }
                
                log.info("KafkaCacheSyncPolicy closed successfully");
            } catch (Exception e) {
                log.error("Failed to close KafkaCacheSyncPolicy", e);
            }
        }
    }

    /**
     * 生成 Consumer 的 groupId
     *
     * <p>一个 group 只有一个 consumer，保证消息被每个 group 的 consumer 消费到，实现消息订阅模式
     */
    private void generateConsumerGroupId(CacheConfig.CacheSyncPolicyConfig cacheSyncPolicy) {
        String baseGroupId = cacheSyncPolicy.getProps().getProperty(
                ConsumerConfig.GROUP_ID_CONFIG, "composite-cache-group");
        // 每实例唯一 group（广播订阅语义：每实例独立消费全部消息）。用 UUID 保证跨实例不碰撞
        // 修正旧实现 new Random(毫秒种子) 在同毫秒启动的多实例上生成相同后缀 → group 冲突 → 仅一个实例收到消息
        String uniqueGroupId = baseGroupId + "-" + RandomUtil.getUUID();
        cacheSyncPolicy.getProps().put(ConsumerConfig.GROUP_ID_CONFIG, uniqueGroupId);
        log.info("Generated Kafka consumer group ID: {}", uniqueGroupId);
    }

    /**
     * 构建 Producer Properties
     */
    private Properties buildProducerProps(Properties properties) {
        Properties props = new Properties();
        setProp(properties, props, ProducerConfig.BOOTSTRAP_SERVERS_CONFIG);
        setProp(properties, props, ProducerConfig.CLIENT_ID_CONFIG);
        setProp(properties, props, ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG);
        setProp(properties, props, ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG);
        setProp(properties, props, ProducerConfig.ACKS_CONFIG);
        return props;
    }

    /**
     * 构建 Consumer Properties
     */
    private Properties buildConsumerProps(Properties properties) {
        Properties props = new Properties();
        setProp(properties, props, ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG);
        setProp(properties, props, ConsumerConfig.GROUP_ID_CONFIG);
        setProp(properties, props, ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG);
        setProp(properties, props, ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG);
        setProp(properties, props, ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG);
        setProp(properties, props, ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG);
        setProp(properties, props, ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG);
        setProp(properties, props, ConsumerConfig.AUTO_OFFSET_RESET_CONFIG);
        setProp(properties, props, ConsumerConfig.MAX_POLL_RECORDS_CONFIG);
        setProp(properties, props, ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG);
        return props;
    }

    private void setProp(Properties source, Properties target, String key) {
        setProp(source, target, key, null);
    }

    private void setProp(Properties source, Properties target, String key, String defaultValue) {
        String value = source.getProperty(key, defaultValue);
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        target.put(key, value);
    }

}
