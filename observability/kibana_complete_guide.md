# 📊 **Complete Kibana Dashboard Guide for Kafka Orchestrator**

## 🚀 **Quick Start** (5 minutes)

### **✅ What's Already Done:**
- ✅ Index pattern created: `orchestrator-logs-*`
- ✅ Empty dashboard created: "Kafka Orchestrator Monitoring"
- ✅ 634+ log entries ready for analysis

### **📍 Access Points:**
- **Kibana**: http://localhost:5601
- **Elasticsearch**: http://localhost:9200
- **Dashboard**: Menu → Analytics → Dashboard → "Kafka Orchestrator Monitoring"

## 🎯 **Step-by-Step Setup**

### **Step 1: Access Kibana**
1. Open: **http://localhost:5601**
2. Wait for all plugins to load (green status)

### **Step 2: Explore Your Logs**
1. **Navigate**: Menu → Analytics → **Discover**
2. **Index Pattern**: Select `orchestrator-logs-*`
3. **Time Range**: Set to "Last 1 hour"
4. **Refresh**: You should see logs streaming in

### **Step 3: Quick Log Analysis**

#### **🔍 Useful Search Queries:**

```bash
# Find all errors
level:ERROR

# Kafka-related logs
logger_name:*kafka* OR message:*kafka*

# Orchestrator service logs
logger_name:"com.acme.orch.starter.runtime.OrchestratorService"

# Transaction processing
message:*transaction* AND message:*producer*

# Consumer group activity
message:*consumer* AND message:*group*

# Database operations
logger_name:*JdbcFailureTracker* OR message:*database*

# Performance indicators
message:("Started" OR "completed" OR "processing")
```

### **Step 4: Create Visualizations**

#### **📈 Visualization 1: Error Rate Metric**
1. **Navigate**: Menu → Analytics → **Visualize Library**
2. **Create**: "Create visualization" → **Metric**
3. **Data source**: `orchestrator-logs-*`
4. **Query**: `level:ERROR`
5. **Metric**: Count
6. **Title**: "Error Count"
7. **Save** as "Error Rate"

#### **📊 Visualization 2: Log Levels Over Time**
1. **Create**: New visualization → **Line**
2. **Data source**: `orchestrator-logs-*`
3. **X-axis**: Date histogram → `@timestamp` → Auto interval
4. **Y-axis**: Count
5. **Split series**: Terms → `level.keyword` → Top 5
6. **Title**: "Log Levels Over Time"
7. **Save**

#### **📋 Visualization 3: Top Error Messages**
1. **Create**: New visualization → **Data table**
2. **Data source**: `orchestrator-logs-*`
3. **Query**: `level:ERROR`
4. **Buckets**: Terms → `message.keyword` → Size 10
5. **Metrics**: Count
6. **Title**: "Top Error Messages"
7. **Save**

#### **⚡ Visualization 4: Kafka Activity Timeline**
1. **Create**: New visualization → **Area**
2. **Data source**: `orchestrator-logs-*`
3. **Query**: `message:*kafka* OR message:*producer* OR message:*consumer*`
4. **X-axis**: Date histogram → `@timestamp` → Auto
5. **Y-axis**: Count
6. **Title**: "Kafka Activity"
7. **Save**

### **Step 5: Build Dashboard**
1. **Navigate**: Menu → Analytics → **Dashboard**
2. **Open**: "Kafka Orchestrator Monitoring"
3. **Edit**: Click "Edit" button
4. **Add**: Click "Add from library"
5. **Select**: All your saved visualizations
6. **Arrange**: Drag to organize layout
7. **Save**: Dashboard

## 🎛️ **Dashboard Layout Suggestion**

```
┌─────────────────┬─────────────────┐
│   Error Rate    │  Log Levels     │
│   (Metric)      │  Over Time      │
│                 │  (Line Chart)   │
├─────────────────┴─────────────────┤
│         Top Error Messages        │
│           (Data Table)            │
├───────────────────────────────────┤
│       Kafka Activity Timeline     │
│          (Area Chart)             │
└───────────────────────────────────┘
```

## 🔍 **Advanced Filters & Analysis**

### **Performance Monitoring**
```bash
# Transaction completion rate
message:"transaction" AND (message:"commit" OR message:"rollback")

# Consumer lag indicators
message:*lag* OR message:*offset*

# Circuit breaker status
message:("circuit" OR "degraded" OR "timeout")
```

### **Error Analysis**
```bash
# Database connection issues
message:*connection* AND level:ERROR

# Kafka connectivity problems
message:*bootstrap* AND level:ERROR

# Serialization errors
message:*serializ* AND level:ERROR
```

### **Operational Insights**
```bash
# Application startup sequence
message:("Starting" OR "Started" OR "Stopped")

# Thread pool activity
thread_name:*pool* OR thread_name:*executor*

# Memory and resource usage
message:*memory* OR message:*pool* OR message:*heap*
```

## 📈 **Real-Time Monitoring Setup**

### **Auto-Refresh Configuration**
1. In your dashboard, click **refresh icon**
2. Set **auto-refresh** to **10 seconds**
3. Time range: **Last 15 minutes** for real-time monitoring

### **Quick Health Checks**
```bash
# System health (should be mostly INFO)
level:* | terms level.keyword

# Recent errors (should be minimal)
level:ERROR AND @timestamp:[now-5m TO now]

# Kafka connectivity (should show regular activity)
message:*coordinator* OR message:*rebalance*
```

## 🚨 **Alerting Setup** (Optional)

### **Watcher for High Error Rate**
1. **Navigate**: Stack Management → **Watcher**
2. **Create watch**:
```json
{
  "trigger": {
    "schedule": {
      "interval": "1m"
    }
  },
  "input": {
    "search": {
      "request": {
        "indices": ["orchestrator-logs-*"],
        "body": {
          "query": {
            "bool": {
              "filter": [
                {"term": {"level.keyword": "ERROR"}},
                {"range": {"@timestamp": {"gte": "now-5m"}}}
              ]
            }
          }
        }
      }
    }
  },
  "condition": {
    "compare": {
      "ctx.payload.hits.total": {
        "gt": 5
      }
    }
  }
}
```

## 💡 **Pro Tips**

1. **Saved Searches**: Save frequently used queries
2. **Time Zones**: Configure your timezone in Kibana settings
3. **Field Formatting**: Format timestamp fields for readability
4. **Index Lifecycle**: Set up index rotation for log management
5. **Performance**: Use shorter time ranges for better performance

## 🛠️ **Troubleshooting**

### **No Data Showing**
- Check time range (set to last 1 hour)
- Verify index pattern exists
- Confirm application is generating logs

### **Slow Performance**
- Reduce time range
- Use more specific filters
- Limit visualization data points

### **Missing Fields**
- Go to Stack Management → Index Patterns
- Select `orchestrator-logs-*`
- Click "Refresh field list"

## 📊 **Current Log Statistics**

Based on your current data:
- **Total Logs**: 634+
- **INFO**: 586 (92.5%)
- **WARN**: 29 (4.6%)  
- **ERROR**: 19 (3.0%)

## 🎯 **Next Steps**

1. **Access Kibana**: http://localhost:5601
2. **Go to Discover**: View real-time logs
3. **Create visualizations**: Follow Step 4 above
4. **Build dashboard**: Combine all visualizations
5. **Set up monitoring**: Auto-refresh and alerts

Your ELK stack is fully operational and ready for comprehensive log analysis! 🚀