package org.mifos.phee.kafkastreamer.importer;

import java.util.List;
import org.elasticsearch.action.bulk.BulkRequest;
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
    public void listen(List<String> records) {
        // One poll is one bulk request owned by this call. It is sent before the method returns,
        // so the offsets of the batch are committed only after its records are in Elasticsearch.
        // A thrown flush makes the container re-poll the batch and rebuild the request from
        // scratch; document ids are derived from the Zeebe record, so a replay overwrites.
        BulkRequest bulk = elasticsearchClient.newBulk();
        int skipped = 0;
        for (String rawData : records) {
            String payload = rawData;
            try {
                if (maskingEnable) {
                    payload = maskingService.mask(rawData);
                }
                JSONObject data = new JSONObject(payload);
                elasticsearchClient.index(data, bulk);
            } catch (Exception e) {
                // A record that cannot be masked or parsed would fail on every retry; skip it so
                // it does not block the partition. Only metadata is logged: when masking is what
                // failed, the payload is still unmasked, and the exception may quote it.
                skipped++;
                logger.error("skipping record that could not be prepared for indexing, {}: {}",
                        e.getClass().getSimpleName(), describe(payload));
            }
        }
        int accepted = elasticsearchClient.flush(bulk);
        logger.info("flushed {} actions to ES for {} records, {} skipped", accepted, records.size(), skipped);
    }

    static String describe(String payload) {
        try {
            JSONObject record = new JSONObject(payload);
            return "valueType=" + record.optString("valueType") + " partitionId=" + record.opt("partitionId")
                    + " position=" + record.opt("position") + " key=" + record.opt("key");
        } catch (RuntimeException e) {
            return "unparseable record of " + (payload == null ? 0 : payload.length()) + " chars";
        }
    }
}
