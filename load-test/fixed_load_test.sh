#!/bin/bash

echo "🚀 Fixed Kafka Load Test Script"
echo "==============================="

# Correct Kafka configuration for external access
export LOCUST_TARGET_MESSAGES=1000000
export LOCUST_PRODUCER_TOPIC="orders.raw"
export LOCUST_CONSUMER_TOPIC="orders.enriched"
export KAFKA_BOOTSTRAP_SERVERS="localhost:9094"  # External port!
export LOCUST_GROUP_ID="locust-verify"
export LATENCY_OUTPUT="latency_results_1M.csv"
export LATENCY_FLUSH_BATCH=10000

echo "🔧 Configuration:"
echo "   Target Messages: $LOCUST_TARGET_MESSAGES"
echo "   Kafka Bootstrap: $KAFKA_BOOTSTRAP_SERVERS"
echo "   Producer Topic: $LOCUST_PRODUCER_TOPIC"
echo "   Consumer Topic: $LOCUST_CONSUMER_TOPIC"
echo ""

# Test Kafka connectivity first
echo "1. Testing Kafka connectivity..."
timeout 5 bash -c "</dev/tcp/localhost/9094" 2>/dev/null
if [ $? -eq 0 ]; then
    echo "✅ Kafka port 9094 is accessible"
else
    echo "❌ Cannot connect to Kafka on port 9094"
    echo "   Make sure docker-compose is running!"
    exit 1
fi

# Create results directory
mkdir -p results
cd results

echo ""
echo "🎯 Starting Load Test Options:"
echo "=============================="
echo "1. Quick Test (1,000 messages)"
echo "2. Medium Test (10,000 messages)" 
echo "3. Full Test (1,000,000 messages)"
echo ""

read -p "Choose test size (1/2/3): " choice

case $choice in
    1)
        export LOCUST_TARGET_MESSAGES=1000
        echo "🧪 Running quick test with 1,000 messages..."
        python -m locust -f ../locustfile.py --headless -u 5 -r 2 -t 120s --html=quick_test_report.html --csv=quick_stats
        ;;
    2)
        export LOCUST_TARGET_MESSAGES=10000
        echo "🏃 Running medium test with 10,000 messages..."
        python -m locust -f ../locustfile.py --headless -u 20 -r 5 -t 300s --html=medium_test_report.html --csv=medium_stats
        ;;
    3)
        export LOCUST_TARGET_MESSAGES=1000000
        echo "🚀 Running full test with 1,000,000 messages..."
        echo "   This will take 15-30 minutes depending on your system"
        python -m locust -f ../locustfile.py --headless -u 100 -r 20 -t 45m --html=full_test_report.html --csv=full_stats
        ;;
    *)
        echo "❌ Invalid choice. Exiting."
        exit 1
        ;;
esac

echo ""
echo "✅ Load test completed!"
echo "📊 Check the generated HTML report for detailed results"