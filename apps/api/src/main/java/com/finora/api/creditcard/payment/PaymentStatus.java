package com.finora.api.creditcard.payment;

public enum PaymentStatus {
    COMPLETED,
    /** Reversed payments stay in history; their financial effects are undone. */
    REVERSED
}
