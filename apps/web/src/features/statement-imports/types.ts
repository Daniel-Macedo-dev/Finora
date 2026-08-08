/**
 * Contracts mirroring the statement-import API DTOs. The backend is the
 * single source of truth for parsing, normalization, duplicate detection and
 * category suggestions — nothing here re-parses statement files or recomputes
 * financial values beyond collecting mapping configuration.
 */

import type { CurrencyCode } from '../../lib/money'

export type StatementImportFormat = 'CSV' | 'OFX'

/**
 * Where a batch's denomination came from. Mirrors the backend enum.
 *
 * Every batch is read in exactly one currency — its destination account's — and
 * nothing is ever converted. These values describe the *evidence* behind that
 * reading, which is what decides whether the user has to confirm an assumption.
 */
export type StatementCurrencySource =
  /** CSV: the Finora CSV contract has no currency column, so the account is it. */
  | 'ACCOUNT'
  /** OFX whose CURDEF was read and matched the account. */
  | 'FILE'
  /** OFX that carried no CURDEF; the account currency is an assumption. */
  | 'ACCOUNT_ASSUMED'
  /** OFX imported before Finora recorded whether CURDEF was present. */
  | 'LEGACY_UNKNOWN'

export type StatementImportStatus =
  | 'NEEDS_MAPPING'
  | 'PREVIEW_READY'
  | 'COMPLETED'
  | 'PARTIALLY_COMPLETED'
  | 'UNDONE'

export type StatementImportItemStatus =
  | 'READY'
  | 'INVALID'
  | 'IMPORTED'
  | 'FAILED'
  | 'SKIPPED'
  | 'UNDONE'

export type DuplicateStatus =
  | 'UNIQUE'
  | 'EXACT_DUPLICATE'
  | 'POSSIBLE_DUPLICATE'
  | 'DUPLICATE_WITHIN_FILE'

export type ItemResultCode =
  | 'SUCCESS'
  | 'FAILED'
  | 'SKIPPED'
  | 'EXACT_DUPLICATE'
  | 'ALREADY_IMPORTED'
  | 'UNDONE'
  | 'ALREADY_UNDONE'
  | 'BLOCKED'

export type TransactionType = 'INCOME' | 'EXPENSE'

export type CsvEncoding = 'UTF_8' | 'WINDOWS_1252'
export type CsvDelimiter = 'COMMA' | 'SEMICOLON'
export type CsvSeparator = 'COMMA' | 'DOT' | 'NONE'

export type RuleConfidence = 'HIGH' | 'MEDIUM' | 'LOW'
export type CategoryRuleField = 'DESCRIPTION' | 'MEMO'
export type CategoryRuleOperation = 'EXACT' | 'STARTS_WITH' | 'CONTAINS'

/* ---------- requests ---------- */

/** CSV interpretation confirmed by the user. */
export interface CsvMappingRequest {
  encoding: CsvEncoding
  delimiter: CsvDelimiter
  hasHeader: boolean
  datePattern: string
  decimalSeparator: CsvSeparator
  thousandsSeparator: CsvSeparator
  dateColumn: number
  descriptionColumn: number
  amountColumn: number | null
  debitColumn: number | null
  creditColumn: number | null
  externalIdColumn: number | null
  memoColumn: number | null
}

/** Pre-confirmation edits; only present fields are applied. */
export interface ItemPatchRequest {
  included?: boolean
  selectedCategoryId?: number
  description?: string
  postedDate?: string
  type?: TransactionType
  amount?: number
  duplicateOverride?: boolean
}

export interface CategoryRuleRequest {
  transactionType: TransactionType
  accountId?: number | null
  matchField: CategoryRuleField
  operation: CategoryRuleOperation
  pattern: string
  categoryId: number
  priority: number
  active: boolean
}

/* ---------- responses ---------- */

/**
 * The one currency a batch is read in, plus how well the source is known.
 *
 * Bundled rather than spread across the batch so no consumer can pick up an
 * amount and leave the denomination behind.
 */
