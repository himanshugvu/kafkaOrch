package com.acme.orch.example;

import com.acme.orch.core.KafkaClientCustomizer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.Map;

@SpringBootApplication
public class OrdersOrchestratorApplication {
  public static void main(String[] args) { SpringApplication.run(OrdersOrchestratorApplication.class, args); }

  /** Optional Kafka tuning overrides for this app. */
  @Bean
  KafkaClientCustomizer kafkaTuner() {
    return new KafkaClientCustomizer() {
      @Override public void customizeProducer(Map<String, Object> p) {
        p.put(ProducerConfig.LINGER_MS_CONFIG, 20);
      }
      @Override public void customizeConsumer(Map<String, Object> c) {
        c.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1000);
      }
    };
  }
}
