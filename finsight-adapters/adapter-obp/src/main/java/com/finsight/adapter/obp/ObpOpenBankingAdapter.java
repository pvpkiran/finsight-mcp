package com.finsight.adapter.obp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsight.core.domain.model.BankAccount;
import com.finsight.core.domain.valueobject.Money;
import com.finsight.core.domain.valueobject.TenantId;
import com.finsight.core.port.OpenBankingPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Open Banking adapter backed by Open Bank Project (OBP) sandbox API.
 *
 * Uses Direct Login for authentication — no eIDAS certificate required,
 * making it suitable for portfolio/development use.
 *
 * OBP API docs: https://apisandbox.openbankproject.com/obp/v5.0.0/
 *
 * Activated with the 'obp' Spring profile.
 */
@Component
@Profile("prod")
@Slf4j
public class ObpOpenBankingAdapter implements OpenBankingPort {

    private static final String OBP_BASE_URL = "https://apisandbox.openbankproject.com/obp/v5.0.0";
    private static final String OBP_LOGIN_URL = "https://apisandbox.openbankproject.com/my/logins/direct";
    private static final String DEFAULT_VIEW = "_test";

    @Value("${finsight.obp.consumer-key}")
    private String consumerKey;

    @Value("${finsight.obp.username}")
    private String username;

    @Value("${finsight.obp.password}")
    private String password;

    private final RestClient restClient = RestClient.builder()
            .defaultHeader("Accept", "application/json")
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile String cachedToken;
    private volatile Instant tokenExpiry = Instant.MIN;

    // ── Authentication ─────────────────────────────────────────────────

    /**
     * Get or refresh the DirectLogin token.
     * OBP tokens last ~24h — cache and refresh when expired.
     */
    private String getToken() {
        if (cachedToken == null || Instant.now().isAfter(tokenExpiry)) {
            log.debug("[OBP] Authenticating with Direct Login");
            String responseBody = restClient.post()
                    .uri(OBP_LOGIN_URL)
                    .header("DirectLogin",
                            "username=" + username +
                                    ", password=" + password +
                                    ", consumer_key=" + consumerKey)
                    .retrieve()
                    .body(String.class);

            Map<String, Object> response = parseJson(responseBody);
            cachedToken = (String) response.get("token");
            tokenExpiry = Instant.now().plusSeconds(82800); // 23h
            log.debug("[OBP] Token obtained, valid for 23h");
        }
        return cachedToken;
    }

    // ── Helper methods ─────────────────────────────────────────────────

    /**
     * GET a JSON endpoint and return as Map.
     * Handles OBP returning text/plain content type.
     */
    @SuppressWarnings("unchecked")
    String callGet(String uri) {
        return restClient.get()
                .uri(uri)
                .header("DirectLogin", "token=" + getToken())
                .retrieve()
                .body(String.class);
    }

    private Map<String, Object> getJson(String uri) {
        return parseJson(callGet(uri));
    }

    private Map<String, Object> parseJson(String body) {
        try {
            return objectMapper.readValue(body, new TypeReference<>() {});
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse OBP response: " + body, e);
        }
    }

    // ── Port implementation ────────────────────────────────────────────

    /**
     * List banks available via OBP.
     * OBP has 199 banks — we return up to 20 for readability.
     */
    @Override
    @SuppressWarnings("unchecked")
    public List<BankInfo> listAvailableBanks(String countryCode) {
        log.debug("[OBP] Listing banks countryCode={}", countryCode);
        Map<String, Object> response = getJson(OBP_BASE_URL + "/banks");
        List<Map<String, Object>> banks = (List<Map<String, Object>>) response.get("banks");

        return banks.stream()
                .map(b -> new BankInfo(
                        (String) b.get("id"),
                        (String) b.getOrDefault("full_name", b.get("short_name")),
                        (String) b.getOrDefault("bic", ""),
                        countryCode != null ? countryCode : "EU",
                        (String) b.getOrDefault("logo", ""),
                        365
                ))
                .limit(20)
                .toList();
    }

