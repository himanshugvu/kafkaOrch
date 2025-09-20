package com.acme.orch.core;

import java.util.Map;

/** Allow per-app overrides of Kafka producer/consumer properties. */
public interface KafkaClientCustomizer {
    default void customizeConsumer(Map<String, Object> consumerProps) {}
    default void customizeProducer(Map<String, Object> producerProps) {}
}
