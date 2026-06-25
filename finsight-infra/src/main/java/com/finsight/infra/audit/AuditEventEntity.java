package com.finsight.infra.audit;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the audit.tool_invocations table.
 * This table is append-only — no updates or deletes ever.
 * Enforced both here (no setters) and at DB level (role grants).
 */
@Entity
@Table(schema = "audit", name = "tool_invocations")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tool_name", nullable = false, length = 128)
    private String toolName;

    @Column(name = "tool_version", length = 32)
    private String toolVersion;

    @Column(name = "tenant_id", nullable = false, length = 128)
    private String tenantId;

    @Column(name = "session_id", length = 256)
    private String sessionId;

    @Column(name = "user_subject", length = 256)
    private String userSubject;

    @Column(name = "trace_id", length = 128)
    private String traceId;

    @Column(name = "input_hash", length = 64)
    private String inputHash;

    @Column(name = "status", nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private AuditStatus status;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "invoked_at", nullable = false)
    private Instant invokedAt;

    public enum AuditStatus {
        SUCCESS, FAILURE, REJECTED, TIMEOUT
    }
}