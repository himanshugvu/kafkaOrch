package com.acme.orch.db;

import com.acme.orch.db.model.FailureRecord;
import java.util.List;

public interface FailureTracker {
    boolean isDbDegraded();

    void bulkInsertReceived(List<FailureRecord> records);

    void insertRow(FailureRecord record);

    void updateStatusSuccess(List<String> dedupKeys);

    void updateStatusFailed(String dedupKey, String message, String stack);

    void insertLightweightFailure(FailureRecord record);

    int markTimedOutReceivedAsFailed(long olderThanMinutes);

    default boolean isCircuitOpen() { return false; }
}