export interface StatementCurrencyContext {
  /** Destination account currency: the authoritative denomination. */
  accountCurrency: CurrencyCode
  currencySource: StatementCurrencySource
  /** What the file declared; only ever set for a FILE source. */
  declaredCurrency: CurrencyCode | null
  /** Currency the amounts are actually read in. Equals accountCurrency. */
  effectiveCurrency: CurrencyCode
  /** Always false: Finora holds no exchange rates and converts nothing. */
  valuesAreConverted: boolean
  /** Whether confirming a new item needs an explicit acknowledgement. */
  currencyAcknowledgementRequired: boolean
}

/** Summary of an existing transaction shown in duplicate review. */
export interface MatchedTransactionSummary {
  id: number
  date: string
  description: string
  amount: number
  /** Denomination of `amount`, from the matched transaction itself. */
  currency: CurrencyCode
  type: TransactionType
  categoryName: string | null
}

export interface StatementItem {
  id: number
  sourceIndex: number
  externalId: string | null
  sourceType: string | null
  postedDate: string | null
  amount: number | null
  /** Denomination of `amount` and `originalAmount`. */
  currency: CurrencyCode
  type: TransactionType | null
  description: string | null
  memo: string | null
  originalDate: string | null
  originalAmount: number | null
  originalType: TransactionType | null
  originalDescription: string | null
  suggestedCategoryId: number | null
  suggestedCategoryName: string | null
  matchedRuleId: number | null
  matchedRulePattern: string | null
  ruleConfidence: RuleConfidence | null
  selectedCategoryId: number | null
  selectedCategoryName: string | null
  included: boolean
  duplicateStatus: DuplicateStatus
  duplicateOverride: boolean
  matchedTransaction: MatchedTransactionSummary | null
  status: StatementImportItemStatus
  validationCode: string | null
  validationMessage: string | null
  resultCode: string | null
  resultMessage: string | null
  transactionId: number | null
  importedAt: string | null
  undoneAt: string | null
  /** Whether confirming now would try to materialize this item. */
  importable: boolean
}

/** Derived batch totals — computed by the backend, never in the frontend. */
export interface BatchTotals {
  /** Denomination of every monetary total below. */
  currency: CurrencyCode
  totalRows: number
  readyCount: number
  invalidCount: number
  importedCount: number
  failedCount: number
  skippedCount: number
  undoneCount: number
  excludedCount: number
  includedPendingCount: number
  exactDuplicateCount: number
  possibleDuplicateCount: number
  withinFileDuplicateCount: number
  unmappedCategoryCount: number
  pendingIncomeTotal: number
  pendingExpenseTotal: number
  pendingNetEffect: number
}

export interface BatchSummary {
  id: number
  createdAt: string
  accountId: number
  accountName: string
  accountCurrency: CurrencyCode
  currencySource: StatementCurrencySource
  declaredCurrency: CurrencyCode | null
  originalFilename: string
  format: StatementImportFormat
  status: StatementImportStatus
  totalRows: number
  importedCount: number
  failedCount: number
  confirmedAt: string | null
  undoneAt: string | null
}

/** Suggested starting point for the CSV mapping step. */
export interface CsvMappingSuggestion {
  encoding: CsvEncoding
  delimiter: CsvDelimiter
  hasHeader: boolean
  datePatterns: string[]
}

export interface BatchDetail {
  id: number
  createdAt: string
  accountId: number
  accountName: string
  /** The one currency this batch is read in, and its provenance. */
  currency: StatementCurrencyContext
  originalFilename: string
  format: StatementImportFormat
  status: StatementImportStatus
  fileSha256: string
  fileSizeBytes: number
  /** Masked bank/account hint from the file — preview only. */
  sourceAccountHint: string | null
  fileAlreadyImported: boolean
  csvMapping: CsvMappingRequest | null
  csvMappingSuggestion: CsvMappingSuggestion | null
  /** First raw rows, present only while a CSV waits for mapping. */
  csvRawPreview: string[][] | null
  confirmedAt: string | null
  undoneAt: string | null
  totals: BatchTotals
  items: StatementItem[]
}

