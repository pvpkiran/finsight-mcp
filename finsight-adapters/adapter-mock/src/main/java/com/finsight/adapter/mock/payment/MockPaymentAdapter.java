package com.finsight.adapter.mock.payment;

import com.finsight.core.domain.model.PaymentRoute;
import com.finsight.core.domain.model.Transaction;
import com.finsight.core.domain.valueobject.Money;
import com.finsight.core.domain.valueobject.TenantId;
import com.finsight.core.domain.valueobject.TransactionId;
import com.finsight.core.port.PaymentPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory mock implementation of PaymentPort.
 * Active when Spring profile "mock" is set (default).
 * Pre-seeded with realistic demo transactions for MCP tool testing.
 */
@Component
@Profile("mock")
public class MockPaymentAdapter implements PaymentPort {

    private final Map<String, Transaction> transactions = new ConcurrentHashMap<>();

    public MockPaymentAdapter() {
        seedDemoData();
    }

    @Override
    public Optional<Transaction> findTransaction(TransactionId id, TenantId tenantId) {
        return Optional.ofNullable(transactions.get(id.value()));
    }

    @Override
    public PaymentRoute analyzeRoute(RouteRequest request) {
        // Simulate routing logic based on amount and country
        boolean isHighValue = request.amount().isGreaterThan(Money.of("1000", request.amount().currencyCode()));
        boolean isCrossBorder = !"DE".equals(request.countryCode());

        String recommendedAcquirer = isHighValue ? "adyen" : "stripe";
        double successProbability = isCrossBorder ? 0.87 : 0.96;

        return new PaymentRoute(
                request.tenantId() != null ? TransactionId.generate() : TransactionId.generate(),
                recommendedAcquirer,
                "stripe-sandbox",
                successProbability,
                Money.of(isHighValue ? "2.50" : "0.30", request.amount().currencyCode()),
                List.of(
                        new PaymentRoute.RouteOption(
                                "worldpay", "worldpay-sandbox", 0.91,
                                Money.of("1.80", request.amount().currencyCode()),
                                "Lower fee but 5% lower approval rate"),
                        new PaymentRoute.RouteOption(
                                "checkout", "checkout-sandbox", 0.89,
                                Money.of("1.50", request.amount().currencyCode()),
                                "Cheapest option, best for low-risk merchants")
                ),
                List.of(
                        "High approval rate for " + request.countryCode() + " transactions",
                        isHighValue ? "High-value routing: Adyen preferred acquirer" : "Standard routing: Stripe optimal for small amounts",
                        "Payment method: " + request.paymentMethod()
                ),
                isCrossBorder ? "Cross-border transaction — monitor for decline rate" : null
        );
    }

    @Override
    public List<Transaction> fetchTransactions(TenantId tenantId, TransactionFilter filter) {
        return transactions.values().stream()
                .filter(t -> t.tenantId().equals(tenantId))
                .filter(t -> filter.status() == null || t.status() == filter.status())
                .filter(t -> t.createdAt().isAfter(filter.from()))
                .filter(t -> t.createdAt().isBefore(filter.to()))
                .limit(filter.limit())
                .toList();
    }

    @Override
    public String explainDecline(TransactionId id, TenantId tenantId) {
        Transaction tx = transactions.get(id.value());
        if (tx == null) return "Transaction not found in mock store";

        return switch (tx.declineCode()) {
            case "insufficient_funds" ->
                    "Card declined due to insufficient funds. The cardholder's available balance " +
                            "was lower than the transaction amount of %s.".formatted(tx.amount());
            case "do_not_honor" ->
                    "Issuing bank returned 'Do Not Honor' (decline code 05). This is a generic " +
                            "decline — the issuer did not provide a specific reason. Common causes: " +
                            "unusual spending pattern, fraud suspicion, or card restrictions.";
            case "card_velocity_exceeded" ->
                    "Transaction declined due to card velocity limit. Too many transactions " +
                            "attempted in a short period. The cardholder should wait before retrying.";
            case "invalid_cvv" ->
                    "Card security code (CVV) did not match. Transaction declined at authorisation.";
            default ->
                    "Transaction declined with code '%s'. Recommend contacting the issuing bank."
                            .formatted(tx.declineCode());
        };
    }

    // ── Demo data seeding ──────────────────────────────────────────────

    private void seedDemoData() {
        seed("txn-001", "tenant-demo-001", "250.00", "EUR",
                "merchant-de-001", "MediaMarkt Berlin", "CARD",
                "adyen-de", "deutsche-bank", Transaction.TransactionStatus.CAPTURED,
                null, "DE", "192.168.1.10",
                Instant.now().minusSeconds(3600));

        seed("txn-002", "tenant-demo-001", "89.99", "EUR",
                "merchant-de-002", "Zalando", "CARD",
                "stripe-de", "commerzbank", Transaction.TransactionStatus.DECLINED,
                "insufficient_funds", "DE", "192.168.1.11",
                Instant.now().minusSeconds(1800));

        seed("txn-003", "tenant-demo-001", "1500.00", "EUR",
                "merchant-uk-001", "ASOS", "OPEN_BANKING",
                "checkout-uk", "barclays", Transaction.TransactionStatus.AUTHORISED,
                null, "GB", "10.0.0.5",
                Instant.now().minusSeconds(900));

        seed("txn-004", "tenant-demo-001", "45.00", "EUR",
                "merchant-de-003", "Rewe Online", "CARD",
                "stripe-de", "sparkasse", Transaction.TransactionStatus.DECLINED,
                "do_not_honor", "DE", "192.168.1.12",
                Instant.now().minusSeconds(600));

        seed("txn-005", "tenant-demo-001", "3200.00", "EUR",
                "merchant-us-001", "Apple Store", "CARD",
                "adyen-us", "chase", Transaction.TransactionStatus.DECLINED,
                "card_velocity_exceeded", "US", "203.0.113.5",
                Instant.now().minusSeconds(300));
    }

    private void seed(String id, String tenantId, String amount, String currency,
                      String merchantId, String merchantName, String paymentMethod,
                      String acquirerId, String issuerId, Transaction.TransactionStatus status,
                      String declineCode, String countryCode, String ipAddress,
                      Instant createdAt) {
        var tx = new Transaction(
                TransactionId.of(id),
                TenantId.of(tenantId),
                Money.of(new BigDecimal(amount), currency),
                merchantId, merchantName, paymentMethod,
                acquirerId, issuerId, status, declineCode,
                countryCode, ipAddress, createdAt,
                status == Transaction.TransactionStatus.PENDING ? null : createdAt.plusSeconds(2)
        );
        transactions.put(id, tx);
    }
}