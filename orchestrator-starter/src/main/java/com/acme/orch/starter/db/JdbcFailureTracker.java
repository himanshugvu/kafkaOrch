package com.acme.orch.starter.db;

import com.acme.orch.db.FailureTracker;
import com.acme.orch.db.model.FailureRecord;
import com.acme.orch.starter.config.OrchestratorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class JdbcFailureTracker implements FailureTracker {
    private static final Logger log = LoggerFactory.getLogger(JdbcFailureTracker.class);

    private final JdbcTemplate jdbc;
    private final String table;
    private final OrchestratorProperties props;
    private final AtomicBoolean dbDegraded = new AtomicBoolean(false);
    private final ExecutorService dbPool = Executors.newVirtualThreadPerTaskExecutor();

    public JdbcFailureTracker(DataSource ds, OrchestratorProperties props) {
        this.jdbc = new JdbcTemplate(ds);
        this.table = props.getErrorTable();
        this.props = props;
        this.jdbc.setQueryTimeout(1); // seconds; we also enforce ms via orTimeout below
    }

    @Override
    public boolean isDbDegraded() {
        return dbDegraded.get();
    }

    @Override
    public void bulkInsertReceived(List<FailureRecord> records) {
        if (records.isEmpty()) {
            return;
        }
        runWithTimeout(() -> new SimpleJdbcInsert(jdbc)
            .withTableName(table)
            .executeBatch(records.stream().map(this::toMap).toArray(Map[]::new)), true);
    }

    @Override
    public void updateStatusSuccess(List<String> dedupKeys) {
        if (dedupKeys.isEmpty()) {
            return;
        }
        runAsyncWithTimeout(() -> jdbc.batchUpdate(
            "UPDATE " + table + " SET status='SUCCESS', updated_at=now() WHERE dedup_key=?",
            dedupKeys.stream().map(k -> new Object[]{k}).toList()));
    }

    @Override
    public void updateStatusFailed(String dedupKey, String message, String stack) {
        runWithTimeout(() -> jdbc.update(
            "UPDATE " + table + " SET status='FAILED', error_message=?, error_stack=?, updated_at=now() WHERE dedup_key=?",
            message, stack, dedupKey), false);
    }

    @Override
    public void insertRow(FailureRecord record) {
        runWithTimeout(() -> new SimpleJdbcInsert(jdbc)
            .withTableName(table)
            .execute(toMap(record)), false);
    }

    @Override
    public void insertLightweightFailure(FailureRecord record) {
        runAsyncWithTimeout(() -> new SimpleJdbcInsert(jdbc)
            .withTableName(table)
            .execute(toMap(record)));
    }

    @Override
    public int markTimedOutReceivedAsFailed(long olderThanMinutes) {
        Integer updated = runWithTimeout(() -> jdbc.update(
            "UPDATE " + table + " SET status='FAILED', updated_at=now() " +
                "WHERE status='RECEIVED' AND created_at < now() - INTERVAL '" + olderThanMinutes + " minutes'"), false);
        return updated == null ? 0 : updated;
    }

    private Map<String, Object> toMap(FailureRecord record) {
        Map<String, Object> map = new HashMap<>();
        map.put("source_topic", record.getSourceTopic());
        map.put("target_topic", record.getTargetTopic());
        map.put("partition", record.getPartition());
        map.put("offset", record.getOffset());
        map.put("message_key", record.getMessageKey());
        map.put("headers_text", record.getHeadersText());
        map.put("status", record.getStatus());
        map.put("dedup_key", record.getDedupKey());
        map.put("error_message", record.getErrorMessage());
        map.put("error_stack", record.getErrorStack());
        map.put("payload_bytes", record.getPayloadBytes());
        map.put("payload_text", record.getPayloadText());
        return map;
    }

    private void runAsyncWithTimeout(Runnable runnable) {
        dbPool.submit(() -> runWithTimeout(runnable, true));
    }

    private <T> T runWithTimeout(Callable<T> call, boolean async) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return call.call();
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }, dbPool).orTimeout(props.getDbTimeoutMs(), TimeUnit.MILLISECONDS)
                .whenComplete((ok, ex) -> {
                    if (ex != null && props.isDbDegradeDontBlock()) {
                        if (dbDegraded.compareAndSet(false, true)) {
                            log.warn("DB degraded; continuing Kafka flow. Cause: {}", ex.toString());
                        }
                    } else if (ex == null && dbDegraded.get()) {
                        dbDegraded.set(false);
                        log.info("DB recovered; cleared degraded flag.");
                    }
                }).join();
        } catch (CompletionException ce) {
            log.debug("DB op degraded/failure (non-blocking): {}", ce.toString());
            return null;
        }
    }

    private <T> T runWithTimeout(Runnable runnable, boolean async) {
        return runWithTimeout(Executors.callable(runnable, null), async);
    }
}
