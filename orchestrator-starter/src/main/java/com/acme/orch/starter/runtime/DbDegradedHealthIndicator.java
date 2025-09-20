package com.acme.orch.starter.runtime;

import com.acme.orch.db.FailureTracker;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

public class DbDegradedHealthIndicator implements HealthIndicator {
    private final FailureTracker tracker;

    public DbDegradedHealthIndicator(FailureTracker tracker) {
        this.tracker = tracker;
    }

    @Override
    public Health health() {
        return tracker.isDbDegraded()
            ? Health.status("DEGRADED").withDetail("db", "degraded").build()
            : Health.up().build();
    }
}
