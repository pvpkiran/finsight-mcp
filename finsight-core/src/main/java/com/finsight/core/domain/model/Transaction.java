package com.finsight.core.domain.model;

import com.finsight.core.domain.valueobject.Money;
import com.finsight.core.domain.valueobject.TenantId;
import com.finsight.core.domain.valueobject.TransactionId;

import java.time.Instant;
import java.util.Objects;

/**
 * Core domain model representing a financial transaction.
 * This is the central concept that crosses all three domains:
 * - Payments: routing, PSP processing, reconciliation
 * - Fraud: scoring, signal analysis
 * - Open Banking: PSD2 payment initiation
 */
public record Transaction(
        TransactionId id,
        TenantId tenantId,
        Money amount,
        String merchantId,
        String merchantName,
        String paymentMethod,       // CARD, BANK_TRANSFER, OPEN_BANKING
        String acquirerId,
        String issuerId,
        TransactionStatus status,
        String declineCode,         // null if not declined
        String countryCode,         // ISO 3166-1 alpha-2
        String ipAddress,
        Instant createdAt,
        Instant processedAt
) {

    public Transaction {
        Objects.requireNonNull(id, "Transaction id required");
        Objects.requireNonNull(tenantId, "TenantId required");
        Objects.requireNonNull(amount, "Amount required");
        Objects.requireNonNull(status, "Status required");
        Objects.requireNonNull(createdAt, "CreatedAt required");
    }

    public boolean isDeclined() {
        return status == TransactionStatus.DECLINED;
    }

    public boolean isSuccessful() {
        return status == TransactionStatus.AUTHORISED || status == TransactionStatus.CAPTURED;
    }

    public boolean isCrossBorder() {
        // Simplified: flag if merchant country differs from issuer BIN country
        // Real implementation would resolve BIN database
        return countryCode != null && !countryCode.equals("DE");
    }

    public enum TransactionStatus {
        PENDING,
        AUTHORISED,
        CAPTURED,
        DECLINED,
        REVERSED,
        REFUNDED,
        ERROR
    }
}