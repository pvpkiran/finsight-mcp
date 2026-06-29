package com.finsight.infra.idempotency;

import com.finsight.core.port.IdempotencyPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis-backed implementation of IdempotencyPort.
 * Active under "local" and all non-mock profiles.
 *
 * Key structure:  idempotency:{tenantId}:{toolName}:{key}
 * Lock structure: idempotency:lock:{tenantId}:{toolName}:{key}
 *
 * TTL: configurable via finsight.idempotency.ttl-hours (default 24h)
 *
 * Protects against agent retry loops — if the same tool call
 * arrives twice with the same idempotency key, the second call
 * returns the cached response immediately without re-executing.
 */
@Component
@Profile("local | prod")
@RequiredArgsConstructor
@Slf4j
public class RedisIdempotencyAdapter implements IdempotencyPort {

    private final StringRedisTemplate redisTemplate;

    @Value("${finsight.idempotency.ttl-hours:24}")
    private int ttlHours;

    @Override
    public Optional<CachedResponse> get(IdempotencyKey key) {
        String redisKey = key.redisKey();
        String value = redisTemplate.opsForValue().get(redisKey);
        if (value == null) {
            return Optional.empty();
        }
        // Format: "status|durationMs|responseBody"
        String[] parts = value.split("\\|", 3);
        if (parts.length < 3) {
            return Optional.empty();
        }
        return Optional.of(new CachedResponse(
                parts[2],                           // responseBody
                parts[0],                           // status
                Long.parseLong(parts[1])            // durationMs
        ));
    }

    @Override
    public void store(IdempotencyKey key, CachedResponse response) {
        String redisKey = key.redisKey();
        String value = "%s|%d|%s".formatted(
                response.status(),
                response.durationMs(),
                response.responseBody()
        );
        redisTemplate.opsForValue().set(redisKey, value, Duration.ofHours(ttlHours));
        // Release lock if held
        redisTemplate.delete(lockKey(key));
        log.debug("[IDEMPOTENCY] Stored response for key={}", redisKey);
    }

    @Override
    public boolean tryLock(IdempotencyKey key) {
        String lock = lockKey(key);
        // SET NX EX — atomic set-if-not-exists with expiry
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(lock, "locked", Duration.ofSeconds(30));
        return Boolean.TRUE.equals(acquired);
    }

    @Override
    public void releaseLock(IdempotencyKey key) {
        redisTemplate.delete(lockKey(key));
    }

    private String lockKey(IdempotencyKey key) {
        return "idempotency:lock:%s:%s:%s".formatted(
                key.tenantId().value(), key.toolName(), key.key());
    }
}