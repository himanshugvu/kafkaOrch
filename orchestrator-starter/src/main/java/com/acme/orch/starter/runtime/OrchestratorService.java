package com.acme.orch.starter.runtime;

import com.acme.orch.core.MessageTransformer;
import com.acme.orch.db.FailureTracker;
import com.acme.orch.db.model.FailureRecord;
import com.acme.orch.starter.config.OrchestratorProperties;
import com.acme.orch.starter.config.OrchestratorProperties.DbStrategy;
import com.acme.orch.starter.config.OrchestratorProperties.FailureMode;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class OrchestratorService {
    private static final String DEDUP_HEADER = "orchestrator-dedup-key";

    private final KafkaTemplate<String, String> template;
    private final OrchestratorProperties props;
    private final MessageTransformer transformer;
    private final FailureTracker failureTracker;
    private final MeterRegistry meter;

    private final ExecutorService transformPool = Executors.newVirtualThreadPerTaskExecutor();

    public OrchestratorService(
        KafkaTemplate<String, String> template,
        OrchestratorProperties props,
        MessageTransformer transformer,
        FailureTracker failureTracker,
        MeterRegistry meter
    ) {
        this.template = template;
        this.props = props;
        this.transformer = transformer;
        this.failureTracker = failureTracker;
        this.meter = meter;
    }

    @KafkaListener(
        topics = "${orchestrator.consumer-topic}",
        containerFactory = "recordContainerFactory",
        autoStartup = "#{!@orchestratorProperties.batchEnabled}")
    public void onRecord(org.apache.kafka.clients.consumer.ConsumerRecord<String, String> rec,
                         Acknowledgment ack,
                         Consumer<?, ?> consumer) {

        if (props.getFailureMode() == FailureMode.SKIP_AND_LOG) {
            boolean receivedPreInserted = false;
            if (props.getDbStrategy() == DbStrategy.OUTBOX) {
                failureTracker.insertRow(row(rec, "RECEIVED", null, false));
                receivedPreInserted = true;
            }
            processRecordNonAtomic(rec, true, receivedPreInserted);
            ack.acknowledge();
            return;
        }

        final String dedupKey = dedupKey(rec.topic(), rec.partition(), rec.offset());

        template.executeInTransaction(ops -> {
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
            boolean outbox = props.getDbStrategy() == DbStrategy.OUTBOX;
            if (outbox) {
                failureTracker.bulkInsertReceived(records.stream()
                    .map(r -> row(r, "RECEIVED", null, false))
                    .toList());
            }
            for (var rec : records) {
                processRecordNonAtomic(rec, false, outbox);
            }
            ack.acknowledge();
            return;
        }

        if (props.getDbStrategy() == DbStrategy.OUTBOX) {
            failureTracker.bulkInsertReceived(records.stream().map(r -> row(r, "RECEIVED", null, false)).toList());
        } else if (props.getDbStrategy() == DbStrategy.RELIABLE) {
            records.forEach(r -> failureTracker.insertRow(row(r, "RECEIVED", null, false)));
        }

        template.executeInTransaction(ops -> {
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
                ops.send(producerRecord(rec, out, dedupKey(rec.topic(), rec.partition(), rec.offset()))).get();
            }

            HashMap<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
            records.forEach(r -> offsets.put(new TopicPartition(r.topic(), r.partition()), new OffsetAndMetadata(r.offset() + 1)));
            ops.sendOffsetsToTransaction(offsets, consumer.groupMetadata());
            return true;
        });

        if (props.getDbStrategy() != DbStrategy.LIGHTWEIGHT) {
            var keys = records.stream().map(r -> dedupKey(r.topic(), r.partition(), r.offset())).toList();
            failureTracker.updateStatusSuccess(keys);
        }

        ack.acknowledge();
        meter.counter("orch.batch.ok").increment(records.size());
    }

    private void processRecordNonAtomic(org.apache.kafka.clients.consumer.ConsumerRecord<String, String> rec,
                                        boolean recordMode,
                                        boolean receivedPreInserted) {
        DbStrategy strategy = props.getDbStrategy();
        String dedupKey = dedupKey(rec.topic(), rec.partition(), rec.offset());

        if (!receivedPreInserted && strategy == DbStrategy.RELIABLE) {
            failureTracker.insertRow(row(rec, "RECEIVED", null, false));
        }

        int attempts = Math.max(1, props.getNonAtomicMaxAttempts());
        Exception lastException = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                String out = transformer.transform(rec.value());
                template.send(producerRecord(rec, out, dedupKey)).get();

                if (strategy != DbStrategy.LIGHTWEIGHT) {
                    failureTracker.updateStatusSuccess(List.of(dedupKey));
                }
                if (recordMode) {
                    meter.counter("orch.records.ok").increment();
                } else {
                    meter.counter("orch.batch.ok").increment();
                }
                return;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                lastException = ie;
                break;
            } catch (Exception ex) {
                lastException = ex;
            }
        }

        recordNonAtomicFailure(rec, dedupKey, lastException);
    }

    private void recordNonAtomicFailure(org.apache.kafka.clients.consumer.ConsumerRecord<String, String> rec,
                                        String dedupKey,
                                        Exception ex) {
        DbStrategy strategy = props.getDbStrategy();
        switch (strategy) {
            case LIGHTWEIGHT -> failureTracker.insertLightweightFailure(row(rec, "FAILED", ex, true));
            case RELIABLE, OUTBOX -> failureTracker.updateStatusFailed(
                dedupKey,
                ex == null ? null : ex.getMessage(),
                ex == null ? null : stack(ex)
            );
        }
        meter.counter("orch.transform.fail").increment();
    }

    private void handleFailure(org.apache.kafka.clients.consumer.ConsumerRecord<String, String> rec,
                               Exception ex) {
        var failureRecord = row(rec, "FAILED", ex, true);
        switch (props.getDbStrategy()) {
            case LIGHTWEIGHT -> failureTracker.insertLightweightFailure(failureRecord);
            case RELIABLE, OUTBOX -> failureTracker.insertRow(failureRecord);
        }
        meter.counter("orch.transform.fail").increment();
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
