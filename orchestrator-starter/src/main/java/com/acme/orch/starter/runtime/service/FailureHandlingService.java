package com.acme.orch.starter.runtime.service;

import com.acme.orch.db.FailureTracker;
import com.acme.orch.db.model.FailureRecord;
import com.acme.orch.starter.config.OrchestratorProperties;
import com.acme.orch.starter.config.OrchestratorProperties.DbStrategy;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

public class FailureHandlingService {

    private static final Logger log = LoggerFactory.getLogger(FailureHandlingService.class);

    private final FailureTracker failureTracker;
    private final OrchestratorProperties properties;
    private final MetricsService metricsService;

    public FailureHandlingService(
        FailureTracker failureTracker,
        OrchestratorProperties properties,
        MetricsService metricsService
    ) {
        this.failureTracker = failureTracker;
        this.properties = properties;
        this.metricsService = metricsService;
    }

    public void handleFailure(ConsumerRecord<String, String> record, Throwable throwable) {
        handleFailure(record, throwable, "transaction");
    }

    public void handleNonAtomicFailure(ConsumerRecord<String, String> record, Throwable throwable) {
        String dedupKey = createDedupKey(record);
        DbStrategy strategy = properties.getDbStrategy();

        metricsService.incrementFailureCounter();

        try {
            Exception exception = extractRootException(throwable);
            persistFailure(strategy, dedupKey, record, exception);
            logFailure(record, exception, properties.getNonAtomicMaxAttempts());
        } catch (RuntimeException dbEx) {
            log.error("Failed to persist failure metadata for dedup {}: {}", dedupKey, dbEx.toString());
        }
    }

    public void handleBatchFailure(List<ConsumerRecord<String, String>> records, Throwable throwable) {
        Exception failure = extractRootException(throwable);

        records.forEach(record -> {
            String key = createDedupKey(record);
            try {
                failureTracker.updateStatusFailed(key, failure.getMessage(), getStackTrace(failure));
            } catch (RuntimeException dbEx) {
                log.error("Failed to persist failure metadata for dedup {}: {}", key, dbEx.toString());
            }
        });

        log.error("Batch processing failed; scheduling retry. cause={}", failure.toString());
    }

    private void handleFailure(ConsumerRecord<String, String> record, Throwable throwable, String stage) {
        String dedupKey = createDedupKey(record);
        MDC.put("dedupKey", dedupKey);

        try {
            Exception exception = extractRootException(throwable);
            metricsService.incrementFailureCounter();
            persistFailure(properties.getDbStrategy(), dedupKey, record, exception);
            logFailure(record, exception, stage);
        } catch (RuntimeException dbEx) {
            log.error("Failed to persist failure metadata for dedup {}: {}", dedupKey, dbEx.toString());
        } finally {
            MDC.remove("dedupKey");
        }
    }

    private void persistFailure(DbStrategy strategy, String dedupKey, ConsumerRecord<String, String> record, Exception exception) {
        switch (strategy) {
            case LIGHTWEIGHT -> failureTracker.insertLightweightFailure(createFailureRecord(record, "FAILED", exception, true));
            case OUTBOX -> failureTracker.updateStatusFailed(dedupKey, exception.getMessage(), getStackTrace(exception));
            case RELIABLE -> failureTracker.insertRow(createFailureRecord(record, "FAILED", exception, true));
            case NONE -> { /* No persistence */ }
        }
    }

    private void logFailure(ConsumerRecord<String, String> record, Exception exception, String stage) {
        log.error("Message {}:{}:{} failed during {} processing.",
            record.topic(), record.partition(), record.offset(), stage, exception);
    }

    private void logFailure(ConsumerRecord<String, String> record, Exception exception, int maxAttempts) {
        if (exception != null) {
            log.error("Message {}:{}:{} skipped after {} attempts.",
                record.topic(), record.partition(), record.offset(), maxAttempts, exception);
        } else {
            log.error("Message {}:{}:{} skipped after {} attempts due to unknown error.",
                record.topic(), record.partition(), record.offset(), maxAttempts);
        }
    }

    public void ensureReceivedRowExists(ConsumerRecord<String, String> record) {
        String dedupKey = createDedupKey(record);
        try {
            failureTracker.insertRow(createFailureRecord(record, "RECEIVED", null, false));
        } catch (RuntimeException ex) {
            if (isDuplicateConstraintViolation(ex)) {
                log.debug("Pre-insert of RECEIVED row skipped for dedup {} ({}).", dedupKey, ex.toString());
            } else {
                log.warn("Pre-insert of RECEIVED row failed for dedup {}: {}", dedupKey, ex.toString());
            }
        }
    }

    public void markSuccess(String dedupKey) {
        try {
            failureTracker.updateStatusSuccess(List.of(dedupKey));
        } catch (RuntimeException ex) {
            log.warn("Unable to mark {} as SUCCESS; message was already produced. Cause: {}", dedupKey, ex.toString());
        }
    }

    private Exception extractRootException(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException || current instanceof ExecutionException) {
            Throwable cause = current.getCause();
            if (cause == null) {
                break;
            }
            current = cause;
        }
        return current instanceof Exception ex ? ex : new RuntimeException(current);
    }

    private boolean isDuplicateConstraintViolation(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("duplicate")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private FailureRecord createFailureRecord(ConsumerRecord<String, String> record, String status, Exception exception, boolean isFailure) {
        FailureRecord.Builder builder = FailureRecord.builder()
            .sourceTopic(record.topic())
            .targetTopic(properties.getProducerTopic())
            .partition(record.partition())
            .offset(record.offset())
            .messageKey(record.key())
            .headersText("")
            .status(status)
            .dedupKey(createDedupKey(record))
            .errorMessage(exception == null ? null : exception.getMessage())
            .errorStack(exception == null ? null : getStackTrace(exception));

        if (shouldStorePayload(isFailure)) {
            setPayload(builder, record.value());
        }

        return builder.build();
    }

    private boolean shouldStorePayload(boolean isFailure) {
        return !"NONE".equalsIgnoreCase(properties.getStorePayload()) &&
            (!properties.isStorePayloadOnFailureOnly() || isFailure);
    }

    private void setPayload(FailureRecord.Builder builder, String value) {
        if ("BYTES".equalsIgnoreCase(properties.getStorePayload())) {
            builder.payloadBytes(value == null ? null : value.getBytes(StandardCharsets.UTF_8));
        } else {
            builder.payloadText(value);
        }
    }

    public String createDedupKey(ConsumerRecord<String, String> record) {
        return record.topic() + ":" + record.partition() + ":" + record.offset();
    }

    private String getStackTrace(Exception exception) {
        var sw = new java.io.StringWriter();
        exception.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }
}