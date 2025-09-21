package com.acme.orch.starter.runtime;

import com.acme.orch.core.MessageTransformer;
import com.acme.orch.db.FailureTracker;
import com.acme.orch.starter.config.OrchestratorProperties;
import com.acme.orch.starter.config.OrchestratorProperties.FailureMode;
import com.acme.orch.starter.runtime.service.AcknowledgmentManager;
import com.acme.orch.starter.runtime.service.FailureHandlingService;
import com.acme.orch.starter.runtime.service.MessageTransformationPipeline;
import com.acme.orch.starter.runtime.service.MetricsService;
import com.acme.orch.starter.runtime.service.TransactionManager;
import com.acme.orch.starter.runtime.strategy.AtomicProcessingStrategy;
import com.acme.orch.starter.runtime.strategy.MessageProcessingStrategy;
import com.acme.orch.starter.runtime.strategy.NonAtomicProcessingStrategy;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RefactoredOrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(RefactoredOrchestratorService.class);

    private final OrchestratorProperties properties;
    private final FailureTracker failureTracker;
    private final ExecutorService transformPool;

    private final MessageProcessingStrategy atomicStrategy;
    private final MessageProcessingStrategy nonAtomicStrategy;

    private final AcknowledgmentManager acknowledgmentManager;
    private final FailureHandlingService failureHandlingService;
    private final MetricsService metricsService;

    public RefactoredOrchestratorService(
        KafkaTemplate<String, String> transactionalTemplate,
        KafkaTemplate<String, String> nonTransactionalTemplate,
        OrchestratorProperties properties,
        MessageTransformer transformer,
        FailureTracker failureTracker,
        MeterRegistry meterRegistry
    ) {
        this.properties = properties;
        this.failureTracker = failureTracker;
        this.transformPool = Executors.newVirtualThreadPerTaskExecutor();

        // Initialize services
        this.metricsService = new MetricsService(meterRegistry);
        this.acknowledgmentManager = new AcknowledgmentManager(
            properties.getCommitBatchSize(),
            properties.getCommitFlushIntervalMs()
        );
        this.failureHandlingService = new FailureHandlingService(
            failureTracker,
            properties,
            metricsService
        );

        // Initialize transformation pipeline
        MessageTransformationPipeline transformationPipeline = new MessageTransformationPipeline(
            transformer,
            transformPool
        );

        // Initialize transaction manager
        TransactionManager transactionManager = new TransactionManager(
            transactionalTemplate,
            properties,
            failureHandlingService
        );

        // Initialize strategies
        this.atomicStrategy = new AtomicProcessingStrategy(
            transformationPipeline,
            transactionManager,
            failureHandlingService,
            acknowledgmentManager,
            properties
        );

        this.nonAtomicStrategy = new NonAtomicProcessingStrategy(
            transformationPipeline,
            nonTransactionalTemplate,
            failureHandlingService,
            acknowledgmentManager,
            properties
        );
    }

    @PreDestroy
    void shutdown() {
        transformPool.shutdown();
        acknowledgmentManager.close();
        log.info("OrchestratorService shutdown completed");
    }

    @KafkaListener(
        topics = "${orchestrator.consumer-topic}",
        containerFactory = "recordContainerFactory",
        autoStartup = "#{!@orchestratorProperties.batchEnabled}"
    )
    public void onRecord(
        ConsumerRecord<String, String> record,
        Acknowledgment acknowledgment,
        Consumer<?, ?> consumer
    ) {
        MessageProcessingStrategy strategy = selectProcessingStrategy();
        strategy.processRecord(record, acknowledgment, consumer);
    }

    @KafkaListener(
        topics = "${orchestrator.consumer-topic}",
        containerFactory = "batchContainerFactory",
        autoStartup = "#{@orchestratorProperties.batchEnabled}"
    )
    public void onBatch(
        List<ConsumerRecord<String, String>> records,
        Acknowledgment acknowledgment,
        Consumer<?, ?> consumer
    ) {
        if (records.isEmpty()) {
            return;
        }

        MessageProcessingStrategy strategy = selectProcessingStrategy();
        strategy.processBatch(records, acknowledgment, consumer);
    }

    private MessageProcessingStrategy selectProcessingStrategy() {
        FailureMode effectiveMode = determineEffectiveFailureMode();
        return effectiveMode == FailureMode.SKIP_AND_LOG ? nonAtomicStrategy : atomicStrategy;
    }

    private FailureMode determineEffectiveFailureMode() {
        if (properties.getFailureMode() == FailureMode.SKIP_AND_LOG) {
            return FailureMode.SKIP_AND_LOG;
        }
        if (failureTracker.isCircuitOpen() || failureTracker.isDbDegraded()) {
            return FailureMode.SKIP_AND_LOG;
        }
        return FailureMode.ATOMIC;
    }
}