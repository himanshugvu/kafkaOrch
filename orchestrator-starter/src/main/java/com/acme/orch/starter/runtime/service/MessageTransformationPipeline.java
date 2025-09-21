package com.acme.orch.starter.runtime.service;

import com.acme.orch.core.MessageTransformer;
import org.apache.kafka.clients.consumer.ConsumerRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

public class MessageTransformationPipeline {

    private final MessageTransformer transformer;
    private final ExecutorService executorService;

    public MessageTransformationPipeline(MessageTransformer transformer, ExecutorService executorService) {
        this.transformer = transformer;
        this.executorService = executorService;
    }

    public CompletableFuture<String> transformAsync(String message) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return transformer.transform(message);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, executorService);
    }

    public CompletableFuture<List<String>> transformBatchAsync(List<ConsumerRecord<String, String>> records) {
        List<CompletableFuture<String>> futures = records.stream()
            .map(record -> transformAsync(record.value()))
            .toList();

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(ignored -> {
                List<String> results = new ArrayList<>(records.size());
                for (CompletableFuture<String> future : futures) {
                    results.add(future.join());
                }
                return results;
            });
    }

    public String transform(String message) throws Exception {
        return transformer.transform(message);
    }
}