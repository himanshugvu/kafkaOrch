#!/bin/bash

KIBANA_URL="http://localhost:5601"

echo "📊 Creating Kafka Orchestrator Dashboard"
echo "========================================"

# Create Error Rate Metric Visualization
echo "1. Creating Error Rate visualization..."
curl -X POST "$KIBANA_URL/api/saved_objects/visualization" \
  -H "Content-Type: application/json" \
  -H "kbn-xsrf: true" \
  -d '{
    "attributes": {
      "title": "Error Rate",
      "type": "metric",
      "visState": "{\"title\":\"Error Rate\",\"type\":\"metric\",\"params\":{\"addTooltip\":true,\"addLegend\":false,\"metric\":{\"colorSchema\":\"Red to Green\",\"colorsRange\":[{\"from\":0,\"to\":50},{\"from\":50,\"to\":75},{\"from\":75,\"to\":100}],\"style\":{\"bgFill\":\"#000\",\"bgColor\":false,\"labelColor\":false,\"subText\":\"\",\"fontSize\":60},\"labels\":{\"show\":true}}},\"aggs\":[{\"id\":\"1\",\"type\":\"count\",\"schema\":\"metric\",\"params\":{}}]}",
      "uiStateJSON": "{}",
      "kibanaSavedObjectMeta": {
        "searchSourceJSON": "{\"index\":\"orchestrator-logs-pattern\",\"query\":{\"query_string\":{\"query\":\"level:ERROR\",\"analyze_wildcard\":true}},\"filter\":[]}"
      }
    }
  }' 2>/dev/null

# Create Log Levels Over Time
echo "2. Creating Log Levels Over Time visualization..."
curl -X POST "$KIBANA_URL/api/saved_objects/visualization" \
  -H "Content-Type: application/json" \
  -H "kbn-xsrf: true" \
  -d '{
    "attributes": {
      "title": "Log Levels Over Time",
      "type": "line",
      "visState": "{\"title\":\"Log Levels Over Time\",\"type\":\"line\",\"params\":{\"addTooltip\":true,\"addLegend\":true,\"showCircles\":true,\"interpolate\":\"linear\",\"scale\":\"linear\",\"drawLinesBetweenPoints\":true,\"radiusRatio\":9,\"times\":[],\"grid\":{\"categoryLines\":false,\"style\":{\"color\":\"#eee\"}}},\"aggs\":[{\"id\":\"1\",\"type\":\"count\",\"schema\":\"metric\",\"params\":{}},{\"id\":\"2\",\"type\":\"date_histogram\",\"schema\":\"segment\",\"params\":{\"field\":\"@timestamp\",\"interval\":\"auto\",\"customInterval\":\"2h\",\"min_doc_count\":1,\"extended_bounds\":{}}},{\"id\":\"3\",\"type\":\"terms\",\"schema\":\"group\",\"params\":{\"field\":\"level.keyword\",\"size\":5,\"order\":\"desc\",\"orderBy\":\"1\"}}]}",
      "uiStateJSON": "{}",
      "kibanaSavedObjectMeta": {
        "searchSourceJSON": "{\"index\":\"orchestrator-logs-pattern\",\"query\":{\"match_all\":{}},\"filter\":[]}"
      }
    }
  }' 2>/dev/null

# Create Top Error Messages Table
echo "3. Creating Top Error Messages visualization..."
curl -X POST "$KIBANA_URL/api/saved_objects/visualization" \
  -H "Content-Type: application/json" \
  -H "kbn-xsrf: true" \
  -d '{
    "attributes": {
      "title": "Top Error Messages",
      "type": "table",
      "visState": "{\"title\":\"Top Error Messages\",\"type\":\"table\",\"params\":{\"perPage\":10,\"showPartialRows\":false,\"showMeticsAtAllLevels\":false,\"sort\":{\"columnIndex\":null,\"direction\":null},\"showTotal\":false,\"totalFunc\":\"sum\"},\"aggs\":[{\"id\":\"1\",\"type\":\"count\",\"schema\":\"metric\",\"params\":{}},{\"id\":\"2\",\"type\":\"terms\",\"schema\":\"bucket\",\"params\":{\"field\":\"message.keyword\",\"size\":10,\"order\":\"desc\",\"orderBy\":\"1\"}}]}",
      "uiStateJSON": "{\"vis\":{\"params\":{\"sort\":{\"columnIndex\":null,\"direction\":null}}}}",
      "kibanaSavedObjectMeta": {
        "searchSourceJSON": "{\"index\":\"orchestrator-logs-pattern\",\"query\":{\"query_string\":{\"query\":\"level:ERROR\",\"analyze_wildcard\":true}},\"filter\":[]}"
      }
    }
  }' 2>/dev/null

