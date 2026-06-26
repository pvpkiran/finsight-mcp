package com.finsight.core.port;

import com.finsight.core.domain.model.BankAccount;
import com.finsight.core.domain.valueobject.Money;
import com.finsight.core.domain.valueobject.TenantId;

import java.util.List;

/**
 * Outbound port for open banking / PSD2 operations.
 *
 * Note: real PSD2 requires an AISP/PISP license in the EU.
 */
public interface OpenBankingPort {

    /**
     * List all banks available for connection in a given country.
     */
    List<BankInfo> listAvailableBanks(String countryCode);

    /**
     * Fetch account data for a consented account.
     * Throws ConsentExpiredException if consent has expired.
     */
    BankAccount fetchAccount(String accountId, TenantId tenantId);

    /**
     * Fetch all accounts for a given consent/requisition.
     */
    List<BankAccount> fetchAllAccounts(String requisitionId, TenantId tenantId);

    /**
     * Check the current consent status for an account.
     */
    ConsentStatus checkConsent(String accountId, TenantId tenantId);

    /**
     * Initiate a payment order via PSD2 PISP.
     * Requires PISP license in production — mock only for portfolio use.
     */
    PaymentInitiationResult initiatePayment(PaymentInitiationRequest request);

    // ── Inner types ────────────────────────────────────────────────────

    record BankInfo(
            String id,
            String name,
            String bic,
            String countryCode,
            String logoUrl,
            int transactionTotalDays     // how far back history is available
    ) {}

    record ConsentStatus(
            String accountId,
            BankAccount.ConsentStatus status,
            java.time.Instant expiresAt,
            List<String> grantedPermissions   // DETAILS, BALANCES, TRANSACTIONS
    ) {}

    record PaymentInitiationRequest(
            String debtorIban,
            String creditorIban,
            String creditorName,
            Money amount,
            String reference,
            TenantId tenantId
    ) {}

    record PaymentInitiationResult(
            String paymentId,
            String status,          // ACCP, RJCT, PDNG
            String redirectUrl      // SCA redirect for user authentication
    ) {}
}