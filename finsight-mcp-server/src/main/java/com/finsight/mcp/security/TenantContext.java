package com.finsight.mcp.security;

import com.finsight.core.domain.valueobject.TenantId;

/**
 * Thread-local holder for the current tenant context.
 * Populated by JwtAuthenticationFilter from the JWT "tenant_id" claim
 * on every inbound MCP tool call request.
 *
 * Usage in tool classes:
 *   TenantId tenant = TenantContext.require();
 */
public final class TenantContext {

    private static final ThreadLocal<TenantId> HOLDER = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(TenantId tenantId) {
        HOLDER.set(tenantId);
    }

    public static TenantId get() {
        return HOLDER.get();
    }

    /**
     * Returns the current tenant or throws if not set.
     * Use this in tool methods — if no tenant is present,
     * the request was not properly authenticated.
     */
    public static TenantId require() {
        TenantId tenantId = HOLDER.get();
        if (tenantId == null) {
            throw new IllegalStateException(
                    "No tenant context found — request not authenticated");
        }
        return tenantId;
    }

    public static void clear() {
        HOLDER.remove();
    }
}