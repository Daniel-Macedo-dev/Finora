package com.finora.api.creditcard.adjustment;

/**
 * Kinds of invoice adjustment. Debit kinds increase the invoice total and are
 * expenses (they require an expense category and count toward budgets);
 * credit kinds reduce the invoice total.
 */
public enum AdjustmentKind {
    FEE,
    INTEREST,
    OTHER_DEBIT,
    CREDIT,
    REFUND;

    public boolean isDebit() {
        return this == FEE || this == INTEREST || this == OTHER_DEBIT;
    }
}
