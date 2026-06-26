package com.finsight.core.port;

import com.finsight.core.domain.model.FraudScore;
import com.finsight.core.domain.model.Transaction;
import com.finsight.core.domain.valueobject.TenantId;
import com.finsight.core.domain.valueobject.TransactionId;

import java.util.List;

/**
 * Outbound port for fraud detection operations.
 *
 * Implementations:
 *   - MockFraudAdapter  (@Profile("local"))  — deterministic in-memory scoring
 *
 * The fraud engine is self-contained — no external API needed.
 * Real scoring uses pgvector embeddings + Redis velocity checks.
 */
public interface FraudDataPort {

    /**
     * Score a transaction for fraud risk.
     * Returns a FraudScore with contributing signals for explainability.
     */
    FraudScore scoreTransaction(Transaction transaction);

    /**
     * Check velocity — how many transactions from this IP/card/device
     * in the last N minutes. Used as a fraud signal.
     */
    VelocityResult checkVelocity(VelocityRequest request);

    /**
     * Find similar historical fraud patterns using vector similarity.
     * Powered by pgvector cosine similarity on transaction embeddings.
     */
    List<FraudScore> findSimilarFraudPatterns(Transaction transaction, int topK);

    /**
     * Store a transaction embedding for future similarity lookups.
     */
    void storeEmbedding(TransactionId id, TenantId tenantId, float[] embedding);

    // ── Inner types ────────────────────────────────────────────────────

    record VelocityRequest(
            String dimension,       // IP, CARD, DEVICE, MERCHANT
            String value,           // the actual IP/card/device/merchant value
            int windowMinutes,
            TenantId tenantId
    ) {}

    record VelocityResult(
            String dimension,
            String value,
            int windowMinutes,
            int transactionCount,
            boolean exceedsThreshold,
            int threshold
    ) {}
}