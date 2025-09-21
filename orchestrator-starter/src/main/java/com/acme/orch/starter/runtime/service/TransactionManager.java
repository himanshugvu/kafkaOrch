package com.acme.orch.starter.runtime.service;

import com.acme.orch.starter.config.OrchestratorProperties;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class TransactionManager {

    private static final String DEDUP_HEADER = "orchestrator-dedup-key";

    private final KafkaTemplate<String, String> transactionalTemplate;
    private final OrchestratorProperties properties;
    private final FailureHandlingService failureHandlingService;

    public TransactionManager(
        KafkaTemplate<String, String> transactionalTemplate,
        OrchestratorProperties properties,
        FailureHandlingService failureHandlingService
    ) {
        this.transactionalTemplate = transactionalTemplate;
        this.properties = properties;
        this.failureHandlingService = failureHandlingService;
    }

    public CompletableFuture<Void> executeInTransaction(
        ConsumerRecord<String, String> record,
        String transformedMessage,
        Consumer<?, ?> consumer
    ) {
        String dedupKey = failureHandlingService.createDedupKey(record);

        return CompletableFuture.runAsync(() -> {
            MDC.put("dedupKey", dedupKey);
            try {
                transactionalTemplate.executeInTransaction(operations -> {
                    ProducerRecord<String, String> producerRecord = createProducerRecord(record, transformedMessage, dedupKey);
                    operations.send(producerRecord);

                    var offsets = new HashMap<TopicPartition, OffsetAndMetadata>(1);
                    offsets.put(
                        new TopicPartition(record.topic(), record.partition()),
                        new OffsetAndMetadata(record.offset() + 1)
                    );
                    operations.sendOffsetsToTransaction(offsets, consumer.groupMetadata());
                    return null;
                });
            } finally {
                MDC.remove("dedupKey");
            }
        });
    }

    public CompletableFuture<Void> executeBatchInTransaction(
        List<ConsumerRecord<String, String>> records,
        List<String> transformedMessages,
        Consumer<?, ?> consumer
    ) {
        return CompletableFuture.runAsync(() -> {
            transactionalTemplate.executeInTransaction(operations -> {
                for (int i = 0; i < records.size(); i++) {
                    ConsumerRecord<String, String> record = records.get(i);
                    String transformedMessage = transformedMessages.get(i);
                    String dedupKey = failureHandlingService.createDedupKey(record);

                    MDC.put("dedupKey", dedupKey);
                    try {
                        ProducerRecord<String, String> producerRecord = createProducerRecord(record, transformedMessage, dedupKey);
                        operations.send(producerRecord);
                    } finally {
                        MDC.remove("dedupKey");
                    }
                }

                HashMap<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
                records.forEach(record ->
                    offsets.put(
                        new TopicPartition(record.topic(), record.partition()),
                        new OffsetAndMetadata(record.offset() + 1)
                    )
                );
                operations.sendOffsetsToTransaction(offsets, consumer.groupMetadata());
                return null;
            });
        });
    }

    private ProducerRecord<String, String> createProducerRecord(
        ConsumerRecord<String, String> sourceRecord,
        String transformedMessage,
        String dedupKey
    ) {
        ProducerRecord<String, String> producerRecord = new ProducerRecord<>(
            properties.getProducerTopic(),
            sourceRecord.key(),
            transformedMessage
        );
        producerRecord.headers().add(new RecordHeader(DEDUP_HEADER, dedupKey.getBytes(StandardCharsets.UTF_8)));
        return producerRecord;
    }
}