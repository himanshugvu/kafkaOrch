package com.acme.orch.starter;

import com.acme.orch.core.KafkaClientCustomizer;
import com.acme.orch.core.MessageTransformer;
import com.acme.orch.db.FailureTracker;
import com.acme.orch.starter.config.KafkaClientProperties;
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
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
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

import static org.apache.kafka.clients.producer.ProducerConfig.*;

@AutoConfiguration
@EnableKafka
@EnableScheduling
@EnableConfigurationProperties({OrchestratorProperties.class, KafkaClientProperties.class})
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
        KafkaClientCustomizer customizer,
        KafkaProperties kafkaProperties,
        KafkaClientProperties kafkaClientProperties
    ) {
        Map<String, Object> props = producerProps(env, kafkaProperties, kafkaClientProperties);
        customizer.customizeProducer(props);
        DefaultKafkaProducerFactory<String, String> factory = new DefaultKafkaProducerFactory<>(props);
        if (!Boolean.FALSE.equals(props.get(ENABLE_IDEMPOTENCE_CONFIG))) {
            String prefix = resolveTransactionIdPrefix(kafkaProperties, kafkaClientProperties);
            if (prefix != null && !prefix.isBlank()) {
                factory.setTransactionIdPrefix(prefix);
            }
        }
        return factory;
    }

    @Bean(name = "nonTransactionalProducerFactory")
    public ProducerFactory<String, String> nonTransactionalProducerFactory(
        org.springframework.core.env.Environment env,
        KafkaClientCustomizer customizer,
        KafkaProperties kafkaProperties,
        KafkaClientProperties kafkaClientProperties
    ) {
        Map<String, Object> props = producerProps(env, kafkaProperties, kafkaClientProperties);
        customizer.customizeProducer(props);
        props.remove(TRANSACTIONAL_ID_CONFIG);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public ConsumerFactory<String, String> consumerFactory(
        org.springframework.core.env.Environment env,
        KafkaClientCustomizer customizer,
        KafkaProperties kafkaProperties,
        KafkaClientProperties kafkaClientProperties,
        OrchestratorProperties orchestratorProperties
    ) {
        Map<String, Object> props = consumerProps(env, kafkaProperties, kafkaClientProperties, orchestratorProperties);
        customizer.customizeConsumer(props);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    
    private Map<String, Object> producerProps(
        org.springframework.core.env.Environment env,
        KafkaProperties kafkaProperties,
        KafkaClientProperties kafkaClientProperties
    ) {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildProducerProperties(null));
        KafkaClientProperties.Producer defaults = kafkaClientProperties.getProducer();
        defaults.applyDefaults(props);
        if (!props.containsKey(BOOTSTRAP_SERVERS_CONFIG)) {
            String bootstrap = defaults.getBootstrapServers();
            if (bootstrap == null) {
                bootstrap = env.getProperty("spring.kafka.bootstrap-servers");
            }
            if (bootstrap != null) {
                props.put(BOOTSTRAP_SERVERS_CONFIG, bootstrap);
            }
        }
        return props;
    }

    private Map<String, Object> consumerProps(
        org.springframework.core.env.Environment env,
        KafkaProperties kafkaProperties,
        KafkaClientProperties kafkaClientProperties,
        OrchestratorProperties orchestratorProperties
    ) {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildConsumerProperties(null));
        KafkaClientProperties.Consumer defaults = kafkaClientProperties.getConsumer();
        defaults.applyDefaults(props);
        if (!props.containsKey(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG)) {
            String bootstrap = defaults.getBootstrapServers();
            if (bootstrap == null) {
                bootstrap = env.getProperty("spring.kafka.bootstrap-servers");
            }
            if (bootstrap != null) {
                props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
            }
        }
        if (!props.containsKey(ConsumerConfig.GROUP_ID_CONFIG)) {
            String groupId = defaults.getGroupId();
            if (groupId == null) {
                groupId = orchestratorProperties.getGroupId();
            }
            if (groupId != null) {
                props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
            }
        }
        return props;
    }

    private String resolveTransactionIdPrefix(
        KafkaProperties kafkaProperties,
        KafkaClientProperties kafkaClientProperties
    ) {
        if (kafkaProperties.getProducer().getTransactionIdPrefix() != null) {
            return kafkaProperties.getProducer().getTransactionIdPrefix();
        }
        return kafkaClientProperties.getProducer().getTransactionIdPrefix();
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

