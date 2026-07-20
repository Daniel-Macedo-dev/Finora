import { useState, type FormEvent } from 'react'
import FormField from '../../components/FormField'
import { errorMessage } from '../../components/states'
import { formatBRL, formatDate } from '../../lib/format'
import { useCsvMapping, useReparse } from './api'
import type {
  BatchDetail,
  CsvDelimiter,
  CsvEncoding,
  CsvMappingRequest,
  CsvSeparator,
  MappingPreview,
} from './types'

interface CsvMappingStepProps {
  batch: BatchDetail
}

const DATE_PATTERNS = ['dd/MM/yyyy', 'dd/MM/yy', 'yyyy-MM-dd', 'dd-MM-yyyy', 'dd.MM.yyyy', 'MM/dd/yyyy']

type AmountMode = 'single' | 'split'

interface ColumnSelectProps {
  value: number | null
  columnCount: number
  optional?: boolean
  onChange: (value: number | null) => void
}

/** Forwards FormField's injected id/aria props so the label stays wired. */
function ColumnSelect({
  value,
  columnCount,
  optional = false,
  onChange,
  ...labelling
}: ColumnSelectProps & Record<string, unknown>) {
  return (
    <select
      {...labelling}
      className="select"
      value={value === null ? '' : String(value)}
      onChange={(event) => onChange(event.target.value === '' ? null : Number(event.target.value))}
    >
      {optional ? <option value="">Não usar</option> : <option value="">Escolha a coluna</option>}
      {Array.from({ length: columnCount }, (_, index) => (
        <option key={index} value={index}>
          Coluna {index + 1}
        </option>
      ))}
    </select>
  )
}

/**
 * CSV mapping step: the user confirms encoding, delimiter, header, columns
 * and regional formats; the backend generates the authoritative preview.
 * Configuration is preserved when validation fails.
 */
