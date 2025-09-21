#!/bin/bash

echo "🔍 Verifying Kibana Setup for Kafka Orchestrator"
echo "================================================="

# Check services
echo "1. Checking service health..."
KIBANA_STATUS=$(curl -s "http://localhost:5601/api/status" | grep -o '"state":"[^"]*"' | head -1 | cut -d'"' -f4)
ES_STATUS=$(curl -s "http://localhost:9200/_cluster/health" | grep -o '"status":"[^"]*"' | cut -d'"' -f4)

if [ "$KIBANA_STATUS" = "green" ]; then
    echo "✅ Kibana: $KIBANA_STATUS"
else
    echo "⚠️  Kibana: $KIBANA_STATUS"
fi

if [ "$ES_STATUS" = "yellow" ] || [ "$ES_STATUS" = "green" ]; then
    echo "✅ Elasticsearch: $ES_STATUS"
else
    echo "❌ Elasticsearch: $ES_STATUS"
fi

# Check index
echo "2. Checking log index..."
LOG_COUNT=$(curl -s "http://localhost:9200/orchestrator-logs-*/_count" | grep -o '"count":[0-9]*' | cut -d':' -f2)
echo "📊 Total logs indexed: $LOG_COUNT"

# Check index pattern
echo "3. Checking index pattern..."
INDEX_PATTERN=$(curl -s "http://localhost:5601/api/saved_objects/index-pattern/orchestrator-logs-pattern" | grep -o '"title":"[^"]*"')
if [[ $INDEX_PATTERN == *"orchestrator-logs"* ]]; then
    echo "✅ Index pattern: Created"
else
    echo "❌ Index pattern: Missing"
fi

# Check dashboard
echo "4. Checking dashboard..."
DASHBOARD=$(curl -s "http://localhost:5601/api/saved_objects/_find?type=dashboard&search=Kafka" | grep -o '"title":"[^"]*Kafka[^"]*"')
if [[ $DASHBOARD == *"Kafka"* ]]; then
    echo "✅ Dashboard: Created"
else
    echo "❌ Dashboard: Missing"
fi

# Show recent log summary
echo "5. Recent log analysis..."
curl -s "http://localhost:9200/orchestrator-logs-*/_search?size=0" \
  -H "Content-Type: application/json" \
  -d '{
    "aggs": {
      "log_levels": {
        "terms": {
          "field": "level.keyword",
          "size": 10
        }
      },
      "recent_activity": {
        "date_histogram": {
          "field": "@timestamp",
          "calendar_interval": "5m"
        }
      }
    }
  }' | python -c "
import json, sys
data = json.load(sys.stdin)
levels = data['aggregations']['log_levels']['buckets']
print('📈 Log Level Distribution:')
for level in levels:
    print(f'   {level[\"key\"]}: {level[\"doc_count\"]} logs')
"

echo ""
echo "🎯 Ready to Use!"
echo "=================="
echo ""
echo "🌐 Access Points:"
echo "   Kibana Dashboard: http://localhost:5601"
echo "   Elasticsearch:   http://localhost:9200"
echo ""
echo "📋 Quick Actions:"
echo "   1. Open Kibana → Analytics → Discover"
echo "   2. Select 'orchestrator-logs-*' index"
echo "   3. Set time range to 'Last 1 hour'"
echo "   4. Try search: level:ERROR"
echo ""
echo "📊 Build Dashboard:"
echo "   1. Go to Analytics → Dashboard"
echo "   2. Open 'Kafka Orchestrator Monitoring'"
echo "   3. Follow the guide in kibana_complete_guide.md"
echo ""
echo "✅ Setup verification complete!"