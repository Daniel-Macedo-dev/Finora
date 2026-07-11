package com.finora.api.creditcard.purchase;

public enum PurchaseStatus {
    ACTIVE,
    /** Cancelled before settlement: installments stop counting, limit is released. */
    CANCELLED
}
