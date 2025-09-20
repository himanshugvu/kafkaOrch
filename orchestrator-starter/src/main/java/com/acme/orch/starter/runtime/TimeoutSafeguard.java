package com.acme.orch.starter.runtime;

import com.acme.orch.db.FailureTracker;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;

public class TimeoutSafeguard {
    private final FailureTracker tracker;
    private final MeterRegistry meterRegistry;

    public TimeoutSafeguard(FailureTracker tracker, MeterRegistry meterRegistry) {
        this.tracker = tracker;
        this.meterRegistry = meterRegistry;
    }

    /** Fail-safe: mark RECEIVED older than 30 minutes as FAILED. */
    @Scheduled(fixedDelay = 60_000L)
    public void markTimedOut() {
        int updated = tracker.markTimedOutReceivedAsFailed(30);
        if (updated > 0) {
            meterRegistry.counter("orch.outbox.timeouts.marked").increment(updated);
        }
    }
}
