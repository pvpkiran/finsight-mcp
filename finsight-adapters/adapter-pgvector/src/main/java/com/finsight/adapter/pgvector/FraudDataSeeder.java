package com.finsight.adapter.pgvector;

import com.finsight.core.domain.model.Transaction;
import com.finsight.core.domain.valueobject.Money;
import com.finsight.core.domain.valueobject.TenantId;
import com.finsight.core.domain.valueobject.TransactionId;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Seeds historical fraud transaction embeddings on startup.
 * Only runs when the embeddings table is empty (first startup).
 * Uses nomic-embed-text to generate real 768-dim vectors.
 */
@Component
@Profile("prod")
@RequiredArgsConstructor
@Slf4j
public class FraudDataSeeder {

    private final PgVectorFraudAdapter fraudAdapter;
    private final TransactionEmbeddingRepository repository;

    @PostConstruct
    public void seedIfEmpty() {
        long count = repository.countByTenantId("tenant-demo-001");
        if (count >= 10) {
            log.info("[PGVECTOR] Seed data already present ({} embeddings)", count);
            return;
        }

        log.info("[PGVECTOR] Seeding historical fraud embeddings...");
        seedFraudTransactions();
        seedLegitimateTransactions();
        log.info("[PGVECTOR] Seeding complete — {} embeddings stored",
                repository.countByTenantId("tenant-demo-001"));
    }

    private void seedFraudTransactions() {
        List<SeedTransaction> fraudTxns = List.of(
                new SeedTransaction("seed-fraud-001", "3200.00", "EUR", "CARD",
                        "Apple Store", "US", "203.0.113.5", "card_fraud"),
                new SeedTransaction("seed-fraud-002", "2800.00", "EUR", "CARD",
                        "Electronics Store", "CN", "198.51.100.1", "card_fraud"),
                new SeedTransaction("seed-fraud-003", "1500.00", "EUR", "CARD",
                        "Jewelry Shop", "RU", "203.0.113.10", "card_fraud"),
                new SeedTransaction("seed-fraud-004", "4200.00", "EUR", "CARD",
                        "Luxury Goods", "US", "198.51.100.5", "account_takeover"),
                new SeedTransaction("seed-fraud-005", "980.00", "EUR", "CARD",
                        "Online Gaming", "CY", "203.0.113.20", "velocity_abuse"),
                new SeedTransaction("seed-fraud-006", "750.00", "EUR", "CARD",
                        "Crypto Exchange", "MT", "198.51.100.15", "card_fraud"),
                new SeedTransaction("seed-fraud-007", "3100.00", "EUR", "CARD",
                        "Unknown Merchant", "TR", "203.0.113.30", "account_takeover"),
                new SeedTransaction("seed-fraud-008", "1200.00", "EUR", "CARD",
                        "Gift Cards Store", "US", "198.51.100.20", "card_fraud"),
                new SeedTransaction("seed-fraud-009", "890.00", "EUR", "CARD",
                        "Wire Transfer", "NG", "203.0.113.40", "card_fraud"),
                new SeedTransaction("seed-fraud-010", "2100.00", "EUR", "CARD",
                        "Electronics Online", "HK", "198.51.100.25", "velocity_abuse")
        );

        for (SeedTransaction t : fraudTxns) {
            seedTransaction(t, true);
        }
    }

    private void seedLegitimateTransactions() {
        List<SeedTransaction> legitTxns = List.of(
                new SeedTransaction("seed-legit-001", "89.99", "EUR", "CARD",
                        "Zalando", "DE", "192.168.1.10", null),
                new SeedTransaction("seed-legit-002", "250.00", "EUR", "CARD",
                        "MediaMarkt", "DE", "192.168.1.11", null),
                new SeedTransaction("seed-legit-003", "45.00", "EUR", "CARD",
                        "Rewe Online", "DE", "192.168.1.12", null),
                new SeedTransaction("seed-legit-004", "120.00", "EUR", "CARD",
                        "H&M Online", "DE", "192.168.1.13", null),
                new SeedTransaction("seed-legit-005", "350.00", "EUR", "OPEN_BANKING",
                        "IKEA", "DE", "192.168.1.14", null),
                new SeedTransaction("seed-legit-006", "75.00", "EUR", "CARD",
                        "Spotify", "SE", "192.168.1.15", null),
                new SeedTransaction("seed-legit-007", "199.00", "EUR", "CARD",
                        "Nike Store", "DE", "192.168.1.16", null),
                new SeedTransaction("seed-legit-008", "430.00", "EUR", "CARD",
                        "Lufthansa", "DE", "192.168.1.17", null),
                new SeedTransaction("seed-legit-009", "55.00", "EUR", "CARD",
                        "Lieferando", "DE", "192.168.1.18", null),
                new SeedTransaction("seed-legit-010", "600.00", "EUR", "CARD",
                        "Saturn Electronics", "DE", "192.168.1.19", null)
        );

        for (SeedTransaction t : legitTxns) {
            seedTransaction(t, false);
        }
    }

    private void seedTransaction(SeedTransaction seed, boolean isFraud) {
        try {
            Transaction tx = new Transaction(
                    TransactionId.of(seed.id()),
                    TenantId.of("tenant-demo-001"),
                    Money.of(new BigDecimal(seed.amount()), seed.currency()),
                    seed.merchantName().toLowerCase().replace(" ", "-"),
                    seed.merchantName(),
                    seed.paymentMethod(),
                    "stripe",
                    "unknown-issuer",
                    isFraud
                            ? Transaction.TransactionStatus.DECLINED
                            : Transaction.TransactionStatus.CAPTURED,
                    isFraud ? "card_declined" : null,
                    seed.countryCode(),
                    seed.ipAddress(),
                    Instant.now().minusSeconds((long)(Math.random() * 7 * 24 * 3600)),
                    Instant.now().minusSeconds((long)(Math.random() * 7 * 24 * 3600))
            );

            // Use the internal storeEmbedding that accepts fraud label
            String transactionText = buildSeedText(tx, seed.fraudLabel());
            float[] embedding = fraudAdapter.generateEmbeddingPublic(transactionText);

            repository.save(TransactionEmbeddingEntity.builder()
                    .tenantId("tenant-demo-001")
                    .transactionRef(seed.id())
                    .isFraud(isFraud)
                    .fraudLabel(seed.fraudLabel())
                    .embedding(embedding)
                    .metadata("{}")
                    .build());

            log.debug("[PGVECTOR] Seeded {} (fraud={})", seed.id(), isFraud);
        } catch (Exception e) {
            log.error("[PGVECTOR] Failed to seed {}: {}", seed.id(), e.getMessage());
        }
    }

    private String buildSeedText(Transaction tx, String fraudLabel) {
        String base = String.format(
                "Payment of %s by %s at merchant %s in %s. Status: %s. Cross-border: %s.",
                tx.amount(), tx.paymentMethod(), tx.merchantName(),
                tx.countryCode(), tx.status(),
                tx.isCrossBorder() ? "yes" : "no"
        );
        if (fraudLabel != null) {
            base += " Fraud type: " + fraudLabel + ".";
        }
        return base;
    }

    private record SeedTransaction(
            String id, String amount, String currency,
            String paymentMethod, String merchantName,
            String countryCode, String ipAddress, String fraudLabel) {}
}