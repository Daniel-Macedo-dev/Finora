package com.finora.api.statementimport;

import com.finora.api.common.error.BusinessRuleException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Bounded temporary storage for a CSV upload while its column mapping is
 * being configured — the only place raw statement bytes ever rest, and only
 * until the authoritative parse discards them.
 *
 * <ul>
 *   <li>Files live under the JVM temp dir with random UUID names; the
 *       original filename never touches the filesystem.</li>
 *   <li>Deleted explicitly after the authoritative parse; anything older
 *       than {@value #EXPIRY_HOURS}h is swept opportunistically on writes,
 *       so an abandoned upload never becomes permanent storage.</li>
 *   <li>File contents are never logged.</li>
 * </ul>
 */
@Component
public class TempStatementStore {

    private static final Logger log = LoggerFactory.getLogger(TempStatementStore.class);
    private static final int EXPIRY_HOURS = 24;

    private final Path directory;

    public TempStatementStore() {
        this(Path.of(System.getProperty("java.io.tmpdir"), "finora-statement-uploads"));
    }

    TempStatementStore(Path directory) {
        this.directory = directory;
    }

    /** Stores the bytes and returns the random token that references them. */
    public String store(byte[] content) {
        sweepExpired();
        String token = UUID.randomUUID().toString().replace("-", "");
        try {
            Files.createDirectories(directory);
            Files.write(directory.resolve(token + ".tmp"), content);
        } catch (IOException e) {
            log.error("Falha ao gravar arquivo temporário de extrato", e);
            throw new BusinessRuleException("STATEMENT_STORAGE_UNAVAILABLE",
                    "Não foi possível processar o arquivo agora. Tente novamente.");
        }
        return token;
    }

    /** Empty when the token expired, was discarded or never existed. */
    public Optional<byte[]> read(String token) {
        if (token == null || !token.matches("[0-9a-f]{32}")) {
            return Optional.empty();
        }
        Path file = directory.resolve(token + ".tmp");
        try {
            if (!Files.exists(file)) {
                return Optional.empty();
            }
            return Optional.of(Files.readAllBytes(file));
        } catch (IOException e) {
            log.error("Falha ao ler arquivo temporário de extrato", e);
            return Optional.empty();
        }
    }

    /** Discards the raw bytes (idempotent). */
    public void discard(String token) {
        if (token == null || !token.matches("[0-9a-f]{32}")) {
            return;
        }
        try {
            Files.deleteIfExists(directory.resolve(token + ".tmp"));
        } catch (IOException e) {
            log.warn("Falha ao remover arquivo temporário de extrato", e);
        }
    }

    private void sweepExpired() {
        if (!Files.isDirectory(directory)) {
            return;
        }
        Instant cutoff = Instant.now().minus(Duration.ofHours(EXPIRY_HOURS));
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(file -> {
                try {
                    return Files.getLastModifiedTime(file).toInstant().isBefore(cutoff);
                } catch (IOException e) {
                    return false;
                }
            }).forEach(file -> {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    log.warn("Falha ao expirar arquivo temporário de extrato", e);
                }
            });
        } catch (IOException e) {
            log.warn("Falha ao varrer arquivos temporários de extrato", e);
        }
    }
}
