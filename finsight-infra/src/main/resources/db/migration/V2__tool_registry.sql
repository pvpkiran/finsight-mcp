-- ─────────────────────────────────────────────────────────────
--  V2__tool_registry.sql
--  FinSight MCP — tool registry
-- ─────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS finsight.tool_registry (
                                                      id                UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
    tool_name         VARCHAR(128) NOT NULL UNIQUE,
    version           VARCHAR(32)  NOT NULL DEFAULT '1.0.0',
    domain_area       VARCHAR(32)  NOT NULL
    CHECK (domain_area IN ('PAYMENT','FRAUD','OPEN_BANKING','SYSTEM')),
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

CREATE INDEX idx_tool_registry_domain
    ON finsight.tool_registry(domain_area);
CREATE INDEX idx_tool_registry_enabled
    ON finsight.tool_registry(enabled) WHERE enabled = TRUE;

-- Seed initial tool registry
INSERT INTO finsight.tool_registry
(tool_name, version, domain_area, description, required_scope, async_capable)
VALUES
    ('analyzePaymentRoute',  '1.0.0', 'PAYMENT',      'Analyse optimal routing for a payment',        'payment:read',  false),
    ('reconcileTransactions','1.0.0', 'PAYMENT',      'Reconcile transactions against PSP records',   'payment:read',  true),
    ('explainDecline',       '1.0.0', 'PAYMENT',      'Explain why a payment was declined',           'payment:read',  false),
    ('getTransaction',       '1.0.0', 'PAYMENT',      'Fetch a transaction by ID',                    'payment:read',  false),
    ('scoreTransaction',     '1.0.0', 'FRAUD',        'Score a transaction for fraud risk',           'fraud:read',    false),
    ('explainFraudSignals',  '1.0.0', 'FRAUD',        'Explain contributing fraud signals',           'fraud:read',    false),
    ('checkVelocity',        '1.0.0', 'FRAUD',        'Check transaction velocity',                   'fraud:read',    false),
    ('fetchAccountData',     '1.0.0', 'OPEN_BANKING', 'Fetch account data via PSD2',                  'banking:read',  false),
    ('fetchAllAccounts',     '1.0.0', 'OPEN_BANKING', 'Fetch all accounts for a requisition',         'banking:read',  false),
    ('checkConsent',         '1.0.0', 'OPEN_BANKING', 'Check open banking consent status',            'banking:read',  false),
    ('listConnectedBanks',   '1.0.0', 'OPEN_BANKING', 'List banks available for connection',          'banking:read',  false)
    ON CONFLICT (tool_name) DO NOTHING;