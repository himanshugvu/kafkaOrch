package com.acme.orch.starter.runtime.strategy;

import com.acme.orch.starter.config.OrchestratorProperties;
import com.acme.orch.starter.runtime.service.FailureHandlingService;
import com.acme.orch.starter.runtime.service.MessageTransformationPipeline;
import com.acme.orch.starter.runtime.service.TransactionManager;
import com.acme.orch.starter.runtime.service.AcknowledgmentManager;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.support.Acknowledgment;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class AtomicProcessingStrategy implements MessageProcessingStrategy {

    private final MessageTransformationPipeline transformationPipeline;
    private final TransactionManager transactionManager;
    private final FailureHandlingService failureHandlingService;
    private final AcknowledgmentManager acknowledgmentManager;
    private final OrchestratorProperties properties;

    public AtomicProcessingStrategy(
        MessageTransformationPipeline transformationPipeline,
        TransactionManager transactionManager,
        FailureHandlingService failureHandlingService,
        AcknowledgmentManager acknowledgmentManager,
        OrchestratorProperties properties
    ) {
        this.transformationPipeline = transformationPipeline;
        this.transactionManager = transactionManager;
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
        String dedupKey = failureHandlingService.createDedupKey(record);

        // Ensure received row exists for outbox pattern
        if (properties.getDbStrategy() == OrchestratorProperties.DbStrategy.OUTBOX) {
            failureHandlingService.ensureReceivedRowExists(record);
        }

        return transformationPipeline.transformAsync(record.value())
            .thenCompose(transformedMessage ->
                transactionManager.executeInTransaction(record, transformedMessage, consumer))
            .handle((result, throwable) -> {
                if (throwable == null) {
                    if (properties.getDbStrategy() == OrchestratorProperties.DbStrategy.OUTBOX) {
                        failureHandlingService.markSuccess(dedupKey);
                    }
                    acknowledgmentManager.markSuccess(record, acknowledgment);
                } else {
                    failureHandlingService.handleFailure(record, throwable);
                    acknowledgmentManager.markFailure(record);
                    acknowledgmentManager.safeNack(acknowledgment);
                }
                return null;
            });
    }

    @Override
    public CompletableFuture<Void> processBatch(
        List<ConsumerRecord<String, String>> records,
        Acknowledgment acknowledgment,
        Consumer<?, ?> consumer
    ) {
        // Initialize batch processing with database strategy
        if (properties.getDbStrategy() == OrchestratorProperties.DbStrategy.OUTBOX) {
            // Bulk insert all received records
            records.forEach(record -> failureHandlingService.ensureReceivedRowExists(record));
        }

        return transformationPipeline.transformBatchAsync(records)
            .thenCompose(transformedMessages ->
                transactionManager.executeBatchInTransaction(records, transformedMessages, consumer))
            .handle((result, throwable) -> {
                if (throwable == null) {
                    if (properties.getDbStrategy() == OrchestratorProperties.DbStrategy.OUTBOX) {
                        var keys = records.stream()
                            .map(failureHandlingService::createDedupKey)
                            .toList();
                        records.forEach(record -> failureHandlingService.markSuccess(failureHandlingService.createDedupKey(record)));
                    }
                    acknowledgmentManager.markBatchSuccess(records, acknowledgment);
                } else {
                    failureHandlingService.handleBatchFailure(records, throwable);
                    acknowledgmentManager.safeNack(acknowledgment);
                }
                return null;
            });
    }

    @Override
    public String getStrategyName() {
        return "ATOMIC";
    }
}