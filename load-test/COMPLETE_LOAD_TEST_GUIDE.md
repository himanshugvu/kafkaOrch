# 🚀 **Complete Load Test Guide for 1 Million Records**

## ✅ **Fixed and Ready to Use!**

### **Quick Start Commands**

#### **🧪 Test with 1,000 messages (2 minutes)**
```bash
cd load-test
set TARGET_MESSAGES=1000 && python final_load_test.py
```

#### **🏃 Test with 10,000 messages (5 minutes)**
```bash
cd load-test
set TARGET_MESSAGES=10000 && python final_load_test.py
```

#### **🚀 Full test with 1,000,000 messages (30-45 minutes)**
```bash
cd load-test
set TARGET_MESSAGES=1000000 && python final_load_test.py
```

---

## 📊 **Multiple Monitoring Windows**

### **Window 1: Run Load Test**
```bash
cd load-test
set TARGET_MESSAGES=1000000 && python final_load_test.py
```

### **Window 2: Monitor Consumer Lag**
```bash
# Run this in a separate terminal
watch -n 5 "docker exec kafkaorch-kafka-1 kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group orders-orchestrator --describe"

# On Windows:
# Run this every few seconds:
docker exec kafkaorch-kafka-1 kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group orders-orchestrator --describe
```

### **Window 3: Monitor Application Logs**
```bash
docker-compose logs -f orders-orchestrator | findstr /i "error warn"

# Or see all logs:
docker-compose logs -f orders-orchestrator
```

### **Window 4: Monitor System Resources**
```bash
docker stats kafkaorch-orders-orchestrator-1
```

---

## 🎯 **Expected Performance**

### **Typical Results for 1M Messages:**
- **Duration**: 15-30 minutes
- **Send Rate**: 500-2,000 messages/second
- **Processing Rate**: 500-2,000 messages/second
- **Consumer Lag**: Should stay low (< 10,000)

### **Success Indicators:**
- ✅ Messages Sent = Target Messages
- ✅ Messages Received ≈ Messages Sent (within 5%)
- ✅ Error count < 1% of total
- ✅ Consumer lag returns to 0 after test

---

## 📈 **Real-Time Monitoring with Kibana**

1. **Open Kibana**: http://localhost:5601
2. **Go to Discover**: Analytics → Discover
3. **Select**: `orchestrator-logs-*`
4. **Time Range**: Set to "Last 1 hour"
5. **Auto-refresh**: Set to 10 seconds
6. **Key Searches**:
   ```bash
   level:ERROR                    # Monitor errors
   message:*producer*             # Producer activity  
   message:*consumer*             # Consumer activity
   message:*transaction*          # Transaction logs
   ```

---

## 🚨 **Troubleshooting**

### **Connection Issues**
```bash
# Test Kafka connectivity
docker exec kafkaorch-kafka-1 kafka-topics.sh --bootstrap-server localhost:9092 --list

# Should show: orders.raw and orders.enriched
```

### **Slow Performance**
- **Check CPU**: Task Manager → Performance
- **Check Memory**: Ensure 8GB+ available
- **Reduce load**: Lower TARGET_MESSAGES
- **Check consumer lag**: Should not grow continuously

### **Application Issues**
```bash
# Check if orchestrator is running
docker-compose ps

# Restart if needed
docker-compose restart orders-orchestrator

# Check logs
docker-compose logs orders-orchestrator --tail=50
```

---

## 🎮 **Advanced Options**

### **Environment Variables**
```bash
# Custom configuration
set TARGET_MESSAGES=500000
set KAFKA_BOOTSTRAP_SERVERS=localhost:9094
set PRODUCER_TOPIC=orders.raw
set CONSUMER_TOPIC=orders.enriched

python final_load_test.py
```

### **Parallel Load Testing**
```bash
# Terminal 1:
set TARGET_MESSAGES=500000 && python final_load_test.py

# Terminal 2 (wait 30 seconds, then run):
set TARGET_MESSAGES=500000 && python final_load_test.py
```

---

## 📊 **Performance Analysis**

### **During Test - Check These Metrics:**

1. **Producer Rate**: 
   ```bash
   # Should be 500-2000 msg/sec
   ```

2. **Consumer Lag**:
   ```bash
   docker exec kafkaorch-kafka-1 kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group orders-orchestrator --describe
   # LAG column should stay manageable
   ```

3. **Error Rate**:
   ```bash
   # Check application logs for errors
   docker-compose logs orders-orchestrator | grep ERROR | wc -l
   ```

### **After Test - Validation:**

1. **Check Final Consumer Lag**:
   ```bash
   # Should be 0 or very low
   docker exec kafkaorch-kafka-1 kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group orders-orchestrator --describe
   ```

2. **Check Database Records** (if DB strategy enabled):
   ```bash
   docker exec kafkaorch-postgres-1 psql -U postgres -d orch -c "SELECT count(*) FROM event_failures;"
   ```

3. **Check Topic Message Counts**:
   ```bash
   # Producer topic
   docker exec kafkaorch-kafka-1 kafka-run-class.sh kafka.tools.GetOffsetShell --broker-list localhost:9092 --topic orders.raw

   # Consumer topic  
   docker exec kafkaorch-kafka-1 kafka-run-class.sh kafka.tools.GetOffsetShell --broker-list localhost:9092 --topic orders.enriched
   ```

---

## 🎯 **Ready to Start?**

**For 1 Million Records:**
```bash
cd load-test
set TARGET_MESSAGES=1000000
python final_load_test.py
```

**Monitor in separate terminals:**
- Consumer lag
- Application logs  
- Kibana dashboard

**Expected duration:** 15-30 minutes
**Success criteria:** All messages sent and processed with minimal errors

---

## 📝 **Sample Output**

```
Kafka Load Test Configuration
===============================
Target Messages: 1,000,000
Kafka Bootstrap: localhost:9094
Producer Topic: orders.raw
Consumer Topic: orders.enriched

Testing Kafka connectivity...
Successfully connected to Kafka
Starting load test...
Consumer started, listening for processed messages...
Starting to send 1,000,000 messages...

Progress: Sent 5,000/1,000,000 (167.2/s) | Received 4,850 (162.1/s) | Errors: 0
Progress: Sent 10,000/1,000,000 (201.5/s) | Received 9,920 (199.8/s) | Errors: 0
...
Progress: Sent 1,000,000/1,000,000 (1,234.5/s) | Received 999,995 (1,233.8/s) | Errors: 5

Finished sending 1,000,000 messages
Waiting for all messages to be processed...

Load Test Results
==================
Messages Sent: 1,000,000
Messages Received: 999,995
Errors: 5
Total Time: 810.45 seconds
Send Rate: 1234.5 messages/second
Receive Rate: 1233.8 messages/second

Load test completed successfully!
```

Your load testing environment is ready! 🚀