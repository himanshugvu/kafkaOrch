package com.acme.orch.core;

@FunctionalInterface
public interface MessageTransformer {
    String transform(String input) throws Exception;
    default byte[] transformBytes(byte[] input) throws Exception {
        return transform(new String(input)).getBytes();
    }
}
