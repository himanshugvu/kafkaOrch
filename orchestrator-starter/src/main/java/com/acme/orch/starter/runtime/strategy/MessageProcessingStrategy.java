package com.acme.orch.starter.runtime.strategy;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.support.Acknowledgment;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface MessageProcessingStrategy {

    CompletableFuture<Void> processRecord(
        ConsumerRecord<String, String> record,
        Acknowledgment acknowledgment,
        Consumer<?, ?> consumer
    );

    CompletableFuture<Void> processBatch(
        List<ConsumerRecord<String, String>> records,
        Acknowledgment acknowledgment,
        Consumer<?, ?> consumer
    );

    String getStrategyName();
}