package com.finsight.core.port;

import com.finsight.core.domain.valueobject.TenantId;

import java.time.Instant;

/**
 * Outbound port for immutable audit logging.
 * Only INSERT is permitted — no UPDATE or DELETE ever.
 * This is enforced both at the port level (no update methods)
 * and at the DB level (role grants INSERT only on audit schema).
 */
public interface AuditPort {

    void record(AuditEvent event);

    record AuditEvent(
            String toolName,
            String toolVersion,
            TenantId tenantId,
            String sessionId,
            String userSubject,
            String traceId,
            String inputHash,       // SHA-256 of input params — never raw PII
            EventStatus status,
            Long durationMs,
            String errorCode,
            Instant invokedAt
    ) {
        public enum EventStatus {
            SUCCESS, FAILURE, REJECTED, TIMEOUT
        }

        public static AuditEvent success(String toolName, TenantId tenantId,
                                         String sessionId, String traceId,
                                         String inputHash, long durationMs) {
            return new AuditEvent(toolName, "1.0.0", tenantId, sessionId,
                    null, traceId, inputHash, EventStatus.SUCCESS, durationMs, null, Instant.now());
        }

        public static AuditEvent failure(String toolName, TenantId tenantId,
                                         String sessionId, String traceId,
                                         String inputHash, String errorCode, long durationMs) {
            return new AuditEvent(toolName, "1.0.0", tenantId, sessionId,
                    null, traceId, inputHash, EventStatus.FAILURE, durationMs, errorCode, Instant.now());
        }
    }
}