
# 📦 Codex Build Spec — **Two Separate Maven Projects**
**Spring Boot 3 · Java 21 · Kafka · DB‑Backed Failure Tracking (Outbox/Reliable/Lightweight)**

You will create **two independent Maven projects** (no multi‑module parent):
1) **Library JAR** → `orchestrator-starter/` (build/install first)  
2) **Deployable App JAR** → `orders-orchestrator-app/` (depends on the library)

Both use Maven; the app depends on the library via `groupId/artifactId/version` and requires the library to be installed locally first.

---

## 0) Build Steps (Agent)

1. Create the two directories **side by side**:
   - `orchestrator-starter/`
   - `orders-orchestrator-app/`
2. Write all files exactly as provided below.
3. Build and **install** the library:  
   `cd orchestrator-starter && mvn -q -DskipTests install`
4. Build or run the app:
   - Build: `cd ../orders-orchestrator-app && mvn -q -DskipTests package`
   - Run:   `mvn spring-boot:run`

---

## 1) Project Trees

```
orchestrator-starter/
├─ pom.xml
└─ src/
   ├─ main/java/com/acme/orch/core/MessageTransformer.java
   ├─ main/java/com/acme/orch/core/KafkaClientCustomizer.java
   ├─ main/java/com/acme/orch/starter/config/OrchestratorProperties.java
   ├─ main/java/com/acme/orch/starter/db/FailureTracker.java
   ├─ main/java/com/acme/orch/starter/OrchestratorAutoConfiguration.java
   ├─ main/java/com/acme/orch/starter/runtime/OrchestratorService.java
   ├─ main/java/com/acme/orch/starter/runtime/DbDegradedHealthIndicator.java
   ├─ main/java/com/acme/orch/starter/runtime/TimeoutSafeguard.java
   └─ main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports

orders-orchestrator-app/
├─ pom.xml
└─ src/
   ├─ main/java/com/acme/orch/example/OrdersOrchestratorApplication.java
   ├─ main/resources/application.yml
   ├─ main/resources/db/migration/V1__event_failure_audit.sql
   └─ main/resources/logback-spring.xml
```

---

## 2) `orchestrator-starter` (Library JAR)

