-- ─────────────────────────────────────────────────────────────
--  V5__fraud_embeddings.sql
--  FinSight MCP — pgvector fraud transaction embeddings
--  Using nomic-embed-text dimensions (768)
-- ─────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS finsight.transaction_embeddings (
                                                               id              UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id       VARCHAR(128) NOT NULL,
    transaction_ref VARCHAR(256) NOT NULL,
    is_fraud        BOOLEAN      NOT NULL DEFAULT false,
    fraud_label     VARCHAR(64),
    embedding       vector(768),
    metadata        JSONB        NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
    );

CREATE INDEX idx_embeddings_tenant
    ON finsight.transaction_embeddings(tenant_id);

CREATE INDEX idx_embeddings_fraud
    ON finsight.transaction_embeddings(is_fraud);

CREATE INDEX idx_embeddings_ivfflat
    ON finsight.transaction_embeddings
    USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 10);