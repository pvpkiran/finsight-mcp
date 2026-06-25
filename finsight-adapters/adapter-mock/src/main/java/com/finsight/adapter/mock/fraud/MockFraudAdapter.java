package com.finsight.adapter.mock.fraud;

import com.finsight.core.domain.model.FraudScore;
import com.finsight.core.domain.model.Transaction;
import com.finsight.core.domain.valueobject.FraudRiskLevel;
import com.finsight.core.domain.valueobject.TenantId;
import com.finsight.core.domain.valueobject.TransactionId;
import com.finsight.core.port.FraudDataPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory mock fraud adapter.
 * Produces deterministic, realistic fraud scores based on transaction properties.
 * Designed so MCP tool demos show interesting results across risk levels.
 */
@Component
@Profile("!stripe & !nordigen")
public class MockFraudAdapter implements FraudDataPort {

    // In-memory velocity store: key = "dimension:value", value = transaction count
    private final Map<String, AtomicInteger> velocityStore = new ConcurrentHashMap<>();

    @Override
    public FraudScore scoreTransaction(Transaction transaction) {
        List<FraudScore.FraudSignal> signals = new ArrayList<>();
        double score = 0.0;

        // Signal 1: High amount
        if (transaction.amount().amount().doubleValue() > 2000) {
            signals.add(new FraudScore.FraudSignal(
                    "HIGH_AMOUNT",
                    "Transaction amount significantly above average for this merchant",
                    0.35,
                    transaction.amount().toString()));
            score += 0.35;
        }

        // Signal 2: Cross-border
        if (transaction.isCrossBorder()) {
            signals.add(new FraudScore.FraudSignal(
                    "GEO_MISMATCH",
                    "Transaction country differs from typical merchant country",
                    0.20,
                    transaction.countryCode()));
            score += 0.20;
        }

        // Signal 3: Velocity — suspicious IP (hardcoded for demo)
        if ("203.0.113.5".equals(transaction.ipAddress())) {
            signals.add(new FraudScore.FraudSignal(
                    "VELOCITY_CHECK",
                    "IP address has initiated 8 transactions in the last 15 minutes",
                    0.40,
                    transaction.ipAddress() + " — 8 txns/15min (threshold: 5)"));
            score += 0.40;
        }

        // Signal 4: Declined card being retried
        if (transaction.status() == Transaction.TransactionStatus.DECLINED) {
            signals.add(new FraudScore.FraudSignal(
                    "DECLINE_PATTERN",
                    "Card has recent declines — possible card testing",
                    0.25,
                    "Decline code: " + transaction.declineCode()));
            score += 0.25;
        }

        // Cap at 1.0
        score = Math.min(score, 1.0);

        FraudRiskLevel riskLevel = switch ((int) (score * 10)) {
            case 0, 1 -> FraudRiskLevel.LOW;
            case 2, 3, 4 -> FraudRiskLevel.MEDIUM;
            case 5, 6, 7 -> FraudRiskLevel.HIGH;
            default -> FraudRiskLevel.CRITICAL;
        };

        boolean blocked = riskLevel == FraudRiskLevel.CRITICAL;
        String blockReason = blocked ? "Automatic block: CRITICAL risk score %.2f".formatted(score) : null;

        return new FraudScore(
                transaction.id(),
                score,
                riskLevel,
                signals,
                "mock-model-v1.0",
                blocked,
                blockReason,
                Instant.now()
        );
    }

    @Override
    public VelocityResult checkVelocity(VelocityRequest request) {
        String key = "%s:%s".formatted(request.dimension(), request.value());
        int count = velocityStore
                .computeIfAbsent(key, k -> new AtomicInteger(0))
                .incrementAndGet();

        // Hardcoded thresholds for demo
        int threshold = switch (request.dimension()) {
            case "IP" -> 5;
            case "CARD" -> 3;
            case "DEVICE" -> 4;
            default -> 10;
        };

        return new VelocityResult(
                request.dimension(),
                request.value(),
                request.windowMinutes(),
                count,
                count > threshold,
                threshold
        );
    }

    @Override
    public List<FraudScore> findSimilarFraudPatterns(Transaction transaction, int topK) {
        // Return mock historical fraud patterns for demo
        return List.of(
                buildMockHistoricalScore("txn-hist-001", 0.82, FraudRiskLevel.HIGH),
                buildMockHistoricalScore("txn-hist-002", 0.76, FraudRiskLevel.HIGH),
                buildMockHistoricalScore("txn-hist-003", 0.91, FraudRiskLevel.CRITICAL)
        ).subList(0, Math.min(topK, 3));
    }

    @Override
    public void storeEmbedding(TransactionId id, TenantId tenantId, float[] embedding) {
        // No-op in mock — pgvector not available in mock profile
    }

    private FraudScore buildMockHistoricalScore(String txnId, double score, FraudRiskLevel level) {
        return new FraudScore(
                TransactionId.of(txnId),
                score,
                level,
                List.of(new FraudScore.FraudSignal(
                        "VELOCITY_CHECK", "Similar velocity pattern detected", 0.4, "historical")),
                "mock-model-v1.0",
                level == FraudRiskLevel.CRITICAL,
                level == FraudRiskLevel.CRITICAL ? "Historical block" : null,
                Instant.now().minusSeconds(86400)
        );
    }
}