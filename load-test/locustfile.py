from __future__ import annotations
import json
import os
import threading
import time
import uuid
from pathlib import Path

from kafka import KafkaConsumer, KafkaProducer
from kafka.errors import KafkaError
from locust import User, constant, events, task

TARGET_MESSAGES = int(os.getenv("LOCUST_TARGET_MESSAGES", "1000000"))
PRODUCER_TOPIC = os.getenv("LOCUST_PRODUCER_TOPIC", "orders.raw")
CONSUMER_TOPIC = os.getenv("LOCUST_CONSUMER_TOPIC", "orders.enriched")
KAFKA_BOOTSTRAP = os.getenv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
GROUP_ID = os.getenv("LOCUST_GROUP_ID", "locust-verify")
TEST_ID = os.getenv("LOCUST_TEST_ID", str(uuid.uuid4()))
LATENCY_OUTPUT = Path(os.getenv("LATENCY_OUTPUT", "latency_samples.csv"))
FLUSH_SIZE = int(os.getenv("LATENCY_FLUSH_BATCH", "5000"))

state_lock = threading.Lock()
state = {"sent": 0, "received": 0, "stopped": False}
latency_lock = threading.Lock()
latency_buffer = []
LATENCY_OUTPUT.parent.mkdir(parents=True, exist_ok=True)
LATENCY_OUTPUT.write_text("latency_ms\n", encoding="utf-8")
environment_holder = {"env": None}


def record_latency(latency_ms: float) -> None:
    with latency_lock:
        latency_buffer.append(latency_ms)
        if len(latency_buffer) >= FLUSH_SIZE:
            _flush_latencies_locked()


def _flush_latencies_locked() -> None:
    if not latency_buffer:
        return
    with LATENCY_OUTPUT.open("a", encoding="utf-8") as fh:
        for value in latency_buffer:
            fh.write(f"{value}\n")
    latency_buffer.clear()


def flush_latencies() -> None:
    with latency_lock:
        _flush_latencies_locked()


def maybe_stop() -> None:
    env = environment_holder.get("env")
    with state_lock:
        if state["stopped"]:
            return
        if state["sent"] >= TARGET_MESSAGES and state["received"] >= TARGET_MESSAGES:
            state["stopped"] = True
            if env and env.runner:
                env.runner.quit()


@events.test_start.add_listener
def _(environment, **kwargs):
    environment_holder["env"] = environment
    environment.process_exit_code = 0


@events.test_stop.add_listener
def _(environment, **kwargs):
    flush_latencies()


class KafkaLoadUser(User):
    abstract = False
    wait_time = constant(0)

    def on_start(self) -> None:
        self.producer = KafkaProducer(
            bootstrap_servers=KAFKA_BOOTSTRAP,
            value_serializer=lambda v: json.dumps(v).encode("utf-8"),
            linger_ms=5,
            acks="all",
        )
        consume = os.getenv("LOCUST_ENABLE_CONSUMER", "1") == "1"
        self.consumer = None
        if consume:
            self.consumer = KafkaConsumer(
                CONSUMER_TOPIC,
                bootstrap_servers=KAFKA_BOOTSTRAP,
                group_id=GROUP_ID,
                auto_offset_reset="earliest",
                enable_auto_commit=True,
                value_deserializer=lambda v: json.loads(v.decode("utf-8")),
                consumer_timeout_ms=1000,
            )
            self.consumer_thread = threading.Thread(target=self._consume_loop, daemon=True)
            self.consumer_thread.start()

    def on_stop(self) -> None:
        try:
            self.producer.flush()
            self.producer.close()
        except Exception:
            pass
        if self.consumer:
            try:
                self.consumer.close()
            except Exception:
                pass

    @task
    def produce_and_track(self) -> None:
        with state_lock:
            if state["sent"] >= TARGET_MESSAGES:
                return
        payload = {
            "test_id": TEST_ID,
            "message_id": str(uuid.uuid4()),
            "sent_at": time.time(),
            "order": {
                "orderId": str(uuid.uuid4()),
                "total": round(100 + 900 * time.perf_counter() % 1, 2),
                "currency": "USD",
            },
        }
        start = time.perf_counter()
        try:
            future = self.producer.send(PRODUCER_TOPIC, value=payload)
            future.get(timeout=30)
            elapsed_ms = (time.perf_counter() - start) * 1000
            events.request.fire(
                request_type="Kafka",
                name="produce",
                response_time=elapsed_ms,
                response_length=0,
            )
            with state_lock:
                state["sent"] += 1
            maybe_stop()
        except KafkaError as exc:
            events.request.fire(
                request_type="Kafka",
                name="produce",
                response_time=0,
                response_length=0,
                exception=exc,
            )
            time.sleep(0.1)

    def _consume_loop(self) -> None:
        assert self.consumer is not None
        while True:
            try:
                records = self.consumer.poll(timeout_ms=500)
            except KafkaError as exc:
                events.request.fire(
                    request_type="Kafka",
                    name="consume",
                    response_time=0,
                    response_length=0,
                    exception=exc,
                )
                time.sleep(0.1)
                continue
            if not records:
                continue
            for tp_records in records.values():
                for record in tp_records:
                    value = record.value
                    if not isinstance(value, dict):
                        continue
                    if value.get("test_id") != TEST_ID:
                        continue
                    sent_at = value.get("sent_at")
                    if not sent_at:
                        continue
                    latency_ms = (time.time() - float(sent_at)) * 1000
                    events.request.fire(
                        request_type="Kafka",
                        name="consume",
                        response_time=latency_ms,
                        response_length=len(json.dumps(value)),
                    )
                    with state_lock:
                        state["received"] += 1
                    record_latency(latency_ms)
                    maybe_stop()
            with state_lock:
                if state["stopped"]:
                    break
        flush_latencies()
