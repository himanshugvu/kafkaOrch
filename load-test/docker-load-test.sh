#!/bin/bash

echo "🐳 Docker-based Kafka Load Test"
echo "==============================="

# Check if docker-compose is running
if ! docker exec kafkaorch-kafka-1 kafka-topics.sh --bootstrap-server localhost:9092 --list > /dev/null 2>&1; then
    echo "❌ Kafka is not accessible. Please run docker-compose up first."
    exit 1
fi

echo "✅ Kafka is accessible"

# Create a temporary load test container
echo "🔧 Setting up load test environment..."

# First, let's run the load test from inside the Docker network
docker run --rm \
    --network kafkaorch_default \
    -v "$(pwd):/workspace" \
    -w /workspace \
    -e LOCUST_TARGET_MESSAGES=1000 \
    -e KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
    -e LOCUST_PRODUCER_TOPIC=orders.raw \
    -e LOCUST_CONSUMER_TOPIC=orders.enriched \
    python:3.13-slim \
    bash -c "
        echo '🔧 Installing dependencies...'
        pip install -q locust kafka-python
        
        echo '🚀 Starting load test with 1,000 messages...'
        python -m locust -f locustfile.py --headless -u 5 -r 2 -t 60s --html=test_report.html
        
        echo '📊 Load test completed!'
        ls -la *.html *.csv 2>/dev/null || echo 'No report files generated'
    "

echo ""
echo "✅ Load test execution complete!"
echo ""
echo "🎯 For 1 Million Records:"
echo "========================"
echo "Run this command:"
echo ""
echo "docker run --rm \\"
echo "    --network kafkaorch_default \\"
echo "    -v \"\$(pwd):/workspace\" \\"
echo "    -w /workspace \\"
echo "    -e LOCUST_TARGET_MESSAGES=1000000 \\"
echo "    -e KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \\"
echo "    -e LOCUST_PRODUCER_TOPIC=orders.raw \\"
echo "    -e LOCUST_CONSUMER_TOPIC=orders.enriched \\"
echo "    python:3.13-slim \\"
echo "    bash -c \""
echo "        pip install -q locust kafka-python && \\"
echo "        python -m locust -f locustfile.py --headless -u 100 -r 20 -t 45m --html=report_1M.html --csv=stats_1M"
echo "    \""
echo ""
echo "📈 Monitor progress:"
echo "   - Watch consumer lag: docker exec kafkaorch-kafka-1 kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group orders-orchestrator --describe"
echo "   - View logs: docker-compose logs -f orders-orchestrator"