-- ─────────────────────────────────────────────────────────────
--  V5__fraud_embeddings.sql
--  FinSight MCP — pgvector fraud transaction embeddings
-- ─────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS finsight.transaction_embeddings (
                                                               id              UUID    PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id       VARCHAR(128) NOT NULL,
    transaction_ref VARCHAR(256) NOT NULL,
    embedding       vector(1536),
    metadata        JSONB   NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

CREATE INDEX idx_embeddings_tenant
    ON finsight.transaction_embeddings(tenant_id);

-- IVFFlat index for approximate nearest neighbour search
-- lists=100 is appropriate for up to ~1M vectors
CREATE INDEX idx_embeddings_ivfflat
    ON finsight.transaction_embeddings
    USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);