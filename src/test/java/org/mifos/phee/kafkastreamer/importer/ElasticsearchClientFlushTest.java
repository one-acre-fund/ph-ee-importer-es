package org.mifos.phee.kafkastreamer.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Field;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.rest.RestStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ElasticsearchClientFlushTest {

    @Mock
    private RestHighLevelClient restClient;

    private ElasticsearchClient elasticsearchClient;

    @BeforeEach
    void setUp() throws Exception {
        elasticsearchClient = new ElasticsearchClient();
        Field field = ElasticsearchClient.class.getDeclaredField("client");
        field.setAccessible(true);
        field.set(elasticsearchClient, restClient);
    }

    @Test
    void emptyBatchIsNotSent() throws Exception {
        assertEquals(0, elasticsearchClient.flush(new BulkRequest()));
        verify(restClient, never()).bulk(any(BulkRequest.class), any(RequestOptions.class));
    }

    @Test
    void documentRejectedForGoodIsDroppedAndTheRestCounts() throws Exception {
        when(restClient.bulk(any(BulkRequest.class), any(RequestOptions.class))).thenReturn(new BulkResponse(new BulkItemResponse[] {
                success(0),
                failure(1, RestStatus.BAD_REQUEST, "mapper_parsing_exception: failed to parse field [key] of type [long]")
        }, 1L));

        assertEquals(1, elasticsearchClient.flush(twoActions()));
    }

    @Test
    void backPressureOrIndexBlockMakesTheWholeBatchRetry() throws Exception {
        when(restClient.bulk(any(BulkRequest.class), any(RequestOptions.class))).thenReturn(new BulkResponse(new BulkItemResponse[] {
                success(0),
                failure(1, RestStatus.TOO_MANY_REQUESTS, "cluster_block_exception: index read-only / allow delete")
        }, 1L));

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> elasticsearchClient.flush(twoActions()));
        assertTrue(thrown.getMessage().contains("retried"), thrown.getMessage());
    }

    @Test
    void transportFailurePropagates() throws Exception {
        when(restClient.bulk(any(BulkRequest.class), any(RequestOptions.class))).thenThrow(new IOException("connection reset"));

        assertThrows(RuntimeException.class, () -> elasticsearchClient.flush(twoActions()));
    }

    @Test
    void onlyClientSideDocumentFaultsArePermanent() {
        assertTrue(ElasticsearchClient.isPermanentRejection(RestStatus.BAD_REQUEST));
        assertTrue(ElasticsearchClient.isPermanentRejection(RestStatus.REQUEST_ENTITY_TOO_LARGE));
        assertFalse(ElasticsearchClient.isPermanentRejection(RestStatus.TOO_MANY_REQUESTS));
        assertFalse(ElasticsearchClient.isPermanentRejection(RestStatus.FORBIDDEN));
        assertFalse(ElasticsearchClient.isPermanentRejection(RestStatus.CONFLICT));
        assertFalse(ElasticsearchClient.isPermanentRejection(RestStatus.SERVICE_UNAVAILABLE));
        assertFalse(ElasticsearchClient.isPermanentRejection(null));
    }

    private static BulkRequest twoActions() {
        BulkRequest bulk = new BulkRequest();
        bulk.add(new IndexRequest("zeebe-record").id("1-1").source("{}", org.elasticsearch.xcontent.XContentType.JSON));
        bulk.add(new IndexRequest("zeebe-record").id("1-2").source("{}", org.elasticsearch.xcontent.XContentType.JSON));
        return bulk;
    }

    private static BulkItemResponse success(int id) {
        return BulkItemResponse.success(id, DocWriteRequest.OpType.INDEX,
                new IndexResponse(new ShardId("zeebe-record", "uuid", 0), "_doc", "1-" + id, 1L, 1L, 1L, true));
    }

    private static BulkItemResponse failure(int id, RestStatus status, String message) {
        return BulkItemResponse.failure(id, DocWriteRequest.OpType.INDEX,
                new BulkItemResponse.Failure("zeebe-record", "_doc", "1-" + id, new IllegalArgumentException(message), status));
    }
}
