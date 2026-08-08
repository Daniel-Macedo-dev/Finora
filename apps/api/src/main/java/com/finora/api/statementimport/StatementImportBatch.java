package com.finora.api.statementimport;

import com.finora.api.common.money.CurrencyCode;
import com.finora.api.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * One statement upload: destination account, sanitized filename, format,
 * file hash, parser/fingerprint versions and currency provenance. Raw uploaded
 * bytes are never persisted — a CSV waiting for column mapping keeps them only
 * in bounded temporary storage referenced by {@link #getTempFileToken()}.
 *
 * <p>Because the raw file is discarded, what the parser learned about the
 * source's currency cannot be recovered later by re-reading it. That is why
 * {@link #getCurrencySource()} and {@link #getDeclaredCurrency()} are durable
 * batch state rather than a transient parse result: after upload they are the
 * only surviving evidence of how the file was denominated.
 */
@Entity
@Table(name = "statement_import_batches")
public class StatementImportBatch extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private StatementImportFormat format;

    @Column(name = "file_sha256", nullable = false, length = 64, updatable = false)
    private String fileSha256;

    @Column(name = "file_size_bytes", nullable = false, updatable = false)
    private long fileSizeBytes;

    @Column(name = "parser_version", nullable = false, updatable = false)
    private int parserVersion;

    @Column(name = "fingerprint_version", nullable = false, updatable = false)
    private int fingerprintVersion;

    /**
     * How confidently Finora knows the source's denomination. Insert-only: the
     * provenance of a parse cannot change after the file is gone, and a later
     * account change alters the effective currency without altering what the
     * file said.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "currency_source", nullable = false, length = 20, updatable = false)
    private StatementCurrencySource currencySource;

    /**
     * The currency the file itself declared, present only for
     * {@link StatementCurrencySource#FILE}. Never inferred, never assumed.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "declared_currency", length = 3, updatable = false)
    private CurrencyCode declaredCurrency;

    /** User-confirmed CSV column/locale mapping, serialized as bounded JSON. */
    @Column(name = "csv_mapping", length = 2000)
    private String csvMapping;

    /**
     * Random name of the bounded temporary file kept only while a CSV batch
     * waits for column mapping; cleared once the authoritative parse discards
     * the raw bytes.
     */
    @Column(name = "temp_file_token", length = 64)
    private String tempFileToken;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StatementImportStatus status;

    @Column(name = "total_rows", nullable = false)
    private int totalRows;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "undone_at")
    private Instant undoneAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected StatementImportBatch() {
    }

    /**
     * @throws IllegalArgumentException when the currency source and the
     *     declared currency disagree — a {@code FILE} batch without the code it
     *     read, or a declared code under a source that cannot have read one.
     *     The same pairing is enforced by a database CHECK constraint.
     */
    public StatementImportBatch(Long userId, Long accountId, String originalFilename,
                                StatementImportFormat format, String fileSha256,
                                long fileSizeBytes, int parserVersion, int fingerprintVersion,
                                StatementCurrencySource currencySource,
                                CurrencyCode declaredCurrency,
                                StatementImportStatus status) {
        if (currencySource.declaresFileCurrency() != (declaredCurrency != null)) {
            throw new IllegalArgumentException(
                    "Origem de moeda %s é incompatível com a moeda declarada %s"
                            .formatted(currencySource, declaredCurrency));
        }
        this.userId = userId;
        this.accountId = accountId;
        this.originalFilename = originalFilename;
        this.format = format;
        this.fileSha256 = fileSha256;
        this.fileSizeBytes = fileSizeBytes;
        this.parserVersion = parserVersion;
        this.fingerprintVersion = fingerprintVersion;
        this.currencySource = currencySource;
        this.declaredCurrency = declaredCurrency;
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public StatementImportFormat getFormat() {
        return format;
    }

    public String getFileSha256() {
        return fileSha256;
    }

    public long getFileSizeBytes() {
        return fileSizeBytes;
    }

    public int getParserVersion() {
        return parserVersion;
    }

    public int getFingerprintVersion() {
        return fingerprintVersion;
    }

    public StatementCurrencySource getCurrencySource() {
        return currencySource;
    }

    public CurrencyCode getDeclaredCurrency() {
        return declaredCurrency;
    }

    public String getCsvMapping() {
        return csvMapping;
    }

    public void setCsvMapping(String csvMapping) {
        this.csvMapping = csvMapping;
    }

    public String getTempFileToken() {
        return tempFileToken;
    }

    public void setTempFileToken(String tempFileToken) {
        this.tempFileToken = tempFileToken;
    }

    public StatementImportStatus getStatus() {
        return status;
    }

    public void setStatus(StatementImportStatus status) {
        this.status = status;
    }

    public int getTotalRows() {
        return totalRows;
    }

    public void setTotalRows(int totalRows) {
        this.totalRows = totalRows;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(Instant confirmedAt) {
        this.confirmedAt = confirmedAt;
    }

    public Instant getUndoneAt() {
        return undoneAt;
    }

    public void setUndoneAt(Instant undoneAt) {
        this.undoneAt = undoneAt;
    }
}
