package org.mifos.phee.kafkastreamer.importer;

import java.util.List;
import org.json.JSONObject;
import org.mifos.phee.kafkastreamer.importer.service.MaskingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class KafkaImporter {

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private ElasticsearchClient elasticsearchClient;

    @Value("${importer.masking.enable}")
    private boolean maskingEnable;

    @Autowired
    private MaskingService maskingService;

    @KafkaListener(topics = "${importer.kafka.topic}")
    public void listen(List<String> records) throws Exception {
        for (String rawData : records) {
            try {
                if (maskingEnable) {
                    logger.debug("Before: {}", rawData);
                    rawData = maskingService.mask(rawData);
                    logger.debug("After: {}", rawData);
                }

                JSONObject data = new JSONObject(rawData);
                logger.trace("from kafka: {}", data.toString(2));

                elasticsearchClient.index(data);
            } catch (RuntimeException e) {
                // A record that cannot be parsed or masked would fail on every retry; skip it so
                // it does not block the partition. Elasticsearch failures surface in flush() below.
                logger.error("skipping record that could not be indexed: {}", rawData, e);
            }
        }

        // Flush before returning so the offsets of this batch are committed only after its
        // records are in Elasticsearch. Throwing makes the container re-poll the batch; document
        // ids are derived from the Zeebe record, so a replay overwrites rather than duplicates.
        if (!elasticsearchClient.flush()) {
            throw new IllegalStateException("bulk request to Elasticsearch failed, batch of " + records.size() + " records will be retried");
        }
        logger.info("flushed {} records to ES", records.size());
    }
}
