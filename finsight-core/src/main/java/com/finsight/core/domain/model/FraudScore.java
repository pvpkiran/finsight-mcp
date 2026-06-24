package com.finsight.core.domain.model;

import com.finsight.core.domain.valueobject.FraudRiskLevel;
import com.finsight.core.domain.valueobject.TransactionId;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Result of fraud analysis on a transaction.
 * Contains the overall risk score, level, and contributing signals
 * so the LLM agent can explain the reasoning to a human.
 */
public record FraudScore(
        TransactionId transactionId,
        double score,                       // 0.0 (clean) to 1.0 (definite fraud)
        FraudRiskLevel riskLevel,
        List<FraudSignal> signals,          // contributing factors, ordered by weight
        String modelVersion,
        boolean blocked,                    // true = transaction was blocked
        String blockReason,                 // null if not blocked
        Instant scoredAt
) {

    public FraudScore {
        Objects.requireNonNull(transactionId, "TransactionId required");
        Objects.requireNonNull(riskLevel, "RiskLevel required");
        Objects.requireNonNull(signals, "Signals required");
        Objects.requireNonNull(scoredAt, "ScoredAt required");
        if (score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException("Score must be between 0.0 and 1.0, got: " + score);
        }
        signals = List.copyOf(signals); // defensive copy — immutable
    }

    public List<FraudSignal> highWeightSignals() {
        return signals.stream()
                .filter(s -> s.weight() >= 0.3)
                .toList();
    }

    /**
     * A single contributing factor to the fraud score.
     * Used by the explainFraudSignal MCP tool to give human-readable explanations.
     */
    public record FraudSignal(
            String signalType,      // VELOCITY_CHECK, GEO_MISMATCH, DEVICE_FINGERPRINT, etc.
            String description,     // human-readable explanation
            double weight,          // 0.0 to 1.0 — contribution to overall score
            String rawValue         // the actual data that triggered this signal
    ) {
        public FraudSignal {
            Objects.requireNonNull(signalType, "SignalType required");
            Objects.requireNonNull(description, "Description required");
        }
    }
}