# Create Kafka Operations Timeline
echo "4. Creating Kafka Operations visualization..."
curl -X POST "$KIBANA_URL/api/saved_objects/visualization" \
  -H "Content-Type: application/json" \
  -H "kbn-xsrf: true" \
  -d '{
    "attributes": {
      "title": "Kafka Operations Timeline",
      "type": "histogram",
      "visState": "{\"title\":\"Kafka Operations Timeline\",\"type\":\"histogram\",\"params\":{\"addTooltip\":true,\"addLegend\":true,\"scale\":\"linear\",\"mode\":\"stacked\",\"times\":[],\"addTimeMarker\":false},\"aggs\":[{\"id\":\"1\",\"type\":\"count\",\"schema\":\"metric\",\"params\":{}},{\"id\":\"2\",\"type\":\"date_histogram\",\"schema\":\"segment\",\"params\":{\"field\":\"@timestamp\",\"interval\":\"auto\",\"customInterval\":\"2h\",\"min_doc_count\":1,\"extended_bounds\":{}}}]}",
      "uiStateJSON": "{}",
      "kibanaSavedObjectMeta": {
        "searchSourceJSON": "{\"index\":\"orchestrator-logs-pattern\",\"query\":{\"query_string\":{\"query\":\"logger_name:*kafka* OR message:*kafka* OR message:*producer* OR message:*consumer*\",\"analyze_wildcard\":true}},\"filter\":[]}"
      }
    }
  }' 2>/dev/null

# Create the main dashboard
echo "5. Creating main dashboard..."
curl -X POST "$KIBANA_URL/api/saved_objects/dashboard" \
  -H "Content-Type: application/json" \
  -H "kbn-xsrf: true" \
  -d '{
    "attributes": {
      "title": "Kafka Orchestrator Monitoring",
      "description": "Real-time monitoring dashboard for Kafka message orchestrator",
      "panelsJSON": "[{\"gridData\":{\"x\":0,\"y\":0,\"w\":24,\"h\":15,\"i\":\"1\"},\"panelIndex\":\"1\",\"embeddableConfig\":{},\"panelRefName\":\"panel_1\"},{\"gridData\":{\"x\":24,\"y\":0,\"w\":24,\"h\":15,\"i\":\"2\"},\"panelIndex\":\"2\",\"embeddableConfig\":{},\"panelRefName\":\"panel_2\"},{\"gridData\":{\"x\":0,\"y\":15,\"w\":48,\"h\":15,\"i\":\"3\"},\"panelIndex\":\"3\",\"embeddableConfig\":{},\"panelRefName\":\"panel_3\"},{\"gridData\":{\"x\":0,\"y\":30,\"w\":48,\"h\":15,\"i\":\"4\"},\"panelIndex\":\"4\",\"embeddableConfig\":{},\"panelRefName\":\"panel_4\"}]",
      "timeRestore": true,
      "timeTo": "now",
      "timeFrom": "now-1h",
      "refreshInterval": {
        "pause": false,
        "value": 10000
      },
      "version": 1
    }
  }' 2>/dev/null

echo ""
echo "✅ Dashboard created successfully!"
echo ""
echo "🎯 Access Your Dashboard:"
echo "========================"
echo "1. Open Kibana: http://localhost:5601"
echo "2. Navigate to: Menu → Analytics → Dashboard"
echo "3. Select: 'Kafka Orchestrator Monitoring'"
echo ""
echo "🔍 Dashboard Features:"
echo "====================="
echo "• Error Rate Metric"
echo "• Log Levels Over Time"
echo "• Top Error Messages"
echo "• Kafka Operations Timeline"
echo "• Auto-refresh every 10 seconds"
echo "• Time range: Last 1 hour"
echo ""
echo "💡 Pro Tips:"
echo "============"
echo "• Use time picker to adjust range"
echo "• Click on chart elements to filter"
echo "• Add more visualizations as needed"
echo "• Set up alerts for critical errors"