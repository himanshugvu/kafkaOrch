package com.acme.orch.starter;

import com.acme.orch.core.KafkaClientCustomizer;
import com.acme.orch.core.MessageTransformer;
import com.acme.orch.db.FailureTracker;
import com.acme.orch.starter.config.OrchestratorProperties;
import com.acme.orch.starter.db.JdbcFailureTracker;
import com.acme.orch.starter.db.NoopFailureTracker;
import com.acme.orch.starter.runtime.DbDegradedHealthIndicator;
import com.acme.orch.starter.runtime.OrchestratorService;
import com.acme.orch.starter.runtime.TimeoutSafeguard;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.transaction.KafkaTransactionManager;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.apache.kafka.clients.producer.ProducerConfig.*;

@AutoConfiguration
@EnableKafka
@EnableScheduling
@EnableConfigurationProperties(OrchestratorProperties.class)
public class OrchestratorAutoConfiguration {

    @Bean
    public static org.springframework.beans.factory.config.BeanFactoryPostProcessor orchestratorPropertiesAliasPostProcessor() {
        return beanFactory -> {
            String targetBeanName = "orchestrator-com.acme.orch.starter.config.OrchestratorProperties";
            if (beanFactory.containsBean(targetBeanName) && !beanFactory.containsBean("orchestratorProperties")) {
                beanFactory.registerAlias(targetBeanName, "orchestratorProperties");
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public MessageTransformer messageTransformer() {
        return input -> input;
    }

    @Bean
    @ConditionalOnMissingBean
    public KafkaClientCustomizer kafkaClientCustomizer() {
        return new KafkaClientCustomizer() {};
    }

    @Bean
    @Primary
    public ProducerFactory<String, String> producerFactory(
        org.springframework.core.env.Environment env,
        KafkaClientCustomizer customizer
    ) {
        Map<String, Object> props = baseProducerProps(env);
        props.put(TRANSACTIONAL_ID_CONFIG,
            env.getProperty("spring.kafka.producer.transaction-id-prefix", "orch-") + UUID.randomUUID());
        customizer.customizeProducer(props);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean(name = "nonTransactionalProducerFactory")
    public ProducerFactory<String, String> nonTransactionalProducerFactory(
        org.springframework.core.env.Environment env,
        KafkaClientCustomizer customizer
    ) {
        Map<String, Object> props = baseProducerProps(env);
        customizer.customizeProducer(props);
        props.remove(TRANSACTIONAL_ID_CONFIG);
        return new DefaultKafkaProducerFactory<>(props);
    }
@Bean
    public ConsumerFactory<String, String> consumerFactory(
        org.springframework.core.env.Environment env,
        KafkaClientCustomizer customizer
    ) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, env.getProperty("spring.kafka.bootstrap-servers"));
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        props.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 1_048_576);
        props.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 50);
        props.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, 5_242_880);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, org.apache.kafka.common.serialization.StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, org.apache.kafka.common.serialization.StringDeserializer.class);
        customizer.customizeConsumer(props);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    
    private Map<String, Object> baseProducerProps(org.springframework.core.env.Environment env) {
        Map<String, Object> props = new HashMap<>();
        props.put(BOOTSTRAP_SERVERS_CONFIG, env.getProperty("spring.kafka.bootstrap-servers"));
        props.put(ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ACKS_CONFIG, "all");
        props.put(MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        props.put(COMPRESSION_TYPE_CONFIG, "zstd");
        props.put(LINGER_MS_CONFIG, 15);
        props.put(BATCH_SIZE_CONFIG, 196_608);
        props.put(DELIVERY_TIMEOUT_MS_CONFIG, 120_000);
        props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
        props.put(KEY_SERIALIZER_CLASS_CONFIG, org.apache.kafka.common.serialization.StringSerializer.class);
        props.put(VALUE_SERIALIZER_CLASS_CONFIG, org.apache.kafka.common.serialization.StringSerializer.class);
        return props;
    }
@Bean
    @Primary
    public KafkaTemplate<String, String> transactionalKafkaTemplate(@Qualifier("producerFactory") ProducerFactory<String, String> producerFactory) {
        KafkaTemplate<String, String> template = new KafkaTemplate<>(producerFactory);
        template.setObservationEnabled(true);
        return template;
    }

    @Bean(name = "nonTransactionalKafkaTemplate")
    public KafkaTemplate<String, String> nonTransactionalKafkaTemplate(@Qualifier("nonTransactionalProducerFactory") ProducerFactory<String, String> producerFactory) {
        KafkaTemplate<String, String> template = new KafkaTemplate<>(producerFactory);
        template.setObservationEnabled(true);
        return template;
    }

    @Bean
    public KafkaTransactionManager<String, String> kafkaTxManager(@Qualifier("producerFactory") ProducerFactory<String, String> producerFactory) {
        return new KafkaTransactionManager<>(producerFactory);
    }

    @Bean
    public DefaultErrorHandler defaultErrorHandler() {
        var backoff = new ExponentialBackOffWithMaxRetries(7);
        backoff.setInitialInterval(200L);
        backoff.setMultiplier(2.0);
        backoff.setMaxInterval(5_000L);
        return new DefaultErrorHandler((rec, ex) -> {}, backoff);
    }

    @Bean(name = "recordContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String> recordContainerFactory(
        ConsumerFactory<String, String> consumerFactory,
        KafkaTransactionManager<String, String> txManager,
        DefaultErrorHandler errorHandler,
        OrchestratorProperties properties
    ) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setGroupId(properties.getGroupId());
        factory.getContainerProperties().setTransactionManager(txManager);
        factory.setConcurrency(properties.getConsumerConcurrency());
        factory.setBatchListener(false);
        factory.setCommonErrorHandler(errorHandler);
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }

    @Bean(name = "batchContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String> batchContainerFactory(
        ConsumerFactory<String, String> consumerFactory,
        KafkaTransactionManager<String, String> txManager,
        DefaultErrorHandler errorHandler,
        OrchestratorProperties properties
    ) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setGroupId(properties.getGroupId());
        factory.getContainerProperties().setTransactionManager(txManager);
        factory.setConcurrency(properties.getConsumerConcurrency());
        factory.setBatchListener(true);
        factory.setCommonErrorHandler(errorHandler);
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }

    @Bean
    public FailureTracker failureTracker(Optional<DataSource> dataSource, OrchestratorProperties properties, MeterRegistry meterRegistry) {
        if (properties.getDbStrategy() == OrchestratorProperties.DbStrategy.NONE || dataSource.isEmpty()) {
            return new NoopFailureTracker();
        }
        JdbcFailureTracker tracker = new JdbcFailureTracker(dataSource.get(), properties);
        meterRegistry.gauge("orch.db.circuit.open", tracker, t -> t.isCircuitOpen() ? 1.0 : 0.0);
        meterRegistry.gauge("orch.db.degraded", tracker, t -> t.isDbDegraded() ? 1.0 : 0.0);
        return tracker;
    }

    @Bean
    public OrchestratorService orchestratorService(
        @Qualifier("transactionalKafkaTemplate") KafkaTemplate<String, String> transactionalTemplate,
        @Qualifier("nonTransactionalKafkaTemplate") KafkaTemplate<String, String> nonTransactionalTemplate,
        OrchestratorProperties properties,
        MessageTransformer transformer,
        FailureTracker failureTracker,
        MeterRegistry meterRegistry
    ) {
        return new OrchestratorService(transactionalTemplate, nonTransactionalTemplate, properties, transformer, failureTracker, meterRegistry);
    }

    @Bean
    public DbDegradedHealthIndicator dbDegradedHealthIndicator(FailureTracker failureTracker) {
        return new DbDegradedHealthIndicator(failureTracker);
    }

    @Bean
    public TimeoutSafeguard timeoutSafeguard(FailureTracker failureTracker,
                                             MeterRegistry meterRegistry) {
        return new TimeoutSafeguard(failureTracker, meterRegistry);
    }
}

