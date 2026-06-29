package com.finsight.adapter.obp;

import com.finsight.core.domain.model.BankAccount;
import com.finsight.core.domain.valueobject.TenantId;
import com.finsight.core.port.OpenBankingPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ObpOpenBankingAdapter.
 *
 * Tests the mapping and business logic without making real HTTP calls.
 * HTTP calls are mocked via a spy on the adapter.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OBP Open Banking Adapter Unit Tests")
class ObpOpenBankingAdapterTest {

    private ObpOpenBankingAdapter adapter;

    private static final TenantId TENANT = TenantId.of("tenant-test-001");

    private static final String BANKS_RESPONSE = """
            {
              "banks": [
                {"id": "rbs", "full_name": "The Royal Bank of Scotland", "short_name": "RBS", "bic": "RBOSGB2L"},
                {"id": "test-bank", "full_name": "Test Bank", "short_name": "TB", "bic": ""},
                {"id": "nordea", "full_name": "Nordea Bank AB", "short_name": "Nordea", "bic": "NDEAFIHHXXX"}
              ]
            }
            """;

    private static final String ACCOUNT_RESPONSE = """
            {
              "id": "fgarcia_account1",
              "label": "Felipe Garcia Account",
              "balance": {
                "currency": "EUR",
                "amount": "990.00"
              },
              "account_routings": [
                {"scheme": "IBAN", "address": "DE89370400440532013000"},
                {"scheme": "OBP", "address": "fgarcia_account1"}
              ]
            }
            """;

    private static final String PUBLIC_ACCOUNTS_RESPONSE = """
            {
              "accounts": [
                {
                  "id": "fgarcia_account1",
                  "label": null,
                  "bank_id": "test-bank"
                }
              ]
            }
            """;

    private static final String TOKEN_RESPONSE = """
            {"token": "test-token-12345"}
            """;

    @BeforeEach
    void setUp() {
        adapter = spy(new ObpOpenBankingAdapter());
        ReflectionTestUtils.setField(adapter, "consumerKey", "test-consumer-key");
        ReflectionTestUtils.setField(adapter, "username", "test-user");
        ReflectionTestUtils.setField(adapter, "password", "test-password");

        // Mock the token so we don't make real HTTP calls for auth
        ReflectionTestUtils.setField(adapter, "cachedToken", "test-token-12345");
        ReflectionTestUtils.setField(adapter, "tokenExpiry",
                java.time.Instant.now().plusSeconds(3600));
    }

    @Test
    @DisplayName("should list banks and map to BankInfo")
    void shouldListBanksAndMapToBankInfo() {
        doReturn(BANKS_RESPONSE).when(adapter).callGet(anyString());

        List<OpenBankingPort.BankInfo> banks = adapter.listAvailableBanks("DE");

        assertThat(banks).isNotEmpty();
        assertThat(banks).hasSizeGreaterThanOrEqualTo(3);

        OpenBankingPort.BankInfo rbs = banks.stream()
                .filter(b -> "rbs".equals(b.id()))
                .findFirst()
                .orElseThrow();

        assertThat(rbs.id()).isEqualTo("rbs");
        assertThat(rbs.name()).isEqualTo("The Royal Bank of Scotland");
        assertThat(rbs.countryCode()).isEqualTo("DE");
        assertThat(rbs.transactionTotalDays()).isEqualTo(365);
    }

    @Test
    @DisplayName("should fetch account and map to BankAccount")
    void shouldFetchAccountAndMapToBankAccount() {
        doReturn(ACCOUNT_RESPONSE).when(adapter).callGet(anyString());

        BankAccount account = adapter.fetchAccount("test-bank/fgarcia_account1", TENANT);

        assertThat(account).isNotNull();
        assertThat(account.accountId()).isEqualTo("test-bank/fgarcia_account1");
        assertThat(account.accountName()).isEqualTo("Felipe Garcia Account");
        assertThat(account.availableBalance().amount().toString()).isEqualTo("990.00");
        assertThat(account.currencyCode()).isEqualTo("EUR");
        assertThat(account.iban()).isEqualTo("DE89370400440532013000");
        assertThat(account.consentStatus()).isEqualTo(BankAccount.ConsentStatus.VALID);
        assertThat(account.tenantId()).isEqualTo(TENANT);
    }

