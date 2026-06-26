package com.finsight.core.service;

import com.finsight.core.domain.model.FraudScore;
import com.finsight.core.domain.model.Transaction;
import com.finsight.core.domain.valueobject.FraudRiskLevel;
import com.finsight.core.port.FraudDataPort;

import java.util.List;

/**
 * Domain service for fraud detection operations.
 * Pure Java — no Spring, no framework coupling.
 */
public class FraudService {

    private final FraudDataPort fraudDataPort;

    public FraudService(FraudDataPort fraudDataPort) {
        this.fraudDataPort = fraudDataPort;
    }

    /**
     * Score a transaction and return the full fraud analysis.
     * Called by the scoreTransaction MCP tool.
     */
    public FraudScore score(Transaction transaction) {
        return fraudDataPort.scoreTransaction(transaction);
    }

    /**
     * Score and immediately determine if the transaction should be blocked.
     * Business rule: CRITICAL risk → always block. HIGH risk → block if velocity also exceeded.
     */
    public FraudDecision scoreAndDecide(Transaction transaction) {
        FraudScore score = fraudDataPort.scoreTransaction(transaction);

        if (score.riskLevel() == FraudRiskLevel.CRITICAL) {
            return new FraudDecision(score, true, "CRITICAL risk level — automatic block");
        }

        if (score.riskLevel() == FraudRiskLevel.HIGH) {
            // Check velocity as tie-breaker
            var velocity = fraudDataPort.checkVelocity(
                    new FraudDataPort.VelocityRequest(
                            "IP", transaction.ipAddress(), 15, transaction.tenantId()));
            if (velocity.exceedsThreshold()) {
                return new FraudDecision(score, true,
                        "HIGH risk + velocity threshold exceeded (%d txns in %d min)"
                                .formatted(velocity.transactionCount(), velocity.windowMinutes()));
            }
        }

        return new FraudDecision(score, false, null);
    }

    /**
     * Explain the fraud signals for a given score in human-readable form.
     * Called by the explainFraudSignal MCP tool.
     */
    public String explainSignals(FraudScore score) {
        if (score.signals().isEmpty()) {
            return "No fraud signals detected. Score: %.2f (%s)"
                    .formatted(score.score(), score.riskLevel());
        }

        var sb = new StringBuilder();
        sb.append("Fraud score: %.2f (%s)\n\n".formatted(score.score(), score.riskLevel()));
        sb.append("Contributing signals:\n");
        score.signals().forEach(signal ->
                sb.append("  - [%.0f%%] %s: %s (value: %s)\n"
                        .formatted(signal.weight() * 100,
                                signal.signalType(),
                                signal.description(),
                                signal.rawValue())));

        if (score.blocked()) {
            sb.append("\nAction: BLOCKED — ").append(score.blockReason());
        }
        return sb.toString();
    }

    /**
     * Find similar fraud patterns from history using vector similarity.
     */
    public List<FraudScore> findSimilarPatterns(Transaction transaction, int topK) {
        return fraudDataPort.findSimilarFraudPatterns(transaction, topK);
    }

    /**
     * Check transaction velocity for an IP, card, or device.
     * Called by the checkVelocity MCP tool.
     */
    public FraudDataPort.VelocityResult checkVelocity(FraudDataPort.VelocityRequest request) {
        return fraudDataPort.checkVelocity(request);
    }

    public record FraudDecision(
            FraudScore score,
            boolean shouldBlock,
            String blockReason
    ) {}
}