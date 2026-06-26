package com.finsight.mcp.audit;

import com.finsight.core.domain.valueobject.TenantId;
import com.finsight.core.event.ToolInvocationEvent;
import com.finsight.core.port.AuditPort;
import com.finsight.core.port.IdempotencyPort;
import com.finsight.infra.kafka.ToolInvocationEventPublisher;
import com.finsight.mcp.security.TenantContext;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * AOP aspect that intercepts all @Tool annotated methods and writes
 * audit records to AuditPort before and after execution.
 *
 * This approach means zero changes to individual tool classes —
 * every new tool automatically gets audited just by having @Tool.
 *
 * Records:
 *   - Tool name and version
 *   - Tenant ID from TenantContext
 *   - SHA-256 hash of input params (never raw PII)
 *   - Execution status (SUCCESS/FAILURE)
 *   - Duration in milliseconds
 *   - OpenTelemetry trace ID for correlation
 */
@Aspect
@Component
@Slf4j
public class ToolAuditAspect {

    private final AuditPort auditPort;

    @Autowired(required = false)
    private Tracer tracer;

    @Autowired(required = false)
    private ToolInvocationEventPublisher eventPublisher;

    @Autowired(required = false)
    private IdempotencyPort idempotencyPort;

    public ToolAuditAspect(AuditPort auditPort) {
        this.auditPort = auditPort;
    }

    @Around("@annotation(tool)")
    public Object auditToolCall(ProceedingJoinPoint joinPoint, Tool tool) throws Throwable {
        long startMs = System.currentTimeMillis();
        String toolName = tool.name().isBlank()
                ? joinPoint.getSignature().getName()
                : tool.name();

        String inputHash = hashInputs(joinPoint.getArgs());
        String traceId = currentTraceId();
        String eventId = UUID.randomUUID().toString();

        TenantId tenantId = TenantContext.get() != null
                ? TenantContext.get()
                : TenantId.of("unknown");

        // ── 1. Check Redis idempotency cache ──────────────────
        if (idempotencyPort != null) {
            IdempotencyPort.IdempotencyKey key =
                    new IdempotencyPort.IdempotencyKey(tenantId, toolName, inputHash);
            Optional<IdempotencyPort.CachedResponse> cached = idempotencyPort.get(key);
            if (cached.isPresent()) {
                long durationMs = System.currentTimeMillis() - startMs;
                log.debug("[IDEMPOTENCY] Cache hit tool={} tenant={}", toolName, tenantId);
                publishEvent(eventId, toolName, tenantId, traceId, durationMs, true, null);
                // Return cached result — Spring AI expects the raw object
                return cached.get().responseBody();
            }
        }

        // ── 2. Execute the tool ───────────────────────────────
        try {
            Object result = joinPoint.proceed();
            long durationMs = System.currentTimeMillis() - startMs;

            // ── 3. Store in Redis cache ───────────────────────
            if (idempotencyPort != null && result != null) {
                IdempotencyPort.IdempotencyKey key =
                        new IdempotencyPort.IdempotencyKey(tenantId, toolName, inputHash);
                idempotencyPort.store(key,
                        new IdempotencyPort.CachedResponse(result.toString(), "SUCCESS", durationMs));
            }

            // ── 4. Write audit record ─────────────────────────
            auditPort.record(AuditPort.AuditEvent.success(
                    toolName, tenantId, null, traceId, inputHash, durationMs));

            // ── 5. Publish Kafka event ────────────────────────
            publishEvent(eventId, toolName, tenantId, traceId, durationMs, false, null);

            return result;

        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startMs;
            auditPort.record(AuditPort.AuditEvent.failure(
                    toolName, tenantId, null, traceId, inputHash,
                    e.getClass().getSimpleName(), durationMs));
            publishEvent(eventId, toolName, tenantId, traceId, durationMs, false,
                    e.getClass().getSimpleName());
            throw e;
        }
    }

    private void publishEvent(String eventId, String toolName, TenantId tenantId,
                              String traceId, long durationMs,
                              boolean cached, String errorCode) {
        if (eventPublisher == null) return;
        try {
            ToolInvocationEvent event = errorCode == null
                    ? ToolInvocationEvent.success(eventId, toolName, tenantId,
                    traceId, durationMs, cached)
                    : ToolInvocationEvent.failure(eventId, toolName, tenantId,
                    traceId, durationMs, errorCode);
            eventPublisher.publish(event);
        } catch (Exception e) {
            log.error("[KAFKA] Error publishing event: {}", e.getMessage());
        }
    }

    private String hashInputs(Object[] args) {
        if (args == null || args.length == 0) return "no-args";
        try {
            String raw = Arrays.toString(args);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (Exception e) {
            return "hash-error";
        }
    }

    private String currentTraceId() {
        try {
            if (tracer != null && tracer.currentSpan() != null) {
                return tracer.currentSpan().context().traceId();
            }
        } catch (Exception ignored) {}
        return null;
    }
}