# Event Orchestrator Design Overview

## Goals
- Transform Kafka records from a source topic into an enriched topic with high throughput.
- Guarantee flexible failure handling (strict transactional vs. skip & log) while keeping Kafka moving.
- Provide optional database durability modes (Outbox, Reliable, Lightweight) to support idempotency and auditability.
- Deliver operational tooling: structured logging, Docker-based local stack, and a Locust-driven million-message soak test.

## Project Structure
- **orchestrator-db-core**: Portable `FailureTracker` interface and immutable `FailureRecord` model. Allows alternative persistence implementations (e.g., Mongo) without touching the Spring starter.
- **orchestrator-starter**: Spring Boot auto-configuration delivering Kafka factories, transactional template, orchestrator service, failure tracker (JDBC implementation), timeout safeguards, and health indicators.
- **orders-orchestrator-app**: Example application wiring the starter, provides a sample transformer and optional Kafka client tweaks plus Flyway migration for the failure table.

## Message Flow (Atomic Mode)
1. Kafka listener obtains either a single record or batch.
2. For `DbStrategy.RELIABLE` / `OUTBOX`, failure tracker pre-inserts rows with `status=RECEIVED`.
3. Records are transformed (parallelized via virtual threads) and produced within a single Kafka transaction.
4. Consumer offsets are committed via `sendOffsetsToTransaction`, keeping EOS semantics.
5. Failure tracker rows are marked `SUCCESS`; metrics (`orch.records.ok` / `orch.batch.ok`) increment.
6. Exceptions roll back the transaction, leave offsets uncommitted, and update the failure table to `FAILED`.

## Message Flow (Skip & Log Mode)
1. Enable via `orchestrator.failure-mode=SKIP_AND_LOG`.
2. Each record is processed independently with up to `non-atomic-max-attempts` retries (defaults to 3).
3. Successful transforms publish inside their own Kafka transaction; tracker rows update to `SUCCESS` when using `RELIABLE`/`OUTBOX`.
4. A lightweight circuit breaker skips database writes while the store is unhealthy (`dbCircuitFailureThreshold`, `dbCircuitOpenMs`).
5. If retries are exhausted, the failure tracker records the error (`FAILED` row or lightweight insert) and processing continues with the next message.
6. Manual acknowledgements advance offsets even for failures; the record is *not* re-delivered, so downstream consumers must be idempotent and recovery comes from the failure log/DB.
7. Duplicate deliveries after restarts are tolerated because pre-inserts and status updates are idempotent; duplicate-key errors are swallowed and logged at debug level.

## Failure Tracking Strategies
- **OUTBOX**: Batch pre-insertion (`bulkInsertReceived`) for high throughput; updates to `SUCCESS/FAILED` post processing. Ideal when DB durability is mandatory.
- **RELIABLE**: Per-record insert for simpler deployments; same status updates as Outbox.
- **LIGHTWEIGHT**: No pre-insert; only failures insert lightweight rows. Lowest DB overhead.
- **NONE**: Disables failure persistence entirely; rely on downstream idempotency and observability.

## Operational Features
- **DB breaker**: `JdbcFailureTracker` opens a short-lived circuit after repeated timeouts (`dbCircuitFailureThreshold`, `dbCircuitOpenMs`) to protect Postgres/MySQL backends.
- **Structured Logging**: Spring Boot 3.4 JSON layout via `logging.structured.format.console=file: json` and `logback-spring.xml` using `JsonLayout` for ECS-friendly logs.
- **Health & Safeguards**: `DbDegradedHealthIndicator` reflects database degradation based on tracker timeout signals; `TimeoutSafeguard` periodically marks stale `RECEIVED` rows as `FAILED`.
- **Docker Stack**: Multi-stage Dockerfile builds the app JAR. `docker-compose.yml` starts Kafka (KRaft), Postgres, orchestrator, and optional Locust worker.
- **Load Testing**: `load-test/locustfile.py` drives up to 1,000,000 messages, records per-message latency, and stops automatically when both produced and consumed counts reach the target. `plot_latency.py` renders histograms from the emitted CSV.

## Key Decisions
- **Bifurcated Failure Mode**: Preserve transactional guarantees by default while offering a configuration flag for high-availability scenarios where poison messages must not halt processing.
- **Database Abstraction**: Split the failure tracker into a separate library to support diverse storage backends and keep the starter focused on Spring wiring.
- **Virtual Threads**: Utilize `Executors.newVirtualThreadPerTaskExecutor()` for parallel transforms without complex thread-pool tuning. (Note: pool lifecycle management may be revisited for graceful shutdown.)
- **Manual Offset Control**: Use manual acknowledgements to determine when offsets advance inside transactions for atomic mode, or after logging for skip mode.
- **Observation & Metrics**: Enable Micrometer observations, expose custom counters (`orch.records.ok`, `orch.batch.ok`, `orch.transform.fail`), and rely on Spring Actuator for runtime visibility.

## Future Enhancements
- Shutdown hooks for the virtual-thread pools in orchestrator and failure tracker.
- Alternative `FailureTracker` implementations (Mongo, Redis) leveraging the new `orchestrator-db-core` module.
- Configurable DLQ publisher for `SKIP_AND_LOG` failures (e.g., forwarding to a dedicated Kafka topic).
- Automated resilience testing (chaos scenarios) integrated into the Locust harness.

## Observability Stack
- JSON logs streamed to stdout and Logstash (via TCP appender) feed Elasticsearch + Kibana for search/visualisation (configure LOGGING_LOGSTASH_DESTINATION as needed).
- logback-spring.xml remains authoritative for the logging pipeline even with Spring Boot structured logging enabled.
- Micrometer gauges expose DB circuit state (`orch.db.circuit.open`) and degradation flag (`orch.db.degraded`).
- MDC now carries the Kafka dedup key so every log line includes `dedupKey` for correlation across services.
