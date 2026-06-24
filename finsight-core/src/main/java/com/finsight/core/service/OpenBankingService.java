package com.finsight.core.service;

import com.finsight.core.domain.model.BankAccount;
import com.finsight.core.domain.valueobject.TenantId;
import com.finsight.core.exception.FinsightDomainException;
import com.finsight.core.port.OpenBankingPort;

import java.util.List;

/**
 * Domain service for open banking / PSD2 operations.
 * Pure Java — no Spring, no framework coupling.
 */
public class OpenBankingService {

    private final OpenBankingPort openBankingPort;

    public OpenBankingService(OpenBankingPort openBankingPort) {
        this.openBankingPort = openBankingPort;
    }

    /**
     * Fetch account data, validating consent first.
     * Called by the fetchAccountData MCP tool.
     */
    public BankAccount fetchAccount(String accountId, TenantId tenantId) {
        // Check consent before making the API call
        var consent = openBankingPort.checkConsent(accountId, tenantId);
        return switch (consent.status()) {
            case VALID -> openBankingPort.fetchAccount(accountId, tenantId);
            case EXPIRED -> throw new FinsightDomainException
                    .ConsentExpiredException(accountId);
            case REVOKED -> throw new FinsightDomainException
                    .ConsentRevokedException(accountId);
            case PENDING -> throw new FinsightDomainException(
                    "CONSENT_PENDING",
                    "Consent not yet granted for account: " + accountId);
        };
    }

    /**
     * List all accounts for a requisition.
     * Called by the fetchAccountData MCP tool with a requisition ID.
     */
    public List<BankAccount> fetchAllAccounts(String requisitionId, TenantId tenantId) {
        return openBankingPort.fetchAllAccounts(requisitionId, tenantId);
    }

    /**
     * List banks available in a country.
     * Called by the listConnectedBanks MCP tool.
     */
    public List<OpenBankingPort.BankInfo> listAvailableBanks(String countryCode) {
        return openBankingPort.listAvailableBanks(countryCode);
    }

    /**
     * Check consent status.
     * Called by the checkConsent MCP tool.
     */
    public OpenBankingPort.ConsentStatus checkConsent(String accountId, TenantId tenantId) {
        return openBankingPort.checkConsent(accountId, tenantId);
    }
}