package com.finora.api.creditcard.installment;

public enum InstallmentStatus {
    ACTIVE,
    /** Cancelled with its purchase: excluded from invoice totals, budgets and limit. */
    CANCELLED
}
