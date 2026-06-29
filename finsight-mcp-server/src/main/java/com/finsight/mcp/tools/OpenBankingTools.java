package com.finsight.mcp.tools;

import com.finsight.core.domain.model.BankAccount;
import com.finsight.core.port.OpenBankingPort;
import com.finsight.core.service.OpenBankingService;
import com.finsight.mcp.security.TenantContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MCP tools for the open banking / PSD2 domain.
 */
@Component
public class OpenBankingTools {

    private final OpenBankingService openBankingService;

    public OpenBankingTools(OpenBankingService openBankingService) {
        this.openBankingService = openBankingService;
    }

    @Tool(name = "listConnectedBanks",
            description = """
                    List banks available for open banking connection in a given country.
                    Returns bank name, BIC, and available transaction history depth.
                    Use this when asked which banks are supported or available to connect.
                    """)
    @PreAuthorize("hasAuthority('SCOPE_banking:read')")
    public List<BankInfoResult> listConnectedBanks(
            @ToolParam(description = "ISO 3166-1 alpha-2 country code, e.g. 'DE', 'GB'")
            String countryCode
    ) {
        return openBankingService.listAvailableBanks(countryCode)
                .stream()
                .map(b -> new BankInfoResult(b.id(), b.name(), b.bic(),
                        b.countryCode(), b.transactionTotalDays()))
                .toList();
    }

    @Tool(name = "fetchAccountData",
            description = """
                    Fetch bank account data for a consented account via PSD2.
                    Returns account details, available balance, booked balance, and consent status.
                    Requires valid user consent. Will return an error if consent has expired or been revoked.
                    Use this when asked about account balances or account details.
                    Account ID format: 'acc-de-001' (mock) or 'bankId/accountId' e.g. 'test-bank/fgarcia_account1' (OBP).
                    """)
    @PreAuthorize("hasAuthority('SCOPE_banking:read')")
    public AccountResult fetchAccountData(
            @ToolParam(description = "Account ID to fetch, e.g. 'acc-de-001'")
            String accountId
    ) {
        var tenantId = TenantContext.require();
        BankAccount account = openBankingService.fetchAccount(accountId, tenantId);
        return AccountResult.from(account);
    }

    @Tool(name = "fetchAllAccounts",
            description = """
                    Fetch all bank accounts for a given open banking requisition or bank.
                    Returns a list of accounts with balances and consent status.
                    Use this when asked to show all connected accounts for a user.
                    Requisition ID format: 'req-demo-001' (mock) or bank ID e.g. 'test-bank' (OBP sandbox).
                    """)
    @PreAuthorize("hasAuthority('SCOPE_banking:read')")
    public List<AccountResult> fetchAllAccounts(
            @ToolParam(description = "Requisition ID, e.g. 'req-demo-001'")
            String requisitionId
    ) {
        var tenantId = TenantContext.require();
        return openBankingService.fetchAllAccounts(requisitionId, tenantId)
                .stream()
                .map(AccountResult::from)
                .toList();
    }

    @Tool(name = "checkConsent",
            description = """
                    Check the open banking consent status for an account.
                    Returns whether consent is VALID, EXPIRED, REVOKED, or PENDING,
                    and when it expires.
                    Use this before fetching account data, or when asked about consent status.
                    """)
    @PreAuthorize("hasAuthority('SCOPE_banking:read')")
    public ConsentResult checkConsent(
            @ToolParam(description = "Account ID to check consent for, e.g. 'acc-de-001'")
            String accountId
    ) {
        var tenantId = TenantContext.require();
        OpenBankingPort.ConsentStatus consent =
                openBankingService.checkConsent(accountId, tenantId);
        return new ConsentResult(
                consent.accountId(),
                consent.status().name(),
                consent.expiresAt() != null ? consent.expiresAt().toString() : null,
                consent.grantedPermissions()
        );
    }

    // ── Result records ─────────────────────────────────────────────────

    public record BankInfoResult(
            String id, String name, String bic,
            String countryCode, int transactionHistoryDays) {
    }

    public record AccountResult(
            String accountId,
            String maskedIban,
            String accountName,
            String bankName,
            String accountType,
            String availableBalance,
            String bookedBalance,
            String currency,
            String consentStatus,
            String consentExpiresAt
    ) {
        public static AccountResult from(BankAccount account) {
            return new AccountResult(
                    account.accountId(),
                    account.maskedIban(),
                    account.accountName(),
                    account.bankName(),
                    account.accountType().name(),
                    account.availableBalance() != null
                            ? account.availableBalance().toString() : null,
                    account.bookedBalance() != null
                            ? account.bookedBalance().toString() : null,
                    account.currencyCode(),
                    account.consentStatus().name(),
                    account.consentExpiresAt() != null
                            ? account.consentExpiresAt().toString() : null
            );
        }
    }

    public record ConsentResult(
            String accountId, String status,
            String expiresAt, List<String> grantedPermissions) {
    }
}