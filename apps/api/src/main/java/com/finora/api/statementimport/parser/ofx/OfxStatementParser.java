package com.finora.api.statementimport.parser.ofx;

import com.finora.api.common.money.MoneyRules;
import com.finora.api.statementimport.parser.StatementEntry;
import com.finora.api.statementimport.parser.StatementLimits;
import com.finora.api.statementimport.parser.StatementParseException;
import com.finora.api.statementimport.parser.StatementParseResult;
import com.finora.api.statementimport.parser.TextNormalizer;
import com.finora.api.statementimport.parser.ValidationIssue;
import com.finora.api.transaction.TransactionType;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Secure OFX parser for bank statements, covering OFX 1.x (SGML-style,
 * unclosed leaf tags) and OFX 2.x (XML-style) with one bounded tag scanner.
 *
 * <p>Security model: there is no XML parser here at all — no DTD
 * processing, no entity resolution beyond the five standard predefined
 * entities and bounded numeric references, no external access, no XInclude,
 * no network. Any {@code <!DOCTYPE} or {@code <!ENTITY} declaration is
 * rejected outright. Every dimension is limited: input size, entry count,
 * tag-name length, value length and nesting depth. Malformed nesting fails
 * safely with a stable code, never a parser stack trace.
 *
 * <p>Dates: {@code DTPOSTED} is interpreted as the bank-stated local date
 * (first 8 digits, strictly validated). Timezone suffixes never shift the
 * date — the machine's zone must not move a transaction to another day.
 */
public final class OfxStatementParser {

    private static final int MAX_TAG_LENGTH = 64;
    private static final int MAX_DEPTH = 32;
    private static final Pattern DTPOSTED_DATE = Pattern.compile("(\\d{4})(\\d{2})(\\d{2}).*");
    private static final Pattern AMOUNT = Pattern.compile("[+-]?\\d{1,12}([.,]\\d{1,2})?");

    private OfxStatementParser() {
    }

    public static StatementParseResult parse(byte[] content) {
        String text = decode(content);
        rejectDoctype(text);
        int start = text.indexOf('<');
        if (start < 0 || !text.toUpperCase(Locale.ROOT).contains("<OFX")) {
            throw new StatementParseException("STATEMENT_OFX_MALFORMED",
                    "O arquivo não é um extrato OFX válido.");
        }
        Scanner scanner = new Scanner(text, start);
        scanner.scan();
        if (scanner.creditCard) {
            throw new StatementParseException("STATEMENT_CARD_NOT_SUPPORTED",
                    "Este arquivo parece ser uma fatura de cartão de crédito. A importação de "
                            + "extratos cobre apenas contas bancárias — use a área de Cartões "
                            + "para lançamentos no crédito.");
        }
        if (scanner.accountType != null
                && !scanner.accountType.equals("CHECKING") && !scanner.accountType.equals("SAVINGS")) {
            throw new StatementParseException("STATEMENT_OFX_ACCOUNT_TYPE",
                    "O tipo de conta do extrato OFX não é suportado. Apenas extratos de conta "
                            + "corrente ou poupança podem ser importados.");
        }
        return new StatementParseResult(List.copyOf(scanner.entries), scanner.accountHint());
    }

