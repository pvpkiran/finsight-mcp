package com.finsight.core.port;

import com.finsight.core.domain.valueobject.TenantId;

import java.util.Optional;

/**
 * Outbound port for idempotency key management.
 * Backed by Redis in production (24h TTL).
 * Protects against duplicate tool calls from agent retry loops.
 */
public interface IdempotencyPort {

    /**
     * Check if a key already exists and return the cached response if so.
     * Returns empty if this is the first time seeing this key.
     */
    Optional<CachedResponse> get(IdempotencyKey key);

    /**
     * Store the response for a completed tool call.
     */
    void store(IdempotencyKey key, CachedResponse response);

    /**
     * Mark a key as currently processing (to handle concurrent duplicate requests).
     * Returns false if another thread is already processing this key.
     */
    boolean tryLock(IdempotencyKey key);

    /**
     * Release the lock without storing a response (e.g. on failure).
     */
    void releaseLock(IdempotencyKey key);

    record IdempotencyKey(
            TenantId tenantId,
            String toolName,
            String key           // client-supplied idempotency key
    ) {
        public String redisKey() {
            return "idempotency:%s:%s:%s".formatted(tenantId.value(), toolName, key);
        }
    }

    record CachedResponse(
            String responseBody,    // JSON-serialised tool response
            String status,          // COMPLETED or FAILED
            long durationMs
    ) {}
}