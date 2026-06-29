package com.finsight.mcp.tools;

import com.finsight.core.domain.valueobject.TenantId;
import com.finsight.mcp.AbstractIntegrationTest;
import com.finsight.mcp.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for open banking tools.
 *
 * Tests the full stack:
 *   OpenBankingTools → OpenBankingService → MockOpenBankingAdapter
 *
 * Mock accounts:
 *   acc-de-001: Deutsche Bank, 4250 EUR, VALID consent
 *   acc-de-002: Sparkasse München, 12800 EUR SAVINGS, VALID consent
 *   acc-gb-001: NatWest, 1875.50 GBP, EXPIRED consent
 */
@DisplayName("Open Banking Tools Integration Tests")
class OpenBankingToolsIT extends AbstractIntegrationTest {

    @Autowired
    private OpenBankingTools openBankingTools;

    private static final TenantId DEMO_TENANT = TenantId.of("tenant-demo-001");

    @BeforeEach
    void setTenantContext() {
        TenantContext.set(DEMO_TENANT);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    // ── listConnectedBanks ─────────────────────────────────────────────

    @Test
    @DisplayName("should list connected banks for country code")
    @WithMockUser(authorities = {"SCOPE_banking:read"})
    void shouldListConnectedBanksForCountryCode() {
        var result = openBankingTools.listConnectedBanks("DE");

        assertThat(result).isNotNull();
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("should list banks without country filter")
    @WithMockUser(authorities = {"SCOPE_banking:read"})
    void shouldListBanksWithoutCountryFilter() {
        var result = openBankingTools.listConnectedBanks(null);

        assertThat(result).isNotNull();
    }

    // ── fetchAccountData ───────────────────────────────────────────────

    @Test
    @DisplayName("should fetch Deutsche Bank account with correct balance")
    @WithMockUser(authorities = {"SCOPE_banking:read"})
    void shouldFetchDeutscheBankAccountWithCorrectBalance() {
        var result = openBankingTools.fetchAccountData("acc-de-001");

        assertThat(result).isNotNull();
        assertThat(result.accountId()).isEqualTo("acc-de-001");
        assertThat(result.bankName()).isEqualTo("Deutsche Bank");
        assertThat(result.availableBalance()).contains("4250");
        assertThat(result.currency()).isEqualTo("EUR");
        assertThat(result.consentStatus()).isEqualTo("VALID");
        assertThat(result.maskedIban()).isNotBlank();
        assertThat(result.maskedIban()).doesNotContain("DE89370400440532013000"); // must be masked
    }

    @Test
    @DisplayName("should fetch Sparkasse savings account")
    @WithMockUser(authorities = {"SCOPE_banking:read"})
    void shouldFetchSparkasseSavingsAccount() {
        var result = openBankingTools.fetchAccountData("acc-de-002");

        assertThat(result.accountId()).isEqualTo("acc-de-002");
        assertThat(result.bankName()).isEqualTo("Sparkasse München");
        assertThat(result.availableBalance()).contains("12800");
        assertThat(result.accountType()).isEqualTo("SAVINGS");
    }

    @Test
    @DisplayName("should throw ConsentExpiredException for expired consent account")
    @WithMockUser(authorities = {"SCOPE_banking:read"})
    void shouldThrowExceptionForExpiredConsentAccount() {
        assertThatThrownBy(() -> openBankingTools.fetchAccountData("acc-gb-001"))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("acc-gb-001");
    }

    // ── fetchAllAccounts ───────────────────────────────────────────────

    @Test
    @DisplayName("should fetch all accounts for requisition")
    @WithMockUser(authorities = {"SCOPE_banking:read"})
    void shouldFetchAllAccountsForRequisition() {
        var result = openBankingTools.fetchAllAccounts("req-demo-001");

        assertThat(result).isNotNull();
        assertThat(result).isNotEmpty();
    }

    // ── checkConsent ───────────────────────────────────────────────────

    @Test
    @DisplayName("should return VALID consent for Deutsche Bank account")
    @WithMockUser(authorities = {"SCOPE_banking:read"})
    void shouldReturnValidConsentForDeutscheBankAccount() {
        var result = openBankingTools.checkConsent("acc-de-001");

        assertThat(result.accountId()).isEqualTo("acc-de-001");
        assertThat(result.status()).isEqualTo("VALID");
        assertThat(result.expiresAt()).isNotNull();
        assertThat(result.grantedPermissions()).isNotEmpty();
    }

    @Test
    @DisplayName("should return EXPIRED consent for NatWest account")
    @WithMockUser(authorities = {"SCOPE_banking:read"})
    void shouldReturnExpiredConsentForNatWestAccount() {
        var result = openBankingTools.checkConsent("acc-gb-001");

        assertThat(result.accountId()).isEqualTo("acc-gb-001");
        assertThat(result.status()).isEqualTo("EXPIRED");
    }
}