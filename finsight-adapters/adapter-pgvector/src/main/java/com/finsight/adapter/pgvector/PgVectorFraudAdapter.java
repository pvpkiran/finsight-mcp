package com.finsight.adapter.pgvector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsight.core.domain.model.FraudScore;
import com.finsight.core.domain.model.FraudScore.FraudSignal;
import com.finsight.core.domain.model.Transaction;
import com.finsight.core.domain.valueobject.FraudRiskLevel;
import com.finsight.core.domain.valueobject.TenantId;
import com.finsight.core.domain.valueobject.TransactionId;
import com.finsight.core.port.FraudDataPort;
import com.pgvector.PGvector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * pgvector-based fraud scoring adapter.
 *
 * Uses Ollama (nomic-embed-text) to convert transaction features
 * to 768-dimension vectors, then queries pgvector for similar
 * historical transactions to compute a fraud risk score.
 *
 * Scoring algorithm:
 *   1. Convert transaction to descriptive text
 *   2. Generate embedding via Ollama
 *   3. Find top-5 most similar historical transactions
 *   4. Compute fraud ratio among similar transactions
 *   5. Weight by cosine similarity distance
 *   6. Return FraudScore with explainable signals
 *
 * Active when Spring profile "pgvector" is set.
 */
@Component
@Profile("pgvector")
@RequiredArgsConstructor
@Slf4j
public class PgVectorFraudAdapter implements FraudDataPort {

    private final EmbeddingModel embeddingModel;
    private final TransactionEmbeddingRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final int TOP_K = 5;
    private static final double COLD_START_SCORE = 0.15;
    private static final int MIN_EMBEDDINGS_FOR_SCORING = 10;
    private static final String MODEL_VERSION = "pgvector-nomic-embed-text-v1";

    // ── scoreTransaction ───────────────────────────────────────

    @Override
    public FraudScore scoreTransaction(Transaction transaction) {
        log.debug("[PGVECTOR] Scoring transaction {} for fraud", transaction.id().value());

        String tenantId = transaction.tenantId().value();
        long totalEmbeddings = repository.countByTenantId(tenantId);

        // Cold start — not enough historical data
        if (totalEmbeddings < MIN_EMBEDDINGS_FOR_SCORING) {
            log.debug("[PGVECTOR] Cold start — only {} embeddings for tenant {}",
                    totalEmbeddings, tenantId);
            return coldStartScore(transaction, totalEmbeddings);
        }

        // Generate embedding for this transaction
        String transactionText = buildTransactionText(transaction);
        float[] embedding = generateEmbedding(transactionText);

        // Find similar historical transactions
        String embeddingStr = toVectorString(embedding);
        List<TransactionEmbeddingEntity> similar =
                repository.findSimilar(tenantId, embeddingStr, TOP_K);

        // Compute fraud score from similar transactions
        FraudScore score = computeFraudScore(transaction, embedding, similar, transactionText);

        // Store this transaction's embedding for future lookups
        storeEmbedding(transaction, embedding, false, null);

        return score;
    }

    // ── checkVelocity ──────────────────────────────────────────

    @Override
    public VelocityResult checkVelocity(VelocityRequest request) {
        // Velocity checking requires time-series data per dimension
        // For now return a safe baseline — real implementation would
        // query Redis counters or a time-series table
        log.debug("[PGVECTOR] Velocity check for {} = {}", request.dimension(), request.value());
        return new VelocityResult(
                request.dimension(),
                request.value(),
                request.windowMinutes(),
                1,      // count — assume 1 transaction in window
                false,  // does not exceed threshold
                10      // threshold
        );
    }

    // ── findSimilarFraudPatterns ───────────────────────────────

    @Override
    public List<FraudScore> findSimilarFraudPatterns(Transaction transaction, int topK) {
        String tenantId = transaction.tenantId().value();
        String transactionText = buildTransactionText(transaction);
        float[] embedding = generateEmbedding(transactionText);

        String embeddingStr = toVectorString(embedding);
        List<TransactionEmbeddingEntity> similarFraud =
                repository.findSimilarFraud(tenantId, embeddingStr, TOP_K);

        return similarFraud.stream()
                .map(entity -> buildFraudScoreFromEntity(entity, transaction))
                .toList();
    }

    // ── storeEmbedding ─────────────────────────────────────────

