package org.mifos.phee.kafkastreamer.importer;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.json.JSONObject;
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

    @Test
    void indexesEveryRecordThenFlushesOnce() throws Exception {
        when(elasticsearchClient.flush()).thenReturn(true);

        kafkaImporter.listen(List.of("{\"partitionId\":1,\"position\":1}", "{\"partitionId\":1,\"position\":2}"));

        verify(elasticsearchClient, times(2)).index(any(JSONObject.class));
        verify(elasticsearchClient, times(1)).flush();
    }

    @Test
    void failedFlushThrowsSoTheBatchIsRetried() {
        when(elasticsearchClient.flush()).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> kafkaImporter.listen(List.of("{\"partitionId\":1,\"position\":1}")));
    }
}
