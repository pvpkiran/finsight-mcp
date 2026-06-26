package com.finsight.adapter.pgvector;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for transaction embeddings with pgvector similarity search.
 *
 * Uses native SQL for the cosine similarity operator (<=>)
 * which is not supported by JPQL.
 */
@Repository
public interface TransactionEmbeddingRepository
        extends JpaRepository<TransactionEmbeddingEntity, UUID> {

    /**
     * Find top-K most similar transactions using pgvector cosine distance.
     * Lower distance = more similar (cosine distance is 1 - cosine similarity).
     *
     * The cast ::vector is needed for pgvector to accept the float array parameter.
     */
    @Query(value = """
        SELECT * FROM finsight.transaction_embeddings
        WHERE tenant_id = :tenantId
        ORDER BY embedding <=> CAST(:embedding AS vector)
        LIMIT :topK
        """, nativeQuery = true)
    List<TransactionEmbeddingEntity> findSimilar(
            @Param("tenantId") String tenantId,
            @Param("embedding") String embedding,  // ← String not float[]
            @Param("topK") int topK
    );

    /**
     * Find similar transactions that were confirmed fraud.
     * Used to compute fraud-specific similarity score.
     */
    @Query(value = """
        SELECT * FROM finsight.transaction_embeddings
        WHERE tenant_id = :tenantId
          AND is_fraud = true
        ORDER BY embedding <=> CAST(:embedding AS vector)
        LIMIT :topK
        """, nativeQuery = true)
    List<TransactionEmbeddingEntity> findSimilarFraud(
            @Param("tenantId") String tenantId,
            @Param("embedding") String embedding,
            @Param("topK") int topK
    );

    /**
     * Count total embeddings for a tenant — used for cold start detection.
     */
    long countByTenantId(String tenantId);

    /**
     * Check if a transaction has already been embedded.
     */
    boolean existsByTransactionRefAndTenantId(String transactionRef, String tenantId);
}