export interface MappingPreviewEntry {
  sourceIndex: number
  postedDate: string | null
  amount: number | null
  /** Denomination of `amount` and `originalAmount`. */
  currency: CurrencyCode
  type: TransactionType | null
  description: string | null
  memo: string | null
  externalId: string | null
  validationCode: string | null
  validationMessage: string | null
}

export interface MappingPreview {
  batchId: number
  /** Denomination of every `entries[].amount`: the destination account's. */
  accountCurrency: CurrencyCode
  sampleSize: number
  validCount: number
  invalidCount: number
  entries: MappingPreviewEntry[]
}

/** Structured outcome of confirming or undoing one item. */
export interface ItemResult {
  itemId: number
  result: ItemResultCode
  transactionId: number | null
  code: string
  message: string
}

export interface ConfirmResponse {
  batchId: number
  batchStatus: StatementImportStatus
  results: ItemResult[]
  totals: BatchTotals
}

export interface CategoryRule {
  id: number
  active: boolean
  transactionType: TransactionType
  accountId: number | null
  accountName: string | null
  matchField: CategoryRuleField
  operation: CategoryRuleOperation
  pattern: string
  categoryId: number
  categoryName: string
  priority: number
  matchCount: number
  lastUsedAt: string | null
}

/* ---------- limits (mirror the backend's StatementLimits) ---------- */

/** Backend maximum accepted upload size, in bytes. */
export const MAX_UPLOAD_BYTES = 5 * 1024 * 1024

/* ---------- labels ---------- */

export const BATCH_STATUS_LABELS: Record<StatementImportStatus, string> = {
  NEEDS_MAPPING: 'Aguardando mapeamento',
  PREVIEW_READY: 'Pré-visualização pronta',
  COMPLETED: 'Concluída',
  PARTIALLY_COMPLETED: 'Parcialmente concluída',
  UNDONE: 'Desfeita',
}

export const ITEM_STATUS_LABELS: Record<StatementImportItemStatus, string> = {
  READY: 'Pronto',
  INVALID: 'Inválido',
  IMPORTED: 'Importado',
  FAILED: 'Falhou',
  SKIPPED: 'Pulado',
  UNDONE: 'Desfeito',
}

export const DUPLICATE_STATUS_LABELS: Record<DuplicateStatus, string> = {
  UNIQUE: 'Único',
  EXACT_DUPLICATE: 'Duplicata exata',
  POSSIBLE_DUPLICATE: 'Possível duplicata',
  DUPLICATE_WITHIN_FILE: 'Repetido no arquivo',
}

export const ITEM_RESULT_LABELS: Record<ItemResultCode, string> = {
  SUCCESS: 'Importado',
  FAILED: 'Falhou',
  SKIPPED: 'Pulado',
  EXACT_DUPLICATE: 'Duplicata exata',
  ALREADY_IMPORTED: 'Já importado',
  UNDONE: 'Desfeito',
  ALREADY_UNDONE: 'Já desfeito',
  BLOCKED: 'Bloqueado',
}

export const RULE_CONFIDENCE_LABELS: Record<RuleConfidence, string> = {
  HIGH: 'Alta',
  MEDIUM: 'Média',
  LOW: 'Baixa',
}

export const RULE_FIELD_LABELS: Record<CategoryRuleField, string> = {
  DESCRIPTION: 'Descrição',
  MEMO: 'Observação',
}

export const RULE_OPERATION_LABELS: Record<CategoryRuleOperation, string> = {
  EXACT: 'Igual a',
  STARTS_WITH: 'Começa com',
  CONTAINS: 'Contém',
}

/**
 * Short provenance label for history and detail.
 *
 * LEGACY_UNKNOWN deliberately does not say the file declared nothing: nobody
 * knows what it declared, because the parser of the day never looked.
 */
export const CURRENCY_SOURCE_LABELS: Record<StatementCurrencySource, string> = {
  ACCOUNT: 'Moeda da conta de destino',
  FILE: 'Moeda declarada pelo arquivo',
  ACCOUNT_ASSUMED: 'Moeda da conta (arquivo não declarou)',
  LEGACY_UNKNOWN: 'Moeda da conta (importação anterior ao registro da moeda)',
}
