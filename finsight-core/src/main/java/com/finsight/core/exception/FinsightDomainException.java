package com.finsight.core.exception;

/**
 * Base exception for all FinSight domain errors.
 * Subclasses map to specific MCP tool error codes.
 */
public class FinsightDomainException extends RuntimeException {

    private final String errorCode;

    public FinsightDomainException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public FinsightDomainException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }

    // ── Specific domain exceptions ─────────────────────────────────────

    public static class TransactionNotFoundException extends FinsightDomainException {
        public TransactionNotFoundException(String transactionId) {
            super("TRANSACTION_NOT_FOUND", "Transaction not found: " + transactionId);
        }
    }

    public static class ConsentExpiredException extends FinsightDomainException {
        public ConsentExpiredException(String accountId) {
            super("CONSENT_EXPIRED", "Open banking consent expired for account: " + accountId);
        }
    }

    public static class ConsentRevokedException extends FinsightDomainException {
        public ConsentRevokedException(String accountId) {
            super("CONSENT_REVOKED", "Open banking consent revoked for account: " + accountId);
        }
    }

    public static class InsufficientScopeException extends FinsightDomainException {
        public InsufficientScopeException(String requiredScope) {
            super("INSUFFICIENT_SCOPE", "Required OAuth scope not granted: " + requiredScope);
        }
    }

    public static class TenantNotFoundException extends FinsightDomainException {
        public TenantNotFoundException(String tenantId) {
            super("TENANT_NOT_FOUND", "Tenant not found: " + tenantId);
        }
    }

    public static class ExternalServiceException extends FinsightDomainException {
        public ExternalServiceException(String service, String message, Throwable cause) {
            super("EXTERNAL_SERVICE_ERROR", "[%s] %s".formatted(service, message), cause);
        }
    }
}