package com.finsight.mcp.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.aspectj.lang.reflect.MethodSignature;
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
 * AOP aspect that intercepts all @Tool annotated methods and:
 *
 *   1. Checks Redis idempotency cache — if hit, deserializes and returns cached response
 *   2. Executes the tool
 *   3. Serializes result to JSON and stores in Redis (24h TTL)
 *   4. Writes audit record to PostgreSQL
 *   5. Publishes ToolInvocationEvent to Kafka
 *
 * Idempotency design:
 *   - Key = SHA-256(tenantId + toolName + inputArgs)
 *   - Value = JSON-serialized tool response
 *   - On cache hit, deserialize using the method's actual return type
 *   - This correctly handles complex return types (FraudScoreResult, etc.)
 *   - If deserialization fails, fall through to re-execute the tool
 *
 * Zero changes needed to individual tool classes — every @Tool method
 * automatically gets audit logging, idempotency, and Kafka events.
 */
@Aspect
@Component
@Slf4j
public class ToolAuditAspect {

    private final AuditPort auditPort;
    private final ObjectMapper objectMapper = new ObjectMapper();

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
                try {
                    // Deserialize using the method's actual return type
                    MethodSignature signature = (MethodSignature) joinPoint.getSignature();
                    Class<?> returnType = signature.getReturnType();
                    Object deserialized = objectMapper.readValue(
                            cached.get().responseBody(), returnType);
                    long durationMs = System.currentTimeMillis() - startMs;
                    log.debug("[IDEMPOTENCY] Cache hit tool={} tenant={} returnType={}",
                            toolName, tenantId, returnType.getSimpleName());
                    publishEvent(eventId, toolName, tenantId, traceId, durationMs, true, null);
                    return deserialized;
                } catch (Exception e) {
                    // Deserialization failed — fall through to re-execute
                    // This handles cases where the return type changed after a deploy
                    log.warn("[IDEMPOTENCY] Cache hit but deserialization failed for tool={}, " +
                            "re-executing: {}", toolName, e.getMessage());
                }
            }
        }

        // ── 2. Execute the tool ───────────────────────────────
        try {
            Object result = joinPoint.proceed();
            long durationMs = System.currentTimeMillis() - startMs;

            // ── 3. Serialize result to JSON and store in Redis ─
            if (idempotencyPort != null && result != null) {
                try {
                    String json = objectMapper.writeValueAsString(result);
                    IdempotencyPort.IdempotencyKey key =
                            new IdempotencyPort.IdempotencyKey(tenantId, toolName, inputHash);
                    idempotencyPort.store(key,
                            new IdempotencyPort.CachedResponse(json, "SUCCESS", durationMs));
                    log.debug("[IDEMPOTENCY] Cached tool={} tenant={} durationMs={}",
                            toolName, tenantId, durationMs);
                } catch (Exception e) {
                    // Serialization failed — log but don't fail the tool call
                    log.warn("[IDEMPOTENCY] Could not serialize result for caching tool={}: {}",
                            toolName, e.getMessage());
                }
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

    /**
     * SHA-256 hash of the input arguments.
     * We never store raw params — they may contain PII (IBANs, card numbers etc.)
     * The hash lets us detect duplicate calls without exposing the data.
     */
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