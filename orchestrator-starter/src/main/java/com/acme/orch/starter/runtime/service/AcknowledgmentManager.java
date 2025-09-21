package com.acme.orch.starter.runtime.service;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Duration;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class AcknowledgmentManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AcknowledgmentManager.class);

    private final int batchSize;
    private final long flushIntervalMs;
    private final ConcurrentHashMap<PartitionKey, PartitionWindow> windows = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    public AcknowledgmentManager(int batchSize, long flushIntervalMs) {
        this.batchSize = Math.max(1, batchSize);
        this.flushIntervalMs = Math.max(0L, flushIntervalMs);
        this.scheduler = this.flushIntervalMs > 0
            ? Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "orch-ack-flush");
                t.setDaemon(true);
                return t;
            })
            : null;
    }

    public void markSuccess(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        if (acknowledgment == null) {
            return;
        }
        markSuccess(record.topic(), record.partition(), record.offset(), acknowledgment);
    }

    public void markSuccess(String topic, int partition, long offset, Acknowledgment acknowledgment) {
        if (acknowledgment == null) {
            return;
        }
        PartitionKey key = new PartitionKey(topic, partition);
        PartitionWindow window = windows.computeIfAbsent(key, k -> new PartitionWindow(this.batchSize, this.flushIntervalMs));
        WindowResult result = window.recordSuccess(offset, acknowledgment);

        if (result.toCommit() != null) {
            commitAcknowledgment(result.toCommit());
        }
        if (result.scheduleFlush() && scheduler != null) {
            scheduleFlush(key, window);
        }
    }

    public void markFailure(ConsumerRecord<String, String> record) {
        markFailure(record.topic(), record.partition());
    }

    public void markFailure(String topic, int partition) {
        PartitionWindow window = windows.get(new PartitionKey(topic, partition));
        if (window == null) {
            return;
        }
        Acknowledgment toCommit = window.flush(true);
        if (toCommit != null) {
            commitAcknowledgment(toCommit);
        }
    }

    public void markBatchSuccess(List<ConsumerRecord<String, String>> records, Acknowledgment acknowledgment) {
        acknowledgment.acknowledge();
    }

    public void markBatchFailure(List<ConsumerRecord<String, String>> records) {
        records.forEach(this::markFailure);
    }

    public void safeNack(Acknowledgment acknowledgment) {
        if (acknowledgment == null) {
            return;
        }
        try {
            acknowledgment.nack(Duration.ZERO);
        } catch (UnsupportedOperationException | IllegalStateException ex) {
            log.warn("Nack not supported; relying on consumer retry. cause={}", ex.toString());
        }
    }

    public void flushAll() {
        windows.values().forEach(window -> {
            Acknowledgment toCommit = window.flush(true);
            if (toCommit != null) {
                commitAcknowledgment(toCommit);
            }
        });
    }

    private void scheduleFlush(PartitionKey key, PartitionWindow window) {
        if (scheduler == null) {
            return;
        }
        try {
            scheduler.schedule(() -> {
                Acknowledgment toCommit = window.flush(false);
                if (toCommit != null) {
                    commitAcknowledgment(toCommit);
                }
            }, flushIntervalMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ex) {
            Acknowledgment toCommit = window.flush(true);
            if (toCommit != null) {
                commitAcknowledgment(toCommit);
            }
        }
    }

    private void commitAcknowledgment(Acknowledgment acknowledgment) {
        try {
            acknowledgment.acknowledge();
        } catch (RuntimeException ex) {
            log.warn("Failed to acknowledge committed offsets: {}", ex.toString());
        }
    }

    @Override
    public void close() {
        flushAll();
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private record PartitionKey(String topic, int partition) { }

    private static final class PartitionWindow {
        private final int batchSize;
        private final long flushIntervalMs;
        private final NavigableMap<Long, Acknowledgment> pending = new TreeMap<>();
        private long highestCommitted = Long.MIN_VALUE;
        private long highestReadyOffset = Long.MIN_VALUE;
        private Acknowledgment highestReadyAck;
        private int readyCount;
        private long lastFlushTs = System.currentTimeMillis();
        private boolean initialised;
        private boolean flushScheduled;

        PartitionWindow(int batchSize, long flushIntervalMs) {
            this.batchSize = batchSize;
            this.flushIntervalMs = flushIntervalMs;
        }

        synchronized WindowResult recordSuccess(long offset, Acknowledgment ack) {
            if (!initialised) {
                highestCommitted = offset - 1;
                initialised = true;
            }
            pending.put(offset, ack);
            long candidate = highestCommitted + 1;
            while (true) {
                Acknowledgment next = pending.remove(candidate);
                if (next == null) {
                    break;
                }
                highestReadyOffset = candidate;
                highestReadyAck = next;
                readyCount++;
                candidate++;
            }
            Acknowledgment toCommit = maybeFlush(false);
            if (toCommit != null) {
                flushScheduled = false;
                return new WindowResult(toCommit, false);
            }
            if (flushIntervalMs > 0 && readyCount > 0 && !flushScheduled) {
                flushScheduled = true;
                return new WindowResult(null, true);
            }
            return new WindowResult(null, false);
        }

        synchronized Acknowledgment flush(boolean force) {
            flushScheduled = false;
            return maybeFlush(force);
        }

        private Acknowledgment maybeFlush(boolean force) {
            if (readyCount == 0 || highestReadyAck == null) {
                return null;
            }
            long now = System.currentTimeMillis();
            if (!force && readyCount < batchSize) {
                if (flushIntervalMs <= 0 || now - lastFlushTs < flushIntervalMs) {
                    return null;
                }
            }
            Acknowledgment toCommit = highestReadyAck;
            highestCommitted = highestReadyOffset;
            readyCount = 0;
            highestReadyAck = null;
            lastFlushTs = now;
            return toCommit;
        }
    }

    private record WindowResult(Acknowledgment toCommit, boolean scheduleFlush) { }
}