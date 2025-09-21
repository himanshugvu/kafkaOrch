# Orders Orchestrator Project Overview

This repository ships a production-ready Kafka orchestrator example plus a reusable Spring Boot starter. Use this document to understand the moving parts, how to build/run them, and where to tweak behaviour.

## High-Level Architecture

1. **Kafka in, Kafka out** – The orchestrator consumes records from a source topic, transforms them, and produces to a target topic inside a single Kafka transaction (exactly-once by default).
2. **Configurable failure tracking** – A pluggable `FailureTracker` persists metadata about in-flight, failed, and succeeded records with optional payload capture.
3. **Virtual-thread transformation pipeline** – Message transformation runs on virtual threads so slow transformations do not block listener threads.
4. **Health & metrics** – Micrometer gauges (`orch.*`) expose DB circuit status and failure counts; Spring Boot Actuator publishes liveness/readiness endpoints.
5. **Observability stack** – Optional Docker Compose services (Elastic, Logstash, Kibana) ingest structured JSON logs for dashboards.

## Module Layout

| Path | Type | Purpose |
|------|------|---------|
| `orchestrator-starter/` | Maven library | Auto-configuration, Kafka client factories, transaction management, runtime service, JDBC failure tracker. |
| `orchestrator-db-core/` | Maven library | Domain interfaces/models (`FailureTracker`, `FailureRecord`) shared across storage implementations. |
| `orders-orchestrator-app/` | Spring Boot app | Example deployment that depends on the starter, provides a message transformer, and exposes tuning hooks. |
| `docs/` | Markdown guides | Tuning, load testing, the new documents you are reading now. |
| `load-test/` | Scripts & Locust profiles | Generate Kafka traffic and monitor consumer lag to validate configuration changes. |
| `observability/` | Shell scripts & dashboards | Bootstrap ELK stack dashboards focused on orchestrator metrics/logs. |
| `docker-compose.yml` | Compose bundle | Spins up Kafka, Postgres, the orchestrator app, elastic stack, and Kafka UI for local testing. |

## Build & Run

```bash
# 1. Install the starter into your local Maven repo
cd orchestrator-starter
mvn -DskipTests install

# 2. Build or run the example app
cd ../orders-orchestrator-app
mvn -DskipTests package
mvn spring-boot:run

# 3. (Optional) Start the full stack
cd ..
docker compose up --build kafka postgres orders-orchestrator
```

- The app expects Kafka at `KAFKA_BOOTSTRAP_SERVERS` (defaults to `localhost:9092`) and Postgres at `JDBC_URL` (defaults to `jdbc:postgresql://localhost:5432/orch`). Override via environment variables or `application.yml`.
- Initial schema runs via Flyway migration `db/migration/V1__event_failure_audit.sql` when the app boots.

## Customisation Points

- **Kafka tuning** – Override `spring.kafka.*` or implement a `KafkaClientCustomizer` bean (see `OrdersOrchestratorApplication`).
- **Message transformation** – Provide your own `MessageTransformer` bean to rewrite payloads before they are published downstream.
- **Failure storage** – Keep the JDBC implementation or replace it by registering a custom `FailureTracker` bean and switching `orchestrator.db-strategy`.
- **Modes & toggles** – Use `orchestrator.*` properties to flip between atomic/non-atomic processing, batching, payload archiving, and DB circuit behaviour (`docs/orchestrator-modes.md`).

## Operational Toolkit

- **Metrics** – Scrape the `/actuator/prometheus` endpoint for Kafka, DB, and orchestrator-specific gauges.
- **Structured logs** – Logs emit JSON by default (`logback-spring.xml`) and include MDC fields like `dedupKey` during processing.
- **Load testing** – Run `load-test/docker-load-test.sh` to spin up a Locust worker that exercises the orchestrator; monitor suggested commands printed by the script.
- **Observability setup** – Use `observability/create_dashboard.sh` plus `observability/setup_kibana.sh` to provision Kibana index patterns and dashboards tailored to Kafka keywords.

## Next Steps

1. Read `docs/orchestrator-kafka-config.md` to adjust Kafka client behaviour safely.
2. Review `docs/orchestrator-modes.md` before changing reliability or persistence modes.
3. Run the provided load tests after any tuning to confirm lag and failure rates stay within SLOs.
