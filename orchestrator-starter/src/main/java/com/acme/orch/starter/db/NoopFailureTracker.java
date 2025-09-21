package com.acme.orch.starter.db;

import com.acme.orch.db.FailureTracker;
import com.acme.orch.db.model.FailureRecord;

import java.util.List;

public class NoopFailureTracker implements FailureTracker {
    @Override public boolean isDbDegraded() { return false; }
    @Override public void bulkInsertReceived(List<FailureRecord> records) {}
    @Override public void insertRow(FailureRecord record) {}
    @Override public void updateStatusSuccess(List<String> dedupKeys) {}
    @Override public void updateStatusFailed(String dedupKey, String message, String stack) {}
    @Override public void insertLightweightFailure(FailureRecord record) {}
    @Override public int markTimedOutReceivedAsFailed(long olderThanMinutes) { return 0; }
}
