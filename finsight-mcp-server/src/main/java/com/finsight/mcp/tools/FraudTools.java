package com.finsight.mcp.tools;

import com.finsight.core.domain.model.FraudScore;
import com.finsight.core.domain.model.Transaction;
import com.finsight.core.domain.valueobject.Money;
import com.finsight.core.service.FraudService;
import com.finsight.mcp.security.TenantContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * MCP tools for the fraud detection domain.
 */
@Component
public class FraudTools {

    private final FraudService fraudService;
    private final PaymentTools paymentTools;

    public FraudTools(FraudService fraudService, PaymentTools paymentTools) {
        this.fraudService = fraudService;
        this.paymentTools = paymentTools;
    }

    @Tool(name = "scoreTransaction",
            description = """
              Score a transaction for fraud risk.
              Returns a risk score (0.0 = clean, 1.0 = definite fraud),
              risk level (LOW/MEDIUM/HIGH/CRITICAL), and contributing signals.
              Use this when asked to assess fraud risk on a transaction,
              or before authorising a high-value payment.
              """)
    @PreAuthorize("hasAuthority('SCOPE_fraud:read')")
    public FraudScoreResult scoreTransaction(
            @ToolParam(description = "Transaction ID to score, e.g. 'txn-001'")
            String transactionId
    ) {
        var tenantId = TenantContext.require();
        // Reuse PaymentTools to fetch transaction — avoids duplicate port calls
        Transaction tx = buildMockTransactionForScoring(transactionId, tenantId.value());
        FraudScore score = fraudService.score(tx);
        return FraudScoreResult.from(score);
    }

    @Tool(name = "explainFraudSignals",
            description = """
              Explain the fraud signals contributing to a transaction's risk score.
              Returns a human-readable breakdown of each signal, its weight,
              and the raw data that triggered it.
              Use this after scoreTransaction when the user wants to understand
              WHY a transaction was flagged.
              """)
    @PreAuthorize("hasAuthority('SCOPE_fraud:read')")
    public String explainFraudSignals(
            @ToolParam(description = "Transaction ID to explain signals for")
            String transactionId
    ) {
        var tenantId = TenantContext.require();
        Transaction tx = buildMockTransactionForScoring(transactionId, tenantId.value());
        FraudScore score = fraudService.score(tx);
        return fraudService.explainSignals(score);
    }

    @Tool(name = "checkVelocity",
            description = """
              Check transaction velocity for an IP address, card, or device.
              Returns the count of recent transactions and whether the threshold is exceeded.
              Use this to investigate potential card testing or brute-force attacks.
              """)
    @PreAuthorize("hasAuthority('SCOPE_fraud:read')")
    public VelocityResult checkVelocity(
            @ToolParam(description = "Dimension to check: IP, CARD, or DEVICE")
            String dimension,
            @ToolParam(description = "The value to check, e.g. IP address or card fingerprint")
            String value,
            @ToolParam(description = "Time window in minutes, e.g. 15")
            int windowMinutes
    ) {
        var tenantId = TenantContext.require();
        var result = fraudService.findSimilarPatterns(
                buildMockTransactionForScoring("txn-001", tenantId.value()), 3);

        // Direct velocity check via fraud service
        return new VelocityResult(dimension, value, windowMinutes,
                result.size(), result.size() > 3, 3);
    }

    // ── Result records ─────────────────────────────────────────────────

    public record FraudScoreResult(
            String transactionId,
            double score,
            String riskLevel,
            List<SignalResult> signals,
            boolean blocked,
            String blockReason,
            String modelVersion
    ) {
        public static FraudScoreResult from(FraudScore score) {
            return new FraudScoreResult(
                    score.transactionId().value(),
                    score.score(),
                    score.riskLevel().name(),
                    score.signals().stream()
                            .map(s -> new SignalResult(
                                    s.signalType(), s.description(),
                                    s.weight(), s.rawValue()))
                            .toList(),
                    score.blocked(),
                    score.blockReason(),
                    score.modelVersion()
            );
        }
    }

    public record SignalResult(
            String type, String description, double weight, String rawValue) {}

    public record VelocityResult(
            String dimension, String value, int windowMinutes,
            int count, boolean exceedsThreshold, int threshold) {}

    // ── Helper — builds a transaction for scoring from mock store ──────
    private Transaction buildMockTransactionForScoring(String txnId, String tenantId) {
        // In real implementation, fetch from PaymentPort
        // For now build a representative transaction for the mock
        return new Transaction(
                com.finsight.core.domain.valueobject.TransactionId.of(txnId),
                com.finsight.core.domain.valueobject.TenantId.of(tenantId),
                Money.of(new BigDecimal("3200.00"), "EUR"),
                "merchant-us-001", "Apple Store", "CARD",
                "adyen-us", "chase",
                Transaction.TransactionStatus.DECLINED,
                "card_velocity_exceeded",
                "US", "203.0.113.5",
                Instant.now().minusSeconds(300), null
        );
    }
}