#!/usr/bin/env python3
"""
High-throughput Locust configuration for 2000 messages/second
Optimized for Kafka load testing with connection pooling and batching
"""

import json
import os
import threading
import time
import uuid
from pathlib import Path

from kafka import KafkaConsumer, KafkaProducer
from kafka.errors import KafkaError
from locust import User, constant_throughput, events, task

# Configuration for high throughput
TARGET_MESSAGES = int(os.getenv("LOCUST_TARGET_MESSAGES", "1000000"))
PRODUCER_TOPIC = os.getenv("LOCUST_PRODUCER_TOPIC", "orders.raw")
CONSUMER_TOPIC = os.getenv("LOCUST_CONSUMER_TOPIC", "orders.enriched")
KAFKA_BOOTSTRAP = os.getenv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9094")
GROUP_ID = os.getenv("LOCUST_GROUP_ID", "locust-high-throughput")
TEST_ID = os.getenv("LOCUST_TEST_ID", str(uuid.uuid4()))

# High-throughput settings
MESSAGES_PER_SECOND = int(os.getenv("MESSAGES_PER_SECOND", "2000"))
BATCH_SIZE = int(os.getenv("BATCH_SIZE", "100"))
LINGER_MS = int(os.getenv("LINGER_MS", "5"))

state_lock = threading.Lock()
state = {"sent": 0, "received": 0, "stopped": False}
environment_holder = {"env": None}

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

class HighThroughputKafkaUser(User):
    abstract = False
    # Calculate wait time to achieve target throughput per user
    # For 2000 msg/sec with 100 users = 20 msg/sec per user = 50ms wait
    wait_time = constant_throughput(MESSAGES_PER_SECOND / 100)  # Assuming 100 users
    
    def on_start(self) -> None:
        # Optimized producer configuration for high throughput
        self.producer = KafkaProducer(
            bootstrap_servers=KAFKA_BOOTSTRAP,
            value_serializer=lambda v: json.dumps(v).encode("utf-8"),
            # High throughput optimizations
            batch_size=32768,  # Larger batch size
            linger_ms=LINGER_MS,  # Small linger for better batching
            compression_type='snappy',  # Enable compression
            acks=1,  # Faster than acks='all'
            retries=3,
            max_in_flight_requests_per_connection=5,
            buffer_memory=67108864,  # 64MB buffer
            # Connection optimizations
            connections_max_idle_ms=540000,
            request_timeout_ms=30000,
            metadata_max_age_ms=300000,
        )
        
        # Consumer setup (optional for end-to-end verification)
        consume = os.getenv("LOCUST_ENABLE_CONSUMER", "0") == "1"
        self.consumer = None
        if consume:
            self.consumer = KafkaConsumer(
                CONSUMER_TOPIC,
                bootstrap_servers=KAFKA_BOOTSTRAP,
                group_id=GROUP_ID,
                auto_offset_reset="latest",
                enable_auto_commit=True,
                value_deserializer=lambda v: json.loads(v.decode("utf-8")),
                consumer_timeout_ms=1000,
                # Consumer optimizations
                fetch_min_bytes=50000,  # Wait for more data
                fetch_max_wait_ms=500,  # Max wait time
                max_partition_fetch_bytes=1048576,  # 1MB per partition
            )
            self.consumer_thread = threading.Thread(target=self._consume_loop, daemon=True)
            self.consumer_thread.start()

    def on_stop(self) -> None:
        try:
            self.producer.flush(timeout=10)
            self.producer.close(timeout=10)
        except Exception as e:
            print(f"Producer close error: {e}")
        
        if self.consumer:
            try:
                self.consumer.close()
            except Exception as e:
                print(f"Consumer close error: {e}")

    @task
    def produce_message(self) -> None:
        with state_lock:
            if state["sent"] >= TARGET_MESSAGES:
                return
                
        # Create message payload
        payload = {
            "test_id": TEST_ID,
            "message_id": str(uuid.uuid4()),
            "sent_at": time.time(),
            "sequence": state["sent"],
            "order": {
                "orderId": str(uuid.uuid4()),
                "customerId": f"customer-{state['sent'] % 10000}",
                "amount": round(10 + (state["sent"] % 1000), 2),
                "status": "pending",
                "items": [
                    {"id": f"item-{i}", "quantity": i + 1, "price": 10.0 * (i + 1)}
                    for i in range(3)  # Add some bulk to message
                ]
            },
        }
        
        start_time = time.perf_counter()
        try:
            # Send asynchronously for better throughput
            future = self.producer.send(PRODUCER_TOPIC, value=payload)
            # Don't wait for confirmation to maximize throughput
            # future.get(timeout=1)  # Commented out for max speed
            
            elapsed_ms = (time.perf_counter() - start_time) * 1000
            events.request.fire(
                request_type="Kafka",
                name="produce",
                response_time=elapsed_ms,
                response_length=len(json.dumps(payload)),
            )
            
            with state_lock:
                state["sent"] += 1
                
            maybe_stop()
            
        except KafkaError as exc:
            elapsed_ms = (time.perf_counter() - start_time) * 1000
            events.request.fire(
                request_type="Kafka",
                name="produce",
                response_time=elapsed_ms,
                response_length=0,
                exception=exc,
            )

    def _consume_loop(self) -> None:
        """Optional consumer loop for end-to-end verification"""
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
                    if not isinstance(value, dict) or value.get("test_id") != TEST_ID:
                        continue
                        
                    sent_at = value.get("sent_at")
                    if sent_at:
                        latency_ms = (time.time() - float(sent_at)) * 1000
                        events.request.fire(
                            request_type="Kafka",
                            name="consume",
                            response_time=latency_ms,
                            response_length=len(json.dumps(value)),
                        )
                    
                    with state_lock:
                        state["received"] += 1
                    maybe_stop()
                    
            with state_lock:
                if state["stopped"]:
                    break

if __name__ == "__main__":
    print("High-Throughput Kafka Load Test Configuration")
    print("=" * 50)
    print(f"Target Messages: {TARGET_MESSAGES:,}")
    print(f"Target Rate: {MESSAGES_PER_SECOND:,} messages/second")
    print(f"Kafka Bootstrap: {KAFKA_BOOTSTRAP}")
    print(f"Producer Topic: {PRODUCER_TOPIC}")
    print(f"Consumer Topic: {CONSUMER_TOPIC}")
    print(f"Batch Size: {BATCH_SIZE}")
    print(f"Linger MS: {LINGER_MS}")
    print("=" * 50)