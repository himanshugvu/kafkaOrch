#!/usr/bin/env python3
"""
Simple Kafka load test using native Python
Works directly with Docker network
"""

import json
import time
import uuid
import threading
from kafka import KafkaProducer, KafkaConsumer
from kafka.errors import KafkaError
import os
import sys

# Configuration
TARGET_MESSAGES = int(os.getenv("TARGET_MESSAGES", "1000"))
KAFKA_BOOTSTRAP = os.getenv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9094")  # External port
PRODUCER_TOPIC = os.getenv("PRODUCER_TOPIC", "orders.raw")
CONSUMER_TOPIC = os.getenv("CONSUMER_TOPIC", "orders.enriched")

print("Kafka Load Test Configuration")
print("===============================")
print(f"Target Messages: {TARGET_MESSAGES:,}")
print(f"Kafka Bootstrap: {KAFKA_BOOTSTRAP}")
print(f"Producer Topic: {PRODUCER_TOPIC}")
print(f"Consumer Topic: {CONSUMER_TOPIC}")
print("")

# Test Kafka connectivity
try:
    print("Testing Kafka connectivity...")
    producer = KafkaProducer(
        bootstrap_servers=KAFKA_BOOTSTRAP,
        value_serializer=lambda v: json.dumps(v).encode('utf-8'),
        acks='all',
        retries=5,
        request_timeout_ms=10000
    )
    print("Successfully connected to Kafka")
    producer.close()
except Exception as e:
    print(f"Failed to connect to Kafka: {e}")
    sys.exit(1)

# Global counters
stats = {
    "sent": 0,
    "received": 0,
    "errors": 0,
    "start_time": time.time()
}
lock = threading.Lock()

def progress_reporter():
    """Print progress every 5 seconds"""
    while stats["sent"] < TARGET_MESSAGES or stats["received"] < TARGET_MESSAGES:
        time.sleep(5)
        with lock:
            elapsed = time.time() - stats["start_time"]
            sent_rate = stats["sent"] / elapsed if elapsed > 0 else 0
            recv_rate = stats["received"] / elapsed if elapsed > 0 else 0
            
            print(f"Progress: Sent {stats['sent']:,}/{TARGET_MESSAGES:,} "
                  f"({sent_rate:.1f}/s) | Received {stats['received']:,} "
                  f"({recv_rate:.1f}/s) | Errors: {stats['errors']}")

def consumer_worker():
    """Consumer worker to track end-to-end latency"""
    try:
        consumer = KafkaConsumer(
            CONSUMER_TOPIC,
            bootstrap_servers=KAFKA_BOOTSTRAP,
            group_id=f"load-test-{uuid.uuid4()}",
            auto_offset_reset='latest',
            value_deserializer=lambda v: json.loads(v.decode('utf-8')),
            consumer_timeout_ms=5000
        )
        
        print("Consumer started, listening for processed messages...")
        
        for message in consumer:
            with lock:
                stats["received"] += 1
                
            if stats["received"] >= TARGET_MESSAGES:
                break
                
    except Exception as e:
        print(f"Consumer error: {e}")
    finally:
        consumer.close()

def producer_worker():
    """Producer worker to send messages"""
    try:
        producer = KafkaProducer(
            bootstrap_servers=KAFKA_BOOTSTRAP,
            value_serializer=lambda v: json.dumps(v).encode('utf-8'),
            acks='all',
            retries=5,
            batch_size=16384,
            linger_ms=10
        )
        
        print(f"Starting to send {TARGET_MESSAGES:,} messages...")
        
        for i in range(TARGET_MESSAGES):
            message = {
                "test_id": str(uuid.uuid4()),
                "sequence": i,
                "timestamp": time.time(),
                "order": {
                    "orderId": str(uuid.uuid4()),
                    "customerId": f"customer-{i % 1000}",
                    "amount": round(10 + (i % 1000), 2),
                    "status": "pending"
                }
            }
            
            try:
                future = producer.send(PRODUCER_TOPIC, value=message)
                future.get(timeout=10)  # Wait for confirmation
                
                with lock:
                    stats["sent"] += 1
                    
            except KafkaError as e:
                with lock:
                    stats["errors"] += 1
                print(f"Send error: {e}")
                
            # Small delay to avoid overwhelming
            if i % 100 == 0:
                time.sleep(0.01)
                
        producer.flush()
        producer.close()
        print(f"Finished sending {stats['sent']:,} messages")
        
    except Exception as e:
        print(f"Producer error: {e}")

def main():
    """Main load test execution"""
    print("Starting load test...")
    
    # Start background threads
    progress_thread = threading.Thread(target=progress_reporter, daemon=True)
    consumer_thread = threading.Thread(target=consumer_worker, daemon=True)
    
    progress_thread.start()
    consumer_thread.start()
    
    # Give consumer time to start
    time.sleep(2)
    
    # Run producer
    start_time = time.time()
    producer_worker()
    
    # Wait for completion or timeout
    print("Waiting for all messages to be processed...")
    timeout = 300  # 5 minutes timeout
    
    while (stats["received"] < TARGET_MESSAGES and 
           time.time() - start_time < timeout):
        time.sleep(1)
    
    # Final results
    elapsed = time.time() - start_time
    
    print(f"\nLoad Test Results")
    print(f"==================")
    print(f"Messages Sent: {stats['sent']:,}")
    print(f"Messages Received: {stats['received']:,}")
    print(f"Errors: {stats['errors']:,}")
    print(f"Total Time: {elapsed:.2f} seconds")
    print(f"Send Rate: {stats['sent'] / elapsed:.2f} messages/second")
    print(f"Receive Rate: {stats['received'] / elapsed:.2f} messages/second")
    
    if stats['received'] >= TARGET_MESSAGES:
        print("Load test completed successfully!")
    else:
        print("Load test completed with partial success")

if __name__ == "__main__":
    main()