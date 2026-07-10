package com.finora.api.insight;

import java.math.BigDecimal;
import java.util.List;
import java.time.YearMonth;

public final class InsightDtos {

    private InsightDtos() {
    }

    public enum InsightSeverity {
        POSITIVE,
        INFO,
        WARNING,
        CRITICAL
    }

    public record Insight(
            String type,
            InsightSeverity severity,
            String title,
            String message,
            /** Main figure behind the insight, when there is one. */
            BigDecimal amount) {
    }

    public record InsightsResponse(
            YearMonth month,
            List<Insight> insights) {
    }
}
