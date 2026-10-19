package org.mifos.phee.kafkastreamer.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;
import org.elasticsearch.action.bulk.BulkRequest;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mifos.phee.kafkastreamer.importer.service.MaskingService;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KafkaImporterTest {

    @Mock
    private ElasticsearchClient elasticsearchClient;

    @Mock
    private MaskingService maskingService;

    @InjectMocks
    private KafkaImporter kafkaImporter;

    private final BulkRequest bulk = new BulkRequest();

    @BeforeEach
    void setUp() {
        lenient().when(elasticsearchClient.newBulk()).thenReturn(bulk);
    }

    @Test
    void indexesEveryRecordIntoTheBatchRequestThenFlushesItOnce() {
        kafkaImporter.listen(List.of("{\"partitionId\":1,\"position\":1}", "{\"partitionId\":1,\"position\":2}"));

        verify(elasticsearchClient, times(2)).index(any(JSONObject.class), same(bulk));
        verify(elasticsearchClient, times(1)).flush(same(bulk));
    }

    @Test
    void retryableFlushFailurePropagatesSoTheBatchIsRePolled() {
        when(elasticsearchClient.flush(same(bulk))).thenThrow(new RuntimeException("429 from Elasticsearch"));

        assertThrows(RuntimeException.class, () -> kafkaImporter.listen(List.of("{\"partitionId\":1,\"position\":1}")));
    }

    @Test
    void recordThatCannotBeMaskedIsSkippedAndTheRestOfTheBatchIsStillFlushed() throws Exception {
        setMasking(true);
        when(maskingService.mask("bad")).thenThrow(new IllegalStateException("cipher failure"));
        when(maskingService.mask("{\"partitionId\":1,\"position\":2}")).thenReturn("{\"partitionId\":1,\"position\":2}");

        kafkaImporter.listen(List.of("bad", "{\"partitionId\":1,\"position\":2}"));

        verify(elasticsearchClient, times(1)).index(any(JSONObject.class), same(bulk));
        verify(elasticsearchClient).flush(same(bulk));
    }

    @Test
    void describeLogsOnlyMetadataNeverThePayload() {
        String payload = "{\"valueType\":\"VARIABLE\",\"partitionId\":1,\"position\":7,\"key\":9,"
                + "\"value\":{\"name\":\"partyId\",\"value\":\"+250788000000\"}}";
        String described = KafkaImporter.describe(payload);

        assertEquals("valueType=VARIABLE partitionId=1 position=7 key=9", described);
        assertFalse(described.contains("250788"));
        assertEquals("unparseable record of 3 chars", KafkaImporter.describe("bad"));
    }

    private void setMasking(boolean enabled) throws Exception {
        Field field = KafkaImporter.class.getDeclaredField("maskingEnable");
        field.setAccessible(true);
        field.set(kafkaImporter, enabled);
    }
}
