package com.finsight.core.domain.model;

import com.finsight.core.domain.valueobject.Money;
import com.finsight.core.domain.valueobject.TenantId;

import java.time.Instant;
import java.util.Objects;

/**
 * Represents a bank account retrieved via PSD2 / Berlin Group open banking.
 * IBAN and account details are sensitive PII — never log raw values.
 */
public record BankAccount(
        String accountId,               // provider-assigned ID (not IBAN)
        TenantId tenantId,
        String iban,                    // masked in logs: DE89***3000
        String bban,
        String accountName,
        String bankName,
        String bic,
        AccountType accountType,
        Money availableBalance,
        Money bookedBalance,
        String currencyCode,
        String countryCode,
        ConsentStatus consentStatus,
        Instant consentExpiresAt,
        Instant lastSyncedAt
) {

    public BankAccount {
        Objects.requireNonNull(accountId, "AccountId required");
        Objects.requireNonNull(tenantId, "TenantId required");
        Objects.requireNonNull(consentStatus, "ConsentStatus required");
    }

    public boolean hasValidConsent() {
        return consentStatus == ConsentStatus.VALID
                && (consentExpiresAt == null || consentExpiresAt.isAfter(Instant.now()));
    }

    /** Returns masked IBAN safe for logging: DE89 3704 0044 0532 0130 00 → DE89***0130 00 */
    public String maskedIban() {
        if (iban == null || iban.length() < 8) return "***";
        return iban.substring(0, 4) + "***" + iban.substring(iban.length() - 6);
    }

    public enum AccountType {
        CURRENT, SAVINGS, CREDIT, LOAN, INVESTMENT
    }

    public enum ConsentStatus {
        VALID,
        EXPIRED,
        REVOKED,
        PENDING
    }
}