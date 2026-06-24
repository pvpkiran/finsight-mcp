package com.finsight.adapter.mock.banking;

import com.finsight.core.domain.model.BankAccount;
import com.finsight.core.domain.valueobject.Money;
import com.finsight.core.domain.valueobject.TenantId;
import com.finsight.core.port.OpenBankingPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * In-memory mock open banking adapter.
 * Pre-seeded with German and European banks for realistic demo.
 * Implements Berlin Group / PSD2 account model.
 */
@Component
@Profile("mock")
public class MockOpenBankingAdapter implements OpenBankingPort {

    private static final Map<String, BankAccount> ACCOUNTS = Map.of(
            "acc-de-001", new BankAccount(
                    "acc-de-001", TenantId.of("tenant-demo-001"),
                    "DE89370400440532013000", "370400440532013000",
                    "Max Mustermann", "Deutsche Bank", "DEUTDEDB",
                    BankAccount.AccountType.CURRENT,
                    Money.ofEur(new BigDecimal("4250.00")),
                    Money.ofEur(new BigDecimal("4100.00")),
                    "EUR", "DE",
                    BankAccount.ConsentStatus.VALID,
                    Instant.now().plus(89, ChronoUnit.DAYS),
                    Instant.now().minus(1, ChronoUnit.HOURS)
            ),
            "acc-de-002", new BankAccount(
                    "acc-de-002", TenantId.of("tenant-demo-001"),
                    "DE75512108001245126199", "512108001245126199",
                    "Max Mustermann Savings", "Sparkasse München", "SSKMDEMMXXX",
                    BankAccount.AccountType.SAVINGS,
                    Money.ofEur(new BigDecimal("12800.00")),
                    Money.ofEur(new BigDecimal("12800.00")),
                    "EUR", "DE",
                    BankAccount.ConsentStatus.VALID,
                    Instant.now().plus(89, ChronoUnit.DAYS),
                    Instant.now().minus(2, ChronoUnit.HOURS)
            ),
            "acc-gb-001", new BankAccount(
                    "acc-gb-001", TenantId.of("tenant-demo-001"),
                    "GB29NWBK60161331926819", null,
                    "M Mustermann", "NatWest", "NWBKGB2L",
                    BankAccount.AccountType.CURRENT,
                    Money.of(new BigDecimal("1875.50"), "GBP"),
                    Money.of(new BigDecimal("1800.00"), "GBP"),
                    "GBP", "GB",
                    BankAccount.ConsentStatus.EXPIRED,
                    Instant.now().minus(5, ChronoUnit.DAYS),
                    Instant.now().minus(10, ChronoUnit.DAYS)
            )
    );

    private static final List<BankInfo> GERMAN_BANKS = List.of(
            new BankInfo("DEUTDEDB", "Deutsche Bank", "DEUTDEDB", "DE", "https://logo.clearbit.com/deutsche-bank.de", 90),
            new BankInfo("SSKMDEMMXXX", "Sparkasse", "SSKMDEMMXXX", "DE", "https://logo.clearbit.com/sparkasse.de", 90),
            new BankInfo("COBADEFFXXX", "Commerzbank", "COBADEFFXXX", "DE", "https://logo.clearbit.com/commerzbank.de", 90),
            new BankInfo("SSKMDEMMXXX", "ING Germany", "INGDDEFFXXX", "DE", "https://logo.clearbit.com/ing.de", 90),
            new BankInfo("BELADEBEXXX", "Berliner Sparkasse", "BELADEBEXXX", "DE", "https://logo.clearbit.com/berliner-sparkasse.de", 90)
    );

    @Override
    public List<BankInfo> listAvailableBanks(String countryCode) {
        if ("DE".equalsIgnoreCase(countryCode)) return GERMAN_BANKS;
        // Return a minimal set for other countries in mock
        return List.of(
                new BankInfo("NWBKGB2L", "NatWest", "NWBKGB2L", "GB", "", 90),
                new BankInfo("BARCGB22", "Barclays", "BARCGB22", "GB", "", 90)
        );
    }

    @Override
    public BankAccount fetchAccount(String accountId, TenantId tenantId) {
        BankAccount account = ACCOUNTS.get(accountId);
        if (account == null) {
            throw new com.finsight.core.exception.FinsightDomainException(
                    "ACCOUNT_NOT_FOUND", "Account not found: " + accountId);
        }
        return account;
    }

    @Override
    public List<BankAccount> fetchAllAccounts(String requisitionId, TenantId tenantId) {
        // In mock, requisition "req-demo-001" returns the two German accounts
        if ("req-demo-001".equals(requisitionId)) {
            return List.of(ACCOUNTS.get("acc-de-001"), ACCOUNTS.get("acc-de-002"));
        }
        return List.of();
    }

    @Override
    public ConsentStatus checkConsent(String accountId, TenantId tenantId) {
        BankAccount account = ACCOUNTS.get(accountId);
        if (account == null) {
            return new ConsentStatus(accountId, BankAccount.ConsentStatus.PENDING, null, List.of());
        }
        return new ConsentStatus(
                accountId,
                account.consentStatus(),
                account.consentExpiresAt(),
                List.of("DETAILS", "BALANCES", "TRANSACTIONS")
        );
    }

    @Override
    public PaymentInitiationResult initiatePayment(PaymentInitiationRequest request) {
        // Mock always returns ACCP (AcceptedCustomerProfile) for demo
        return new PaymentInitiationResult(
                "pay-mock-" + System.currentTimeMillis(),
                "ACCP",
                "https://mock-bank.finsight.local/sca?ref=demo"
        );
    }
}