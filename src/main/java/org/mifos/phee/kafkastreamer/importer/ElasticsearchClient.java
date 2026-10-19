package org.mifos.phee.kafkastreamer.importer;

import io.zeebe.exporter.ElasticsearchExporterException;
import io.zeebe.exporter.ElasticsearchMetrics;
import io.zeebe.util.VersionUtil;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Map;
import javax.annotation.PostConstruct;
import javax.net.ssl.SSLContext;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.http.impl.nio.reactor.IOReactorConfig;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.ssl.SSLContexts;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.admin.indices.template.put.PutIndexTemplateRequest;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.xcontent.XContentType;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ElasticsearchClient {

    public static String INDEX_TEMPLATE_FILENAME_PATTERN = "/zeebe-record-%s-template.json";
    public static String INDEX_DELIMITER = "_";
    public static String ALIAS_DELIMITER = "-";

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Value("${importer.elasticsearch.bulk-size}")
    private Integer bulkSize;

    @Value("${importer.elasticsearch.url}")
    private String elasticUrl;

    @Value("${importer.elasticsearch.index-prefix}")
    private String indexPrefix;

    @Value("${reporting.enabled}")
    private Boolean reportingEnabled;

    @Value("${elasticsearch.security.enabled}")
    private Boolean securityEnabled;

    @Value("${elasticsearch.sslVerification}")
    private Boolean sslVerify;

    @Value("${elasticsearch.username}")
    private String username;

    @Value("${elasticsearch.password}")
    private String password;

    @Autowired
    private PaymentsIndexConfiguration paymentsIndexConfiguration;

    private RestHighLevelClient client;
    private ElasticsearchMetrics metrics;

    private DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    @PostConstruct
    public void setup() {
        this.client = createClient();
    }

    public void close() throws IOException {
        client.close();
    }

    /**
     * The actions of one listener batch. Each batch owns its request, so nothing is shared between
     * listener threads and a failed batch is rebuilt from the re-polled records instead of being
     * kept here and appended to again.
     */
    public BulkRequest newBulk() {
        return new BulkRequest();
    }

    public void index(JSONObject record, BulkRequest bulk) {
        if (metrics == null) {
            metrics = new ElasticsearchMetrics(record.getInt("partitionId"));
        }
        logger.debug("Getting index method called with record value type {}", record.getString("valueType"));
        if (reportingEnabled) {
            upsertToReportingIndex(record, bulk);
        }
        logger.debug("Pushing index for {}", indexFor(record));
        IndexRequest request = new IndexRequest(indexFor(record), typeFor(record), idFor(record))
                .source(record.toString(), XContentType.JSON).routing(Integer.toString(record.getInt("partitionId")));
        bulk.add(request);
    }

    public void upsertToReportingIndex(JSONObject record, BulkRequest bulk) {
        JSONObject newRecord = new JSONObject();
        if (record.getString("valueType").equalsIgnoreCase("variable")) {
            JSONObject valueObj = record.getJSONObject("value");
            if (valueObj.has("name")) { // Checking for variable events
                if (paymentsIndexConfiguration.getVariables().contains(valueObj.getString("name"))) {
                    if (valueObj.getString("name").equalsIgnoreCase("amount")) {
                        String rawAmount = valueObj.getString("value").replace("\"", "").trim();
                        try {
                            newRecord.put((String) valueObj.get("name"), Double.parseDouble(rawAmount));
                        } catch (NumberFormatException e) {
                            logger.warn("Skipping non-numeric amount value for processInstanceKey={}: '{}'",
                                    valueObj.opt("processInstanceKey"), rawAmount);
                        }
                    } else if (valueObj.getString("name").equalsIgnoreCase("originDate")) {
                        Instant timestamp = Instant.ofEpochMilli(valueObj.getLong("value"));
                        newRecord.put((String) valueObj.get("name"), timestamp);
                    } else {
                        newRecord.put((String) valueObj.get("name"), valueObj.get("value").toString().replace("\"", ""));
                    }
                }
                if (!newRecord.has("processInstanceKey")) {
                    newRecord.put("processInstanceKey", String.valueOf(valueObj.getLong("processInstanceKey")));
                    Instant timestamp = Instant.ofEpochMilli(record.getLong("timestamp"));
                    newRecord.put("timestamp", timestamp);
                }
            }
            logger.debug("New Record before insert is: {}", newRecord);
            String version = VersionUtil.getVersionLowerCase();
            Instant timestamp = Instant.ofEpochMilli(record.getLong("timestamp"));
            String name = "zeebe-payments" + INDEX_DELIMITER + version + INDEX_DELIMITER + formatter.format(timestamp);
            UpdateRequest request1 = new UpdateRequest(name, valueObj.get("processInstanceKey").toString()).doc(newRecord.toMap())
                    .upsert(newRecord.toString(), XContentType.JSON);
            bulk.add(request1);
        }
    }

    /**
     * Sends one batch. A transport failure or an item Elasticsearch may accept later (back
     * pressure, index block, version conflict, server error) is thrown, so the caller re-polls
     * the batch; nothing is retained here, so a retry cannot grow the request or leak into
     * another thread's batch. An item rejected for good (400, the document itself is invalid for
     * the mapping, or 413) is logged with its index and id and dropped: retrying it would block
     * the partition forever.
     *
     * @return the number of actions Elasticsearch accepted
     * @throws ElasticsearchExporterException when the batch has to be retried
     */
    public int flush(BulkRequest bulk) {
        int actions = bulk.numberOfActions();
        if (actions == 0) {
            return 0;
        }
        if (metrics != null) {
            metrics.recordBulkSize(actions);
        }
        BulkResponse responses;
        try {
            responses = client.bulk(bulk, RequestOptions.DEFAULT);
        } catch (IOException e) {
            throw new ElasticsearchExporterException("Failed to flush bulk of " + actions + " actions", e);
        }
        int rejected = 0;
        for (BulkItemResponse response : responses) {
            if (!response.isFailed()) {
                continue;
            }
            BulkItemResponse.Failure failure = response.getFailure();
            if (isPermanentRejection(failure.getStatus())) {
                rejected++;
                logger.error("Elasticsearch rejected document {}/{} with {}, dropping it: {}",
                        response.getIndex(), response.getId(), failure.getStatus(), response.getFailureMessage());
            } else {
                throw new ElasticsearchExporterException("Elasticsearch did not accept document " + response.getIndex()
                        + "/" + response.getId() + " (" + failure.getStatus() + "), batch will be retried: " + response.getFailureMessage());
            }
        }
        return actions - rejected;
    }

    static boolean isPermanentRejection(RestStatus status) {
        return status == RestStatus.BAD_REQUEST || status == RestStatus.REQUEST_ENTITY_TOO_LARGE;
    }

    /**
     * @return true if request was acknowledged
     */
    public boolean putIndexTemplate(ExtendedValueType extendedValueType) {
        String templateName = indexPrefixForValueType(extendedValueType);
        String aliasName = aliasNameForValueType(extendedValueType);
        String filename = indexTemplateForValueType(extendedValueType);
        return putIndexTemplate(templateName, aliasName, filename);
    }

    /**
     * @return true if request was acknowledged
     */
    public boolean putIndexTemplate(String templateName, String aliasName, String filename) {
        Map<String, Object> template;
        try (InputStream inputStream = KafkaElasticImportApplication.class.getResourceAsStream(filename)) {
            if (inputStream != null) {
                template = XContentHelper.convertToMap(XContentType.JSON.xContent(), inputStream, true);
            } else {
                throw new ElasticsearchExporterException("Failed to find index template in classpath " + filename);
            }
        } catch (IOException e) {
            throw new ElasticsearchExporterException("Failed to load index template from classpath " + filename, e);
        }

        // update prefix in template in case it was changed in configuration
        template.put("index_patterns", Collections.singletonList(templateName + INDEX_DELIMITER + "*"));

        // update alias in template in case it was changed in configuration
        template.put("aliases", Collections.singletonMap(aliasName, Collections.EMPTY_MAP));

        if (reportingEnabled) {
            if (templateName.equals("zeebe-record")) {
                Map<String, Object> template1;
                try (InputStream inputStream1 = KafkaElasticImportApplication.class.getResourceAsStream("/zeebe-payments.json")) {
                    if (inputStream1 != null) {
                        template1 = XContentHelper.convertToMap(XContentType.JSON.xContent(), inputStream1, true);
                    } else {
                        throw new ElasticsearchExporterException("Failed to find index template in classpath " + filename);
                    }
                } catch (IOException e) {
                    throw new ElasticsearchExporterException("Failed to load index template from classpath " + filename, e);
                }
                template1.put("index_patterns", Collections.singletonList("zeebe-payments" + INDEX_DELIMITER + "*"));
                template1.put("aliases", Collections.singletonMap("zeebe-payments", Collections.EMPTY_MAP));
                PutIndexTemplateRequest request = new PutIndexTemplateRequest("zeebe-payments").source(template1);
                putIndexTemplate(request);
            }
        }
        PutIndexTemplateRequest request = new PutIndexTemplateRequest(templateName).source(template);

        return putIndexTemplate(request);
    }

    /**
     * @return true if request was acknowledged
     */
    private boolean putIndexTemplate(PutIndexTemplateRequest putIndexTemplateRequest) {
        try {
            return client.indices().putTemplate(putIndexTemplateRequest, RequestOptions.DEFAULT).isAcknowledged();
        } catch (ElasticsearchException exception) {
            throw new ElasticsearchExporterException("Failed to Connect ES", exception);
        } catch (IOException e) {
            throw new ElasticsearchExporterException("Failed to put index template", e);
        }
    }

    private RestHighLevelClient createClient() {
        RestClientBuilder builder;
        SSLContext sslContext = null;
        if (securityEnabled) {
            final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(username, password));
            if (sslVerify) {
                SSLContextBuilder sslBuilder = null;
                try {
                    sslBuilder = SSLContexts.custom().loadTrustMaterial(null, (x509Certificates, s) -> true);
                    sslContext = sslBuilder.build();
                } catch (NoSuchAlgorithmException | KeyStoreException | KeyManagementException e) {
                    e.printStackTrace();
                }
                HttpHost httpHost = urlToHttpHost(elasticUrl);
                SSLContext finalSslContext = sslContext;
                builder = RestClient.builder(httpHost)
                        .setHttpClientConfigCallback(httpClientBuilder -> httpClientBuilder.setSSLContext(finalSslContext)
                                .setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE).setDefaultCredentialsProvider(credentialsProvider));
            } else {
                HttpHost httpHost = urlToHttpHost(elasticUrl);
                builder = RestClient.builder(httpHost).setHttpClientConfigCallback(new RestClientBuilder.HttpClientConfigCallback() {

                    @Override
                    public HttpAsyncClientBuilder customizeHttpClient(HttpAsyncClientBuilder httpClientBuilder) {
                        return httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
                    }
                });
            }
        } else {
            HttpHost httpHost = urlToHttpHost(elasticUrl);
            builder = RestClient.builder(httpHost).setHttpClientConfigCallback(this::setHttpClientConfigCallback);
        }
        return new RestHighLevelClient(builder);
    }

    private HttpAsyncClientBuilder setHttpClientConfigCallback(HttpAsyncClientBuilder builder) {
        builder.setDefaultIOReactorConfig(IOReactorConfig.custom().setIoThreadCount(1).build());

        return builder;
    }

    private static HttpHost urlToHttpHost(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new ElasticsearchExporterException("Failed to parse url " + url, e);
        }

        return new HttpHost(uri.getHost(), uri.getPort(), uri.getScheme());
    }

    protected String indexFor(JSONObject record) {
        Instant timestamp = Instant.ofEpochMilli(record.getLong("timestamp"));
        return indexPrefixForValueTypeWithDelimiter(ExtendedValueType.valueOf(record.getString("valueType"))) + formatter.format(timestamp);
    }

    protected String idFor(JSONObject record) {
        return record.getInt("partitionId") + "-" + record.getLong("position");
    }

    protected String typeFor(JSONObject record) {
        return "_doc";
    }

    protected String indexPrefixForValueTypeWithDelimiter(ExtendedValueType extendedValueType) {
        return indexPrefixForValueType(extendedValueType) + INDEX_DELIMITER;
    }

    private String aliasNameForValueType(ExtendedValueType extendedValueType) {
        return indexPrefix + ALIAS_DELIMITER + valueTypeToString(extendedValueType);
    }

    private String indexPrefixForValueType(ExtendedValueType valueType) {
        String version = VersionUtil.getVersionLowerCase();

        return indexPrefix + INDEX_DELIMITER + valueTypeToString(valueType) + INDEX_DELIMITER + version;
    }

    private static String valueTypeToString(ExtendedValueType extendedValueType) {
        if (extendedValueType.name().equalsIgnoreCase("process_instance")) {
            extendedValueType = ExtendedValueType.WORKFLOW_INSTANCE;
        }
        return extendedValueType.name().toLowerCase().replace("_", "-");
    }

    private static String indexTemplateForValueType(ExtendedValueType valueType) {
        return String.format(INDEX_TEMPLATE_FILENAME_PATTERN, valueTypeToString(valueType));
    }
}
