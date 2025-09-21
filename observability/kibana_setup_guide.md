# Kibana Dashboard Setup Guide for Kafka Orchestrator

## 🚀 **Quick Access**
- **Kibana URL**: http://localhost:5601
- **Elasticsearch**: http://localhost:9200
- **Available Index**: `orchestrator-logs-2025.09.21`

## 📋 **Step-by-Step Configuration**

### **Step 1: Access Kibana**
1. Open your browser and go to: **http://localhost:5601**
2. You should see the Kibana welcome screen

### **Step 2: Create Index Pattern**
1. **Navigate**: Kibana Menu → Stack Management → Index Patterns
2. **Click**: "Create index pattern"
3. **Index pattern name**: `orchestrator-logs-*`
4. **Time field**: `@timestamp`
5. **Click**: "Create index pattern"

### **Step 3: View Logs in Discover**
1. **Navigate**: Kibana Menu → Discover
2. **Select**: `orchestrator-logs-*` index pattern
3. **Time range**: Set to "Last 1 hour" or "Today"

## 🔍 **Key Log Fields to Focus On**

- **`@timestamp`**: Log timestamp
- **`level`**: Log level (INFO, WARN, ERROR)
- **`logger_name`**: Java logger name
- **`message`**: Log message content
- **`thread_name`**: Thread that generated log
- **`dedupKey`**: Message deduplication key (if present)

## 🎯 **Useful Filters and Searches**

### **Find Errors**
```
level:ERROR
```

### **Kafka-related Logs**
```
logger_name:*kafka* OR message:*kafka*
```

### **Orchestrator Service Logs**
```
logger_name:"com.acme.orch.starter.runtime.OrchestratorService"
```

### **Transaction Logs**
```
message:*transaction*
```

### **Consumer Group Logs**
```
message:*consumer* AND message:*group*
```

## 📊 **Dashboard Visualizations to Create**

### **1. Log Levels Over Time**
- **Type**: Line chart
- **X-axis**: @timestamp
- **Y-axis**: Count
- **Split series**: level

### **2. Error Rate**
- **Type**: Metric
- **Aggregation**: Count
- **Filter**: level:ERROR

### **3. Top Error Messages**
- **Type**: Data table
- **Rows**: message.keyword
- **Metrics**: Count
- **Filter**: level:ERROR

### **4. Thread Activity**
- **Type**: Heat map
- **X-axis**: @timestamp
- **Y-axis**: thread_name.keyword
- **Values**: Count

## 🎨 **Quick Dashboard Setup**

1. **Navigate**: Kibana Menu → Dashboard
2. **Click**: "Create new dashboard"
3. **Add visualizations** from above
4. **Save dashboard** as "Kafka Orchestrator Monitoring"

## 🔍 **Log Analysis Queries**

### **Performance Monitoring**
```
message:("Started" OR "completed" OR "processing")
```

### **Database Operations**
```
logger_name:*JdbcFailureTracker* OR message:*database*
```

### **Circuit Breaker Status**
```
message:("circuit" OR "degraded" OR "timeout")
```

## 🚨 **Alerting Setup**

### **Error Rate Alert**
1. **Navigate**: Stack Management → Watcher
2. **Create watch** for error rate > threshold
3. **Condition**: 
   ```json
   {
     "compare": {
       "ctx.payload.hits.total": {
         "gt": 10
       }
     }
   }
   ```

## 💡 **Pro Tips**

1. **Use time filters**: Always set appropriate time ranges
2. **Save searches**: Save frequently used queries
3. **Create dashboards**: Group related visualizations
4. **Set auto-refresh**: For real-time monitoring
5. **Use filters**: Narrow down to specific components

## 🛠 **Troubleshooting**

### **No Data Visible**
- Check time range
- Verify index pattern exists
- Confirm Logstash is running

### **Missing Fields**
- Refresh index pattern: Stack Management → Index Patterns → Refresh

### **Slow Performance**
- Reduce time range
- Add more specific filters
- Limit visualization data points