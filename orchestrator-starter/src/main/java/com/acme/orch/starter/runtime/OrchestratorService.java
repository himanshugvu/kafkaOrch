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
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.kafka.support.Acknowledgment;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class OrchestratorService {
    private static final Logger log = LoggerFactory.getLogger(OrchestratorService.class);
    private static final String DEDUP_HEADER = "orchestrator-dedup-key";

    private final KafkaTemplate<String, String> transactionalTemplate;
    private final KafkaTemplate<String, String> nonTransactionalTemplate;
    private final OrchestratorProperties props;
    private final MessageTransformer transformer;
    private final FailureTracker failureTracker;
    private final MeterRegistry meter;
    private final AckManager ackManager;

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
        this.ackManager = new AckManager(props.getCommitBatchSize(), props.getCommitFlushIntervalMs());
    }

    @PreDestroy
    void shutdown() {
        transformPool.shutdown();
        ackManager.close();
    }

    @KafkaListener(
        topics = "${orchestrator.consumer-topic}",
        containerFactory = "recordContainerFactory",
        autoStartup = "#{!@orchestratorProperties.batchEnabled}")
    public void onRecord(org.apache.kafka.clients.consumer.ConsumerRecord<String, String> rec,
                         Acknowledgment ack,
                         Consumer<?, ?> consumer) {

        FailureMode mode = effectiveFailureMode();
        if (mode == FailureMode.SKIP_AND_LOG) {
            processRecordNonAtomicAsync(rec, ack, true);
            return;
        }

        final String dedupKey = dedupKey(rec.topic(), rec.partition(), rec.offset());
        if (props.getDbStrategy() == DbStrategy.OUTBOX) {
            ensureReceivedRow(rec, dedupKey);
        }

        CompletableFuture.supplyAsync(() -> transformer.transform(rec.value()), transformPool)
            .thenAcceptAsync(out -> {
                MDC.put("dedupKey", dedupKey);
                try {
                    transactionalTemplate.executeInTransaction(ops -> {
                        ops.send(producerRecord(rec, out, dedupKey));
                        var offsets = new HashMap<TopicPartition, OffsetAndMetadata>(1);
                        offsets.put(new TopicPartition(rec.topic(), rec.partition()), new OffsetAndMetadata(rec.offset() + 1));
                        ops.sendOffsetsToTransaction(offsets, consumer.groupMetadata());
                        return true;
                    });
                } finally {
                    MDC.remove("dedupKey");
                }
            }, transformPool)
            .whenComplete((ignored, throwable) -> {
                if (throwable == null) {
                    if (props.getDbStrategy() == DbStrategy.OUTBOX) {
                        markSuccess(dedupKey);
                    }
                    incrementSuccessCounter(true);
                    ackManager.markSuccess(rec.topic(), rec.partition(), rec.offset(), ack);
                } else {
                    ackManager.markFailure(rec.topic(), rec.partition());
                    Exception failure = asException(throwable);
                    handleFailure(rec, failure);
                    safeNack(ack);
                }
            });
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

        FailureMode mode = effectiveFailureMode();
        if (mode == FailureMode.SKIP_AND_LOG) {
            List<CompletableFuture<Void>> futures = records.stream()
                .map(rec -> processRecordNonAtomicAsync(rec, null, false))
                .toList();
            CompletableFuture<?>[] futureArray = futures.toArray(new CompletableFuture[0]);
            CompletableFuture.allOf(futureArray)
                .whenComplete((ignored, throwable) -> {
                    ack.acknowledge();
                    meter.counter("orch.batch.ok").increment(records.size());
                });
            return;
        }

        if (props.getDbStrategy() == DbStrategy.OUTBOX) {
            failureTracker.bulkInsertReceived(records.stream().map(r -> row(r, "RECEIVED", null, false)).toList());
        } else if (props.getDbStrategy() == DbStrategy.RELIABLE) {
            records.forEach(r -> failureTracker.insertRow(row(r, "RECEIVED", null, false)));
        }

        CompletableFuture.runAsync(() -> {
            List<CompletableFuture<String>> futures = records.stream()
                .map(r -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return transformer.transform(r.value());
                    } catch (Exception e) {
                        throw new CompletionException(e);
                    }
                }, transformPool))
                .toList();

            List<String> outs = new ArrayList<>(records.size());
            for (int i = 0; i < records.size(); i++) {
                var rec = records.get(i);
                try {
                    outs.add(futures.get(i).join());
                } catch (CompletionException | CancellationException ce) {
                    Exception failure = asException(ce);
                    recordAtomicFailure(rec, failure, "transform");
                    throw new CompletionException(failure);
                }
            }

            transactionalTemplate.executeInTransaction(ops -> {
                for (int i = 0; i < records.size(); i++) {
                    var rec = records.get(i);
                    var out = outs.get(i);
                    String perRecordDedup = dedupKey(rec.topic(), rec.partition(), rec.offset());
                    MDC.put("dedupKey", perRecordDedup);
                    try {
                        ops.send(producerRecord(rec, out, perRecordDedup));
                    } finally {
                        MDC.remove("dedupKey");
                    }
                }

                HashMap<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
                records.forEach(r -> offsets.put(new TopicPartition(r.topic(), r.partition()), new OffsetAndMetadata(r.offset() + 1)));
                ops.sendOffsetsToTransaction(offsets, consumer.groupMetadata());
                return true;
            });
        }, transformPool).whenComplete((ignored, throwable) -> {
            if (throwable == null) {
                    if (props.getDbStrategy() == DbStrategy.OUTBOX) {
                    var keys = records.stream().map(r -> dedupKey(r.topic(), r.partition(), r.offset())).toList();
                    failureTracker.updateStatusSuccess(keys);
                }
                ack.acknowledge();
                meter.counter("orch.batch.ok").increment(records.size());
            } else {
                Exception failure = asException(throwable);
                records.forEach(rec -> {
                    String key = dedupKey(rec.topic(), rec.partition(), rec.offset());
                    try {
                        failureTracker.updateStatusFailed(key, failure.getMessage(), stack(failure));
                    } catch (RuntimeException dbEx) {
                        log.error("Failed to persist failure metadata for dedup {}: {}", key, dbEx.toString());
                    }
                });
                log.error("Batch processing failed; scheduling retry. cause={}", failure.toString());
                safeNack(ack);
            }
        });
    }

    private CompletableFuture<Void> processRecordNonAtomicAsync(
        org.apache.kafka.clients.consumer.ConsumerRecord<String, String> rec,
        Acknowledgment ack,
        boolean recordMode) {

        DbStrategy strategy = props.getDbStrategy();
        String dedupKey = dedupKey(rec.topic(), rec.partition(), rec.offset());

        if (strategy == DbStrategy.OUTBOX || strategy == DbStrategy.RELIABLE) {
            ensureReceivedRow(rec, dedupKey);
        }

        int attempts = Math.max(1, props.getNonAtomicMaxAttempts());
        return attemptNonAtomic(rec, ack, recordMode, strategy, dedupKey, attempts);
    }

    private CompletableFuture<Void> attemptNonAtomic(
        org.apache.kafka.clients.consumer.ConsumerRecord<String, String> rec,
        Acknowledgment ack,
        boolean recordMode,
        DbStrategy strategy,
        String dedupKey,
        int remainingAttempts) {

        return CompletableFuture.supplyAsync(() -> transformer.transform(rec.value()), transformPool)
            .thenCompose(out -> toCompletableFuture(nonTransactionalTemplate.send(producerRecord(rec, out, dedupKey)))
                .thenApply(ignored -> out))
            .thenApply(out -> {
                if (strategy == DbStrategy.RELIABLE || strategy == DbStrategy.OUTBOX) {
                    markSuccess(dedupKey);
                }
                if (recordMode) {
                    incrementSuccessCounter(true);
                }
                if (ack != null) {
                    ackManager.markSuccess(rec.topic(), rec.partition(), rec.offset(), ack);
                }
                return null;
            })
            .handle((ignored, throwable) -> {
                if (throwable == null) {
                    return CompletableFuture.<Void>completedFuture(null);
                }
                Exception failure = asException(throwable);
                if (remainingAttempts > 1) {
                    return attemptNonAtomic(rec, ack, recordMode, strategy, dedupKey, remainingAttempts - 1);
                }
                recordNonAtomicFailure(rec, dedupKey, failure);
                if (ack != null) {
                    ackManager.markFailure(rec.topic(), rec.partition());
                    ack.acknowledge();
                }
                return CompletableFuture.<Void>completedFuture(null);
            })
            .thenCompose(f -> f);
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

    private static final class AckManager implements AutoCloseable {
        private final int batchSize;
        private final long flushIntervalMs;
        private final ConcurrentHashMap<PartitionKey, PartitionWindow> windows = new ConcurrentHashMap<>();
        private final ScheduledExecutorService scheduler;

        AckManager(int batchSize, long flushIntervalMs) {
            this.batchSize = Math.max(1, batchSize);
            this.flushIntervalMs = Math.max(0L, flushIntervalMs);
            this.scheduler = this.flushIntervalMs > 0
                ? Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "orch-ack-flush");
                    t.setDaemon(true);
                    return t;
                })
                : null;
        }

        void markSuccess(String topic, int partition, long offset, Acknowledgment ack) {
            if (ack == null) {
                return;
            }
            PartitionKey key = new PartitionKey(topic, partition);
            PartitionWindow window = windows.computeIfAbsent(key, k -> new PartitionWindow(this.batchSize, this.flushIntervalMs));
            WindowResult result = window.recordSuccess(offset, ack);
            if (result.toCommit() != null) {
                commit(result.toCommit());
            }
            if (result.scheduleFlush() && scheduler != null) {
                scheduleFlush(key, window);
            }
        }

        void markFailure(String topic, int partition) {
            PartitionWindow window = windows.get(new PartitionKey(topic, partition));
            if (window == null) {
                return;
            }
            Acknowledgment toCommit = window.flush(true);
            if (toCommit != null) {
                commit(toCommit);
            }
        }

        void flushAll() {
            windows.values().forEach(window -> {
                Acknowledgment toCommit = window.flush(true);
                if (toCommit != null) {
                    commit(toCommit);
                }
            });
        }

        private void scheduleFlush(PartitionKey key, PartitionWindow window) {
            if (scheduler == null) {
                return;
            }
            try {
                scheduler.schedule(() -> {
                    Acknowledgment toCommit = window.flush(false);
                    if (toCommit != null) {
                        commit(toCommit);
                    }
                }, flushIntervalMs, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException ex) {
                Acknowledgment toCommit = window.flush(true);
                if (toCommit != null) {
                    commit(toCommit);
                }
            }
        }

        private void commit(Acknowledgment ack) {
            try {
                ack.acknowledge();
            } catch (RuntimeException ex) {
                log.warn("Failed to acknowledge committed offsets: {}", ex.toString());
            }
        }

        @Override
        public void close() {
            flushAll();
            if (scheduler != null) {
                scheduler.shutdownNow();
            }
        }

        private record PartitionKey(String topic, int partition) { }

        private static final class PartitionWindow {
            private final int batchSize;
            private final long flushIntervalMs;
            private final NavigableMap<Long, Acknowledgment> pending = new TreeMap<>();
            private long highestCommitted = Long.MIN_VALUE;
            private long highestReadyOffset = Long.MIN_VALUE;
            private Acknowledgment highestReadyAck;
            private int readyCount;
            private long lastFlushTs = System.currentTimeMillis();
            private boolean initialised;
            private boolean flushScheduled;

            PartitionWindow(int batchSize, long flushIntervalMs) {
                this.batchSize = batchSize;
                this.flushIntervalMs = flushIntervalMs;
            }

            synchronized WindowResult recordSuccess(long offset, Acknowledgment ack) {
                if (!initialised) {
                    highestCommitted = offset - 1;
                    initialised = true;
                }
                pending.put(offset, ack);
                long candidate = highestCommitted + 1;
                while (true) {
                    Acknowledgment next = pending.remove(candidate);
                    if (next == null) {
                        break;
                    }
                    highestReadyOffset = candidate;
                    highestReadyAck = next;
                    readyCount++;
                    candidate++;
                }
                Acknowledgment toCommit = maybeFlush(false);
                if (toCommit != null) {
                    flushScheduled = false;
                    return new WindowResult(toCommit, false);
                }
                if (flushIntervalMs > 0 && readyCount > 0 && !flushScheduled) {
                    flushScheduled = true;
                    return new WindowResult(null, true);
                }
                return new WindowResult(null, false);
            }

            synchronized Acknowledgment flush(boolean force) {
                flushScheduled = false;
                return maybeFlush(force);
            }

            private Acknowledgment maybeFlush(boolean force) {
                if (readyCount == 0 || highestReadyAck == null) {
                    return null;
                }
                long now = System.currentTimeMillis();
                if (!force && readyCount < batchSize) {
                    if (flushIntervalMs <= 0 || now - lastFlushTs < flushIntervalMs) {
                        return null;
                    }
                }
                Acknowledgment toCommit = highestReadyAck;
                highestCommitted = highestReadyOffset;
                readyCount = 0;
                highestReadyAck = null;
                lastFlushTs = now;
                return toCommit;
            }
        }

        private record WindowResult(Acknowledgment toCommit, boolean scheduleFlush) { }
    }
    private FailureMode effectiveFailureMode() {
        if (props.getFailureMode() == FailureMode.SKIP_AND_LOG) {
            return FailureMode.SKIP_AND_LOG;
        }
        if (failureTracker.isCircuitOpen() || failureTracker.isDbDegraded()) {
            return FailureMode.SKIP_AND_LOG;
        }
        return FailureMode.ATOMIC;
    }

    private void safeNack(Acknowledgment ack) {
        if (ack == null) {
            return;
        }
        try {
            ack.nack(Duration.ZERO);
        } catch (UnsupportedOperationException | IllegalStateException ex) {
            log.warn("Nack not supported; relying on consumer retry. cause={}", ex.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private <T> CompletableFuture<T> toCompletableFuture(Object future) {
        if (future instanceof CompletableFuture<?> cf) {
            return (CompletableFuture<T>) cf;
        }
        if (future instanceof ListenableFuture<?> lf) {
            CompletableFuture<T> completable = new CompletableFuture<>();
            ((ListenableFuture<T>) lf).addCallback(completable::complete, completable::completeExceptionally);
            return completable;
        }
        throw new IllegalArgumentException("Unsupported future type: " + future);
    }

    private void recordAtomicFailure(org.apache.kafka.clients.consumer.ConsumerRecord<String, String> rec,
                                     Exception ex,
                                     String stage) {
        String dedupKey = dedupKey(rec.topic(), rec.partition(), rec.offset());
        MDC.put("dedupKey", dedupKey);
        try {
            meter.counter("orch.transform.fail").increment();
            try {
                switch (props.getDbStrategy()) {
                    case LIGHTWEIGHT -> failureTracker.insertLightweightFailure(row(rec, "FAILED", ex, true));
                    case OUTBOX -> failureTracker.updateStatusFailed(dedupKey, ex.getMessage(), stack(ex));
                    case RELIABLE -> failureTracker.insertRow(row(rec, "FAILED", ex, true));
                    case NONE -> { }
                }
            } catch (RuntimeException dbEx) {
                log.error("Failed to persist failure metadata for dedup {}: {}", dedupKey, dbEx.toString());
            }
            log.error("Message {}:{}:{} failed during {} processing.", rec.topic(), rec.partition(), rec.offset(), stage, ex);
        } finally {
            MDC.remove("dedupKey");
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
        recordAtomicFailure(rec, ex, "transaction");
    }

    private Exception asException(Throwable throwable) {
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







