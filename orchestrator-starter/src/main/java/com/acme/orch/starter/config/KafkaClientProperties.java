package com.acme.orch.starter.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.Map;

@ConfigurationProperties(prefix = "orchestrator.kafka")
public class KafkaClientProperties {
    private final Producer producer = new Producer();
    private final Consumer consumer = new Consumer();

    public Producer getProducer() {
        return producer;
    }

    public Consumer getConsumer() {
        return consumer;
    }

    public static final class Producer {
        private String bootstrapServers;
        private String acks = "all";
        private Integer retries = Integer.MAX_VALUE;
        private Duration requestTimeout = Duration.ofSeconds(30);
        private Boolean enableIdempotence = Boolean.TRUE;
        private Integer batchSize = 196_608;
        private Duration linger = Duration.ofMillis(15);
        private String compressionType = "zstd";
        private DataSize bufferMemory = DataSize.ofMegabytes(32);
        private Integer maxInFlightRequestsPerConnection = 5;
        private Duration deliveryTimeout = Duration.ofMinutes(2);
        private DataSize maxRequestSize;
        private String transactionIdPrefix = "orch-tx-";
        private String clientId;

        public String getBootstrapServers() {
            return bootstrapServers;
        }

        public void setBootstrapServers(String bootstrapServers) {
            this.bootstrapServers = bootstrapServers;
        }

        public String getAcks() {
            return acks;
        }

        public void setAcks(String acks) {
            this.acks = acks;
        }

        public Integer getRetries() {
            return retries;
        }

        public void setRetries(Integer retries) {
            this.retries = retries;
        }

        public Duration getRequestTimeout() {
            return requestTimeout;
        }

        public void setRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
        }

        public Boolean getEnableIdempotence() {
            return enableIdempotence;
        }

        public void setEnableIdempotence(Boolean enableIdempotence) {
            this.enableIdempotence = enableIdempotence;
        }

        public Integer getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(Integer batchSize) {
            this.batchSize = batchSize;
        }

        public Duration getLinger() {
            return linger;
        }

        public void setLinger(Duration linger) {
            this.linger = linger;
        }

        public String getCompressionType() {
            return compressionType;
        }

        public void setCompressionType(String compressionType) {
            this.compressionType = compressionType;
        }

        public DataSize getBufferMemory() {
            return bufferMemory;
        }

        public void setBufferMemory(DataSize bufferMemory) {
            this.bufferMemory = bufferMemory;
        }

        public Integer getMaxInFlightRequestsPerConnection() {
            return maxInFlightRequestsPerConnection;
        }

        public void setMaxInFlightRequestsPerConnection(Integer maxInFlightRequestsPerConnection) {
            this.maxInFlightRequestsPerConnection = maxInFlightRequestsPerConnection;
        }

        public Duration getDeliveryTimeout() {
            return deliveryTimeout;
        }

        public void setDeliveryTimeout(Duration deliveryTimeout) {
            this.deliveryTimeout = deliveryTimeout;
        }

        public DataSize getMaxRequestSize() {
            return maxRequestSize;
        }

        public void setMaxRequestSize(DataSize maxRequestSize) {
            this.maxRequestSize = maxRequestSize;
        }

        public String getTransactionIdPrefix() {
            return transactionIdPrefix;
        }

