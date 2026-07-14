package com.finora.api.commitment.occurrence;

import com.finora.api.commitment.occurrence.OccurrenceDtos.OccurrencePreviewResponse;
import com.finora.api.commitment.occurrence.OccurrenceDtos.OccurrenceResponse;
import com.finora.api.commitment.occurrence.OccurrenceDtos.ProcessDueResponse;
import com.finora.api.commitment.occurrence.OccurrenceDtos.RescheduleRequest;
import com.finora.api.common.web.PageResponse;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Occurrence lifecycle. An occurrence is addressed by its stable identity —
 * the definition id plus the original scheduled date — so actions are
 * idempotent across retries and never depend on a pre-persisted row.
 */
@RestController
@RequestMapping("/api/commitments")
public class OccurrenceController {

    private final OccurrenceService service;

    public OccurrenceController(OccurrenceService service) {
        this.service = service;
    }

    @GetMapping("/{id}/occurrences")
    public OccurrencePreviewResponse preview(
            @PathVariable Long id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.preview(id, from, to);
    }

    @GetMapping("/{id}/occurrences/history")
    public PageResponse<OccurrenceResponse> history(@PathVariable Long id,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        return service.history(id, page, size);
    }

    @PostMapping("/{id}/occurrences/{date}/materialize")
    public OccurrenceResponse materialize(
            @PathVariable Long id,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.materializeManually(id, date);
    }

    /** Retry shares the materialization path; FAILED occurrences are eligible. */
    @PostMapping("/{id}/occurrences/{date}/retry")
    public OccurrenceResponse retry(
            @PathVariable Long id,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.materializeManually(id, date);
    }

    @PostMapping("/{id}/occurrences/{date}/skip")
    public OccurrenceResponse skip(
            @PathVariable Long id,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.skip(id, date);
    }

    @PostMapping("/{id}/occurrences/{date}/unskip")
    public OccurrenceResponse unskip(
            @PathVariable Long id,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.unskip(id, date);
    }

    @PostMapping("/{id}/occurrences/{date}/reschedule")
    public OccurrenceResponse reschedule(
            @PathVariable Long id,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Valid @RequestBody RescheduleRequest request) {
        return service.reschedule(id, date, request.newDate());
    }

    @PostMapping("/{id}/occurrences/{date}/reverse")
    public OccurrenceResponse reverse(
            @PathVariable Long id,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.reverse(id, date);
    }

    /** Deterministic processing trigger for the authenticated user. */
    @PostMapping("/process-due")
    public ProcessDueResponse processDue() {
        return service.processDueForCurrentUser();
    }
}
