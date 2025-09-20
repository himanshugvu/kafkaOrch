package com.acme.orch.example.transform;

import com.acme.orch.core.MessageTransformer;
import org.springframework.stereotype.Component;

@Component
public class RoutedByMessageTransformer implements MessageTransformer {
    @Override
    public String transform(String input) {
        if (input == null) {
            return null;
        }
        String trimmed = input.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed.substring(0, trimmed.length() - 1) + ",\"routedBy\":\"orders-orchestrator\"}";
        }
        return input;
    }
}
