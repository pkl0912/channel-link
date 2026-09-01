package com.channellink.domain.model;

/**
 * 요금
 */
public record Money(long amount, String currency) {

    public Money {
        if (amount < 0) {
            throw new IllegalArgumentException("amount must not be negative: " + amount);
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("currency must not be blank");
        }
    }
}
