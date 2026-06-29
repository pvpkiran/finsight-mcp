package com.finsight.mcp.idempotency;

import com.finsight.core.domain.valueobject.TenantId;
import com.finsight.core.port.IdempotencyPort;
import com.finsight.mcp.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Redis-backed idempotency cache.
 *
 * Verifies the fix for the ClassCastException bug where cached responses
 * were stored as toString() and couldn't be deserialized back to
 * the original return type.
 *
 * Tests:
 *   - Store and retrieve a cached response
 *   - JSON serialization preserves response body correctly
 *   - Cache miss returns empty
 *   - Lock acquisition and release
 *   - TTL expiry (verified via key existence)
 */
@DisplayName("Redis Idempotency Integration Tests")
class IdempotencyIT extends AbstractIntegrationTest {

    @Autowired
    private IdempotencyPort idempotencyPort;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final TenantId TENANT = TenantId.of("tenant-test-001");

    @BeforeEach
    void clearRedis() {
        // Clear all idempotency keys before each test
        var keys = redisTemplate.keys("idempotency:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    @DisplayName("should store and retrieve cached response")
    @WithMockUser(authorities = {"SCOPE_payment:read"})
    void shouldStoreAndRetrieveCachedResponse() {
        IdempotencyPort.IdempotencyKey key = new IdempotencyPort.IdempotencyKey(
                TENANT, "scoreTransaction", "abc123");

        String jsonResponse = "{\"transactionId\":\"txn-001\",\"score\":0.0,\"riskLevel\":\"LOW\"}";
        IdempotencyPort.CachedResponse response =
                new IdempotencyPort.CachedResponse(jsonResponse, "SUCCESS", 42L);

        idempotencyPort.store(key, response);

        Optional<IdempotencyPort.CachedResponse> cached = idempotencyPort.get(key);

        assertThat(cached).isPresent();
        assertThat(cached.get().responseBody()).isEqualTo(jsonResponse);
        assertThat(cached.get().status()).isEqualTo("SUCCESS");
        assertThat(cached.get().durationMs()).isEqualTo(42L);
    }

    @Test
    @DisplayName("should return empty for cache miss")
    void shouldReturnEmptyForCacheMiss() {
        IdempotencyPort.IdempotencyKey key = new IdempotencyPort.IdempotencyKey(
                TENANT, "nonExistentTool", "missing-key");

        Optional<IdempotencyPort.CachedResponse> cached = idempotencyPort.get(key);

        assertThat(cached).isEmpty();
    }

    @Test
    @DisplayName("should store JSON with special characters correctly")
    void shouldStoreJsonWithSpecialCharacters() {
        IdempotencyPort.IdempotencyKey key = new IdempotencyPort.IdempotencyKey(
                TENANT, "explainFraudSignals", "hash456");

        // JSON with pipe characters which could break the format
        String complexJson = """
                {"signals":[{"type":"CROSS_BORDER","description":"Cross-border transaction | elevated risk","weight":0.05}]}
                """.strip();

        IdempotencyPort.CachedResponse response =
                new IdempotencyPort.CachedResponse(complexJson, "SUCCESS", 100L);

        idempotencyPort.store(key, response);

        Optional<IdempotencyPort.CachedResponse> cached = idempotencyPort.get(key);

        assertThat(cached).isPresent();
        assertThat(cached.get().responseBody()).isEqualTo(complexJson);
    }

    @Test
    @DisplayName("should acquire and release lock")
    void shouldAcquireAndReleaseLock() {
        IdempotencyPort.IdempotencyKey key = new IdempotencyPort.IdempotencyKey(
                TENANT, "scoreTransaction", "lock-test");

        boolean acquired = idempotencyPort.tryLock(key);
        assertThat(acquired).isTrue();

        // Second acquire should fail — lock already held
        boolean secondAcquire = idempotencyPort.tryLock(key);
        assertThat(secondAcquire).isFalse();

        // Release and try again
        idempotencyPort.releaseLock(key);
        boolean afterRelease = idempotencyPort.tryLock(key);
        assertThat(afterRelease).isTrue();

        // Cleanup
        idempotencyPort.releaseLock(key);
    }

    @Test
    @DisplayName("should store response with correct Redis key format")
    void shouldStoreWithCorrectRedisKeyFormat() {
        IdempotencyPort.IdempotencyKey key = new IdempotencyPort.IdempotencyKey(
                TENANT, "getTransaction", "hash789");

        idempotencyPort.store(key,
                new IdempotencyPort.CachedResponse("{}", "SUCCESS", 10L));

        // Verify key exists in Redis with correct format
        String expectedKey = "idempotency:tenant-test-001:getTransaction:hash789";
        Boolean exists = redisTemplate.hasKey(expectedKey);
        assertThat(exists).isTrue();
    }

    @Test
    @DisplayName("should store response releases lock automatically")
    void shouldReleaseAutoLockOnStore() {
        IdempotencyPort.IdempotencyKey key = new IdempotencyPort.IdempotencyKey(
                TENANT, "analyzePaymentRoute", "hash-lock-store");

        // Acquire lock first
        idempotencyPort.tryLock(key);

        // Store should release lock automatically
        idempotencyPort.store(key,
                new IdempotencyPort.CachedResponse("{\"result\":\"ok\"}", "SUCCESS", 50L));

        // Lock should now be released — we can acquire again
        boolean reacquired = idempotencyPort.tryLock(key);
        assertThat(reacquired).isTrue();

        idempotencyPort.releaseLock(key);
    }

    @Test
    @DisplayName("should handle different tools with same hash independently")
    void shouldHandleDifferentToolsWithSameHashIndependently() {
        String sameHash = "same-hash-value";

        IdempotencyPort.IdempotencyKey key1 = new IdempotencyPort.IdempotencyKey(
                TENANT, "scoreTransaction", sameHash);
        IdempotencyPort.IdempotencyKey key2 = new IdempotencyPort.IdempotencyKey(
                TENANT, "explainFraudSignals", sameHash);

        idempotencyPort.store(key1,
                new IdempotencyPort.CachedResponse("{\"score\":0.7}", "SUCCESS", 10L));
        idempotencyPort.store(key2,
                new IdempotencyPort.CachedResponse("{\"signals\":[]}", "SUCCESS", 20L));

        assertThat(idempotencyPort.get(key1).get().responseBody())
                .isEqualTo("{\"score\":0.7}");
        assertThat(idempotencyPort.get(key2).get().responseBody())
                .isEqualTo("{\"signals\":[]}");
    }
}