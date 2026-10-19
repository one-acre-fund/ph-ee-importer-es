package org.mifos.phee.kafkastreamer.importer;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@EnableKafka
@Configuration
public class KafkaConfiguration {

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Value("${kafka.brokers}")
    private String kafkaBrokers;

    @Value("${kafka.consumer-group}")
    private String consumerGroup;

    @Value("${kafka.concurrency}")
    private int concurrency;

    @Value("${importer.elasticsearch.bulk-size}")
    private int bulkSize;

    @Value("${kafka.poll-timeout-ms:3000}")
    private long pollTimeoutMs;

    @Value("${kafka.retry-backoff-ms:1000}")
    private long retryBackoffMs;

    @Value("${kafka.auto-offset-reset:earliest}")
    private String autoOffsetReset;

    @Bean
    KafkaListenerContainerFactory<ConcurrentMessageListenerContainer<String, String>> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setConcurrency(concurrency);
        factory.getContainerProperties().setPollTimeout(pollTimeoutMs);
        // One poll is one Elasticsearch bulk: the listener flushes before the batch is committed.
        factory.setBatchListener(true);
        // A batch whose bulk did not reach Elasticsearch is re-polled instead of committed past.
        // Only a retryable flush failure reaches this handler (malformed records and documents
        // Elasticsearch rejects for good are logged and skipped), so retrying without limit stalls
        // the partition while Elasticsearch is unavailable instead of dropping the batch.
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(retryBackoffMs, FixedBackOff.UNLIMITED_ATTEMPTS)));

        return factory;
    }

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        return new DefaultKafkaConsumerFactory<>(consumerConfigs());
    }

    @Bean
    public Map<String, Object> consumerConfigs() {
        String hostname = buildKafkaClientId(logger);

        Map<String, Object> properties = new HashMap<>();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBrokers);
        properties.put(ConsumerConfig.CLIENT_ID_CONFIG, hostname);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroup);
        // A group without committed offsets (first deploy of the fixed group id) starts at the
        // beginning of the retained topic rather than skipping the backlog; replays overwrite.
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, bulkSize);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        logger.info("using Kafka consumer group {} with {} consumer threads, {} records per poll", consumerGroup, concurrency, bulkSize);
        return properties;
    }

    public static String buildKafkaClientId(Logger logger) {
        String clientId;
        try {
            clientId = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            logger.error("failed to resolve local hostname, picking random clientId");
            clientId = UUID.randomUUID().toString();
        }
        return clientId;
    }

}
