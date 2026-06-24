package com.finsight.core.domain.valueobject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * Immutable money value object.
 * Always stores amount with the currency's default fraction digits.
 * Never use double/float for money — always BigDecimal.
 */
public record Money(BigDecimal amount, Currency currency) {

    public Money {
        Objects.requireNonNull(amount, "Amount must not be null");
        Objects.requireNonNull(currency, "Currency must not be null");
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Amount must not be negative: " + amount);
        }
        // Normalise to currency's fraction digits (e.g. EUR=2, JPY=0)
        amount = amount.setScale(currency.getDefaultFractionDigits(), RoundingMode.HALF_EVEN);
    }

    public static Money of(BigDecimal amount, String currencyCode) {
        return new Money(amount, Currency.getInstance(currencyCode));
    }

    public static Money of(String amount, String currencyCode) {
        return of(new BigDecimal(amount), currencyCode);
    }

    public static Money ofEur(BigDecimal amount) {
        return of(amount, "EUR");
    }

    public Money add(Money other) {
        assertSameCurrency(other);
        return new Money(this.amount.add(other.amount), this.currency);
    }

    public Money subtract(Money other) {
        assertSameCurrency(other);
        BigDecimal result = this.amount.subtract(other.amount);
        if (result.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Subtraction would result in negative money");
        }
        return new Money(result, this.currency);
    }

    public boolean isGreaterThan(Money other) {
        assertSameCurrency(other);
        return this.amount.compareTo(other.amount) > 0;
    }

    public String currencyCode() {
        return currency.getCurrencyCode();
    }

    private void assertSameCurrency(Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Currency mismatch: %s vs %s".formatted(this.currency, other.currency));
        }
    }

    @Override
    public String toString() {
        return "%s %s".formatted(amount.toPlainString(), currency.getCurrencyCode());
    }
}
