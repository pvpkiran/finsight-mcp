package com.finsight.mcp.tools;

import com.finsight.core.domain.valueobject.TenantId;
import com.finsight.mcp.AbstractIntegrationTest;
import com.finsight.mcp.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.test.context.support.WithMockUser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for fraud detection tools.
 *
 * Tests the full stack:
 *   FraudTools → FraudService → MockFraudAdapter
 *                             ↑
 *               PaymentService → MockPaymentAdapter (for transaction fetch)
 *
 * MockFraudAdapter scoring rules:
 *   - Amount > 2000 EUR  → +0.35 (HIGH_AMOUNT signal)
 *   - Cross-border       → +0.20 (GEO_MISMATCH signal)
 *   - IP 203.0.113.5     → +0.40 (VELOCITY_CHECK signal)
 *   - Declined status    → +0.25 (DECLINE_PATTERN signal)
 *
 * Mock transactions in MockPaymentAdapter:
 *   txn-001: 250 EUR, MediaMarkt DE, CAPTURED  → LOW risk
 *   txn-002: 89.99 EUR, Zalando DE, DECLINED   → MEDIUM risk (declined)
 *   txn-005: 3200 EUR, Apple Store US, DECLINED → CRITICAL (all signals)
 */
@DisplayName("Fraud Tools Integration Tests")
class FraudToolsIT extends AbstractIntegrationTest {

    @Autowired
    private FraudTools fraudTools;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final TenantId DEMO_TENANT = TenantId.of("tenant-demo-001");

