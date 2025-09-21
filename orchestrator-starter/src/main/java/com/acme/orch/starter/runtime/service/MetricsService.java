package com.acme.orch.starter.runtime.service;

import io.micrometer.core.instrument.MeterRegistry;

public class MetricsService {

    private final MeterRegistry meterRegistry;

    public MetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void incrementSuccessCounter(boolean isRecordMode) {
        if (isRecordMode) {
            meterRegistry.counter("orch.records.ok").increment();
        } else {
            meterRegistry.counter("orch.batch.ok").increment();
        }
    }

    public void incrementBatchSuccessCounter(int recordCount) {
        meterRegistry.counter("orch.batch.ok").increment(recordCount);
    }

    public void incrementFailureCounter() {
        meterRegistry.counter("orch.transform.fail").increment();
    }

    public void recordProcessingTime(long startTime, boolean isRecordMode) {
        long processingTime = System.currentTimeMillis() - startTime;
        String timerName = isRecordMode ? "orch.records.processing.time" : "orch.batch.processing.time";
        meterRegistry.timer(timerName).record(processingTime, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public void recordTransformationTime(long startTime) {
        long transformationTime = System.currentTimeMillis() - startTime;
        meterRegistry.timer("orch.transformation.time").record(transformationTime, java.util.concurrent.TimeUnit.MILLISECONDS);
    }
}