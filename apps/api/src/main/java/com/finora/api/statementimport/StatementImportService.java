package com.finora.api.statementimport;

import com.finora.api.account.Account;
import com.finora.api.account.AccountRepository;
import com.finora.api.account.AccountType;
import com.finora.api.category.Category;
import com.finora.api.category.CategoryRepository;
import com.finora.api.common.error.BusinessRuleException;
import com.finora.api.common.error.NotFoundException;
import com.finora.api.common.money.MoneyRules;
import com.finora.api.common.web.PageResponse;
import com.finora.api.identity.CurrentUserProvider;
import com.finora.api.statementimport.StatementImportDtos.AccountChangeRequest;
import com.finora.api.statementimport.StatementImportDtos.BatchDetailResponse;
import com.finora.api.statementimport.StatementImportDtos.BatchSummaryResponse;
import com.finora.api.statementimport.StatementImportDtos.CsvMappingRequest;
import com.finora.api.statementimport.StatementImportDtos.CsvMappingSuggestion;
import com.finora.api.statementimport.StatementImportDtos.ItemPatchRequest;
import com.finora.api.statementimport.StatementImportDtos.ItemResponse;
import com.finora.api.statementimport.StatementImportDtos.MappingPreviewResponse;
import com.finora.api.statementimport.parser.Fingerprints;
import com.finora.api.statementimport.parser.StatementEntry;
import com.finora.api.statementimport.parser.StatementLimits;
import com.finora.api.statementimport.parser.StatementParseException;
import com.finora.api.statementimport.parser.StatementParseResult;
import com.finora.api.statementimport.parser.TextNormalizer;
import com.finora.api.statementimport.parser.csv.CsvDecoder;
import com.finora.api.statementimport.parser.csv.CsvDelimiter;
import com.finora.api.statementimport.parser.csv.CsvEncoding;
import com.finora.api.statementimport.parser.csv.CsvMappingConfig;
import com.finora.api.statementimport.parser.csv.CsvStatementParser;
import com.finora.api.statementimport.parser.ofx.OfxStatementParser;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Upload, mapping, preview and pre-confirmation editing of statement
 * imports. Uploading never creates financial transactions — parsed rows are
 * previews until {@code StatementConfirmationService} materializes them.
 */
@Service
@Transactional
public class StatementImportService {

    static final int MAX_PAGE_SIZE = 50;
    private static final int MAPPING_PREVIEW_ROWS = 20;

    private final StatementImportBatchRepository batches;
    private final StatementImportItemRepository items;
    private final AccountRepository accounts;
    private final CategoryRepository categories;
    private final CategoryMappingRuleRepository rules;
    private final CategoryRuleEngine ruleEngine;
    private final DuplicateDetectionService duplicates;
    private final StatementImportAssembler assembler;
    private final TempStatementStore tempStore;
    private final CurrentUserProvider currentUser;
    private final ObjectMapper objectMapper;

    public StatementImportService(StatementImportBatchRepository batches,
                                  StatementImportItemRepository items,
                                  AccountRepository accounts,
                                  CategoryRepository categories,
                                  CategoryMappingRuleRepository rules,
                                  CategoryRuleEngine ruleEngine,
                                  DuplicateDetectionService duplicates,
                                  StatementImportAssembler assembler,
                                  TempStatementStore tempStore,
                                  CurrentUserProvider currentUser,
                                  ObjectMapper objectMapper) {
        this.batches = batches;
        this.items = items;
        this.accounts = accounts;
        this.categories = categories;
        this.rules = rules;
        this.ruleEngine = ruleEngine;
        this.duplicates = duplicates;
        this.assembler = assembler;
        this.tempStore = tempStore;
        this.currentUser = currentUser;
        this.objectMapper = objectMapper;
    }

    // ── Upload ──────────────────────────────────────────────────────────────

