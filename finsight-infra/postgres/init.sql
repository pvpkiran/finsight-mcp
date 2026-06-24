-- FinSight MCP — PostgreSQL initialisation
-- Runs once on first container start

CREATE SCHEMA IF NOT EXISTS finsight;
CREATE SCHEMA IF NOT EXISTS keycloak;
CREATE SCHEMA IF NOT EXISTS audit;

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
CREATE EXTENSION IF NOT EXISTS "vector";

-- ── Tool registry ──────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS finsight.tool_registry (
                                                      id                UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    tool_name         VARCHAR(128) NOT NULL UNIQUE,
    version           VARCHAR(32)  NOT NULL DEFAULT '1.0.0',
    domain_area       VARCHAR(32)  NOT NULL CHECK (domain_area IN ('PAYMENT','FRAUD','OPEN_BANKING','SYSTEM')),
    description       TEXT,
    input_schema      JSONB        NOT NULL DEFAULT '{}',
    output_schema     JSONB        NOT NULL DEFAULT '{}',
    required_scope    VARCHAR(128) NOT NULL,
    async_capable     BOOLEAN      NOT NULL DEFAULT FALSE,
    enabled           BOOLEAN      NOT NULL DEFAULT TRUE,
    deprecated_at     TIMESTAMPTZ,
    sunset_at         TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
    );

CREATE INDEX idx_tool_registry_domain  ON finsight.tool_registry(domain_area);
CREATE INDEX idx_tool_registry_enabled ON finsight.tool_registry(enabled) WHERE enabled = TRUE;

-- ── Idempotency keys ──────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS finsight.idempotency_keys (
                                                         id                UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
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
CREATE INDEX idx_idempotency_expires ON finsight.idempotency_keys(expires_at);

-- ── Audit log (append-only) ───────────────────────────────────────────
CREATE TABLE IF NOT EXISTS audit.tool_invocations (
                                                      id            UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    tool_name     VARCHAR(128) NOT NULL,
    tool_version  VARCHAR(32),
    tenant_id     VARCHAR(128) NOT NULL,
    session_id    VARCHAR(256),
    user_subject  VARCHAR(256),
    trace_id      VARCHAR(128),
    input_hash    VARCHAR(64),
    status        VARCHAR(32)  NOT NULL CHECK (status IN ('SUCCESS','FAILURE','REJECTED','TIMEOUT')),
    duration_ms   BIGINT,
    error_code    VARCHAR(64),
    invoked_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
    );

CREATE INDEX idx_audit_tenant_time ON audit.tool_invocations(tenant_id, invoked_at DESC);
CREATE INDEX idx_audit_trace       ON audit.tool_invocations(trace_id);
CREATE INDEX idx_audit_tool_name   ON audit.tool_invocations(tool_name, invoked_at DESC);

-- ── Async jobs ─────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS finsight.async_jobs (
                                                   id            UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
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

CREATE INDEX idx_async_jobs_tenant ON finsight.async_jobs(tenant_id, status);
CREATE INDEX idx_async_jobs_status ON finsight.async_jobs(status) WHERE status IN ('PENDING','RUNNING');

-- ── Fraud embeddings (pgvector) ───────────────────────────────────────
CREATE TABLE IF NOT EXISTS finsight.transaction_embeddings (
                                                               id              UUID    PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id       VARCHAR(128) NOT NULL,
    transaction_ref VARCHAR(256) NOT NULL,
    embedding       vector(1536),
    metadata        JSONB   NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

CREATE INDEX idx_embeddings_tenant ON finsight.transaction_embeddings(tenant_id);
CREATE INDEX idx_embeddings_ivfflat
    ON finsight.transaction_embeddings
    USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);

-- ── Seed tool registry ────────────────────────────────────────────────
INSERT INTO finsight.tool_registry
(tool_name, version, domain_area, description, required_scope, async_capable)
VALUES
    ('analyzePaymentRoute',  '1.0.0', 'PAYMENT',      'Analyse optimal routing for a payment',       'payment:read',  false),
    ('reconcileTransactions','1.0.0', 'PAYMENT',      'Reconcile transactions against PSP records',  'payment:read',  true),
    ('explainDecline',       '1.0.0', 'PAYMENT',      'Explain why a payment was declined',          'payment:read',  false),
    ('getTransaction',       '1.0.0', 'PAYMENT',      'Fetch a transaction by ID',                   'payment:read',  false),
    ('scoreTransaction',     '1.0.0', 'FRAUD',        'Score a transaction for fraud risk',          'fraud:read',    false),
    ('explainFraudSignals',  '1.0.0', 'FRAUD',        'Explain contributing fraud signals',          'fraud:read',    false),
    ('checkVelocity',        '1.0.0', 'FRAUD',        'Check transaction velocity',                  'fraud:read',    false),
    ('fetchAccountData',     '1.0.0', 'OPEN_BANKING', 'Fetch account data via PSD2',                 'banking:read',  false),
    ('fetchAllAccounts',     '1.0.0', 'OPEN_BANKING', 'Fetch all accounts for a requisition',        'banking:read',  false),
    ('checkConsent',         '1.0.0', 'OPEN_BANKING', 'Check open banking consent status',           'banking:read',  false),
    ('listConnectedBanks',   '1.0.0', 'OPEN_BANKING', 'List banks available for connection',         'banking:read',  false)
    ON CONFLICT (tool_name) DO NOTHING;

-- ── DB-level role for app (append-only on audit) ──────────────────────
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'finsight_app') THEN
CREATE ROLE finsight_app LOGIN PASSWORD 'finsight_secret';
END IF;
END$$;

GRANT USAGE ON SCHEMA finsight TO finsight_app;
GRANT USAGE ON SCHEMA audit    TO finsight_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA finsight TO finsight_app;
GRANT INSERT, SELECT                  ON ALL TABLES IN SCHEMA audit    TO finsight_app;