### `orchestrator-starter/pom.xml`
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>com.acme.orch</groupId>
  <artifactId>orchestrator-starter</artifactId>
  <version>1.0.0</version>
  <name>orchestrator-starter</name>
  <packaging>jar</packaging>

  <properties>
    <java.version>21</java.version>
    <spring-boot.version>3.3.3</spring-boot.version>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-dependencies</artifactId>
        <version>${spring-boot.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <dependencies>
    <dependency>
      <groupId>org.springframework.kafka</groupId>
      <artifactId>spring-kafka</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-autoconfigure</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-jdbc</artifactId>
    </dependency>
    <dependency>
      <groupId>io.micrometer</groupId>
      <artifactId>micrometer-core</artifactId>
    </dependency>
    <dependency>
      <groupId>org.slf4j</groupId>
      <artifactId>slf4j-api</artifactId>
    </dependency>
    <!-- Optional: metadata for IDEs -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-configuration-processor</artifactId>
      <optional>true</optional>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.11.0</version>
        <configuration>
          <release>${java.version}</release>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

### `src/main/java/com/acme/orch/core/MessageTransformer.java`
```java
package com.acme.orch.core;

@FunctionalInterface
public interface MessageTransformer {
    String transform(String input) throws Exception;
    default byte[] transformBytes(byte[] input) throws Exception {
        return transform(new String(input)).getBytes();
    }
}
```

### `src/main/java/com/acme/orch/core/KafkaClientCustomizer.java`
```java
package com.acme.orch.core;

import java.util.Map;

/** Allow per-app overrides of Kafka producer/consumer properties. */
public interface KafkaClientCustomizer {
    default void customizeConsumer(Map<String, Object> consumerProps) {}
    default void customizeProducer(Map<String, Object> producerProps) {}
}
```

### `src/main/java/com/acme/orch/starter/config/OrchestratorProperties.java`
```java
package com.acme.orch.starter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "orchestrator")
public class OrchestratorProperties {
    private String consumerTopic;
    private String producerTopic;
    private String groupId;

    private boolean batchEnabled = true;
    private int consumerConcurrency = Math.max(1, Runtime.getRuntime().availableProcessors());
    private int batchMaxSize = 500;
    private int batchFlushIntervalMs = 200;

    public enum DbStrategy { OUTBOX, RELIABLE, LIGHTWEIGHT }
    private DbStrategy dbStrategy = DbStrategy.RELIABLE;
    private String errorTable = "event_failures";

    private long dbTimeoutMs = 150;            // very small timeout
    private boolean dbDegradeDontBlock = true; // keep Kafka flowing on DB issues

    /** Payload storage choice: NONE | BYTES | TEXT */
    private String storePayload = "NONE";
    private boolean storePayloadOnFailureOnly = true;

    public String getConsumerTopic() { return consumerTopic; }
    public void setConsumerTopic(String consumerTopic) { this.consumerTopic = consumerTopic; }
    public String getProducerTopic() { return producerTopic; }
    public void setProducerTopic(String producerTopic) { this.producerTopic = producerTopic; }
    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }
    public boolean isBatchEnabled() { return batchEnabled; }
    public void setBatchEnabled(boolean batchEnabled) { this.batchEnabled = batchEnabled; }
    public int getConsumerConcurrency() { return consumerConcurrency; }
    public void setConsumerConcurrency(int consumerConcurrency) { this.consumerConcurrency = consumerConcurrency; }
    public int getBatchMaxSize() { return batchMaxSize; }
    public void setBatchMaxSize(int batchMaxSize) { this.batchMaxSize = batchMaxSize; }
    public int getBatchFlushIntervalMs() { return batchFlushIntervalMs; }
    public void setBatchFlushIntervalMs(int batchFlushIntervalMs) { this.batchFlushIntervalMs = batchFlushIntervalMs; }
    public DbStrategy getDbStrategy() { return dbStrategy; }
    public void setDbStrategy(DbStrategy dbStrategy) { this.dbStrategy = dbStrategy; }
    public String getErrorTable() { return errorTable; }
    public void setErrorTable(String errorTable) { this.errorTable = errorTable; }
    public long getDbTimeoutMs() { return dbTimeoutMs; }
    public void setDbTimeoutMs(long dbTimeoutMs) { this.dbTimeoutMs = dbTimeoutMs; }
    public boolean isDbDegradeDontBlock() { return dbDegradeDontBlock; }
    public void setDbDegradeDontBlock(boolean dbDegradeDontBlock) { this.dbDegradeDontBlock = dbDegradeDontBlock; }
    public String getStorePayload() { return storePayload; }
    public void setStorePayload(String storePayload) { this.storePayload = storePayload; }
    public boolean isStorePayloadOnFailureOnly() { return storePayloadOnFailureOnly; }
    public void setStorePayloadOnFailureOnly(boolean storePayloadOnFailureOnly) { this.storePayloadOnFailureOnly = storePayloadOnFailureOnly; }
}
```

### `src/main/java/com/acme/orch/starter/db/FailureTracker.java`
```java
package com.acme.orch.starter.db;

import com.acme.orch.starter.config.OrchestratorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;

import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class FailureTracker {
    private static final Logger log = LoggerFactory.getLogger(FailureTracker.class);

    private final JdbcTemplate jdbc;
    private final String table;
    private final OrchestratorProperties props;
    private final AtomicBoolean dbDegraded = new AtomicBoolean(false);
    private final ExecutorService dbPool = Executors.newVirtualThreadPerTaskExecutor();

    public FailureTracker(DataSource ds, OrchestratorProperties props) {
        this.jdbc = new JdbcTemplate(ds);
        this.table = props.getErrorTable();
        this.props = props;
        this.jdbc.setQueryTimeout(1); // seconds; we also enforce ms via orTimeout below
    }

    public boolean isDbDegraded() { return dbDegraded.get(); }

    public void bulkInsertReceived(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return;
        runWithTimeout(() -> new SimpleJdbcInsert(jdbc).withTableName(table)
               .executeBatch(rows.toArray(new Map[0])), true);
    }

    public void updateStatusSuccess(List<String> dedupKeys) {
        if (dedupKeys.isEmpty()) return;
        runAsyncWithTimeout(() -> jdbc.batchUpdate(
            "UPDATE " + table + " SET status='SUCCESS', updated_at=now() WHERE dedup_key=?",
            dedupKeys.stream().map(k -> new Object[]{k}).toList()));
    }

    public void updateStatusFailed(String dedupKey, String message, String stack) {
        runWithTimeout(() -> jdbc.update(
            "UPDATE " + table + " SET status='FAILED', error_message=?, error_stack=?, updated_at=now() WHERE dedup_key=?",
            message, stack, dedupKey), false);
    }

    public void insertRow(Map<String, Object> row) {
        runWithTimeout(() -> new SimpleJdbcInsert(jdbc).withTableName(table).execute(row), false);
    }

    public void insertLightweightFailure(Map<String, Object> row) {
        runAsyncWithTimeout(() -> new SimpleJdbcInsert(jdbc).withTableName(table).execute(row));
    }

    public int markTimedOutReceivedAsFailed(long olderThanMinutes) {
        Integer updated = runWithTimeout(() -> jdbc.update(
            "UPDATE " + table + " SET status='FAILED', updated_at=now() " +
            "WHERE status='RECEIVED' AND created_at < now() - INTERVAL '" + olderThanMinutes + " minutes'"), false);
        return updated == null ? 0 : updated;
    }

    private void runAsyncWithTimeout(Runnable r) { dbPool.submit(() -> runWithTimeout(r, true)); }

    private <T> T runWithTimeout(Callable<T> call, boolean async) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try { return call.call(); }
                catch (Exception e) { throw new CompletionException(e); }
            }, dbPool).orTimeout(props.getDbTimeoutMs(), TimeUnit.MILLISECONDS)
              .whenComplete((ok, ex) -> {
                  if (ex != null && props.isDbDegradeDontBlock()) {
                      if (dbDegraded.compareAndSet(false, true)) {
                          log.warn("DB degraded; continuing Kafka flow. Cause: {}", ex.toString());
                      }
                  } else if (ex == null && dbDegraded.get()) {
                      dbDegraded.set(false);
                      log.info("DB recovered; cleared degraded flag.");
                  }
              }).join();
        } catch (CompletionException ce) {
            log.debug("DB op degraded/failure (non-blocking): {}", ce.toString());
            return null;
        }
    }

    private <T> T runWithTimeout(Runnable r, boolean async) { return runWithTimeout(Executors.callable(r, null), async); }
}
```

### `src/main/java/com/acme/orch/starter/OrchestratorAutoConfiguration.java`
```java
package com.acme.orch.starter;

import com.acme.orch.core.KafkaClientCustomizer;
import com.acme.orch.core.MessageTransformer;
import com.acme.orch.starter.config.OrchestratorProperties;
import com.acme.orch.starter.db.FailureTracker;
import com.acme.orch.starter.runtime.DbDegradedHealthIndicator;
import com.acme.orch.starter.runtime.OrchestratorService;
import com.acme.orch.starter.runtime.TimeoutSafeguard;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
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
import java.util.UUID;

import static org.apache.kafka.clients.producer.ProducerConfig.*;

@AutoConfiguration
@EnableKafka
@EnableScheduling
@EnableConfigurationProperties(OrchestratorProperties.class)
public class OrchestratorAutoConfiguration {

    @Bean @ConditionalOnMissingBean
    public MessageTransformer messageTransformer() { return input -> input; } // identity

    @Bean @ConditionalOnMissingBean
    public KafkaClientCustomizer kafkaClientCustomizer() { return new KafkaClientCustomizer() {}; }

    /** Expose properties bean by a known name for SpEL usage. */
    @Bean(name = "orchestratorProperties")
    public OrchestratorProperties orchestratorPropertiesAlias(OrchestratorProperties p) { return p; }

    // Producer/Consumer factories with high-throughput defaults + EOS
    @Bean
    public ProducerFactory<String, String> producerFactory(
            org.springframework.core.env.Environment env,
            KafkaClientCustomizer customizer) {

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
        props.put(TRANSACTIONAL_ID_CONFIG, env.getProperty("spring.kafka.producer.transaction-id-prefix", "orch-") + UUID.randomUUID());
        customizer.customizeProducer(props);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public ConsumerFactory<String, String> consumerFactory(
            org.springframework.core.env.Environment env,
            KafkaClientCustomizer customizer) {

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

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> pf) {
        KafkaTemplate<String, String> template = new KafkaTemplate<>(pf);
        template.setObservationEnabled(true);
        return template;
    }

    @Bean
    public KafkaTransactionManager<String, String> kafkaTxManager(ProducerFactory<String, String> pf) {
        return new KafkaTransactionManager<>(pf);
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
            ConsumerFactory<String, String> cf,
            KafkaTransactionManager<String, String> txManager,
            DefaultErrorHandler errorHandler,
            OrchestratorProperties props) {
        var f = new ConcurrentKafkaListenerContainerFactory<String, String>();
        f.setConsumerFactory(cf);
        f.getContainerProperties().setGroupId(props.getGroupId());
        f.getContainerProperties().setTransactionManager(txManager);
        f.setConcurrency(props.getConsumerConcurrency());
        f.setBatchListener(false);
        f.setCommonErrorHandler(errorHandler);
        f.getContainerProperties().setObservationEnabled(true);
        return f;
    }

    @Bean(name = "batchContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String> batchContainerFactory(
            ConsumerFactory<String, String> cf,
            KafkaTransactionManager<String, String> txManager,
            DefaultErrorHandler errorHandler,
            OrchestratorProperties props) {
        var f = new ConcurrentKafkaListenerContainerFactory<String, String>();
        f.setConsumerFactory(cf);
        f.getContainerProperties().setGroupId(props.getGroupId());
        f.getContainerProperties().setTransactionManager(txManager);
        f.setConcurrency(props.getConsumerConcurrency());
        f.setBatchListener(true);
        f.setCommonErrorHandler(errorHandler);
        f.getContainerProperties().setObservationEnabled(true);
        return f;
    }

    // DB failure tracking + orchestrator service
    @Bean @ConditionalOnBean(DataSource.class)
    public FailureTracker failureTracker(DataSource ds, OrchestratorProperties props) {
        return new FailureTracker(ds, props);
    }

    @Bean
    public OrchestratorService orchestratorService(
            KafkaTemplate<String, String> template,
            OrchestratorProperties props,
            MessageTransformer transformer,
            FailureTracker tracker,
            MeterRegistry meter) {
        return new OrchestratorService(template, props, transformer, tracker, meter);
    }

    @Bean
    public DbDegradedHealthIndicator dbDegradedHealthIndicator(FailureTracker tracker) {
        return new DbDegradedHealthIndicator(tracker);
    }

    @Bean
    public TimeoutSafeguard timeoutSafeguard(FailureTracker tracker, OrchestratorProperties props, MeterRegistry meterRegistry) {
        return new TimeoutSafeguard(tracker, props, meterRegistry);
    }
}
```

### `src/main/java/com/acme/orch/starter/runtime/OrchestratorService.java`
```java
package com.acme.orch.starter.runtime;

import com.acme.orch.core.MessageTransformer;
import com.acme.orch.starter.config.OrchestratorProperties;
import com.acme.orch.starter.db.FailureTracker;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class OrchestratorService {
    private static final Logger log = LoggerFactory.getLogger(OrchestratorService.class);

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

    // Record mode
    @KafkaListener(
        topics = "${orchestrator.consumer-topic}",
        containerFactory = "recordContainerFactory",
        autoStartup = "#{!@orchestratorProperties.batchEnabled}")
    public void onRecord(org.apache.kafka.clients.consumer.ConsumerRecord<String, String> rec,
                         Acknowledgment ack,
                         Consumer<?, ?> consumer) {

        final String dedupKey = dedupKey(rec.topic(), rec.partition(), rec.offset());

        template.executeInTransaction(ops -> {
            try {
                String out = transformer.transform(rec.value());
                var pr = new ProducerRecord<>(props.getProducerTopic(), rec.key(), out);
                pr.headers().add(new RecordHeader("orchestrator-dedup-key", dedupKey.getBytes(StandardCharsets.UTF_8)));
                try { ops.send(pr).get(); } catch (Exception e) { throw new RuntimeException(e); }

                Map<TopicPartition, OffsetAndMetadata> offsets = Map.of(
                    new TopicPartition(rec.topic(), rec.partition()), new OffsetAndMetadata(rec.offset()+1));
                ops.sendOffsetsToTransaction(offsets, consumer.groupMetadata());
                return true;
            } catch (Exception ex) {
                handleFailure(rec, ex, true);
                throw new RuntimeException(ex);
            }
        });

        ack.acknowledge();
        meter.counter("orch.records.ok").increment();
    }

    // Batch mode
    @KafkaListener(
        topics = "${orchestrator.consumer-topic}",
        containerFactory = "batchContainerFactory",
        autoStartup = "#{@orchestratorProperties.batchEnabled}")
    public void onBatch(List<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> records,
                        Acknowledgment ack,
                        Consumer<?, ?> consumer) {

        if (records.isEmpty()) return;

        // Pre-insert (Outbox/Reliable)
        if (props.getDbStrategy() == OrchestratorProperties.DbStrategy.OUTBOX) {
            failureTracker.bulkInsertReceived(records.stream().map(r -> row(r, "RECEIVED", null, false)).toList());
        } else if (props.getDbStrategy() == OrchestratorProperties.DbStrategy.RELIABLE) {
            records.forEach(r -> failureTracker.insertRow(row(r, "RECEIVED", null, false)));
        }

        template.executeInTransaction(ops -> {
            List<CompletableFuture<String>> futures = records.stream()
                .map(r -> CompletableFuture.supplyAsync(() -> {
                    try { return transformer.transform(r.value()); }
                    catch (Exception e) { throw new CompletionException(e); }
                }, transformPool)).toList();

            List<String> outs = futures.stream().map(CompletableFuture::join).toList();

            for (int i = 0; i < records.size(); i++) {
                var rec = records.get(i);
                var out = outs.get(i);
                var pr = new ProducerRecord<>(props.getProducerTopic(), rec.key(), out);
                pr.headers().add(new RecordHeader("orchestrator-dedup-key",
                        dedupKey(rec.topic(), rec.partition(), rec.offset()).getBytes(StandardCharsets.UTF_8)));
                try { ops.send(pr).get(); } catch (Exception e) { throw new RuntimeException(e); }
            }

            Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
            records.forEach(r -> offsets.put(new TopicPartition(r.topic(), r.partition()), new OffsetAndMetadata(r.offset()+1)));
            ops.sendOffsetsToTransaction(offsets, consumer.groupMetadata());
            return true;
        });

        if (props.getDbStrategy() != OrchestratorProperties.DbStrategy.LIGHTWEIGHT) {
            var keys = records.stream().map(r -> dedupKey(r.topic(), r.partition(), r.offset())).toList();
            failureTracker.updateStatusSuccess(keys);
        }

        ack.acknowledge();
        meter.counter("orch.batch.ok").increment(records.size());
    }

    private void handleFailure(org.apache.kafka.clients.consumer.ConsumerRecord<String, String> rec,
                               Exception ex,
                               boolean isRecordMode) {
        var row = row(rec, "FAILED", ex, true);
        switch (props.getDbStrategy()) {
            case LIGHTWEIGHT -> failureTracker.insertLightweightFailure(row);
            case RELIABLE, OUTBOX -> failureTracker.insertRow(row);
        }
        meter.counter("orch.transform.fail").increment();
    }

    private String dedupKey(String topic, int partition, long offset) {
        return topic + ":" + partition + ":" + offset;
    }

    private Map<String, Object> row(org.apache.kafka.clients.consumer.ConsumerRecord<String, String> rec,
                                    String status,
                                    Exception ex,
                                    boolean isFailure) {
        Map<String, Object> m = new HashMap<>();
        m.put("source_topic", rec.topic());
        m.put("target_topic", props.getProducerTopic());
        m.put("partition", rec.partition());
        m.put("offset", rec.offset());
        m.put("message_key", rec.key());
        m.put("headers_text", "");
        m.put("status", status);
        m.put("dedup_key", dedupKey(rec.topic(), rec.partition(), rec.offset()));
        m.put("error_message", ex == null ? null : ex.getMessage());
        m.put("error_stack", ex == null ? null : stack(ex));

        boolean storePayload =
            !"NONE".equalsIgnoreCase(props.getStorePayload()) &&
            (!props.isStorePayloadOnFailureOnly() || isFailure);

        if (storePayload) {
            if ("BYTES".equalsIgnoreCase(props.getStorePayload())) {
                m.put("payload_bytes", rec.value() == null ? null : rec.value().getBytes(StandardCharsets.UTF_8));
            } else {
                m.put("payload_text", rec.value());
            }
        }
        return m;
    }

    private String stack(Exception ex) {
        var sw = new java.io.StringWriter();
        ex.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }
}
```

### `src/main/java/com/acme/orch/starter/runtime/DbDegradedHealthIndicator.java`
```java
package com.acme.orch.starter.runtime;

import com.acme.orch.starter.db.FailureTracker;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

public class DbDegradedHealthIndicator implements HealthIndicator {
    private final FailureTracker tracker;
    public DbDegradedHealthIndicator(FailureTracker tracker) { this.tracker = tracker; }
    @Override public Health health() {
        return tracker.isDbDegraded() ? Health.status("DEGRADED").withDetail("db", "degraded").build() : Health.up().build();
    }
}
```

### `src/main/java/com/acme/orch/starter/runtime/TimeoutSafeguard.java`
```java
package com.acme.orch.starter.runtime;

import com.acme.orch.starter.db.FailureTracker;
import com.acme.orch.starter.config.OrchestratorProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;

public class TimeoutSafeguard {
    private final FailureTracker tracker;
    private final OrchestratorProperties props;
    private final MeterRegistry meter;
    public TimeoutSafeguard(FailureTracker tracker, OrchestratorProperties props, MeterRegistry meter) {
        this.tracker = tracker; this.props = props; this.meter = meter;
    }

    /** Fail-safe: mark RECEIVED older than 30 minutes as FAILED. */
    @Scheduled(fixedDelay = 60_000L)
    public void markTimedOut() {
        int n = tracker.markTimedOutReceivedAsFailed(30);
        if (n > 0) meter.counter("orch.outbox.timeouts.marked").increment(n);
    }
}
```

### `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
```
com.acme.orch.starter.OrchestratorAutoConfiguration
```

---

## 3) `orders-orchestrator-app` (Deployable JAR)

### `orders-orchestrator-app/pom.xml`
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>com.acme.orch</groupId>
  <artifactId>orders-orchestrator-app</artifactId>
  <version>1.0.0</version>
  <name>orders-orchestrator-app</name>
  <packaging>jar</packaging>

  <properties>
    <java.version>21</java.version>
    <spring-boot.version>3.3.3</spring-boot.version>
    <orch.version>1.0.0</orch.version>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-dependencies</artifactId>
        <version>${spring-boot.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <dependencies>
    <dependency>
      <groupId>com.acme.orch</groupId>
      <artifactId>orchestrator-starter</artifactId>
      <version>${orch.version}</version>
    </dependency>

    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.kafka</groupId>
      <artifactId>spring-kafka</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
      <groupId>io.micrometer</groupId>
      <artifactId>micrometer-registry-prometheus</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-jdbc</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
      <groupId>net.logstash.logback</groupId>
      <artifactId>logstash-logback-encoder</artifactId>
      <version>7.4</version>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.11.0</version>
        <configuration>
          <release>${java.version}</release>
        </configuration>
      </plugin>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
        <version>${spring-boot.version}</version>
      </plugin>
    </plugins>
  </build>
</project>
```

### `src/main/java/com/acme/orch/example/OrdersOrchestratorApplication.java`
```java
package com.acme.orch.example;

import com.acme.orch.core.KafkaClientCustomizer;
import com.acme.orch.core.MessageTransformer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.Map;

@SpringBootApplication
public class OrdersOrchestratorApplication {
  public static void main(String[] args) { SpringApplication.run(OrdersOrchestratorApplication.class, args); }

  /** Optional ultra-light transform: add a routedBy attribute without full parse. */
  @Bean
  MessageTransformer transformer() {
    return json -> {
      if (json == null) return null;
      String t = json.trim();
      if (t.startsWith("{") && t.endsWith("}")) {
        return t.substring(0, t.length()-1) + ",\"routedBy\":\"orders-orchestrator\"}";
      }
      return json;
    };
  }

  /** Optional Kafka tuning overrides for this app. */
  @Bean
  KafkaClientCustomizer kafkaTuner() {
    return new KafkaClientCustomizer() {
      @Override public void customizeProducer(Map<String, Object> p) {
        p.put(ProducerConfig.LINGER_MS_CONFIG, 20);
      }
      @Override public void customizeConsumer(Map<String, Object> c) {
        c.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1000);
      }
    };
  }
}
```

### `src/main/resources/application.yml`
```yaml
spring:
  application:
    name: orders-orchestrator
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    consumer:
      group-id: ${GROUP_ID:orders-orchestrator}
    producer:
      transaction-id-prefix: orders-tx-
  datasource:
    url: ${JDBC_URL:jdbc:postgresql://localhost:5432/orch?reWriteBatchedInserts=true}
    username: ${JDBC_USER:postgres}
    password: ${JDBC_PASSWORD:postgres}
    hikari:
      connection-timeout: 250
      validation-timeout: 250
      maximum-pool-size: 8

orchestrator:
  consumer-topic: ${SOURCE_TOPIC:orders.raw}
  producer-topic: ${TARGET_TOPIC:orders.enriched}
  group-id: ${GROUP_ID:orders-orchestrator}
  batch-enabled: true
  consumer-concurrency: ${CONCURRENCY:3}
  db-strategy: ${DB_STRATEGY:RELIABLE}        # OUTBOX | RELIABLE | LIGHTWEIGHT
  error-table: ${ERROR_TABLE:event_failures}
  db-timeout-ms: ${DB_TIMEOUT_MS:150}
  db-degrade-dont-block: true
  store-payload: ${STORE_PAYLOAD:NONE}         # NONE | BYTES | TEXT
  store-payload-on-failure-only: ${STORE_PAYLOAD_ON_FAILURE_ONLY:true}

management:
  endpoints:
    web:
      exposure:
        include: "health,info,metrics,prometheus"
  endpoint:
    health:
      probes:
        enabled: true
```

### `src/main/resources/db/migration/V1__event_failure_audit.sql`
```sql
CREATE TABLE IF NOT EXISTS event_failures (
  id BIGSERIAL PRIMARY KEY,
  source_topic   VARCHAR(255) NOT NULL,
  target_topic   VARCHAR(255) NOT NULL,
  partition      INT NOT NULL,
  offset         BIGINT NOT NULL,
  message_key    TEXT,
  headers_text   TEXT,
  payload_bytes  BYTEA,      -- prefer BYTES for speed if you must store payloads
  payload_text   TEXT,       -- optional human-readable storage
  status         VARCHAR(32) NOT NULL,  -- RECEIVED | SUCCESS | FAILED
  error_message  TEXT,
  error_stack    TEXT,
  dedup_key      VARCHAR(512),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_event_failures_status ON event_failures(status);
CREATE UNIQUE INDEX IF NOT EXISTS uidx_event_failures_dedup ON event_failures(dedup_key);
```

### `src/main/resources/logback-spring.xml`
```xml
<configuration>
  <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="net.logstash.logback.encoder.LogstashEncoder"/>
  </appender>
  <root level="INFO">
    <appender-ref ref="JSON"/>
  </root>
</configuration>
```

---

## 4) Outbox payload: **byte[] vs JSON** (what’s fastest?)

- **Fastest default (provided here):** In **Outbox** mode, batch pre-inserts write **metadata only** (`status=RECEIVED`).  
  Set `orchestrator.store-payload=NONE` (default) → smallest row, minimal CPU.
- If you must persist payloads:
  - Use `store-payload=BYTES` → writes Kafka value as **`BYTEA`** (`setBytes`) with **no extra encoding** → **fastest** storage.
  - `store-payload=TEXT` (JSON string) is slower but easier to inspect.
  - Keep `store-payload-on-failure-only=true` so payloads are stored **only on failures**.

**DB speed tips included:**
- Postgres JDBC: `reWriteBatchedInserts=true` for server-side multi-row INSERTs.
- Very small per-op timeouts (`db-timeout-ms: 150`) + **degrade‑don’t‑block** (Kafka continues even if DB is down).
- Batch consumer enabled by default and virtual threads for parallel transforms.

---

## 5) Run Commands

```bash
# Build & install the library first
cd orchestrator-starter
mvn -q -DskipTests install

# Build/run the app (separate project)
cd ../orders-orchestrator-app
mvn -q -DskipTests package
mvn spring-boot:run
```

**End of Codex Spec.**