    public BatchDetailResponse upload(Long accountId, String originalFilename, byte[] content) {
        Long userId = currentUser.currentUserId();
        Account account = resolveDestination(userId, accountId);
        if (content == null || content.length == 0) {
            throw new BusinessRuleException("STATEMENT_FILE_EMPTY", "O arquivo enviado está vazio.");
        }
        if (content.length > StatementLimits.MAX_FILE_BYTES) {
            throw new BusinessRuleException("STATEMENT_FILE_TOO_LARGE",
                    "O arquivo excede o limite de 5 MB.");
        }
        String filename = sanitizeFilename(originalFilename);
        StatementImportFormat format = detectFormat(content, filename);

        if (format == StatementImportFormat.OFX) {
            StatementParseResult result = parseOfx(content);
            if (result.entries().isEmpty()) {
                throw new BusinessRuleException("STATEMENT_EMPTY",
                        "O arquivo não contém lançamentos para importar.");
            }
            StatementImportBatch batch = new StatementImportBatch(userId, account.getId(),
                    filename, StatementImportFormat.OFX, Fingerprints.fileSha256(content),
                    content.length, Fingerprints.PARSER_VERSION, Fingerprints.VERSION,
                    StatementImportStatus.PREVIEW_READY);
            batch.setTotalRows(result.entries().size());
            batch = batches.save(batch);
            materializePreviewItems(batch, result.entries());
            return detailInternal(batch, result.accountHint(), null, null);
        }

        // CSV: the raw bytes rest in bounded temporary storage only while the
        // user confirms the column mapping.
        CsvDecoder.rejectBinary(content);
        StatementImportBatch batch = new StatementImportBatch(userId, account.getId(),
                filename, StatementImportFormat.CSV, Fingerprints.fileSha256(content),
                content.length, Fingerprints.PARSER_VERSION, Fingerprints.VERSION,
                StatementImportStatus.NEEDS_MAPPING);
        batch.setTempFileToken(tempStore.store(content));
        batch = batches.save(batch);
        return detailInternal(batch, null, content, null);
    }

    // ── CSV mapping ─────────────────────────────────────────────────────────

    /** Stores the candidate mapping and returns a bounded parse preview. */
    public MappingPreviewResponse csvMapping(Long batchId, CsvMappingRequest request) {
        Long userId = currentUser.currentUserId();
        StatementImportBatch batch = find(batchId, userId);
        requireCsvWithRawFile(batch);
        CsvMappingConfig config = request.toConfig();
        byte[] content = rawFile(batch);
        StatementParseResult result = parseCsv(content, config);
        batch.setCsvMapping(writeMapping(request));

        List<StatementEntry> sample = result.entries().stream()
                .limit(MAPPING_PREVIEW_ROWS)
                .toList();
        int valid = (int) result.entries().stream().filter(StatementEntry::valid).count();
        return new MappingPreviewResponse(
                batch.getId(),
                sample.size(),
                valid,
                result.entries().size() - valid,
                sample.stream().map(entry -> new MappingPreviewResponse.ItemPreview(
                        entry.sourceIndex(),
                        entry.postedDate(),
                        entry.absoluteAmount(),
                        entry.type(),
                        entry.description(),
                        entry.memo(),
                        entry.externalId(),
                        entry.issues().isEmpty() ? null : entry.issues().getFirst().code(),
                        entry.issues().isEmpty() ? null : entry.issues().getFirst().message()))
                        .toList());
    }

    /** Authoritative parse with the stored mapping; discards the raw bytes. */
    public BatchDetailResponse reparse(Long batchId) {
        Long userId = currentUser.currentUserId();
        StatementImportBatch batch = find(batchId, userId);
        requireCsvWithRawFile(batch);
        if (batch.getCsvMapping() == null) {
            throw new BusinessRuleException("STATEMENT_MAPPING_MISSING",
                    "Configure o mapeamento das colunas antes de processar o arquivo.");
        }
        CsvMappingConfig config = readMapping(batch.getCsvMapping()).toConfig();
        byte[] content = rawFile(batch);
        StatementParseResult result = parseCsv(content, config);
        if (result.entries().isEmpty()) {
            throw new BusinessRuleException("STATEMENT_EMPTY",
                    "O arquivo não contém lançamentos para importar.");
        }
        // Replace any items from a previous mapping attempt (nothing was
        // imported yet — the raw file only exists before confirmation).
        items.deleteAll(items.findAllByBatchIdAndUserIdOrderBySourceIndexAsc(batch.getId(), userId));
        items.flush();
        batch.setTotalRows(result.entries().size());
        batch.setStatus(StatementImportStatus.PREVIEW_READY);
        materializePreviewItems(batch, result.entries());
        tempStore.discard(batch.getTempFileToken());
        batch.setTempFileToken(null);
        return detailInternal(batch, null, null, null);
    }

