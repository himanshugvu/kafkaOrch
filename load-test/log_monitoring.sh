#!/bin/bash

echo "=== Kafka Orchestrator Log Monitoring Guide ==="
echo ""

echo "1. REAL-TIME APPLICATION LOGS:"
echo "   docker-compose logs -f orders-orchestrator"
echo ""

echo "2. FILTERED LOGS (errors only):"
echo "   docker-compose logs orders-orchestrator | grep ERROR"
echo ""

echo "3. KAFKA CONSUMER GROUP MONITORING:"
echo "   docker exec kafkaorch-kafka-1 kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group orders-orchestrator --describe"
echo ""

echo "4. DATABASE FAILURE TRACKING:"
echo "   docker exec kafkaorch-postgres-1 psql -U postgres -d orch -c \"SELECT * FROM event_failures ORDER BY created_at DESC LIMIT 10;\""
echo ""

echo "5. KAFKA TOPICS LIST:"
echo "   docker exec kafkaorch-kafka-1 kafka-topics.sh --bootstrap-server localhost:9092 --list"
echo ""

echo "6. CONSUMER LAG MONITORING:"
echo "   watch -n 2 'docker exec kafkaorch-kafka-1 kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group orders-orchestrator --describe'"
echo ""

echo "7. APPLICATION METRICS (if available):"
echo "   curl -s http://localhost:8080/actuator/metrics | python -m json.tool"
echo ""

echo "8. CONTAINER STATS:"
echo "   docker stats kafkaorch-orders-orchestrator-1"