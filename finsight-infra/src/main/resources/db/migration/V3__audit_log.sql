-- ─────────────────────────────────────────────────────────────
--  V3__audit_log.sql
--  FinSight MCP — append-only audit log
--  No UPDATE or DELETE ever permitted on this table
-- ─────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS audit.tool_invocations (
                                                      id            UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
    tool_name     VARCHAR(128) NOT NULL,
    tool_version  VARCHAR(32),
    tenant_id     VARCHAR(128) NOT NULL,
    session_id    VARCHAR(256),
    user_subject  VARCHAR(256),
    trace_id      VARCHAR(128),
    input_hash    VARCHAR(64),
    status        VARCHAR(32)  NOT NULL
    CHECK (status IN ('SUCCESS','FAILURE','REJECTED','TIMEOUT')),
    duration_ms   BIGINT,
    error_code    VARCHAR(64),
    invoked_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
    );

CREATE INDEX idx_audit_tenant_time
    ON audit.tool_invocations(tenant_id, invoked_at DESC);
CREATE INDEX idx_audit_trace
    ON audit.tool_invocations(trace_id);
CREATE INDEX idx_audit_tool_name
    ON audit.tool_invocations(tool_name, invoked_at DESC);

-- Enforce append-only at DB level
-- Application role only has INSERT + SELECT, never UPDATE or DELETE
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