    // ── Destination account ─────────────────────────────────────────────────

    /** Account change before confirmation: reruns identity-sensitive state. */
    public BatchDetailResponse changeAccount(Long batchId, AccountChangeRequest request) {
        Long userId = currentUser.currentUserId();
        StatementImportBatch batch = find(batchId, userId);
        if (batch.getStatus() != StatementImportStatus.NEEDS_MAPPING
                && batch.getStatus() != StatementImportStatus.PREVIEW_READY) {
            throw new BusinessRuleException("STATEMENT_BATCH_LOCKED",
                    "A conta de destino não pode mudar após a confirmação da importação.");
        }
        Account account = resolveDestination(userId, request.accountId());
        batch.setAccountId(account.getId());
        List<StatementImportItem> batchItems =
                items.findAllByBatchIdAndUserIdOrderBySourceIndexAsc(batch.getId(), userId);
        for (StatementImportItem item : batchItems) {
            item.setAccountId(account.getId());
        }
        // Fingerprints embed the account: duplicates and account-scoped rules
        // must be recomputed, and old preview decisions re-derived.
        duplicates.classify(userId, account.getId(), batchItems);
        applySuggestions(userId, batchItems);
        return detailInternal(batch, null, null, batchItems);
    }

    // ── Item editing ────────────────────────────────────────────────────────

    public ItemResponse patchItem(Long batchId, Long itemId, ItemPatchRequest request) {
        Long userId = currentUser.currentUserId();
        StatementImportBatch batch = find(batchId, userId);
        if (batch.getStatus() != StatementImportStatus.PREVIEW_READY
                && batch.getStatus() != StatementImportStatus.PARTIALLY_COMPLETED) {
            throw new BusinessRuleException("STATEMENT_BATCH_LOCKED",
                    "Este lote não está em uma etapa editável.");
        }
        StatementImportItem item = items.findByIdAndUserId(itemId, userId)
                .filter(found -> found.getBatchId().equals(batch.getId()))
                .orElseThrow(() -> new NotFoundException("Lançamento do extrato", itemId));
        if (item.getStatus() == StatementImportItemStatus.IMPORTED
                || item.getStatus() == StatementImportItemStatus.UNDONE) {
            throw new BusinessRuleException("STATEMENT_ITEM_LOCKED",
                    "Um lançamento já importado (ou desfeito) não pode ser editado.");
        }

        boolean identityChanged = false;
        if (request.description() != null) {
            String description = TextNormalizer.truncate(
                    TextNormalizer.clean(request.description()),
                    StatementLimits.MAX_DESCRIPTION_LENGTH);
            if (description == null || description.isBlank()) {
                throw new BusinessRuleException("STATEMENT_ITEM_DESCRIPTION_BLANK",
                        "A descrição não pode ficar vazia.");
            }
            item.setDescription(description);
            item.setNormalizedDescription(TextNormalizer.truncate(
                    TextNormalizer.canonical(description), StatementLimits.MAX_DESCRIPTION_LENGTH));
            identityChanged = true;
        }
        if (request.postedDate() != null) {
            item.setPostedDate(request.postedDate());
            identityChanged = true;
        }
        if (request.amount() != null) {
            item.setAmount(MoneyRules.normalize(request.amount()));
            identityChanged = true;
        }
        if (request.type() != null && request.type() != item.getType()) {
            item.setType(request.type());
            // A direction change invalidates category decisions of the old type.
            item.setSuggestedCategoryId(null);
            item.setMatchedRuleId(null);
            if (item.getSelectedCategoryId() != null) {
                Category selected = categories.findByIdAndUserId(
                        item.getSelectedCategoryId(), userId).orElse(null);
                if (selected == null
                        || !selected.getType().name().equals(request.type().name())) {
                    item.setSelectedCategoryId(null);
                }
            }
            identityChanged = true;
        }
        if (request.selectedCategoryId() != null) {
            Category category = categories.findByIdAndUserId(request.selectedCategoryId(), userId)
                    .orElseThrow(() -> new NotFoundException("Categoria",
                            request.selectedCategoryId()));
            if (!category.isActive()) {
                throw new BusinessRuleException("STATEMENT_CATEGORY_INACTIVE",
                        "A categoria selecionada está desativada.");
            }
            if (item.getType() == null
                    || !category.getType().name().equals(item.getType().name())) {
                throw new BusinessRuleException("CATEGORY_TYPE_MISMATCH",
                        "A categoria selecionada não corresponde ao tipo do lançamento.");
            }
            item.setSelectedCategoryId(category.getId());
        }
        if (request.included() != null) {
            item.setIncluded(request.included());
        }
        if (request.duplicateOverride() != null) {
            if (request.duplicateOverride()
                    && item.getDuplicateStatus() == DuplicateStatus.EXACT_DUPLICATE) {
                throw new BusinessRuleException("STATEMENT_EXACT_DUPLICATE",
                        "Um lançamento com identidade já importada não pode ser importado de novo.");
            }
            item.setDuplicateOverride(request.duplicateOverride());
        }

        if (item.getStatus() == StatementImportItemStatus.INVALID && coreFieldsComplete(item)) {
            item.setStatus(StatementImportItemStatus.READY);
            item.setValidation(null, null);
        }
        List<StatementImportItem> batchItems =
                items.findAllByBatchIdAndUserIdOrderBySourceIndexAsc(batch.getId(), userId);
        if (identityChanged) {
            duplicates.classify(userId, batch.getAccountId(), batchItems);
            if (item.getSuggestedCategoryId() == null) {
                applySuggestions(userId, List.of(item));
            }
        }
        return assembler.toItemResponses(userId, List.of(item)).getFirst();
    }

