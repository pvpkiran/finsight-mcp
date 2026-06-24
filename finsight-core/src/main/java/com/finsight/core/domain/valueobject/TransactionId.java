package com.finsight.core.domain.valueobject;

import java.util.Objects;
import java.util.UUID;

/**
 * Strongly-typed transaction identifier.
 * Wrapping UUID in a value object prevents passing raw strings
 * where a TransactionId is expected (type safety at compile time).
 */
public record TransactionId(String value) {

    public TransactionId {
        Objects.requireNonNull(value, "TransactionId must not be null");
        if (value.isBlank()) throw new IllegalArgumentException("TransactionId must not be blank");
    }

    public static TransactionId of(String value) {
        return new TransactionId(value);
    }

    public static TransactionId generate() {
        return new TransactionId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
