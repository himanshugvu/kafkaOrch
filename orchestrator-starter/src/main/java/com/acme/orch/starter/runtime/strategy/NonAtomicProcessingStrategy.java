package com.acme.orch.starter.runtime.strategy;

import com.acme.orch.starter.runtime.service.FailureHandlingService;
import com.acme.orch.starter.runtime.service.MessageTransformationPipeline;
import com.acme.orch.starter.runtime.service.AcknowledgmentManager;
import com.acme.orch.starter.config.OrchestratorProperties;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class NonAtomicProcessingStrategy implements MessageProcessingStrategy {

    private final MessageTransformationPipeline transformationPipeline;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final FailureHandlingService failureHandlingService;
    private final AcknowledgmentManager acknowledgmentManager;
    private final OrchestratorProperties properties;

    public NonAtomicProcessingStrategy(
        MessageTransformationPipeline transformationPipeline,
        KafkaTemplate<String, String> kafkaTemplate,
        FailureHandlingService failureHandlingService,
        AcknowledgmentManager acknowledgmentManager,
        OrchestratorProperties properties
    ) {
        this.transformationPipeline = transformationPipeline;
        this.kafkaTemplate = kafkaTemplate;
        this.failureHandlingService = failureHandlingService;
        this.acknowledgmentManager = acknowledgmentManager;
        this.properties = properties;
    }

    @Override
    public CompletableFuture<Void> processRecord(
        ConsumerRecord<String, String> record,
        Acknowledgment acknowledgment,
        Consumer<?, ?> consumer
    ) {
        return processWithRetry(record, acknowledgment, properties.getNonAtomicMaxAttempts());
    }

    @Override
    public CompletableFuture<Void> processBatch(
        List<ConsumerRecord<String, String>> records,
        Acknowledgment acknowledgment,
        Consumer<?, ?> consumer
    ) {
        return CompletableFuture.allOf(
            records.stream()
                .map(record -> processRecord(record, null, consumer))
                .toArray(CompletableFuture[]::new)
        ).handle((result, throwable) -> {
            acknowledgment.acknowledge();
            return null;
        });
    }

    private CompletableFuture<Void> processWithRetry(
        ConsumerRecord<String, String> record,
        Acknowledgment acknowledgment,
        int remainingAttempts
    ) {
        return transformationPipeline.transformAsync(record.value())
            .thenCompose(transformedMessage ->
                kafkaTemplate.send(properties.getProducerTopic(), record.key(), transformedMessage))
            .handle((result, throwable) -> {
                if (throwable == null) {
                    acknowledgmentManager.markSuccess(record, acknowledgment);
                    return CompletableFuture.<Void>completedFuture(null);
                }

                if (remainingAttempts > 1) {
                    return processWithRetry(record, acknowledgment, remainingAttempts - 1);
                }

                failureHandlingService.handleNonAtomicFailure(record, throwable);
                acknowledgmentManager.markFailure(record);
                return CompletableFuture.<Void>completedFuture(null);
            })
            .thenCompose(future -> future);
    }

    @Override
    public String getStrategyName() {
        return "NON_ATOMIC";
    }
}