-- ─────────────────────────────────────────────────────────────
--  V4__idempotency_and_jobs.sql
--  FinSight MCP — idempotency keys + async jobs
-- ─────────────────────────────────────────────────────────────

-- ── Idempotency keys ──────────────────────────────────────────
CREATE TABLE IF NOT EXISTS finsight.idempotency_keys (
                                                         id                UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
    idempotency_key   VARCHAR(256) NOT NULL,
    tenant_id         VARCHAR(128) NOT NULL,
    tool_name         VARCHAR(128) NOT NULL,
    response_hash     VARCHAR(64),
    response_body     JSONB,
    status            VARCHAR(32)  NOT NULL DEFAULT 'PROCESSING'
    CHECK (status IN ('PROCESSING','COMPLETED','FAILED')),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW() + INTERVAL '24 hours'
    );

CREATE UNIQUE INDEX idx_idempotency_unique
    ON finsight.idempotency_keys(tenant_id, tool_name, idempotency_key);
CREATE INDEX idx_idempotency_expires
    ON finsight.idempotency_keys(expires_at);

-- ── Async jobs ────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS finsight.async_jobs (
                                                   id            UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
    tool_name     VARCHAR(128) NOT NULL,
    tenant_id     VARCHAR(128) NOT NULL,
    session_id    VARCHAR(256),
    status        VARCHAR(32)  NOT NULL DEFAULT 'PENDING'
    CHECK (status IN ('PENDING','RUNNING','COMPLETED','FAILED','CANCELLED')),
    input_params  JSONB        NOT NULL DEFAULT '{}',
    result        JSONB,
    error_detail  TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    started_at    TIMESTAMPTZ,
    completed_at  TIMESTAMPTZ,
    expires_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW() + INTERVAL '7 days'
    );

CREATE INDEX idx_async_jobs_tenant
    ON finsight.async_jobs(tenant_id, status);
CREATE INDEX idx_async_jobs_status
    ON finsight.async_jobs(status) WHERE status IN ('PENDING','RUNNING');