    @BeforeEach
    void setTenantContext() {
        TenantContext.set(DEMO_TENANT);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    // ── scoreTransaction ───────────────────────────────────────────────

    @Test
    @DisplayName("should score low-risk domestic transaction as LOW")
    @WithMockUser(authorities = {"SCOPE_fraud:read", "SCOPE_payment:read"})
    void shouldScoreLowRiskTransactionAsLow() {
        // txn-001: 250 EUR, MediaMarkt DE, CAPTURED — no risk signals
        var result = fraudTools.scoreTransaction("txn-001");

        assertThat(result.transactionId()).isEqualTo("txn-001");
        assertThat(result.score()).isLessThan(0.3);
        assertThat(result.riskLevel()).isEqualTo("LOW");
        assertThat(result.blocked()).isFalse();
        assertThat(result.blockReason()).isNull();
        assertThat(result.modelVersion()).isEqualTo("mock-model-v1.0");
    }

    @Test
    @DisplayName("should score declined transaction with DECLINE_PATTERN signal")
    @WithMockUser(authorities = {"SCOPE_fraud:read", "SCOPE_payment:read"})
    void shouldScoreDeclinedTransactionWithDeclineSignal() {
        // txn-002: 89.99 EUR, Zalando DE, DECLINED — decline signal only
        var result = fraudTools.scoreTransaction("txn-002");

        assertThat(result.transactionId()).isEqualTo("txn-002");
        assertThat(result.score()).isGreaterThan(0.0);
        assertThat(result.signals()).anyMatch(s -> "DECLINE_PATTERN".equals(s.type()));
        assertThat(result.blocked()).isFalse();
    }

    @Test
    @DisplayName("should score high-value cross-border declined transaction as HIGH or CRITICAL")
    @WithMockUser(authorities = {"SCOPE_fraud:read", "SCOPE_payment:read"})
    void shouldScoreHighRiskTransactionAsHighOrCritical() {
        // txn-005: 3200 EUR, Apple Store US, DECLINED, IP 203.0.113.5
        // HIGH_AMOUNT(0.35) + GEO_MISMATCH(0.20) + VELOCITY_CHECK(0.40) + DECLINE_PATTERN(0.25) = 1.0 (capped)
        var result = fraudTools.scoreTransaction("txn-005");

        assertThat(result.transactionId()).isEqualTo("txn-005");
        assertThat(result.score()).isGreaterThanOrEqualTo(0.5);
        assertThat(result.riskLevel()).isIn("HIGH", "CRITICAL");
        assertThat(result.signals()).anyMatch(s -> "HIGH_AMOUNT".equals(s.type()));
        assertThat(result.signals()).anyMatch(s -> "GEO_MISMATCH".equals(s.type()));
    }

    @Test
    @DisplayName("should return correct FraudScoreResult structure")
    @WithMockUser(authorities = {"SCOPE_fraud:read", "SCOPE_payment:read"})
    void shouldReturnCorrectFraudScoreResultStructure() {
        var result = fraudTools.scoreTransaction("txn-005");

        assertThat(result.transactionId()).isNotBlank();
        assertThat(result.score()).isBetween(0.0, 1.0);
        assertThat(result.riskLevel()).isIn("LOW", "MEDIUM", "HIGH", "CRITICAL");
        assertThat(result.modelVersion()).isNotBlank();
        result.signals().forEach(signal -> {
            assertThat(signal.type()).isNotBlank();
            assertThat(signal.description()).isNotBlank();
            assertThat(signal.weight()).isBetween(0.0, 1.0);
        });
    }

    @Test
    @DisplayName("should throw exception for unknown transaction")
    @WithMockUser(authorities = {"SCOPE_fraud:read", "SCOPE_payment:read"})
    void shouldThrowExceptionForUnknownTransaction() {
        assertThatThrownBy(() -> fraudTools.scoreTransaction("txn-999"))
                .hasMessageContaining("txn-999");
    }

    // ── explainFraudSignals ────────────────────────────────────────────

    @Test
    @DisplayName("should explain fraud signals in human-readable format")
    @WithMockUser(authorities = {"SCOPE_fraud:read", "SCOPE_payment:read"})
    void shouldExplainFraudSignalsInHumanReadableFormat() {
        String explanation = fraudTools.explainFraudSignals("txn-005");

        assertThat(explanation).isNotBlank();
        assertThat(explanation).contains("Fraud score:");
        assertThat(explanation).containsIgnoringCase("signal");
    }

    @Test
    @DisplayName("should return score explanation for clean transaction")
    @WithMockUser(authorities = {"SCOPE_fraud:read", "SCOPE_payment:read"})
    void shouldReturnScoreExplanationForCleanTransaction() {
        String explanation = fraudTools.explainFraudSignals("txn-001");

        assertThat(explanation).isNotBlank();
        assertThat(explanation).satisfiesAnyOf(
                e -> assertThat(e).containsIgnoringCase("no fraud signals"),
                e -> assertThat(e).contains("Fraud score:")
        );
    }

    // ── checkVelocity ─────────────────────────────────────────────────
    @Test
    @DisplayName("should track IP velocity and detect threshold breach after 6 calls")
    @WithMockUser(authorities = {"SCOPE_fraud:read"})
    void shouldTrackIpVelocityAndDetectBreach() {
        String testIp = "velocity-test-ip";

        FraudTools.VelocityResult lastResult = null;
        for (int i = 0; i < 6; i++) {
            // Clear idempotency cache before each call so tool actually executes
            var keys = redisTemplate.keys("idempotency:*checkVelocity*");
            if (keys != null) redisTemplate.delete(keys);

            lastResult = fraudTools.checkVelocity("IP", testIp, 15);
        }

        assertThat(lastResult).isNotNull();
        assertThat(lastResult.count()).isGreaterThan(5);
        assertThat(lastResult.exceedsThreshold()).isTrue();
        assertThat(lastResult.threshold()).isEqualTo(5);
    }

    @Test
    @DisplayName("should not exceed threshold on first call")
    @WithMockUser(authorities = {"SCOPE_fraud:read"})
    void shouldNotExceedThresholdOnFirstCall() {
        var result = fraudTools.checkVelocity("CARD", "card-unique-xyz", 15);

        assertThat(result.exceedsThreshold()).isFalse();
        assertThat(result.threshold()).isEqualTo(3);
        assertThat(result.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("should use correct threshold per dimension")
    @WithMockUser(authorities = {"SCOPE_fraud:read"})
    void shouldUseCorrectThresholdPerDimension() {
        var ipResult = fraudTools.checkVelocity("IP", "unique-ip-xyz", 15);
        var cardResult = fraudTools.checkVelocity("CARD", "unique-card-xyz", 15);
        var deviceResult = fraudTools.checkVelocity("DEVICE", "unique-device-xyz", 15);

        assertThat(ipResult.threshold()).isEqualTo(5);
        assertThat(cardResult.threshold()).isEqualTo(3);
        assertThat(deviceResult.threshold()).isEqualTo(4);
    }
}