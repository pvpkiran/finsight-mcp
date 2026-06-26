package com.finsight.core.event;

import com.finsight.core.domain.valueobject.TenantId;

import java.time.Instant;

/**
 * Domain event published to Kafka when an MCP tool is invoked.
 *
 * Downstream consumers can use this for:
 *   - Real-time analytics dashboards
 *   - Fraud pattern detection across tool calls
 *   - SLA monitoring and alerting
 *   - ML training data collection
 *   - Billing/metering per tenant
 */
public record ToolInvocationEvent(
        String eventId,          // UUID — deduplicate consumers
        String toolName,
        String tenantId,
        String traceId,
        String status,           // SUCCESS, FAILURE, CACHED
        long durationMs,
        String errorCode,        // null on success
        boolean servedFromCache, // true if Redis returned cached response
        Instant occurredAt
) {
    public static ToolInvocationEvent success(
            String eventId,
            String toolName,
            TenantId tenantId,
            String traceId,
            long durationMs,
            boolean servedFromCache) {
        return new ToolInvocationEvent(
                eventId, toolName, tenantId.value(),
                traceId, "SUCCESS", durationMs,
                null, servedFromCache, Instant.now());
    }

    public static ToolInvocationEvent failure(
            String eventId,
            String toolName,
            TenantId tenantId,
            String traceId,
            long durationMs,
            String errorCode) {
        return new ToolInvocationEvent(
                eventId, toolName, tenantId.value(),
                traceId, "FAILURE", durationMs,
                errorCode, false, Instant.now());
    }
}