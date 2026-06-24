package com.finsight.core.domain.valueobject;

import java.util.Objects;

/**
 * Identifies a tenant in the multi-tenant FinSight platform.
 * Extracted from the JWT claim "tenant_id" on every request.
 */
public record TenantId(String value) {

    public TenantId {
        Objects.requireNonNull(value, "TenantId value must not be null");
        if (value.isBlank()) throw new IllegalArgumentException("TenantId must not be blank");
    }

    public static TenantId of(String value) {
        return new TenantId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
