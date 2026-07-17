package com.finora.api.legacyconversion;

import com.finora.api.common.error.BusinessRuleException;
import com.finora.api.common.error.NotFoundException;
import com.finora.api.identity.CurrentUserProvider;
import com.finora.api.legacyconversion.LegacyConversionDtos.BatchConversionResponse;
import com.finora.api.legacyconversion.LegacyConversionDtos.BatchItemResult;
import com.finora.api.legacyconversion.LegacyConversionDtos.BatchItemStatus;
import com.finora.api.legacyconversion.LegacyConversionPreviewService.PreviewInput;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Assisted batch conversion. Every item carries its own card, effective date,
 * installment count and confirmed first invoice — nothing is assumed to be
 * shared. Items are processed independently, each in its own transaction
 * (the engine runs REQUIRES_NEW): one invalid item never rolls back another's
 * success, and the response carries exactly one result per input item, in
 * input order. Retrying the same batch is idempotent — already converted
 * sources report {@code ALREADY_CONVERTED} instead of converting twice.
 *
 * <p>Deliberately <em>not</em> transactional at this level: a batch never
 * holds one giant transaction.
 */
@Service
public class LegacyConversionBatchService {

    static final int MAX_BATCH_SIZE = 50;

    private static final Logger log = LoggerFactory.getLogger(LegacyConversionBatchService.class);

    private final LegacyConversionEngine engine;
    private final LegacyConversionRepository conversions;
    private final CurrentUserProvider currentUser;

    public LegacyConversionBatchService(LegacyConversionEngine engine,
                                        LegacyConversionRepository conversions,
                                        CurrentUserProvider currentUser) {
        this.engine = engine;
        this.conversions = conversions;
        this.currentUser = currentUser;
    }

    public BatchConversionResponse convertAll(List<PreviewInput> items) {
        Long userId = currentUser.currentUserId();
        if (items.size() > MAX_BATCH_SIZE) {
            throw new BusinessRuleException("BATCH_TOO_LARGE",
                    "Converta no máximo %d transações por lote.".formatted(MAX_BATCH_SIZE));
        }

        Set<Long> seen = new HashSet<>();
        List<BatchItemResult> results = new ArrayList<>(items.size());
        for (PreviewInput item : items) {
            if (!seen.add(item.transactionId())) {
                results.add(new BatchItemResult(item.transactionId(), BatchItemStatus.SKIPPED,
                        null, null, "DUPLICATE_SOURCE",
                        "Transação repetida no lote; apenas a primeira ocorrência foi processada."));
                continue;
            }
            results.add(convertOne(userId, item));
        }

        return new BatchConversionResponse(
                results.size(),
                (int) results.stream().filter(r -> r.status() == BatchItemStatus.SUCCESS).count(),
                (int) results.stream()
                        .filter(r -> r.status() == BatchItemStatus.ALREADY_CONVERTED).count(),
                (int) results.stream().filter(r -> r.status() == BatchItemStatus.FAILED).count(),
                (int) results.stream().filter(r -> r.status() == BatchItemStatus.SKIPPED).count(),
                List.copyOf(results));
    }

    private BatchItemResult convertOne(Long userId, PreviewInput item) {
        // Reported separately from SUCCESS so a retried batch reads honestly;
        // the engine's locked re-check stays the real idempotency guarantee.
        var existing = conversions.findBySourceTransactionIdAndStatus(
                item.transactionId(), ConversionStatus.ACTIVE);
        if (existing.isPresent() && existing.get().getUserId().equals(userId)) {
            LegacyCreditConversion conversion = existing.get();
            return new BatchItemResult(item.transactionId(), BatchItemStatus.ALREADY_CONVERTED,
                    conversion.getId(), conversion.getCardPurchaseId(), null,
                    "Esta transação já foi convertida anteriormente.");
        }
        try {
            LegacyCreditConversion conversion = engine.convert(userId, item);
            return new BatchItemResult(item.transactionId(), BatchItemStatus.SUCCESS,
                    conversion.getId(), conversion.getCardPurchaseId(), null,
                    "Conversão concluída.");
        } catch (BusinessRuleException failure) {
            return new BatchItemResult(item.transactionId(), BatchItemStatus.FAILED,
                    null, null, failure.getCode(), failure.getMessage());
        } catch (NotFoundException absent) {
            return new BatchItemResult(item.transactionId(), BatchItemStatus.FAILED,
                    null, null, "NOT_FOUND", "Transação ou cartão não encontrado.");
        } catch (RuntimeException unexpected) {
            // Never leak internals into the per-item message.
            log.error("Batch conversion of transaction {} failed unexpectedly",
                    item.transactionId(), unexpected);
            return new BatchItemResult(item.transactionId(), BatchItemStatus.FAILED,
                    null, null, "CONVERSION_FAILED",
                    "Não foi possível converter esta transação. Tente novamente.");
        }
    }
}
