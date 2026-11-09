package org.mifos.phee.kafkastreamer.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.DefaultLifecycleProcessor;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;

class KafkaConfigurationTest {

    @Test
    void containerWaitsForTheCurrentBulkBeforeTheContextCloses() throws Exception {
        KafkaConfiguration configuration = new KafkaConfiguration();
        set(configuration, "kafkaBrokers", "kafka:9092");
        set(configuration, "consumerGroup", "ph-ee-importer-es");
        set(configuration, "concurrency", 3);
        set(configuration, "bulkSize", 100);
        set(configuration, "pollTimeoutMs", 3000L);
        set(configuration, "retryBackoffMs", 1000L);
        set(configuration, "autoOffsetReset", "earliest");
        set(configuration, "shutdownTimeoutMs", 30000L);

        ConcurrentKafkaListenerContainerFactory<?, ?> factory =
                (ConcurrentKafkaListenerContainerFactory<?, ?>) configuration.kafkaListenerContainerFactory();

        assertEquals(30000L, factory.getContainerProperties().getShutdownTimeout());
        assertEquals(100, configuration.consumerConfigs().get(ConsumerConfig.MAX_POLL_RECORDS_CONFIG));

        // the context-close path is bounded by the lifecycle processor, not by the container property
        DefaultLifecycleProcessor processor = configuration.lifecycleProcessor();
        Field timeout = DefaultLifecycleProcessor.class.getDeclaredField("timeoutPerShutdownPhase");
        timeout.setAccessible(true);
        assertEquals(30000L, timeout.get(processor));
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
