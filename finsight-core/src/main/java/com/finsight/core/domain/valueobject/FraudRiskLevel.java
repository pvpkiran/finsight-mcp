package com.finsight.core.domain.valueobject;

/**
 * Risk classification for a transaction or entity.
 * Ordered from lowest to highest risk.
 */
public enum FraudRiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    public boolean isAtLeast(FraudRiskLevel threshold) {
        return this.ordinal() >= threshold.ordinal();
    }

    public boolean requiresHumanReview() {
        return this == HIGH || this == CRITICAL;
    }

    public boolean requiresImmediateBlock() {
        return this == CRITICAL;
    }
}
