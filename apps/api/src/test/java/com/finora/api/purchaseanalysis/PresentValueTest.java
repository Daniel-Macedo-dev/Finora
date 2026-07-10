package com.finora.api.purchaseanalysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.api.wishlist.PurchaseOption;
import com.finora.api.wishlist.PurchaseOptionKind;
import com.finora.api.wishlist.WishlistItem;
import com.finora.api.wishlist.WishlistPriority;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PresentValueTest {

    private final WishlistItem item = new WishlistItem("Notebook", WishlistPriority.MEDIUM);

    private PurchaseOption cash(String price, String shipping) {
        return new PurchaseOption(item, "Loja A", PurchaseOptionKind.CASH,
                new BigDecimal(price), new BigDecimal(shipping), BigDecimal.ZERO);
    }

    private PurchaseOption installments(String total, int count, String each, String shipping) {
        PurchaseOption option = new PurchaseOption(item, "Loja B", PurchaseOptionKind.INSTALLMENT,
                new BigDecimal(total), new BigDecimal(shipping), BigDecimal.ZERO);
        option.setInstallmentCount(count);
        option.setInstallmentAmount(new BigDecimal(each));
        return option;
    }

    @Test
    void cashPresentValueIsNominalCost() {
        assertThat(PurchaseAnalysisService.presentValue(cash("1000.00", "50.00"), new BigDecimal("0.01")))
                .isEqualByComparingTo("1050.00");
    }

    @Test
    void zeroRateDegradesToNominalComparison() {
        PurchaseOption option = installments("1200.00", 12, "100.00", "0");
        assertThat(PurchaseAnalysisService.presentValue(option, BigDecimal.ZERO))
                .isEqualByComparingTo("1200.00");
    }

    @Test
    void positiveRateDiscountsInstallments() {
        // 12 x 100 at 1% monthly: PV = 100 * annuity(12, 1%) = 100 * 11.255077 = 1125.51
        PurchaseOption option = installments("1200.00", 12, "100.00", "0");
        assertThat(PurchaseAnalysisService.presentValue(option, new BigDecimal("0.01")))
                .isEqualByComparingTo("1125.51");
    }

    @Test
    void shippingAndFeesStayAtFaceValue() {
        PurchaseOption option = installments("1200.00", 12, "100.00", "80.00");
        assertThat(PurchaseAnalysisService.presentValue(option, new BigDecimal("0.01")))
                .isEqualByComparingTo("1205.51");
    }

    @Test
    void singleInstallmentDiscountsOnePeriod() {
        // 1 x 101 at 1%: PV = 101 / 1.01 = 100.00
        PurchaseOption option = installments("101.00", 1, "101.00", "0");
        assertThat(PurchaseAnalysisService.presentValue(option, new BigDecimal("0.01")))
                .isEqualByComparingTo("100.00");
    }
}
