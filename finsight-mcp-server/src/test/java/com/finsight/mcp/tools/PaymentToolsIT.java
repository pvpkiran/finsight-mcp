package com.finsight.mcp.tools;

import com.finsight.core.domain.model.Transaction;
import com.finsight.core.domain.valueobject.TenantId;
import com.finsight.core.service.PaymentService;
import com.finsight.mcp.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for payment domain tools.
 *
 * Tests the full stack:
 *   PaymentTools → PaymentService → MockPaymentAdapter
 *
 * Verifies that:
 *   - Transactions can be fetched by ID
 *   - Declined transactions return human-readable explanations
 *   - Reconciliation returns filtered transaction lists
 *   - Route analysis returns routing recommendations
 */
@DisplayName("Payment Tools Integration Tests")
class PaymentToolsIT extends AbstractIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentTools paymentTools;

    private static final TenantId DEMO_TENANT = TenantId.of("tenant-demo-001");

    @Test
    @DisplayName("should fetch transaction by ID")
    @WithMockUser(authorities = {"SCOPE_payment:read"})
    void shouldFetchTransactionById() {
        Transaction tx = paymentService.getTransaction("txn-001", DEMO_TENANT);

        assertThat(tx).isNotNull();
        assertThat(tx.id().value()).isEqualTo("txn-001");
        assertThat(tx.amount().currencyCode()).isEqualTo("EUR");
        assertThat(tx.status()).isEqualTo(Transaction.TransactionStatus.CAPTURED);
        assertThat(tx.tenantId()).isEqualTo(DEMO_TENANT);
    }

    @Test
    @DisplayName("should return declined transaction with decline code")
    @WithMockUser(authorities = {"SCOPE_payment:read"})
    void shouldReturnDeclinedTransactionWithDeclineCode() {
        Transaction tx = paymentService.getTransaction("txn-002", DEMO_TENANT);

        assertThat(tx.status()).isEqualTo(Transaction.TransactionStatus.DECLINED);
        assertThat(tx.declineCode()).isEqualTo("insufficient_funds");
        assertThat(tx.isDeclined()).isTrue();
    }

    @Test
    @DisplayName("should explain decline in human-readable format")
    @WithMockUser(authorities = {"SCOPE_payment:read"})
    void shouldExplainDeclineInHumanReadableFormat() {
        String explanation = paymentService.explainDecline("txn-002", DEMO_TENANT);

        assertThat(explanation).isNotBlank();
        assertThat(explanation.toLowerCase()).contains("insufficient funds");
        assertThat(explanation).contains("89.99");
    }

    @Test
    @DisplayName("should not explain decline for non-declined transaction")
    @WithMockUser(authorities = {"SCOPE_payment:read"})
    void shouldNotExplainDeclineForNonDeclinedTransaction() {
        String result = paymentService.explainDecline("txn-001", DEMO_TENANT);

        assertThat(result).contains("not declined");
        assertThat(result).contains("CAPTURED");
    }

    @Test
    @DisplayName("should throw exception for unknown transaction")
    @WithMockUser(authorities = {"SCOPE_payment:read"})
    void shouldThrowExceptionForUnknownTransaction() {
        assertThatThrownBy(() ->
                paymentService.getTransaction("txn-999", DEMO_TENANT))
                .hasMessageContaining("txn-999");
    }

    @Test
    @DisplayName("should fetch transactions for reconciliation")
    @WithMockUser(authorities = {"SCOPE_payment:read"})
    void shouldFetchTransactionsForReconciliation() {
        var filter = new com.finsight.core.port.PaymentPort.TransactionFilter(
                Instant.now().minusSeconds(7200),
                Instant.now(),
                null,
                10
        );

        var transactions = paymentService.fetchForReconciliation(DEMO_TENANT, filter);

        assertThat(transactions).isNotEmpty();
        assertThat(transactions).allMatch(tx ->
                tx.tenantId().equals(DEMO_TENANT));
    }

    @Test
    @DisplayName("should filter reconciliation by status")
    @WithMockUser(authorities = {"SCOPE_payment:read"})
    void shouldFilterReconciliationByStatus() {
        var filter = new com.finsight.core.port.PaymentPort.TransactionFilter(
                Instant.now().minusSeconds(7200),
                Instant.now(),
                Transaction.TransactionStatus.DECLINED,
                10
        );

        var transactions = paymentService.fetchForReconciliation(DEMO_TENANT, filter);

        assertThat(transactions).isNotEmpty();
        assertThat(transactions).allMatch(Transaction::isDeclined);
    }

    @Test
    @DisplayName("should analyze payment route for domestic transaction")
    @WithMockUser(authorities = {"SCOPE_payment:read"})
    void shouldAnalyzePaymentRouteForDomesticTransaction() {
        var request = new com.finsight.core.port.PaymentPort.RouteRequest(
                com.finsight.core.domain.valueobject.Money.of("250.00", "EUR"),
                "merchant-de-001",
                "DE",
                "CARD",
                DEMO_TENANT
        );

        var route = paymentService.analyzeRoute(request);

        assertThat(route).isNotNull();
        assertThat(route.successProbability()).isGreaterThan(0.9);
        assertThat(route.estimatedFee()).isNotNull();
        assertThat(route.alternatives()).isNotEmpty();
        assertThat(route.routingReasons()).isNotEmpty();
        // Domestic transaction — no decline risk
        assertThat(route.declineRiskReason()).isNull();
    }

    @Test
    @DisplayName("should flag cross-border transaction with decline risk")
    @WithMockUser(authorities = {"SCOPE_payment:read"})
    void shouldFlagCrossBorderTransactionWithDeclineRisk() {
        var request = new com.finsight.core.port.PaymentPort.RouteRequest(
                com.finsight.core.domain.valueobject.Money.of("3200.00", "EUR"),
                "merchant-us-001",
                "US",
                "CARD",
                DEMO_TENANT
        );

        var route = paymentService.analyzeRoute(request);

        assertThat(route.successProbability()).isLessThan(0.95);
        assertThat(route.declineRiskReason()).isNotNull();
        assertThat(route.declineRiskReason().toLowerCase()).contains("cross-border");
    }
}