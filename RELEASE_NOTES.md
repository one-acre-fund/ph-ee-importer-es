Release Notes

## Version 1.1.0 - Up to date with Mifos Version 1.0.0
    * Payment Hub Importer-es
        *  Horizontal scaling
            * [FD-1931] - Fixed Kafka consumer group (KAFKA_CONSUMER_GROUP) and configurable
              concurrency (KAFKA_CONCURRENCY) so replicas share the topic partitions; records
              are consumed in batches and flushed to Elasticsearch before the batch offsets
              are committed, so a pod kill no longer loses the pending bulk; a failed bulk
              is retried without limit (the partition stalls, lag grows) instead of the batch
              being skipped after ten attempts; a record that cannot be parsed is logged and
              skipped on its own
            * [FD-1931] - Review follow-ups: each poll builds its own bulk request, nothing is
              shared between listener threads, so a failed batch is rebuilt on redelivery
              instead of growing a retained buffer or committing another thread's actions; a
              document Elasticsearch rejects for good (400, 413) is logged with index and id
              and dropped while back pressure, index blocks, conflicts and server errors still
              retry the batch; the skip log for an unmaskable or unparseable record carries
              only metadata, never the payload; auto.offset.reset (KAFKA_AUTO_OFFSET_RESET,
              default earliest), the poll timeout and the retry back-off are configurable


## Version 1.0.2 - Up to date with Mifos Version 1.0.0
    * Payment Hub Importer-es
        *  Bulk Upsert of Transactions
            * [CP-4074] - Fix NumberFormatException when Zeebe exporter sends non-numeric amount variable

## Version 1.0.1 - Up to date with Mifos Version 1.0.0
    * Payment Hub Importer-es
        *  Elastic APM + OpenTelemetry Integration
            * [CP-3980] - Integrate Elastic APM with PH
