package com.finsight.infra.audit;

import com.finsight.core.port.AuditPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * PostgreSQL-backed implementation of AuditPort.
 * Active under "local" and "stripe" and "nordigen" profiles.
 * Writes immutable audit records to audit.tool_invocations.
 *
 * Uses @Transactional with propagation REQUIRES_NEW so audit
 * records are committed even if the outer transaction rolls back.
 * This ensures we always have a complete audit trail including failures.
 */
@Component
@Profile("!mock")
@RequiredArgsConstructor
@Slf4j
public class PostgresAuditAdapter implements AuditPort {

    private final AuditEventRepository repository;

    @Override
    public void record(AuditEvent event) {
        try {
            AuditEventEntity entity = AuditEventEntity.builder()
                    .toolName(event.toolName())
                    .toolVersion(event.toolVersion())
                    .tenantId(event.tenantId().value())
                    .sessionId(event.sessionId())
                    .userSubject(event.userSubject())
                    .traceId(event.traceId())
                    .inputHash(event.inputHash())
                    .status(mapStatus(event.status()))
                    .durationMs(event.durationMs())
                    .errorCode(event.errorCode())
                    .invokedAt(event.invokedAt())
                    .build();

            repository.save(entity);

            log.debug("[AUDIT] tool={} tenant={} status={} duration={}ms",
                    event.toolName(), event.tenantId(),
                    event.status(), event.durationMs());

        } catch (Exception e) {
            // Never let audit failures break the main request flow
            log.error("[AUDIT] Failed to record audit event for tool={}: {}",
                    event.toolName(), e.getMessage(), e);
        }
    }

    private AuditEventEntity.AuditStatus mapStatus(AuditEvent.EventStatus status) {
        return switch (status) {
            case SUCCESS  -> AuditEventEntity.AuditStatus.SUCCESS;
            case FAILURE  -> AuditEventEntity.AuditStatus.FAILURE;
            case REJECTED -> AuditEventEntity.AuditStatus.REJECTED;
            case TIMEOUT  -> AuditEventEntity.AuditStatus.TIMEOUT;
        };
    }
}