    @Override
    public void storeEmbedding(TransactionId id, TenantId tenantId, float[] embedding) {
        // Check if already stored
        if (repository.existsByTransactionRefAndTenantId(id.value(), tenantId.value())) {
            log.debug("[PGVECTOR] Embedding already exists for {}", id.value());
            return;
        }

        TransactionEmbeddingEntity entity = TransactionEmbeddingEntity.builder()
                .tenantId(tenantId.value())
                .transactionRef(id.value())
                .isFraud(false)
                .embedding(embedding)
                .metadata("{}")
                .build();

        repository.save(entity);
        log.debug("[PGVECTOR] Stored embedding for transaction {}", id.value());
    }

    // ── private helpers ────────────────────────────────────────

    /**
     * Convert transaction features to a descriptive text string.
     * This is what gets embedded — the quality of this text
     * directly affects the quality of the fraud detection.
     */
    private String buildTransactionText(Transaction transaction) {
        return String.format(
                "Payment of %s by %s at merchant %s in %s. " +
                        "Acquirer: %s. Issuer: %s. Status: %s. " +
                        "Cross-border: %s. IP: %s.",
                transaction.amount(),
                transaction.paymentMethod(),
                transaction.merchantName() != null ? transaction.merchantName() : "unknown",
                transaction.countryCode() != null ? transaction.countryCode() : "unknown",
                transaction.acquirerId() != null ? transaction.acquirerId() : "unknown",
                transaction.issuerId() != null ? transaction.issuerId() : "unknown",
                transaction.status(),
                transaction.isCrossBorder() ? "yes" : "no",
                transaction.ipAddress() != null ? transaction.ipAddress() : "unknown"
        );
    }

    /**
     * Call Ollama nomic-embed-text to generate a 768-dim vector.
     */
    private float[] generateEmbedding(String text) {
        try {
            float[] embedding = embeddingModel.embed(text);
            log.debug("[PGVECTOR] Generated embedding with {} dimensions", embedding.length);
            return embedding;
        } catch (Exception e) {
            log.error("[PGVECTOR] Error generating embedding: {}", e.getMessage());
            throw new RuntimeException("Embedding generation failed: " + e.getMessage(), e);
        }
    }

    /**
     * Compute fraud score based on similar historical transactions.
     *
     * Algorithm:
     *   - Find top-K similar transactions
     *   - Count how many were fraud
     *   - Weight by their similarity (closer = higher weight)
     *   - Score = weighted fraud ratio
     */
    private FraudScore computeFraudScore(
            Transaction transaction,
            float[] embedding,
            List<TransactionEmbeddingEntity> similar,
            String transactionText) {

        List<FraudSignal> signals = new ArrayList<>();
        double weightedFraudScore = 0.0;
        double totalWeight = 0.0;

        for (int i = 0; i < similar.size(); i++) {
            TransactionEmbeddingEntity entity = similar.get(i);

            // Weight by rank — closest match gets highest weight
            double weight = 1.0 / (i + 1);
            totalWeight += weight;

            if (entity.isFraud()) {
                weightedFraudScore += weight;
                signals.add(new FraudSignal(
                        "SIMILAR_FRAUD_PATTERN",
                        String.format("Similar transaction '%s' was confirmed fraud (%s)",
                                entity.getTransactionRef(),
                                entity.getFraudLabel() != null ? entity.getFraudLabel() : "unknown"),
                        weight / similar.size(),
                        entity.getTransactionRef()
                ));
            }
        }

        // Normalize score
        double rawScore = totalWeight > 0 ? weightedFraudScore / totalWeight : 0.0;

        // Add contextual signals
        if (transaction.isCrossBorder()) {
            rawScore = Math.min(1.0, rawScore + 0.05);
            signals.add(new FraudSignal(
                    "CROSS_BORDER",
                    "Cross-border transaction — slightly elevated risk",
                    0.05,
                    transaction.countryCode()
            ));
        }

        if (transaction.isDeclined()) {
            rawScore = Math.min(1.0, rawScore + 0.10);
            signals.add(new FraudSignal(
                    "DECLINED_TRANSACTION",
                    "Transaction was declined — " + transaction.declineCode(),
                    0.10,
                    transaction.declineCode()
            ));
        }

        // Add similarity context signal
        int fraudCount = (int) similar.stream().filter(TransactionEmbeddingEntity::isFraud).count();
        signals.add(new FraudSignal(
                "VECTOR_SIMILARITY",
                String.format("Found %d similar historical transactions, %d were fraud",
                        similar.size(), fraudCount),
                rawScore,
                String.format("%d/%d fraud in top-%d similar", fraudCount, similar.size(), TOP_K)
        ));

        double finalScore = Math.min(1.0, Math.max(0.0, rawScore));
        FraudRiskLevel riskLevel = computeRiskLevel(finalScore);
        boolean blocked = finalScore >= 0.85;

        log.info("[PGVECTOR] Transaction {} scored {:.3f} ({}) — {}/{} similar were fraud",
                transaction.id().value(), finalScore, riskLevel, fraudCount, similar.size());

        return new FraudScore(
                transaction.id(),
                finalScore,
                riskLevel,
                signals,
                MODEL_VERSION,
                blocked,
                blocked ? "High similarity to known fraud patterns" : null,
                Instant.now()
        );
    }