    @Test
    @DisplayName("should fetch account without IBAN when no IBAN routing")
    void shouldFetchAccountWithoutIban() {
        String noIbanResponse = """
                {
                  "id": "account-no-iban",
                  "label": "Account Without IBAN",
                  "balance": {"currency": "GBP", "amount": "500.00"},
                  "account_routings": [
                    {"scheme": "OBP", "address": "account-no-iban"}
                  ]
                }
                """;
        doReturn(noIbanResponse).when(adapter).callGet(anyString());

        BankAccount account = adapter.fetchAccount("rbs/account-no-iban", TENANT);

        assertThat(account.iban()).isNull();
        assertThat(account.maskedIban()).isEqualTo("***");
        assertThat(account.currencyCode()).isEqualTo("GBP");
    }

    @Test
    @DisplayName("should fetch all public accounts for a bank")
    void shouldFetchAllPublicAccounts() {
        doReturn(PUBLIC_ACCOUNTS_RESPONSE).when(adapter).callGet(
                contains("accounts/public"));
        doReturn(ACCOUNT_RESPONSE).when(adapter).callGet(
                contains("fgarcia_account1/_test/account"));

        List<BankAccount> accounts = adapter.fetchAllAccounts("test-bank", TENANT);

        assertThat(accounts).hasSize(1);
        assertThat(accounts.get(0).accountId()).isEqualTo("test-bank/fgarcia_account1");
    }

    @Test
    @DisplayName("should return VALID consent when account is accessible")
    void shouldReturnValidConsentWhenAccountAccessible() {
        doReturn(ACCOUNT_RESPONSE).when(adapter).callGet(anyString());

        OpenBankingPort.ConsentStatus consent =
                adapter.checkConsent("test-bank/fgarcia_account1", TENANT);

        assertThat(consent.status()).isEqualTo(BankAccount.ConsentStatus.VALID);
        assertThat(consent.grantedPermissions())
                .containsExactlyInAnyOrder("DETAILS", "BALANCES", "TRANSACTIONS");
        assertThat(consent.expiresAt()).isAfter(java.time.Instant.now());
    }

    @Test
    @DisplayName("should return EXPIRED consent when account is not accessible")
    void shouldReturnExpiredConsentWhenAccountNotAccessible() {
        doThrow(new RuntimeException("404 Not Found"))
                .when(adapter).callGet(anyString());

        OpenBankingPort.ConsentStatus consent =
                adapter.checkConsent("test-bank/invalid-account", TENANT);

        assertThat(consent.status()).isEqualTo(BankAccount.ConsentStatus.EXPIRED);
        assertThat(consent.grantedPermissions()).isEmpty();
    }

    @Test
    @DisplayName("should return pending payment initiation result")
    void shouldReturnPendingPaymentInitiation() {
        var request = new OpenBankingPort.PaymentInitiationRequest(
                "DE89370400440532013000",
                "GB29NWBK60161331926819",
                "John Doe",
                com.finsight.core.domain.valueobject.Money.of("100.00", "EUR"),
                "Invoice 1234",
                TENANT
        );

        OpenBankingPort.PaymentInitiationResult result = adapter.initiatePayment(request);

        assertThat(result.paymentId()).startsWith("obp-payment-");
        assertThat(result.status()).isEqualTo("PDNG");
        assertThat(result.redirectUrl()).contains("openbankproject.com");
    }

    @Test
    @DisplayName("should handle account ID without bank prefix")
    void shouldHandleAccountIdWithoutBankPrefix() {
        doReturn(ACCOUNT_RESPONSE).when(adapter).callGet(anyString());

        // Should default to test-bank when no / in accountId
        BankAccount account = adapter.fetchAccount("fgarcia_account1", TENANT);

        assertThat(account).isNotNull();
        assertThat(account.accountId()).contains("fgarcia_account1");
    }
}