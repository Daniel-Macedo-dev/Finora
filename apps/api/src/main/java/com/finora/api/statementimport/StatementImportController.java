package com.finora.api.statementimport;

import com.finora.api.common.web.PageResponse;
import com.finora.api.statementimport.StatementImportDtos.AccountChangeRequest;
import com.finora.api.statementimport.StatementImportDtos.BatchDetailResponse;
import com.finora.api.statementimport.StatementImportDtos.BatchSummaryResponse;
import com.finora.api.statementimport.StatementImportDtos.ConfirmRequest;
import com.finora.api.statementimport.StatementImportDtos.ConfirmResponse;
import com.finora.api.statementimport.StatementImportDtos.CsvMappingRequest;
import com.finora.api.statementimport.StatementImportDtos.ItemPatchRequest;
import com.finora.api.statementimport.StatementImportDtos.ItemResponse;
import com.finora.api.statementimport.StatementImportDtos.MappingPreviewResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Owner-scoped statement-import API. Authentication, CSRF and Problem
 * Details follow the application-wide security configuration; another
 * user's batch, item, account or category behaves as absent (404).
 */
@RestController
@RequestMapping("/api/statement-imports")
public class StatementImportController {

    private final StatementImportService imports;
    private final StatementConfirmationService confirmation;
    private final StatementUndoService undo;

    public StatementImportController(StatementImportService imports,
                                     StatementConfirmationService confirmation,
                                     StatementUndoService undo) {
        this.imports = imports;
        this.confirmation = confirmation;
        this.undo = undo;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BatchDetailResponse upload(@RequestPart("file") MultipartFile file,
                                      @RequestParam("accountId") Long accountId) {
        try {
            return imports.upload(accountId, file.getOriginalFilename(), file.getBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @GetMapping
    public PageResponse<BatchSummaryResponse> history(
            @RequestParam(required = false) Long accountId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return imports.history(accountId, page, size);
    }

    @GetMapping("/{id}")
    public BatchDetailResponse detail(@PathVariable Long id) {
        return imports.detail(id);
    }

    @PutMapping("/{id}/csv-mapping")
    public MappingPreviewResponse csvMapping(@PathVariable Long id,
                                             @Valid @RequestBody CsvMappingRequest request) {
        return imports.csvMapping(id, request);
    }

    @PostMapping("/{id}/reparse")
    public BatchDetailResponse reparse(@PathVariable Long id) {
        return imports.reparse(id);
    }

    @PatchMapping("/{id}")
    public BatchDetailResponse changeAccount(@PathVariable Long id,
                                             @Valid @RequestBody AccountChangeRequest request) {
        return imports.changeAccount(id, request);
    }

    @PatchMapping("/{id}/items/{itemId}")
    public ItemResponse patchItem(@PathVariable Long id, @PathVariable Long itemId,
                                  @Valid @RequestBody ItemPatchRequest request) {
        return imports.patchItem(id, itemId, request);
    }

    @PostMapping("/{id}/confirm")
    public ConfirmResponse confirm(@PathVariable Long id,
                                   @RequestBody(required = false) ConfirmRequest request) {
        return confirmation.confirm(id, request);
    }

    @PostMapping("/{id}/undo")
    public ConfirmResponse undoBatch(@PathVariable Long id) {
        return undo.undoBatch(id);
    }

    @PostMapping("/{id}/items/{itemId}/undo")
    public ConfirmResponse undoItem(@PathVariable Long id, @PathVariable Long itemId) {
        return undo.undoItem(id, itemId);
    }
}
