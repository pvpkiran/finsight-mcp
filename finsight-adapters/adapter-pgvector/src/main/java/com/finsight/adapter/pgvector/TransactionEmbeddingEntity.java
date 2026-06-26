package com.finsight.adapter.pgvector;

import com.pgvector.PGvector;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Type;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for finsight.transaction_embeddings table.
 * Stores transaction feature vectors for pgvector similarity search.
 */
@Entity
@Table(name = "transaction_embeddings", schema = "finsight")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionEmbeddingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "transaction_ref", nullable = false)
    private String transactionRef;

    @Column(name = "is_fraud", nullable = false)
    private boolean isFraud;

    @Column(name = "fraud_label")
    private String fraudLabel;

    /**
     * 768-dimension vector from nomic-embed-text.
     * Stored as pgvector type for cosine similarity search.
     */
    @JdbcTypeCode(SqlTypes.VECTOR)
    @Column(name = "embedding", columnDefinition = "vector(768)")
    private float[] embedding;

    @Column(name = "metadata", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String metadata;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}