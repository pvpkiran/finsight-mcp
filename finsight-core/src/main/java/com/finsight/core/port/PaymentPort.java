package com.finsight.core.port;

import com.finsight.core.domain.model.PaymentRoute;
import com.finsight.core.domain.model.Transaction;
import com.finsight.core.domain.valueobject.TenantId;
import com.finsight.core.domain.valueobject.TransactionId;

import java.util.List;
import java.util.Optional;

/**
 * Outbound port for payment operations.
 *
 * Implementations:
 *   - MockPaymentAdapter  (@Profile("mock"))  — zero external deps
 *   - StripePaymentAdapter (@Profile("stripe")) — Stripe sandbox/live
 *
 * The domain core ONLY depends on this interface, never on the adapter.
 * Swap adapters via Spring @Profile without changing a single line of domain code.
 */
public interface PaymentPort {

    /**
     * Fetch a transaction by ID from the PSP.
     */
    Optional<Transaction> findTransaction(TransactionId id, TenantId tenantId);

    /**
     * Analyse optimal routing for a transaction amount and context.
     * @param request routing context (amount, merchant, country, payment method)
     * @return recommended route with alternatives
     */
    PaymentRoute analyzeRoute(RouteRequest request);

    /**
     * Fetch recent transactions for reconciliation.
     */
    List<Transaction> fetchTransactions(TenantId tenantId, TransactionFilter filter);

    /**
     * Explain why a specific transaction was declined.
     * Returns a human-readable explanation string suitable for LLM context.
     */
    String explainDecline(TransactionId id, TenantId tenantId);

    // ── Inner request/filter types ─────────────────────────────────────

    record RouteRequest(
            com.finsight.core.domain.valueobject.Money amount,
            String merchantId,
            String countryCode,
            String paymentMethod,
            TenantId tenantId
    ) {}

    record TransactionFilter(
            java.time.Instant from,
            java.time.Instant to,
            Transaction.TransactionStatus status,   // null = all statuses
            int limit
    ) {}
}