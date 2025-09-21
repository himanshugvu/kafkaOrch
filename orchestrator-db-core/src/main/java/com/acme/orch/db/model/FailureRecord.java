package com.acme.orch.db.model;

import java.util.Arrays;
import java.util.Objects;

public final class FailureRecord {
    private final String sourceTopic;
    private final String targetTopic;
    private final int partition;
    private final long offset;
    private final String messageKey;
    private final String headersText;
    private final String status;
    private final String errorMessage;
    private final String errorStack;
    private final String dedupKey;
    private final byte[] payloadBytes;
    private final String payloadText;

    private FailureRecord(Builder builder) {
        this.sourceTopic = builder.sourceTopic;
        this.targetTopic = builder.targetTopic;
        this.partition = builder.partition;
        this.offset = builder.offset;
        this.messageKey = builder.messageKey;
        this.headersText = builder.headersText;
        this.status = builder.status;
        this.errorMessage = builder.errorMessage;
        this.errorStack = builder.errorStack;
        this.dedupKey = builder.dedupKey;
        this.payloadBytes = builder.payloadBytes == null ? null : builder.payloadBytes.clone();
        this.payloadText = builder.payloadText;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getSourceTopic() {
        return sourceTopic;
    }

    public String getTargetTopic() {
        return targetTopic;
    }

    public int getPartition() {
        return partition;
    }

    public long getOffset() {
        return offset;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public String getHeadersText() {
        return headersText;
    }

    public String getStatus() {
        return status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getErrorStack() {
        return errorStack;
    }

    public String getDedupKey() {
        return dedupKey;
    }

    public byte[] getPayloadBytes() {
        return payloadBytes == null ? null : payloadBytes.clone();
    }

    public String getPayloadText() {
        return payloadText;
    }

    public Builder toBuilder() {
        return new Builder()
            .sourceTopic(sourceTopic)
            .targetTopic(targetTopic)
            .partition(partition)
            .offset(offset)
            .messageKey(messageKey)
            .headersText(headersText)
            .status(status)
            .errorMessage(errorMessage)
            .errorStack(errorStack)
            .dedupKey(dedupKey)
            .payloadBytes(payloadBytes)
            .payloadText(payloadText);
    }

    @Override
    public String toString() {
        return "FailureRecord{" +
            "sourceTopic='" + sourceTopic + '\'' +
            ", targetTopic='" + targetTopic + '\'' +
            ", partition=" + partition +
            ", offset=" + offset +
            ", dedupKey='" + dedupKey + '\'' +
            ", status='" + status + '\'' +
            "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FailureRecord that = (FailureRecord) o;
        return partition == that.partition &&
            offset == that.offset &&
            Objects.equals(sourceTopic, that.sourceTopic) &&
            Objects.equals(targetTopic, that.targetTopic) &&
            Objects.equals(messageKey, that.messageKey) &&
            Objects.equals(headersText, that.headersText) &&
            Objects.equals(status, that.status) &&
            Objects.equals(errorMessage, that.errorMessage) &&
            Objects.equals(errorStack, that.errorStack) &&
            Objects.equals(dedupKey, that.dedupKey) &&
            Arrays.equals(payloadBytes, that.payloadBytes) &&
            Objects.equals(payloadText, that.payloadText);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(sourceTopic, targetTopic, partition, offset, messageKey, headersText,
            status, errorMessage, errorStack, dedupKey, payloadText);
        result = 31 * result + Arrays.hashCode(payloadBytes);
        return result;
    }

    public static final class Builder {
        private String sourceTopic;
        private String targetTopic;
        private int partition;
        private long offset;
        private String messageKey;
        private String headersText;
        private String status;
        private String errorMessage;
        private String errorStack;
        private String dedupKey;
        private byte[] payloadBytes;
        private String payloadText;

        private Builder() {
        }

        public Builder sourceTopic(String sourceTopic) {
            this.sourceTopic = sourceTopic;
            return this;
        }

        public Builder targetTopic(String targetTopic) {
            this.targetTopic = targetTopic;
            return this;
        }

        public Builder partition(int partition) {
            this.partition = partition;
            return this;
        }

        public Builder offset(long offset) {
            this.offset = offset;
            return this;
        }

        public Builder messageKey(String messageKey) {
            this.messageKey = messageKey;
            return this;
        }

        public Builder headersText(String headersText) {
            this.headersText = headersText;
            return this;
        }

        public Builder status(String status) {
            this.status = status;
            return this;
        }

        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        public Builder errorStack(String errorStack) {
            this.errorStack = errorStack;
            return this;
        }

        public Builder dedupKey(String dedupKey) {
            this.dedupKey = dedupKey;
            return this;
        }

        public Builder payloadBytes(byte[] payloadBytes) {
            this.payloadBytes = payloadBytes == null ? null : payloadBytes.clone();
            return this;
        }

        public Builder payloadText(String payloadText) {
            this.payloadText = payloadText;
            return this;
        }

        public FailureRecord build() {
            return new FailureRecord(this);
        }
    }
}