    // ── History and detail ──────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<BatchSummaryResponse> history(Long accountId, int page, int size) {
        Long userId = currentUser.currentUserId();
        var pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        var result = accountId != null
                ? batches.findAllByUserIdAndAccountId(userId, accountId, pageable)
                : batches.findAllByUserId(userId, pageable);
        Map<Long, String> accountNames = accountNames(userId);
        return PageResponse.from(result.map(batch -> {
            long imported = items.countByBatchIdAndStatus(batch.getId(),
                    StatementImportItemStatus.IMPORTED);
            long failed = items.countByBatchIdAndStatus(batch.getId(),
                    StatementImportItemStatus.FAILED);
            return new BatchSummaryResponse(
                    batch.getId(), batch.getCreatedAt(), batch.getAccountId(),
                    accountNames.get(batch.getAccountId()), batch.getOriginalFilename(),
                    batch.getFormat(), batch.getStatus(), batch.getTotalRows(),
                    imported, failed, batch.getConfirmedAt(), batch.getUndoneAt());
        }));
    }

    @Transactional(readOnly = true)
    public BatchDetailResponse detail(Long batchId) {
        Long userId = currentUser.currentUserId();
        StatementImportBatch batch = find(batchId, userId);
        return detailInternal(batch, null, null, null);
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private BatchDetailResponse detailInternal(StatementImportBatch batch, String accountHint,
                                               byte[] uploadedContent,
                                               List<StatementImportItem> loadedItems) {
        Long userId = batch.getUserId();
        List<StatementImportItem> batchItems = loadedItems != null ? loadedItems
                : items.findAllByBatchIdAndUserIdOrderBySourceIndexAsc(batch.getId(), userId);
        String accountName = accounts.findByIdAndUserId(batch.getAccountId(), userId)
                .map(Account::getName).orElse(null);
        boolean reupload = batches.existsByUserIdAndAccountIdAndFileSha256AndIdNot(
                userId, batch.getAccountId(), batch.getFileSha256(), batch.getId());

        CsvMappingRequest mapping = batch.getCsvMapping() == null ? null
                : readMapping(batch.getCsvMapping());
        CsvMappingSuggestion suggestion = null;
        List<List<String>> rawPreview = null;
        if (batch.getStatus() == StatementImportStatus.NEEDS_MAPPING) {
            byte[] content = uploadedContent != null ? uploadedContent
                    : tempStore.read(batch.getTempFileToken()).orElse(null);
            if (content != null) {
                CsvEncoding encoding = mapping != null ? mapping.encoding()
                        : CsvDecoder.detectEncoding(content);
                String text = CsvDecoder.decode(content, encoding);
                CsvDelimiter delimiter = mapping != null ? mapping.delimiter()
                        : CsvDecoder.detectDelimiter(text);
                rawPreview = CsvStatementParser.rawPreview(content, encoding, delimiter,
                        MAPPING_PREVIEW_ROWS);
                suggestion = new CsvMappingSuggestion(encoding, delimiter,
                        guessHeader(rawPreview),
                        CsvMappingConfig.DATE_PATTERN_OPTIONS);
            }
        }
        return assembler.detail(batch, accountName, batchItems, accountHint, reupload,
                mapping, suggestion, rawPreview);
    }

    /** Persists parsed entries as preview items, classifies and suggests. */
    private void materializePreviewItems(StatementImportBatch batch,
                                         List<StatementEntry> entries) {
        Long userId = batch.getUserId();
        List<StatementImportItem> created = entries.stream()
                .map(entry -> toItem(batch, entry))
                .toList();
        items.saveAll(created);
        items.flush();
        duplicates.classify(userId, batch.getAccountId(), created);
        applySuggestions(userId, created);
    }

    private StatementImportItem toItem(StatementImportBatch batch, StatementEntry entry) {
        StatementImportItem item = new StatementImportItem(batch.getUserId(), batch.getId(),
                batch.getAccountId(), entry.sourceIndex(),
                entry.valid() ? StatementImportItemStatus.READY
                        : StatementImportItemStatus.INVALID);
        item.setExternalId(entry.externalId());
        item.setSourceType(entry.sourceType());
        item.setPostedDate(entry.postedDate());
        item.setAmount(entry.absoluteAmount());
        item.setType(entry.type());
        item.setDescription(entry.description());
        item.setNormalizedDescription(entry.normalizedDescription());
        item.setMemo(entry.memo());
        item.setOriginalDate(entry.postedDate());
        item.setOriginalAmount(entry.absoluteAmount());
        item.setOriginalType(entry.type());
        item.setOriginalDescription(TextNormalizer.truncate(entry.description(), 500));
        if (!entry.valid()) {
            var issue = entry.issues().getFirst();
            item.setValidation(issue.code(), issue.message());
            item.setIncluded(false);
        }
        return item;
    }

    /** One rule load per call; suggestions never overwrite a user selection. */
    private void applySuggestions(Long userId, List<StatementImportItem> batchItems) {
        List<CategoryMappingRule> active = rules.findAllByUserIdAndActiveTrue(userId);
        Map<Long, Category> categoryById = new java.util.HashMap<>();
        for (Category category : categories.findAllByUserIdOrderByTypeAscNameAsc(userId)) {
            categoryById.put(category.getId(), category);
        }
        for (StatementImportItem item : batchItems) {
            if (item.getStatus() != StatementImportItemStatus.READY) {
                continue;
            }
            CategoryRuleEngine.Match match = active.isEmpty() ? null
                    : ruleEngine.bestMatch(item, active);
            if (match != null) {
                Category category = categoryById.get(match.rule().getCategoryId());
                boolean usable = category != null && category.isActive()
                        && category.getType().name().equals(item.getType().name());
                item.setSuggestedCategoryId(usable ? category.getId() : null);
                item.setMatchedRuleId(usable ? match.rule().getId() : null);
            } else {
                item.setSuggestedCategoryId(null);
                item.setMatchedRuleId(null);
            }
            if (item.getSelectedCategoryId() == null && item.getSuggestedCategoryId() != null) {
                item.setSelectedCategoryId(item.getSuggestedCategoryId());
            }
        }
    }

    private Account resolveDestination(Long userId, Long accountId) {
        Account account = accounts.findByIdAndUserId(accountId, userId)
                .orElseThrow(() -> new NotFoundException("Conta", accountId));
        if (account.isArchived()) {
            throw new BusinessRuleException("STATEMENT_ACCOUNT_ARCHIVED",
                    "A conta de destino está arquivada.");
        }
        if (account.getType() != AccountType.CHECKING && account.getType() != AccountType.SAVINGS) {
            throw new BusinessRuleException("STATEMENT_ACCOUNT_TYPE",
                    "Extratos bancários só podem ser importados em contas corrente ou poupança.");
        }
        return account;
    }

    private StatementImportBatch find(Long batchId, Long userId) {
        return batches.findByIdAndUserId(batchId, userId)
                .orElseThrow(() -> new NotFoundException("Importação de extrato", batchId));
    }

    private void requireCsvWithRawFile(StatementImportBatch batch) {
        if (batch.getFormat() != StatementImportFormat.CSV) {
            throw new BusinessRuleException("STATEMENT_NOT_CSV",
                    "Apenas arquivos CSV possuem mapeamento de colunas.");
        }
        if (batch.getStatus() != StatementImportStatus.NEEDS_MAPPING) {
            throw new BusinessRuleException("STATEMENT_FILE_DISCARDED",
                    "O arquivo original já foi processado e descartado. Para alterar o "
                            + "mapeamento, envie o arquivo novamente.");
        }
        if (tempStore.read(batch.getTempFileToken()).isEmpty()) {
            throw new BusinessRuleException("STATEMENT_FILE_EXPIRED",
                    "O arquivo temporário expirou. Envie o arquivo novamente.");
        }
    }

    private byte[] rawFile(StatementImportBatch batch) {
        return tempStore.read(batch.getTempFileToken())
                .orElseThrow(() -> new BusinessRuleException("STATEMENT_FILE_EXPIRED",
                        "O arquivo temporário expirou. Envie o arquivo novamente."));
    }

    private static StatementParseResult parseOfx(byte[] content) {
        try {
            return OfxStatementParser.parse(content);
        } catch (StatementParseException e) {
            throw new BusinessRuleException(e.getCode(), e.getMessage());
        }
    }

    private static StatementParseResult parseCsv(byte[] content, CsvMappingConfig config) {
        try {
            return CsvStatementParser.parse(content, config);
        } catch (StatementParseException e) {
            throw new BusinessRuleException(e.getCode(), e.getMessage());
        }
    }

    private static boolean coreFieldsComplete(StatementImportItem item) {
        return item.getPostedDate() != null && item.getAmount() != null
                && item.getAmount().signum() > 0 && item.getType() != null
                && item.getDescription() != null && !item.getDescription().isBlank();
    }

    /** Data-free first row (no parseable date/number cell) suggests a header. */
    private static boolean guessHeader(List<List<String>> rawPreview) {
        if (rawPreview == null || rawPreview.isEmpty()) {
            return false;
        }
        for (String cell : rawPreview.getFirst()) {
            if (cell == null || cell.isBlank()) {
                continue;
            }
            String value = cell.strip().replace("R$", "").replace(".", "")
                    .replace(",", ".").replace("/", "").replace("-", "");
            if (value.matches("[+]?\\d+(\\.\\d+)?")) {
                return false;
            }
        }
        return true;
    }

    private String writeMapping(CsvMappingRequest request) {
        return objectMapper.writeValueAsString(request);
    }

    private CsvMappingRequest readMapping(String json) {
        return objectMapper.readValue(json, CsvMappingRequest.class);
    }

    private Map<Long, String> accountNames(Long userId) {
        Map<Long, String> names = new java.util.HashMap<>();
        for (Account account : accounts.findAllByUserIdOrderByDisplayOrderAscNameAsc(userId)) {
            names.put(account.getId(), account.getName());
        }
        return names;
    }

    private static StatementImportFormat detectFormat(byte[] content, String filename) {
        int probeLength = Math.min(content.length, 4096);
        String head = new String(content, 0, probeLength,
                java.nio.charset.StandardCharsets.ISO_8859_1).toUpperCase(Locale.ROOT);
        if (head.contains("OFXHEADER") || head.contains("<OFX")) {
            return StatementImportFormat.OFX;
        }
        if (filename.toLowerCase(Locale.ROOT).endsWith(".ofx")) {
            return StatementImportFormat.OFX;
        }
        return StatementImportFormat.CSV;
    }

    private static String sanitizeFilename(String raw) {
        if (raw == null || raw.isBlank()) {
            return "extrato";
        }
        String name = raw;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        String cleaned = TextNormalizer.clean(name);
        if (cleaned == null || cleaned.isBlank()) {
            return "extrato";
        }
        return TextNormalizer.truncate(cleaned, StatementLimits.MAX_FILENAME_LENGTH);
    }
}