        public void setTransactionIdPrefix(String transactionIdPrefix) {
            this.transactionIdPrefix = transactionIdPrefix;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public void applyDefaults(Map<String, Object> target) {
            putIfAbsent(target, ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            putIfAbsent(target, ProducerConfig.ACKS_CONFIG, acks);
            putIfAbsent(target, ProducerConfig.RETRIES_CONFIG, retries);
            putDuration(target, ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, requestTimeout);
            putIfAbsent(target, ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, enableIdempotence);
            putIfAbsent(target, ProducerConfig.BATCH_SIZE_CONFIG, batchSize);
            putDuration(target, ProducerConfig.LINGER_MS_CONFIG, linger);
            putIfAbsent(target, ProducerConfig.COMPRESSION_TYPE_CONFIG, compressionType);
            putDataSize(target, ProducerConfig.BUFFER_MEMORY_CONFIG, bufferMemory);
            putIfAbsent(target, ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, maxInFlightRequestsPerConnection);
            putDuration(target, ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, deliveryTimeout);
            putDataSize(target, ProducerConfig.MAX_REQUEST_SIZE_CONFIG, maxRequestSize);
            putIfAbsent(target, ProducerConfig.CLIENT_ID_CONFIG, clientId);
            target.putIfAbsent(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            target.putIfAbsent(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        }
    }

    public static final class Consumer {
        private String bootstrapServers;
        private String groupId;
        private Boolean enableAutoCommit = Boolean.FALSE;
        private String autoOffsetReset = "earliest";
        private String isolationLevel = "read_committed";
        private Integer maxPollRecords = 500;
        private Duration maxPollInterval = Duration.ofMinutes(5);
        private Integer fetchMinBytes = 1_048_576;
        private Duration fetchMaxWait = Duration.ofMillis(50);
        private Integer maxPartitionFetchBytes = 5_242_880;
        private DataSize receiveBuffer = DataSize.ofKilobytes(64);
        private DataSize sendBuffer = DataSize.ofKilobytes(128);
        private Duration sessionTimeout = Duration.ofSeconds(30);
        private Duration heartbeatInterval = Duration.ofSeconds(3);
        private Duration requestTimeout = Duration.ofSeconds(30);
        private String clientId;
        private boolean errorHandlingDeserializer = true;

        public String getBootstrapServers() {
            return bootstrapServers;
        }

        public void setBootstrapServers(String bootstrapServers) {
            this.bootstrapServers = bootstrapServers;
        }

        public String getGroupId() {
            return groupId;
        }

        public void setGroupId(String groupId) {
            this.groupId = groupId;
        }

        public Boolean getEnableAutoCommit() {
            return enableAutoCommit;
        }

        public void setEnableAutoCommit(Boolean enableAutoCommit) {
            this.enableAutoCommit = enableAutoCommit;
        }

        public String getAutoOffsetReset() {
            return autoOffsetReset;
        }

        public void setAutoOffsetReset(String autoOffsetReset) {
            this.autoOffsetReset = autoOffsetReset;
        }

        public String getIsolationLevel() {
            return isolationLevel;
        }

        public void setIsolationLevel(String isolationLevel) {
            this.isolationLevel = isolationLevel;
        }

        public Integer getMaxPollRecords() {
            return maxPollRecords;
        }

        public void setMaxPollRecords(Integer maxPollRecords) {
            this.maxPollRecords = maxPollRecords;
        }

        public Duration getMaxPollInterval() {
            return maxPollInterval;
        }

        public void setMaxPollInterval(Duration maxPollInterval) {
            this.maxPollInterval = maxPollInterval;
        }

        public Integer getFetchMinBytes() {
            return fetchMinBytes;
        }

        public void setFetchMinBytes(Integer fetchMinBytes) {
            this.fetchMinBytes = fetchMinBytes;
        }

        public Duration getFetchMaxWait() {
            return fetchMaxWait;
        }

        public void setFetchMaxWait(Duration fetchMaxWait) {
            this.fetchMaxWait = fetchMaxWait;
        }

        public Integer getMaxPartitionFetchBytes() {
            return maxPartitionFetchBytes;
        }

        public void setMaxPartitionFetchBytes(Integer maxPartitionFetchBytes) {
            this.maxPartitionFetchBytes = maxPartitionFetchBytes;
        }

        public DataSize getReceiveBuffer() {
            return receiveBuffer;
        }

        public void setReceiveBuffer(DataSize receiveBuffer) {
            this.receiveBuffer = receiveBuffer;
        }

        public DataSize getSendBuffer() {
            return sendBuffer;
        }

        public void setSendBuffer(DataSize sendBuffer) {
            this.sendBuffer = sendBuffer;
        }

        public Duration getSessionTimeout() {
            return sessionTimeout;
        }

        public void setSessionTimeout(Duration sessionTimeout) {
            this.sessionTimeout = sessionTimeout;
        }

        public Duration getHeartbeatInterval() {
            return heartbeatInterval;
        }

        public void setHeartbeatInterval(Duration heartbeatInterval) {
            this.heartbeatInterval = heartbeatInterval;
        }

        public Duration getRequestTimeout() {
            return requestTimeout;
        }

        public void setRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public boolean isErrorHandlingDeserializer() {
            return errorHandlingDeserializer;
        }

        public void setErrorHandlingDeserializer(boolean errorHandlingDeserializer) {
            this.errorHandlingDeserializer = errorHandlingDeserializer;
        }

        public void applyDefaults(Map<String, Object> target) {
            putIfAbsent(target, ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            putIfAbsent(target, ConsumerConfig.GROUP_ID_CONFIG, groupId);
            putIfAbsent(target, ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, enableAutoCommit);
            putIfAbsent(target, ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
            putIfAbsent(target, ConsumerConfig.ISOLATION_LEVEL_CONFIG, isolationLevel);
            putIfAbsent(target, ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPollRecords);
            putDuration(target, ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, maxPollInterval);
            putIfAbsent(target, ConsumerConfig.FETCH_MIN_BYTES_CONFIG, fetchMinBytes);
            putDuration(target, ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, fetchMaxWait);
            putIfAbsent(target, ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, maxPartitionFetchBytes);
            putDataSize(target, ConsumerConfig.RECEIVE_BUFFER_CONFIG, receiveBuffer);
            putDataSize(target, ConsumerConfig.SEND_BUFFER_CONFIG, sendBuffer);
            putDuration(target, ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, sessionTimeout);
            putDuration(target, ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, heartbeatInterval);
            putDuration(target, ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, requestTimeout);
            putIfAbsent(target, ConsumerConfig.CLIENT_ID_CONFIG, clientId);
            if (errorHandlingDeserializer) {
                target.putIfAbsent(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
                target.putIfAbsent(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
                target.putIfAbsent(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
                target.putIfAbsent(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, StringDeserializer.class);
            } else {
                target.putIfAbsent(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
                target.putIfAbsent(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            }
        }
    }

    private static void putIfAbsent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.putIfAbsent(key, value);
        }
    }

    private static void putDuration(Map<String, Object> target, String key, Duration duration) {
        if (duration != null) {
            long millis = duration.toMillis();
            Object store = millis <= Integer.MAX_VALUE ? (int) millis : millis;
            target.putIfAbsent(key, store);
        }
    }

    private static void putDataSize(Map<String, Object> target, String key, DataSize dataSize) {
        if (dataSize != null) {
            long bytes = dataSize.toBytes();
            Object store = bytes <= Integer.MAX_VALUE ? (int) bytes : bytes;
            target.putIfAbsent(key, store);
        }
    }
}
