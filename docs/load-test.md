# Kafka Load Test Playbook

This guide shows how to spin up the orchestrator stack locally, drive 1,000,000 Kafka messages with Locust, and produce a latency histogram.

## Prerequisites

- Docker & Docker Compose v2
- Python 3.10+ (for optional plotting script)
- Python packages: `locust`, `kafka-python`, `matplotlib` (installable via `pip install -r load-test/requirements.txt` if you create one)

## 1. Build & Launch the Stack

```bash
# Build the application image and start Kafka + Postgres + app
docker compose up --build kafka postgres orders-orchestrator -d

# (Optional) tail application logs in structured JSON
docker compose logs -f orders-orchestrator
```

Kafka exposes `localhost:9094` for external tooling; Postgres is reachable at `localhost:5432` (`postgres/postgres`).

## 2. Run the Locust Scenario for 1,000,000 Messages

You can run Locust inside Docker (profile `load`) or on the host.

### Option A: Dockerised Locust

```bash
# Ensure an output directory exists for reports
mkdir -p reports

# Launch Locust headlessly via compose profile
docker compose --profile load up locust
```

The bundled command drives 50 concurrent virtual users (`-u 50`, spawn rate `-r 5`) until one million messages are produced and seen on the enrichment topic. CSV reports and `latency_samples.csv` land in `./reports`.

### Option B: Host-Run Locust

```bash
export KAFKA_BOOTSTRAP_SERVERS=localhost:9094
export LOCUST_PRODUCER_TOPIC=orders.raw
export LOCUST_CONSUMER_TOPIC=orders.enriched
export LOCUST_GROUP_ID=locust-verify
export LOCUST_TARGET_MESSAGES=1000000
export LOCUST_TEST_ID=host-run
export LATENCY_OUTPUT=./reports/latency_samples.csv
mkdir -p reports

locust -f load-test/locustfile.py --headless -u 50 -r 5 --stop-timeout 300 --csv=reports/locust
```

### Success Criteria

- `reports/latency_samples.csv` contains 1,000,000 rows (plus header).
- Locust summary displays 1,000,000 requests for both `Kafka/produce` and `Kafka/consume` with zero failures.
- The orchestrator metrics endpoint (`http://localhost:8080/actuator/metrics/orch.batch.ok`) should reflect the total processed records.

## 3. Generate the Latency Histogram

```bash
python load-test/plot_latency.py reports/latency_samples.csv --png reports/latency_histogram.png --bins 120
```

The script emits `reports/latency_histogram.png`. Open it to visualise end-to-end latency distribution.

## 4. Reset Between Runs

```bash
# Remove Kafka/Postgres data (destructive!)
docker compose down -v
```

This wipes broker logs and database state so the next million-message run starts clean.