    /**
     * Cold start score — not enough historical data to do similarity search.
     * Return a low baseline score with an explanation.
     */
    private FraudScore coldStartScore(Transaction transaction, long totalEmbeddings) {
        double score = COLD_START_SCORE;

        List<FraudSignal> signals = List.of(
                new FraudSignal(
                        "COLD_START",
                        String.format("Insufficient historical data for similarity scoring. " +
                                        "Only %d transactions indexed (minimum %d required). " +
                                        "Returning baseline score.",
                                totalEmbeddings, MIN_EMBEDDINGS_FOR_SCORING),
                        score,
                        String.valueOf(totalEmbeddings)
                )
        );

        // Still store the embedding to build up history
        String transactionText = buildTransactionText(transaction);
        float[] embedding = generateEmbedding(transactionText);
        storeEmbedding(transaction, embedding, false, null);

        return new FraudScore(
                transaction.id(),
                score,
                FraudRiskLevel.LOW,
                signals,
                MODEL_VERSION + "-cold-start",
                false,
                null,
                Instant.now()
        );
    }

    private void storeEmbedding(Transaction transaction, float[] embedding,
                                boolean isFraud, String fraudLabel) {
        if (repository.existsByTransactionRefAndTenantId(
                transaction.id().value(), transaction.tenantId().value())) {
            return;
        }

        String metadata;
        try {
            metadata = objectMapper.writeValueAsString(Map.of(
                    "amount", transaction.amount().toString(),
                    "merchant", transaction.merchantName() != null ? transaction.merchantName() : "",
                    "country", transaction.countryCode() != null ? transaction.countryCode() : "",
                    "status", transaction.status().name()
            ));
        } catch (Exception e) {
            metadata = "{}";
        }

        TransactionEmbeddingEntity entity = TransactionEmbeddingEntity.builder()
                .tenantId(transaction.tenantId().value())
                .transactionRef(transaction.id().value())
                .isFraud(isFraud)
                .fraudLabel(fraudLabel)
                .embedding(embedding)
                .metadata(metadata)
                .build();

        repository.save(entity);
        log.debug("[PGVECTOR] Stored embedding for {}", transaction.id().value());
    }

    private FraudRiskLevel computeRiskLevel(double score) {
        if (score >= 0.85) return FraudRiskLevel.CRITICAL;
        if (score >= 0.65) return FraudRiskLevel.HIGH;
        if (score >= 0.40) return FraudRiskLevel.MEDIUM;
        return FraudRiskLevel.LOW;
    }

    private FraudScore buildFraudScoreFromEntity(
            TransactionEmbeddingEntity entity, Transaction transaction) {
        return new FraudScore(
                transaction.id(),
                entity.isFraud() ? 0.90 : 0.10,
                entity.isFraud() ? FraudRiskLevel.HIGH : FraudRiskLevel.LOW,
                List.of(new FraudSignal(
                        "HISTORICAL_MATCH",
                        String.format("Matched historical transaction %s",
                                entity.getTransactionRef()),
                        0.90,
                        entity.getTransactionRef()
                )),
                MODEL_VERSION,
                false,
                null,
                Instant.now()
        );
    }

    float[] generateEmbeddingPublic(String text) {
        return generateEmbedding(text);
    }

    private String toVectorString(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            sb.append(embedding[i]);
            if (i < embedding.length - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }
}