    /**
     * Fetch account data for a specific account.
     * accountId format: bankId/accountId (e.g. "test-bank/fgarcia_account1")
     */
    @Override
    public BankAccount fetchAccount(String accountId, TenantId tenantId) {
        log.debug("[OBP] Fetching account accountId={}", accountId);

        String[] parts = accountId.contains("/")
                ? accountId.split("/", 2)
                : new String[]{"test-bank", accountId};
        String bankId = parts[0];
        String accId = parts[1];

        Map<String, Object> account = getJson(
                OBP_BASE_URL + "/banks/" + bankId + "/accounts/" + accId + "/" + DEFAULT_VIEW + "/account");

        return mapToBankAccount(account, bankId, tenantId);
    }

    /**
     * Fetch all public accounts for a given bank (requisitionId = bankId).
     */
    @Override
    @SuppressWarnings("unchecked")
    public List<BankAccount> fetchAllAccounts(String requisitionId, TenantId tenantId) {
        log.debug("[OBP] Fetching all accounts requisitionId={}", requisitionId);
        String bankId = requisitionId != null ? requisitionId : "test-bank";

        Map<String, Object> response = getJson(
                OBP_BASE_URL + "/banks/" + bankId + "/accounts/public");
        List<Map<String, Object>> accounts = (List<Map<String, Object>>) response.get("accounts");
        if (accounts == null) return List.of();

        return accounts.stream()
                .map(a -> {
                    try {
                        return fetchAccount(bankId + "/" + a.get("id"), tenantId);
                    } catch (Exception e) {
                        log.warn("[OBP] Failed to fetch account {}: {}", a.get("id"), e.getMessage());
                        return null;
                    }
                })
                .filter(a -> a != null)
                .toList();
    }

    /**
     * Check consent status.
     * OBP uses public/private views instead of PSD2 consents.
     * If we can fetch the account, access is valid.
     */
    @Override
    public ConsentStatus checkConsent(String accountId, TenantId tenantId) {
        log.debug("[OBP] Checking consent accountId={}", accountId);
        try {
            fetchAccount(accountId, tenantId);
            return new ConsentStatus(
                    accountId,
                    BankAccount.ConsentStatus.VALID,
                    Instant.now().plusSeconds(86400 * 90),
                    List.of("DETAILS", "BALANCES", "TRANSACTIONS")
            );
        } catch (Exception e) {
            return new ConsentStatus(
                    accountId,
                    BankAccount.ConsentStatus.EXPIRED,
                    null,
                    List.of()
            );
        }
    }

    /**
     * Payment initiation — OBP supports SEPA in sandbox.
     * Returns pending status with SCA redirect URL.
     */
    @Override
    public PaymentInitiationResult initiatePayment(PaymentInitiationRequest request) {
        log.debug("[OBP] Initiating payment amount={} to={}", request.amount(), request.creditorIban());
        return new PaymentInitiationResult(
                "obp-payment-" + System.currentTimeMillis(),
                "PDNG",
                "https://apisandbox.openbankproject.com/obp/v5.0.0/payment/sca"
        );
    }

    // ── Mapping ────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private BankAccount mapToBankAccount(Map<String, Object> account,
                                         String bankId,
                                         TenantId tenantId) {
        String accountId = (String) account.get("id");
        String label = (String) account.getOrDefault("label", accountId);

        Map<String, Object> balance = (Map<String, Object>) account.get("balance");
        String currency = balance != null ? (String) balance.get("currency") : "EUR";
        String amount = balance != null ? (String) balance.get("amount") : "0.00";
        Money balanceMoney = Money.of(new BigDecimal(amount), currency);

        // Extract IBAN from account routings if available
        String iban = null;
        List<Map<String, Object>> routings =
                (List<Map<String, Object>>) account.get("account_routings");
        if (routings != null) {
            iban = routings.stream()
                    .filter(r -> "IBAN".equals(r.get("scheme")))
                    .map(r -> (String) r.get("address"))
                    .findFirst()
                    .orElse(null);
        }

        return new BankAccount(
                bankId + "/" + accountId,
                tenantId,
                iban,
                null,
                label != null ? label : accountId,
                bankId,
                null,
                BankAccount.AccountType.CURRENT,
                balanceMoney,
                balanceMoney,
                currency,
                "EU",
                BankAccount.ConsentStatus.VALID,
                Instant.now().plusSeconds(86400 * 90),
                Instant.now()
        );
    }
}