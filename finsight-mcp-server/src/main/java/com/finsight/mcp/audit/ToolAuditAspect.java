package com.finsight.mcp.audit;

import com.finsight.core.port.AuditPort;
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

        try {
            Object result = joinPoint.proceed();
            long durationMs = System.currentTimeMillis() - startMs;

            auditPort.record(AuditPort.AuditEvent.success(
                    toolName,
                    TenantContext.require(),
                    null,        // sessionId — not available at this level
                    traceId,
                    inputHash,
                    durationMs
            ));

            return result;

        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startMs;

            auditPort.record(AuditPort.AuditEvent.failure(
                    toolName,
                    TenantContext.get() != null
                            ? TenantContext.get()
                            : com.finsight.core.domain.valueobject.TenantId.of("unknown"),
                    null,
                    traceId,
                    inputHash,
                    e.getClass().getSimpleName(),
                    durationMs
            ));

            throw e;
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
            return HexFormat.of().formatHex(hash).substring(0, 16); // first 16 chars enough
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