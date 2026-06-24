package com.finsight.adapter.mock.idempotency;

import com.finsight.core.port.IdempotencyPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory idempotency adapter for mock profile.
 * No TTL in mock (no Redis) — keys live for the duration of the process.
 */
@Component
@Profile("mock")
public class MockIdempotencyAdapter implements IdempotencyPort {

    private final Map<String, CachedResponse> store = new ConcurrentHashMap<>();
    private final Set<String> locks = ConcurrentHashMap.newKeySet();

    @Override
    public Optional<CachedResponse> get(IdempotencyKey key) {
        return Optional.ofNullable(store.get(key.redisKey()));
    }

    @Override
    public void store(IdempotencyKey key, CachedResponse response) {
        store.put(key.redisKey(), response);
        locks.remove(key.redisKey());
    }

    @Override
    public boolean tryLock(IdempotencyKey key) {
        return locks.add(key.redisKey());
    }

    @Override
    public void releaseLock(IdempotencyKey key) {
        locks.remove(key.redisKey());
    }
}