export default function CsvMappingStep({ batch }: CsvMappingStepProps) {
  const suggestion = batch.csvMappingSuggestion
  const saved = batch.csvMapping
  const applyMapping = useCsvMapping()
  const reparse = useReparse()

  const [encoding, setEncoding] = useState<CsvEncoding>(saved?.encoding ?? suggestion?.encoding ?? 'UTF_8')
  const [delimiter, setDelimiter] = useState<CsvDelimiter>(
    saved?.delimiter ?? suggestion?.delimiter ?? 'SEMICOLON',
  )
  const [hasHeader, setHasHeader] = useState(saved?.hasHeader ?? suggestion?.hasHeader ?? false)
  const [datePattern, setDatePattern] = useState(
    saved?.datePattern ?? suggestion?.datePatterns[0] ?? 'dd/MM/yyyy',
  )
  const [decimalSeparator, setDecimalSeparator] = useState<CsvSeparator>(
    saved?.decimalSeparator ?? 'COMMA',
  )
  const [thousandsSeparator, setThousandsSeparator] = useState<CsvSeparator>(
    saved?.thousandsSeparator ?? 'DOT',
  )
  const [dateColumn, setDateColumn] = useState<number | null>(saved?.dateColumn ?? null)
  const [descriptionColumn, setDescriptionColumn] = useState<number | null>(
    saved?.descriptionColumn ?? null,
  )
  const [amountMode, setAmountMode] = useState<AmountMode>(
    saved && saved.amountColumn === null ? 'split' : 'single',
  )
  const [amountColumn, setAmountColumn] = useState<number | null>(saved?.amountColumn ?? null)
  const [debitColumn, setDebitColumn] = useState<number | null>(saved?.debitColumn ?? null)
  const [creditColumn, setCreditColumn] = useState<number | null>(saved?.creditColumn ?? null)
  const [externalIdColumn, setExternalIdColumn] = useState<number | null>(
    saved?.externalIdColumn ?? null,
  )
  const [memoColumn, setMemoColumn] = useState<number | null>(saved?.memoColumn ?? null)
  const [preview, setPreview] = useState<MappingPreview | null>(null)
  const [localError, setLocalError] = useState<string | null>(null)

  const rawRows = batch.csvRawPreview ?? []
  // A handful of preview rows at most — cheap to derive on every render.
  const columnCount = rawRows.reduce((max, row) => Math.max(max, row.length), 0)

  function buildMapping(): CsvMappingRequest | null {
    if (dateColumn === null || descriptionColumn === null) {
      setLocalError('Escolha as colunas de data e descrição.')
      return null
    }
    if (amountMode === 'single' && amountColumn === null) {
      setLocalError('Escolha a coluna de valor.')
      return null
    }
    if (amountMode === 'split' && (debitColumn === null || creditColumn === null)) {
      setLocalError('Escolha as colunas de débito e crédito.')
      return null
    }
    setLocalError(null)
    return {
      encoding,
      delimiter,
      hasHeader,
      datePattern,
      decimalSeparator,
      thousandsSeparator,
      dateColumn,
      descriptionColumn,
      amountColumn: amountMode === 'single' ? amountColumn : null,
      debitColumn: amountMode === 'split' ? debitColumn : null,
      creditColumn: amountMode === 'split' ? creditColumn : null,
      externalIdColumn,
      memoColumn,
    }
  }

  function handlePreview(event: FormEvent) {
    event.preventDefault()
    const mapping = buildMapping()
    if (!mapping) {
      return
    }
    applyMapping.mutate(
      { batchId: batch.id, mapping },
      { onSuccess: (result) => setPreview(result) },
    )
  }

  function handleProcess() {
    // The authoritative parse persists the items and discards the raw file.
    reparse.mutate({ batchId: batch.id })
  }

  const busy = applyMapping.isPending || reparse.isPending

  return (
    <section aria-label="Mapeamento das colunas do CSV">
      <p className="si-step-intro">
        Confirme como o arquivo deve ser interpretado. As primeiras linhas abaixo ajudam a
        identificar cada coluna — nada é importado até a confirmação final.
      </p>

      {rawRows.length > 0 && (
        <div className="card table-wrap si-raw-preview" style={{ padding: 0 }}>
          <table className="data">
            <caption className="visually-hidden">Primeiras linhas do arquivo enviado</caption>
            <thead>
              <tr>
                {Array.from({ length: columnCount }, (_, index) => (
                  <th key={index} scope="col">
                    Coluna {index + 1}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rawRows.map((row, rowIndex) => (
                <tr key={rowIndex}>
                  {Array.from({ length: columnCount }, (_, columnIndex) => (
                    <td key={columnIndex} className="si-raw-cell">
                      {row[columnIndex] ?? ''}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <form onSubmit={handlePreview} noValidate>
        <div className="si-mapping-grid">
          <FormField label="Codificação">
            <select
              className="select"
              value={encoding}
              onChange={(event) => setEncoding(event.target.value as CsvEncoding)}
            >
              <option value="UTF_8">UTF-8 (padrão)</option>
              <option value="WINDOWS_1252">Windows-1252 (ANSI)</option>
            </select>
          </FormField>
          <FormField label="Delimitador">
            <select
              className="select"
              value={delimiter}
              onChange={(event) => setDelimiter(event.target.value as CsvDelimiter)}
            >
              <option value="SEMICOLON">Ponto e vírgula (;)</option>
              <option value="COMMA">Vírgula (,)</option>
            </select>
          </FormField>
          <FormField label="Padrão de data">
            <select
              className="select"
              value={datePattern}
              onChange={(event) => setDatePattern(event.target.value)}
            >
              {DATE_PATTERNS.map((pattern) => (
                <option key={pattern} value={pattern}>
                  {pattern}
                </option>
              ))}
            </select>
          </FormField>
          <FormField label="Separador decimal">
            <select
              className="select"
              value={decimalSeparator}
              onChange={(event) => setDecimalSeparator(event.target.value as CsvSeparator)}
            >
              <option value="COMMA">Vírgula (1.234,56)</option>
              <option value="DOT">Ponto (1,234.56)</option>
            </select>
          </FormField>
          <FormField label="Separador de milhar">
            <select
              className="select"
              value={thousandsSeparator}
              onChange={(event) => setThousandsSeparator(event.target.value as CsvSeparator)}
            >
              <option value="DOT">Ponto</option>
              <option value="COMMA">Vírgula</option>
              <option value="NONE">Nenhum</option>
            </select>
          </FormField>
          <div className="field">
            <label htmlFor="si-has-header">Primeira linha</label>
            <label className="si-checkbox-row" htmlFor="si-has-header">
              <input
                id="si-has-header"
                type="checkbox"
                checked={hasHeader}
                onChange={(event) => setHasHeader(event.target.checked)}
              />
              O arquivo tem cabeçalho (a primeira linha não é um lançamento)
            </label>
          </div>
        </div>

        <div className="si-mapping-grid">
          <FormField label="Coluna de data">
            <ColumnSelect value={dateColumn} columnCount={columnCount} onChange={setDateColumn} />
          </FormField>
          <FormField label="Coluna de descrição">
            <ColumnSelect
              value={descriptionColumn}
              columnCount={columnCount}
              onChange={setDescriptionColumn}
            />
          </FormField>
          <fieldset className="field si-amount-mode">
            <legend>Como o valor aparece no arquivo</legend>
            <label className="si-checkbox-row">
              <input
                type="radio"
                name="amount-mode"
                checked={amountMode === 'single'}
                onChange={() => setAmountMode('single')}
              />
              Uma coluna com sinal (negativo = despesa)
            </label>
            <label className="si-checkbox-row">
              <input
                type="radio"
                name="amount-mode"
                checked={amountMode === 'split'}
                onChange={() => setAmountMode('split')}
              />
              Colunas separadas de débito e crédito
            </label>
          </fieldset>
          {amountMode === 'single' ? (
            <FormField label="Coluna de valor">
              <ColumnSelect
                value={amountColumn}
                columnCount={columnCount}
                onChange={setAmountColumn}
              />
            </FormField>
          ) : (
            <>
              <FormField label="Coluna de débito (saídas)">
                <ColumnSelect
                  value={debitColumn}
                  columnCount={columnCount}
                  onChange={setDebitColumn}
                />
              </FormField>
              <FormField label="Coluna de crédito (entradas)">
                <ColumnSelect
                  value={creditColumn}
                  columnCount={columnCount}
                  onChange={setCreditColumn}
                />
              </FormField>
            </>
          )}
          <FormField label="Coluna de identificador (opcional)">
            <ColumnSelect
              value={externalIdColumn}
              columnCount={columnCount}
              optional
              onChange={setExternalIdColumn}
            />
          </FormField>
          <FormField label="Coluna de observação (opcional)">
            <ColumnSelect
              value={memoColumn}
              columnCount={columnCount}
              optional
              onChange={setMemoColumn}
            />
          </FormField>
        </div>

        {(localError || applyMapping.isError) && (
          <p className="field-error" role="alert">
            {localError ?? errorMessage(applyMapping.error)}
          </p>
        )}

        <div className="form-footer">
          <button type="submit" className="btn btn-secondary" disabled={busy}>
            {applyMapping.isPending ? 'Gerando prévia…' : 'Testar mapeamento'}
          </button>
          <button
            type="button"
            className="btn btn-primary"
            disabled={busy || !preview}
            onClick={handleProcess}
          >
            {reparse.isPending ? 'Processando…' : 'Processar arquivo'}
          </button>
        </div>
      </form>

      {preview && (
        <section className="si-mapping-preview" aria-label="Prévia do mapeamento">
          <h3 className="si-subtitle">
            Prévia: {preview.validCount} de {preview.sampleSize}{' '}
            {preview.sampleSize === 1 ? 'linha válida' : 'linhas válidas'}
          </h3>
          <div className="card table-wrap" style={{ padding: 0 }}>
            <table className="data">
              <thead>
                <tr>
                  <th scope="col">Linha</th>
                  <th scope="col">Data</th>
                  <th scope="col">Descrição</th>
                  <th scope="col" style={{ textAlign: 'right' }}>
                    Valor
                  </th>
                  <th scope="col">Situação</th>
                </tr>
              </thead>
              <tbody>
                {preview.entries.map((entry) => (
                  <tr key={entry.sourceIndex}>
                    <td>{entry.sourceIndex}</td>
                    <td>{formatDate(entry.postedDate)}</td>
                    <td className="si-description">{entry.description ?? '—'}</td>
                    <td style={{ textAlign: 'right' }}>
                      {entry.amount !== null && entry.type === 'EXPENSE' ? '−' : ''}
                      {formatBRL(entry.amount)}
                    </td>
                    <td>
                      {entry.validationMessage ? (
                        <span className="badge badge-warning">{entry.validationMessage}</span>
                      ) : (
                        <span className="badge badge-positive">Válida</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}

      {reparse.isError && (
        <p className="field-error" role="alert">
          {errorMessage(reparse.error)}
        </p>
      )}
    </section>
  )
}