    /** BOM/strict UTF-8 first, Windows-1252 fallback (OFX 1.x é comum em latin). */
    private static String decode(byte[] content) {
        byte[] body = content;
        if (content.length >= 3 && (content[0] & 0xFF) == 0xEF
                && (content[1] & 0xFF) == 0xBB && (content[2] & 0xFF) == 0xBF) {
            body = new byte[content.length - 3];
            System.arraycopy(content, 3, body, 0, body.length);
        }
        for (byte b : body) {
            if (b == 0) {
                throw new StatementParseException("STATEMENT_FILE_BINARY",
                        "O arquivo não parece ser um extrato em texto (CSV ou OFX).");
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(body, Charset.forName("windows-1252"));
        }
    }

    private static void rejectDoctype(String text) {
        String upper = text.toUpperCase(Locale.ROOT);
        if (upper.contains("<!DOCTYPE") || upper.contains("<!ENTITY")) {
            throw new StatementParseException("STATEMENT_OFX_DTD",
                    "O arquivo OFX contém declarações não permitidas e foi rejeitado por segurança.");
        }
    }

    /** One pass over {@code <TAG>value} tokens shared by SGML and XML OFX. */
    private static final class Scanner {

        private final String text;
        private int position;
        private final Deque<String> stack = new ArrayDeque<>();
        final List<StatementEntry> entries = new ArrayList<>();
        boolean creditCard;
        String accountType;
        private String bankId;
        private String maskedAccount;
        private Transactionfields current;

        private static final class Transactionfields {
            String trnType;
            String dtPosted;
            String trnAmt;
            String fitId;
            String name;
            String memo;
            String checkNum;
        }

        Scanner(String text, int start) {
            this.text = text;
            this.position = start;
        }

        void scan() {
            while (position < text.length()) {
                int open = text.indexOf('<', position);
                if (open < 0) {
                    break;
                }
                int close = text.indexOf('>', open + 1);
                if (close < 0) {
                    throw malformed();
                }
                if (close - open - 1 > MAX_TAG_LENGTH + 1) {
                    throw malformed();
                }
                String rawTag = text.substring(open + 1, close).strip();
                position = close + 1;
                if (rawTag.startsWith("?") || rawTag.startsWith("!--")) {
                    continue;
                }
                boolean closing = rawTag.startsWith("/");
                String tag = (closing ? rawTag.substring(1) : rawTag)
                        .toUpperCase(Locale.ROOT);
                if (tag.isEmpty() || !tag.chars().allMatch(
                        c -> c == '.' || c == '_' || Character.isLetterOrDigit(c))) {
                    throw malformed();
                }
                if (closing) {
                    onClose(tag);
                    continue;
                }
                String value = pendingValue();
                if (value != null) {
                    onLeaf(tag, value);
                } else {
                    onOpen(tag);
                }
            }
            if (current != null) {
                // An unterminated STMTTRN still counts — SGML files may omit
                // every closing tag, including the last one.
                finishTransaction();
            }
        }

        /**
         * Text between this tag and the next '<' — non-blank means an SGML
         * leaf value (or an XML text node; the XML closing tag is then
         * consumed as a no-op).
         */
        private String pendingValue() {
            int nextOpen = text.indexOf('<', position);
            String value = (nextOpen < 0 ? text.substring(position)
                    : text.substring(position, nextOpen));
            if (value.isBlank()) {
                return null;
            }
            if (value.length() > StatementLimits.MAX_FIELD_LENGTH) {
                throw new StatementParseException("STATEMENT_OFX_FIELD_TOO_LONG",
                        "O arquivo OFX contém um campo longo demais para um extrato válido.");
            }
            return decodeEntities(value).strip();
        }

        private void onOpen(String tag) {
            if (stack.size() >= MAX_DEPTH) {
                throw malformed();
            }
            stack.push(tag);
            switch (tag) {
                case "CREDITCARDMSGSRSV1", "CCSTMTRS", "CCSTMTTRNRS", "CCACCTFROM" ->
                        creditCard = true;
                case "STMTTRN" -> {
                    if (current != null) {
                        finishTransaction();
                    }
                    current = new Transactionfields();
                }
                default -> {
                }
            }
        }

        private void onClose(String tag) {
            if (tag.equals("STMTTRN") && current != null) {
                finishTransaction();
            }
            // SGML files omit most closing tags: unwind the stack until the
            // matching open tag if it is there; otherwise ignore.
            if (stack.contains(tag)) {
                while (!stack.isEmpty() && !stack.pop().equals(tag)) {
                    // unwound implicitly closed SGML aggregates
                }
            }
        }

        private void onLeaf(String tag, String value) {
            if (current != null) {
                switch (tag) {
                    case "TRNTYPE" -> current.trnType = value;
                    case "DTPOSTED" -> current.dtPosted = value;
                    case "TRNAMT" -> current.trnAmt = value;
                    case "FITID" -> current.fitId = value;
                    case "NAME" -> current.name = value;
                    case "MEMO" -> current.memo = value;
                    case "CHECKNUM" -> current.checkNum = value;
                    default -> {
                    }
                }
                return;
            }
            switch (tag) {
                case "ACCTTYPE" -> accountType = value.toUpperCase(Locale.ROOT);
                case "BANKID" -> bankId = value;
                case "ACCTID" -> maskedAccount = mask(value);
                default -> {
                }
            }
        }

        private void finishTransaction() {
            Transactionfields fields = current;
            current = null;
            if (entries.size() >= StatementLimits.MAX_ENTRIES) {
                throw new StatementParseException("STATEMENT_TOO_MANY_ROWS",
                        "O arquivo excede o limite de %d lançamentos por importação."
                                .formatted(StatementLimits.MAX_ENTRIES));
            }
            List<ValidationIssue> issues = new ArrayList<>();

            LocalDate date = null;
            if (fields.dtPosted == null) {
                issues.add(new ValidationIssue("STATEMENT_ROW_MISSING_DATE",
                        "O lançamento não possui data (DTPOSTED)."));
            } else {
                var matcher = DTPOSTED_DATE.matcher(fields.dtPosted);
                if (matcher.matches()) {
                    try {
                        date = LocalDate.of(Integer.parseInt(matcher.group(1)),
                                Integer.parseInt(matcher.group(2)),
                                Integer.parseInt(matcher.group(3)));
                    } catch (java.time.DateTimeException e) {
                        issues.add(new ValidationIssue("STATEMENT_ROW_INVALID_DATE",
                                "A data do lançamento é inválida."));
                    }
                } else {
                    issues.add(new ValidationIssue("STATEMENT_ROW_INVALID_DATE",
                            "A data do lançamento é inválida."));
                }
            }

            BigDecimal signed = null;
            TransactionType type = null;
            if (fields.trnAmt == null) {
                issues.add(new ValidationIssue("STATEMENT_ROW_MISSING_AMOUNT",
                        "O lançamento não possui valor (TRNAMT)."));
            } else {
                String normalized = fields.trnAmt.replace(" ", "");
                if (AMOUNT.matcher(normalized).matches()) {
                    signed = new BigDecimal(normalized.replace(',', '.'));
                    if (signed.signum() == 0) {
                        issues.add(new ValidationIssue("STATEMENT_ROW_ZERO_AMOUNT",
                                "O valor do lançamento é zero."));
                    } else {
                        type = signed.signum() > 0 ? TransactionType.INCOME
                                : TransactionType.EXPENSE;
                    }
                } else {
                    issues.add(new ValidationIssue("STATEMENT_ROW_INVALID_AMOUNT",
                            "O valor do lançamento é inválido."));
                }
            }

            String name = TextNormalizer.clean(fields.name);
            String memo = TextNormalizer.clean(fields.memo);
            String description = name != null && !name.isBlank() ? name : memo;
            String remainingMemo = description != null && description.equals(memo) ? null : memo;
            if (description == null || description.isBlank()) {
                issues.add(new ValidationIssue("STATEMENT_ROW_MISSING_DESCRIPTION",
                        "O lançamento não possui descrição (NAME ou MEMO)."));
                description = null;
            } else {
                if (fields.checkNum != null && !fields.checkNum.isBlank()) {
                    String check = TextNormalizer.clean(fields.checkNum);
                    remainingMemo = remainingMemo == null ? "Cheque " + check
                            : remainingMemo + " (cheque " + check + ")";
                }
                description = TextNormalizer.truncate(description,
                        StatementLimits.MAX_DESCRIPTION_LENGTH);
            }

            entries.add(new StatementEntry(
                    entries.size() + 1,
                    emptyToNull(TextNormalizer.truncate(TextNormalizer.clean(fields.fitId), 255)),
                    date,
                    type,
                    type == null ? null : MoneyRules.normalize(signed.abs()),
                    description,
                    description == null ? null
                            : TextNormalizer.truncate(TextNormalizer.canonical(description),
                                    StatementLimits.MAX_DESCRIPTION_LENGTH),
                    emptyToNull(TextNormalizer.truncate(remainingMemo, 500)),
                    fields.trnType == null ? "OFX"
                            : TextNormalizer.truncate(TextNormalizer.clean(fields.trnType), 40),
                    List.copyOf(issues)));
        }

        /** Masked preview hint only — never a full account number. */
        String accountHint() {
            if (maskedAccount == null) {
                return null;
            }
            return bankId == null ? maskedAccount : "Banco " + bankId + " — " + maskedAccount;
        }

        private static String mask(String accountId) {
            String cleaned = TextNormalizer.clean(accountId);
            if (cleaned == null || cleaned.length() <= 4) {
                return "•••";
            }
            return "•••" + cleaned.substring(cleaned.length() - 4);
        }

        /** The five predefined entities plus bounded numeric references. */
        private static String decodeEntities(String value) {
            if (value.indexOf('&') < 0) {
                return value;
            }
            StringBuilder out = new StringBuilder(value.length());
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c != '&') {
                    out.append(c);
                    continue;
                }
                int end = value.indexOf(';', i + 1);
                if (end < 0 || end - i > 8) {
                    out.append(c);
                    continue;
                }
                String entity = value.substring(i + 1, end);
                String decoded = switch (entity) {
                    case "amp" -> "&";
                    case "lt" -> "<";
                    case "gt" -> ">";
                    case "quot" -> "\"";
                    case "apos" -> "'";
                    default -> numericEntity(entity);
                };
                if (decoded == null) {
                    out.append(c);
                } else {
                    out.append(decoded);
                    i = end;
                }
            }
            return out.toString();
        }

        private static String numericEntity(String entity) {
            try {
                int code = entity.startsWith("#x") || entity.startsWith("#X")
                        ? Integer.parseInt(entity.substring(2), 16)
                        : entity.startsWith("#") ? Integer.parseInt(entity.substring(1)) : -1;
                if (code >= 32 && code <= 0x10FFFF && Character.isValidCodePoint(code)) {
                    return new String(Character.toChars(code));
                }
            } catch (NumberFormatException e) {
                // fall through — treated as literal text
            }
            return null;
        }

        private static String emptyToNull(String value) {
            return value == null || value.isBlank() ? null : value;
        }

        private static StatementParseException malformed() {
            return new StatementParseException("STATEMENT_OFX_MALFORMED",
                    "O arquivo não é um extrato OFX válido.");
        }
    }
}
