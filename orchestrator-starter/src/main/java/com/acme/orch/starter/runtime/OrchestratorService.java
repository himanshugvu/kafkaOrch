package com.acme.orch.starter.runtime;

import com.acme.orch.core.MessageTransformer;
import com.acme.orch.db.FailureTracker;
import com.acme.orch.db.model.FailureRecord;
import com.acme.orch.starter.config.OrchestratorProperties;
import com.acme.orch.starter.config.OrchestratorProperties.DbStrategy;
import com.acme.orch.starter.config.OrchestratorProperties.FailureMode;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class OrchestratorService {
    private static final Logger log = LoggerFactory.getLogger(OrchestratorService.class);
    private static final String DEDUP_HEADER = "orchestrator-dedup-key";

    private final KafkaTemplate<String, String> transactionalTemplate;
    private final KafkaTemplate<String, String> nonTransactionalTemplate;
    private final OrchestratorProperties props;
    private final MessageTransformer transformer;
    private final FailureTracker failureTracker;
    private final MeterRegistry meter;

    private final ExecutorService transformPool = Executors.newVirtualThreadPerTaskExecutor();

    public OrchestratorService(
        KafkaTemplate<String, String> transactionalTemplate,
        KafkaTemplate<String, String> nonTransactionalTemplate,
        OrchestratorProperties props,
        MessageTransformer transformer,
        FailureTracker failureTracker,
        MeterRegistry meter
    ) {
        this.transactionalTemplate = transactionalTemplate;
        this.nonTransactionalTemplate = nonTransactionalTemplate;
        this.props = props;
        this.transformer = transformer;
        this.failureTracker = failureTracker;
        this.meter = meter;
    }

    @PreDestroy
    void shutdown() {
        transformPool.shutdown();
    }

    @KafkaListener(
        topics = "${orchestrator.consumer-topic}",
        containerFactory = "recordContainerFactory",
        autoStartup = "#{!@orchestratorProperties.batchEnabled}")
    public void onRecord(org.apache.kafka.clients.consumer.ConsumerRecord<String, String> rec,
                         Acknowledgment ack,
                         Consumer<?, ?> consumer) {

        if (props.getFailureMode() == FailureMode.SKIP_AND_LOG) {
            processRecordNonAtomic(rec, true);
            ack.acknowledge();
            return;
        }

        final String dedupKey = dedupKey(rec.topic(), rec.partition(), rec.offset());
        MDC.put("dedupKey", dedupKey);
        try {
            transactionalTemplate.executeInTransaction(ops -> {
                try {
                    String out = transformer.transform(rec.value());
                    ops.send(producerRecord(rec, out, dedupKey)).get();

                    var offsets = new HashMap<TopicPartition, OffsetAndMetadata>(1);
                    offsets.put(new TopicPartition(rec.topic(), rec.partition()), new OffsetAndMetadata(rec.offset() + 1));
                    ops.sendOffsetsToTransaction(offsets, consumer.groupMetadata());
                    return true;
                } catch (Exception ex) {
                    handleFailure(rec, ex);
                    throw new RuntimeException(ex);
                }
            });
        } finally {
            MDC.remove("dedupKey");
        }

        ack.acknowledge();
        meter.counter("orch.records.ok").increment();
    }

    @KafkaListener(
        topics = "${orchestrator.consumer-topic}",
        containerFactory = "batchContainerFactory",
        autoStartup = "#{@orchestratorProperties.batchEnabled}")
    public void onBatch(List<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> records,
                        Acknowledgment ack,
                        Consumer<?, ?> consumer) {

        if (records.isEmpty()) {
            return;
        }

        if (props.getFailureMode() == FailureMode.SKIP_AND_LOG) {
            records.forEach(rec -> processRecordNonAtomic(rec, false));
            ack.acknowledge();
            return;
        }

        if (props.getDbStrategy() == DbStrategy.OUTBOX) {
            failureTracker.bulkInsertReceived(records.stream().map(r -> row(r, "RECEIVED", null, false)).toList());
        } else if (props.getDbStrategy() == DbStrategy.RELIABLE) {
            records.forEach(r -> failureTracker.insertRow(row(r, "RECEIVED", null, false)));
        }

        transactionalTemplate.executeInTransaction(ops -> {
            List<CompletableFuture<String>> futures = records.stream()
                .map(r -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return transformer.transform(r.value());
                    } catch (Exception e) {
                        throw new CompletionException(e);
                    }
                }, transformPool)).toList();

            List<String> outs = futures.stream().map(CompletableFuture::join).toList();

            for (int i = 0; i < records.size(); i++) {
                var rec = records.get(i);
                var out = outs.get(i);
                String perRecordDedup = dedupKey(rec.topic(), rec.partition(), rec.offset());
                MDC.put("dedupKey", perRecordDedup);
                try {
                    ops.send(producerRecord(rec, out, perRecordDedup)).get();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ie);
                } catch (java.util.concurrent.ExecutionException ee) {
                    Throwable cause = ee.getCause();
                    throw new RuntimeException(cause != null ? cause : ee);
                } finally {
                    MDC.remove("dedupKey");
                }
            }

            HashMap<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
            records.forEach(r -> offsets.put(new TopicPartition(r.topic(), r.partition()), new OffsetAndMetadata(r.offset() + 1)));
            ops.sendOffsetsToTransaction(offsets, consumer.groupMetadata());
            return true;
        });

        if (props.getDbStrategy() == DbStrategy.RELIABLE || props.getDbStrategy() == DbStrategy.OUTBOX) {
            var keys = records.stream().map(r -> dedupKey(r.topic(), r.partition(), r.offset())).toList();
            failureTracker.updateStatusSuccess(keys);
        }

        ack.acknowledge();
        meter.counter("orch.batch.ok").increment(records.size());
    }

    private void processRecordNonAtomic(org.apache.kafka.clients.consumer.ConsumerRecord<String, String> rec,
                                        boolean recordMode) {
        DbStrategy strategy = props.getDbStrategy();
        String dedupKey = dedupKey(rec.topic(), rec.partition(), rec.offset());

        MDC.put("dedupKey", dedupKey);
        try {
            if (strategy == DbStrategy.OUTBOX || strategy == DbStrategy.RELIABLE) {
                ensureReceivedRow(rec, dedupKey);
            }

            int attempts = Math.max(1, props.getNonAtomicMaxAttempts());
            Exception lastException = null;
            for (int attempt = 1; attempt <= attempts; attempt++) {
                try {
                    String out = transformer.transform(rec.value());
                    nonTransactionalTemplate.send(producerRecord(rec, out, dedupKey)).get();

                    if (strategy == DbStrategy.RELIABLE || strategy == DbStrategy.OUTBOX) {
                        markSuccess(dedupKey);
                    }
                    incrementSuccessCounter(recordMode);
                    return;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    lastException = ie;
                    break;
                } catch (ExecutionException ee) {
                    Throwable cause = ee.getCause();
                    lastException = cause instanceof Exception ? (Exception) cause : new RuntimeException(cause);
                } catch (Exception ex) {
                    lastException = ex;
                }
            }

            recordNonAtomicFailure(rec, dedupKey, lastException);
        } finally {
            MDC.remove("dedupKey");
        }
    }

    private void ensureReceivedRow(org.apache.kafka.clients.consumer.ConsumerRecord<String, String> rec,
                                   String dedupKey) {
        try {
            failureTracker.insertRow(row(rec, "RECEIVED", null, false));
        } catch (RuntimeException ex) {
            if (isDuplicateConstraint(ex)) {
                log.debug("Pre-insert of RECEIVED row skipped for dedup {} ({}).", dedupKey, ex.toString());
            } else {
                log.warn("Pre-insert of RECEIVED row failed for dedup {}: {}", dedupKey, ex.toString());
            }
        }
    }

    private void markSuccess(String dedupKey) {
        try {
            failureTracker.updateStatusSuccess(List.of(dedupKey));
        } catch (RuntimeException ex) {
            log.warn("Unable to mark {} as SUCCESS; message was already produced. Cause: {}", dedupKey, ex.toString());
        }
    }

    private void incrementSuccessCounter(boolean recordMode) {
        if (recordMode) {
            meter.counter("orch.records.ok").increment();
        } else {
            meter.counter("orch.batch.ok").increment();
        }
    }

    private void recordNonAtomicFailure(org.apache.kafka.clients.consumer.ConsumerRecord<String, String> rec,
                                        String dedupKey,
                                        Exception ex) {
        DbStrategy strategy = props.getDbStrategy();
        meter.counter("orch.transform.fail").increment();
        try {
            switch (strategy) {
                case LIGHTWEIGHT -> failureTracker.insertLightweightFailure(row(rec, "FAILED", ex, true));
                case RELIABLE, OUTBOX -> failureTracker.updateStatusFailed(
                    dedupKey,
                    ex == null ? null : ex.getMessage(),
                    ex == null ? null : stack(ex)
                );
                case NONE -> { }
            }
        } catch (RuntimeException dbEx) {
            log.error("Failed to persist failure metadata for dedup {}: {}", dedupKey, dbEx.toString());
        }

        if (ex != null) {
            log.error("Message {}:{}:{} skipped after {} attempts.", rec.topic(), rec.partition(), rec.offset(),
                props.getNonAtomicMaxAttempts(), ex);
        } else {
            log.error("Message {}:{}:{} skipped after {} attempts due to unknown error.",
                rec.topic(), rec.partition(), rec.offset(), props.getNonAtomicMaxAttempts());
        }
    }

    private void handleFailure(org.apache.kafka.clients.consumer.ConsumerRecord<String, String> rec,
                               Exception ex) {
        String dedupKey = dedupKey(rec.topic(), rec.partition(), rec.offset());
        MDC.put("dedupKey", dedupKey);
        try {
            var failureRecord = row(rec, "FAILED", ex, true);
            switch (props.getDbStrategy()) {
                case LIGHTWEIGHT -> failureTracker.insertLightweightFailure(failureRecord);
                case RELIABLE, OUTBOX -> failureTracker.insertRow(failureRecord);
                case NONE -> { }
            }
            meter.counter("orch.transform.fail").increment();
        } finally {
            MDC.remove("dedupKey");
        }
    }

    private ProducerRecord<String, String> producerRecord(
        org.apache.kafka.clients.consumer.ConsumerRecord<String, String> rec,
        String value,
        String dedupKey
    ) {
        var pr = new ProducerRecord<>(props.getProducerTopic(), rec.key(), value);
        pr.headers().add(new RecordHeader(DEDUP_HEADER, dedupKey.getBytes(StandardCharsets.UTF_8)));
        return pr;
    }

    private String dedupKey(String topic, int partition, long offset) {
        return topic + ":" + partition + ":" + offset;
    }

    private boolean isDuplicateConstraint(Throwable ex) {
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

    private FailureRecord row(org.apache.kafka.clients.consumer.ConsumerRecord<String, String> rec,
                              String status,
                              Exception ex,
                              boolean isFailure) {
        FailureRecord.Builder builder = FailureRecord.builder()
            .sourceTopic(rec.topic())
            .targetTopic(props.getProducerTopic())
            .partition(rec.partition())
            .offset(rec.offset())
            .messageKey(rec.key())
            .headersText("")
            .status(status)
            .dedupKey(dedupKey(rec.topic(), rec.partition(), rec.offset()))
            .errorMessage(ex == null ? null : ex.getMessage())
            .errorStack(ex == null ? null : stack(ex));

        boolean storePayload =
            !"NONE".equalsIgnoreCase(props.getStorePayload()) &&
                (!props.isStorePayloadOnFailureOnly() || isFailure);

        if (storePayload) {
            if ("BYTES".equalsIgnoreCase(props.getStorePayload())) {
                builder.payloadBytes(rec.value() == null ? null : rec.value().getBytes(StandardCharsets.UTF_8));
            } else {
                builder.payloadText(rec.value());
            }
        }
        return builder.build();
    }

    private String stack(Exception ex) {
        var sw = new java.io.StringWriter();
        ex.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }
}
