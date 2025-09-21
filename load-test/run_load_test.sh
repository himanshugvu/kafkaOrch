#!/bin/bash

# Load Test Configuration
export LOCUST_TARGET_MESSAGES=1000000
export LOCUST_PRODUCER_TOPIC="orders.raw"
export LOCUST_CONSUMER_TOPIC="orders.enriched" 
export KAFKA_BOOTSTRAP_SERVERS="localhost:9092"
export LOCUST_GROUP_ID="locust-verify"
export LATENCY_OUTPUT="latency_results_1M.csv"
export LATENCY_FLUSH_BATCH=10000

echo "=== Starting Kafka Load Test for 1 Million Records ==="
echo "Target Messages: $LOCUST_TARGET_MESSAGES"
echo "Producer Topic: $LOCUST_PRODUCER_TOPIC"
echo "Consumer Topic: $LOCUST_CONSUMER_TOPIC"
echo "Kafka Bootstrap: $KAFKA_BOOTSTRAP_SERVERS"
echo ""

# Check if Kafka is accessible
echo "Checking Kafka connectivity..."
docker exec kafkaorch-kafka-1 kafka-topics.sh --bootstrap-server localhost:9092 --list > /dev/null 2>&1
if [ $? -eq 0 ]; then
    echo "✅ Kafka is accessible"
else
    echo "❌ Kafka is not accessible. Please start docker-compose first."
    exit 1
fi

# Create output directory
mkdir -p results
cd results

echo ""
echo "=== Available Load Test Options ==="
echo ""
echo "1. HEADLESS MODE (fastest, no UI):"
echo "   locust -f ../locustfile.py --headless -u 50 -r 10 -t 30m --html=report_1M.html"
echo ""
echo "2. WEB UI MODE (with monitoring dashboard):"
echo "   locust -f ../locustfile.py --host=http://localhost:9092"
echo "   Then visit: http://localhost:8089"
echo ""
echo "3. CUSTOM USERS AND SPAWN RATE:"
echo "   locust -f ../locustfile.py --headless -u 100 -r 20 -t 45m"
echo ""

read -p "Choose mode (1=headless, 2=web): " mode

if [ "$mode" = "1" ]; then
    echo "Starting headless load test..."
    locust -f ../locustfile.py --headless -u 50 -r 10 -t 30m --html=report_1M.html --csv=stats_1M
elif [ "$mode" = "2" ]; then
    echo "Starting web UI mode..."
    echo "Visit http://localhost:8089 to configure and start the test"
    locust -f ../locustfile.py --host=http://localhost:9092
else
    echo "Invalid choice. Exiting."
    exit 1
fi