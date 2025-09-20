package com.acme.orch.starter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "orchestrator")
public class OrchestratorProperties {
    private String consumerTopic;
    private String producerTopic;
    private String groupId;

    private boolean batchEnabled = true;
    private int consumerConcurrency = Math.max(1, Runtime.getRuntime().availableProcessors());
    private int batchMaxSize = 500;
    private int batchFlushIntervalMs = 200;

    public enum DbStrategy { OUTBOX, RELIABLE, LIGHTWEIGHT }
    public enum FailureMode { ATOMIC, SKIP_AND_LOG }

    private DbStrategy dbStrategy = DbStrategy.RELIABLE;
    private FailureMode failureMode = FailureMode.ATOMIC;
    private String errorTable = "event_failures";

    private long dbTimeoutMs = 150;            // very small timeout
    private boolean dbDegradeDontBlock = true; // keep Kafka flowing on DB issues

    private int nonAtomicMaxAttempts = 3;

    /** Payload storage choice: NONE | BYTES | TEXT */
    private String storePayload = "NONE";
    private boolean storePayloadOnFailureOnly = true;

    public String getConsumerTopic() { return consumerTopic; }
    public void setConsumerTopic(String consumerTopic) { this.consumerTopic = consumerTopic; }
    public String getProducerTopic() { return producerTopic; }
    public void setProducerTopic(String producerTopic) { this.producerTopic = producerTopic; }
    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }
    public boolean isBatchEnabled() { return batchEnabled; }
    public void setBatchEnabled(boolean batchEnabled) { this.batchEnabled = batchEnabled; }
    public int getConsumerConcurrency() { return consumerConcurrency; }
    public void setConsumerConcurrency(int consumerConcurrency) { this.consumerConcurrency = consumerConcurrency; }
    public int getBatchMaxSize() { return batchMaxSize; }
    public void setBatchMaxSize(int batchMaxSize) { this.batchMaxSize = batchMaxSize; }
    public int getBatchFlushIntervalMs() { return batchFlushIntervalMs; }
    public void setBatchFlushIntervalMs(int batchFlushIntervalMs) { this.batchFlushIntervalMs = batchFlushIntervalMs; }
    public DbStrategy getDbStrategy() { return dbStrategy; }
    public void setDbStrategy(DbStrategy dbStrategy) { this.dbStrategy = dbStrategy; }
    public FailureMode getFailureMode() { return failureMode; }
    public void setFailureMode(FailureMode failureMode) { this.failureMode = failureMode; }
    public String getErrorTable() { return errorTable; }
    public void setErrorTable(String errorTable) { this.errorTable = errorTable; }
    public long getDbTimeoutMs() { return dbTimeoutMs; }
    public void setDbTimeoutMs(long dbTimeoutMs) { this.dbTimeoutMs = dbTimeoutMs; }
    public boolean isDbDegradeDontBlock() { return dbDegradeDontBlock; }
    public void setDbDegradeDontBlock(boolean dbDegradeDontBlock) { this.dbDegradeDontBlock = dbDegradeDontBlock; }
    public int getNonAtomicMaxAttempts() { return nonAtomicMaxAttempts; }
    public void setNonAtomicMaxAttempts(int nonAtomicMaxAttempts) { this.nonAtomicMaxAttempts = nonAtomicMaxAttempts; }
    public String getStorePayload() { return storePayload; }
    public void setStorePayload(String storePayload) { this.storePayload = storePayload; }
    public boolean isStorePayloadOnFailureOnly() { return storePayloadOnFailureOnly; }
    public void setStorePayloadOnFailureOnly(boolean storePayloadOnFailureOnly) {
        this.storePayloadOnFailureOnly = storePayloadOnFailureOnly;
    }
}
