#!/bin/bash

KIBANA_URL="http://localhost:5601"
ES_URL="http://localhost:9200"

echo "🔧 Setting up Kibana Dashboard for Kafka Orchestrator"
echo "======================================================"

# Check if Kibana is accessible
echo "1. Checking Kibana connectivity..."
if curl -s "$KIBANA_URL/api/status" > /dev/null; then
    echo "✅ Kibana is accessible"
else
    echo "❌ Kibana is not accessible. Please check if it's running."
    exit 1
fi

# Check if index exists
echo "2. Checking Elasticsearch index..."
if curl -s "$ES_URL/orchestrator-logs-*/_count" > /dev/null; then
    echo "✅ Orchestrator logs index found"
else
    echo "❌ No orchestrator logs found. Please run the application first."
    exit 1
fi

# Create index pattern
echo "3. Creating index pattern..."
curl -X POST "$KIBANA_URL/api/saved_objects/index-pattern/orchestrator-logs-pattern" \
  -H "Content-Type: application/json" \
  -H "kbn-xsrf: true" \
  -d '{
    "attributes": {
      "title": "orchestrator-logs-*",
      "timeFieldName": "@timestamp",
      "fields": "[{\"name\":\"@timestamp\",\"type\":\"date\",\"searchable\":true,\"aggregatable\":true},{\"name\":\"level\",\"type\":\"string\",\"searchable\":true,\"aggregatable\":true},{\"name\":\"message\",\"type\":\"string\",\"searchable\":true,\"aggregatable\":false},{\"name\":\"logger_name\",\"type\":\"string\",\"searchable\":true,\"aggregatable\":true},{\"name\":\"thread_name\",\"type\":\"string\",\"searchable\":true,\"aggregatable\":true}]"
    }
  }' 2>/dev/null

if [ $? -eq 0 ]; then
    echo "✅ Index pattern created successfully"
else
    echo "⚠️  Index pattern might already exist (this is normal)"
fi

echo ""
echo "🎯 Next Steps:"
echo "==============="
echo ""
echo "1. Open Kibana in your browser:"
echo "   👉 http://localhost:5601"
echo ""
echo "2. Go to Discover to view logs:"
echo "   👉 Menu → Analytics → Discover"
echo ""
echo "3. Select the index pattern:"
echo "   👉 'orchestrator-logs-*'"
echo ""
echo "4. Set time range to 'Last 1 hour'"
echo ""
echo "5. Try these useful filters:"
echo "   • level:ERROR                    (Show only errors)"
echo "   • logger_name:*OrchestratorService*  (Service logs)"
echo "   • message:*kafka*               (Kafka-related logs)"
echo "   • message:*transaction*         (Transaction logs)"
echo ""
echo "6. Create visualizations:"
echo "   👉 Menu → Analytics → Visualize Library"
echo ""
echo "🔍 Quick Log Analysis:"
echo "======================"

# Show recent log summary
echo ""
echo "Recent log levels:"
curl -s "$ES_URL/orchestrator-logs-*/_search?size=0&pretty" \
  -H "Content-Type: application/json" \
  -d '{
    "aggs": {
      "log_levels": {
        "terms": {
          "field": "level.keyword",
          "size": 10
        }
      }
    }
  }' | grep -A 20 '"aggregations"' | grep -E '"key"|"doc_count"'

echo ""
echo "✅ Kibana setup complete!"
echo "Visit: http://localhost:5601"