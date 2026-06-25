-- ─────────────────────────────────────────────────────────────
--  V1__baseline_schema.sql
--  FinSight MCP — baseline schema
--  Creates extensions and schemas
-- ─────────────────────────────────────────────────────────────

-- Extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
CREATE EXTENSION IF NOT EXISTS "vector";

-- Schemas
CREATE SCHEMA IF NOT EXISTS finsight;
CREATE SCHEMA IF NOT EXISTS audit;
CREATE SCHEMA IF NOT EXISTS keycloak;