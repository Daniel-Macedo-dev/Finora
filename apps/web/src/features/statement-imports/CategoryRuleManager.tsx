import { useState, type FormEvent } from 'react'
import { Plus, Trash2 } from 'lucide-react'
import Dialog from '../../components/Dialog'
import FormField from '../../components/FormField'
import { EmptyState, ErrorState, LoadingCards, errorMessage } from '../../components/states'
import { useAccounts, useCategories } from '../shared/api'
import {
  useCategoryRules,
  useCreateCategoryRule,
  useDeleteCategoryRule,
  useUpdateCategoryRule,
} from './api'
import {
  RULE_FIELD_LABELS,
  RULE_OPERATION_LABELS,
  type CategoryRule,
  type CategoryRuleField,
  type CategoryRuleOperation,
  type CategoryRuleRequest,
  type TransactionType,
} from './types'

interface CategoryRuleManagerProps {
  onClose: () => void
}

interface RuleFormProps {
  initial: CategoryRule | null
  busy: boolean
  error: unknown
  onSubmit: (request: CategoryRuleRequest) => void
  onCancel: () => void
}

function RuleForm({ initial, busy, error, onSubmit, onCancel }: RuleFormProps) {
  const [transactionType, setTransactionType] = useState<TransactionType>(
    initial?.transactionType ?? 'EXPENSE',
  )
  const [matchField, setMatchField] = useState<CategoryRuleField>(
    initial?.matchField ?? 'DESCRIPTION',
  )
  const [operation, setOperation] = useState<CategoryRuleOperation>(
    initial?.operation ?? 'CONTAINS',
  )
  const [pattern, setPattern] = useState(initial?.pattern ?? '')
  const [categoryId, setCategoryId] = useState(
    initial ? String(initial.categoryId) : '',
  )
  const [accountId, setAccountId] = useState(
    initial?.accountId !== null && initial?.accountId !== undefined
      ? String(initial.accountId)
      : '',
  )
  const [priority, setPriority] = useState(String(initial?.priority ?? 100))
  const [active, setActive] = useState(initial?.active ?? true)
  const [localError, setLocalError] = useState<string | null>(null)

  const categories = useCategories(transactionType)
  const accounts = useAccounts()

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (!pattern.trim() || !categoryId) {
      setLocalError('Informe o texto da regra e a categoria.')
      return
    }
    const parsedPriority = Number(priority)
    if (!Number.isInteger(parsedPriority) || parsedPriority < 0 || parsedPriority > 1000) {
      setLocalError('A prioridade deve estar entre 0 e 1000.')
      return
    }
    setLocalError(null)
    onSubmit({
      transactionType,
      matchField,
      operation,
      pattern: pattern.trim(),
      categoryId: Number(categoryId),
      accountId: accountId ? Number(accountId) : null,
      priority: parsedPriority,
      active,
    })
  }

  return (
    <form onSubmit={handleSubmit} noValidate>
      <div className="form-columns">
        <FormField label="Tipo">
          <select
            className="select"
            value={transactionType}
            onChange={(event) => {
              setTransactionType(event.target.value as TransactionType)
              setCategoryId('')
            }}
          >
            <option value="EXPENSE">Despesa</option>
            <option value="INCOME">Receita</option>
          </select>
        </FormField>
        <FormField label="Campo comparado">
          <select
            className="select"
            value={matchField}
            onChange={(event) => setMatchField(event.target.value as CategoryRuleField)}
          >
            {Object.entries(RULE_FIELD_LABELS).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </FormField>
        <FormField label="Operação">
          <select
            className="select"
            value={operation}
            onChange={(event) => setOperation(event.target.value as CategoryRuleOperation)}
          >
            {Object.entries(RULE_OPERATION_LABELS).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </FormField>
      </div>

      <FormField
        label="Texto da regra"
        hint="Comparação de texto simples e determinística — sem inteligência artificial."
      >
        <input
          className="input"
          value={pattern}
          onChange={(event) => setPattern(event.target.value)}
          maxLength={200}
          required
        />
      </FormField>

      <div className="form-columns">
        <FormField label="Categoria">
          <select
            className="select"
            value={categoryId}
            onChange={(event) => setCategoryId(event.target.value)}
            required
          >
            <option value="">Escolha a categoria</option>
            {(categories.data ?? [])
              .filter((category) => category.active)
              .map((category) => (
                <option key={category.id} value={category.id}>
                  {category.name}
                </option>
              ))}
          </select>
        </FormField>
        <FormField label="Conta (opcional)" hint="Regra de conta vence a regra geral.">
          <select
            className="select"
            value={accountId}
            onChange={(event) => setAccountId(event.target.value)}
          >
            <option value="">Todas as contas</option>
            {(accounts.data ?? [])
              .filter((account) => !account.archived)
              .map((account) => (
                <option key={account.id} value={account.id}>
                  {account.name}
                </option>
              ))}
          </select>
        </FormField>
        <FormField label="Prioridade" hint="Maior prioridade vence (0 a 1000).">
          <input
            className="input"
            type="number"
            min={0}
            max={1000}
            value={priority}
            onChange={(event) => setPriority(event.target.value)}
          />
        </FormField>
      </div>

      <div className="field">
        <label className="si-checkbox-row" htmlFor="si-rule-active">
          <input
            id="si-rule-active"
            type="checkbox"
            checked={active}
            onChange={(event) => setActive(event.target.checked)}
          />
          Regra ativa
        </label>
      </div>

      {(localError || Boolean(error)) && (
        <p className="field-error" role="alert">
          {localError ?? errorMessage(error)}
        </p>
      )}

      <div className="form-footer">
        <button type="button" className="btn btn-secondary" onClick={onCancel} disabled={busy}>
          Cancelar
        </button>
        <button type="submit" className="btn btn-primary" disabled={busy}>
          {busy ? 'Salvando…' : initial ? 'Salvar regra' : 'Criar regra'}
        </button>
      </div>
    </form>
  )
}

/**
 * Management of the user's deterministic category-mapping rules: list with
 * usage counters, activate/deactivate, edit, delete and create.
 */
export default function CategoryRuleManager({ onClose }: CategoryRuleManagerProps) {
  const rules = useCategoryRules()
  const createRule = useCreateCategoryRule()
  const updateRule = useUpdateCategoryRule()
  const deleteRule = useDeleteCategoryRule()
  const [editing, setEditing] = useState<CategoryRule | null>(null)
  const [creating, setCreating] = useState(false)

  function toRequest(rule: CategoryRule): CategoryRuleRequest {
    return {
      transactionType: rule.transactionType,
      accountId: rule.accountId,
      matchField: rule.matchField,
      operation: rule.operation,
      pattern: rule.pattern,
      categoryId: rule.categoryId,
      priority: rule.priority,
      active: rule.active,
    }
  }

  function toggleActive(rule: CategoryRule) {
    updateRule.mutate({ id: rule.id, request: { ...toRequest(rule), active: !rule.active } })
  }

  const formOpen = creating || editing !== null

  return (
    <Dialog open title="Regras de categoria" onClose={onClose} wide>
      {formOpen ? (
        <RuleForm
          initial={editing}
          busy={createRule.isPending || updateRule.isPending}
          error={editing ? (updateRule.isError ? updateRule.error : null)
            : createRule.isError ? createRule.error : null}
          onSubmit={(request) => {
            if (editing) {
              updateRule.mutate(
                { id: editing.id, request },
                { onSuccess: () => setEditing(null) },
              )
            } else {
              createRule.mutate(request, { onSuccess: () => setCreating(false) })
            }
          }}
          onCancel={() => {
            setEditing(null)
            setCreating(false)
          }}
        />
      ) : (
        <>
          <p className="si-step-intro">
            As regras aplicam categorias automaticamente aos lançamentos importados, por
            comparação de texto determinística. Regras de conta específica e maior prioridade
            vencem.
          </p>

          {rules.isPending ? (
            <LoadingCards count={2} height={60} />
          ) : rules.isError ? (
            <ErrorState error={rules.error} onRetry={() => rules.refetch()} />
          ) : (rules.data ?? []).length === 0 ? (
            <EmptyState
              title="Nenhuma regra ainda"
              description="Crie regras para categorizar automaticamente os próximos extratos."
            />
          ) : (
            <div className="table-wrap">
              <table className="data">
                <thead>
                  <tr>
                    <th scope="col">Regra</th>
                    <th scope="col">Categoria</th>
                    <th scope="col" className="si-col-optional">
                      Escopo
                    </th>
                    <th scope="col" className="si-col-optional" style={{ textAlign: 'right' }}>
                      Prioridade
                    </th>
                    <th scope="col" className="si-col-optional" style={{ textAlign: 'right' }}>
                      Usos
                    </th>
                    <th scope="col">Ativa</th>
                    <th scope="col">
                      <span className="visually-hidden">Ações</span>
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {(rules.data ?? []).map((rule) => (
                    <tr key={rule.id}>
                      <td>
                        <span className="si-rule-pattern">
                          {RULE_FIELD_LABELS[rule.matchField]}{' '}
                          {RULE_OPERATION_LABELS[rule.operation].toLowerCase()} “{rule.pattern}”
                        </span>
                        <span className="si-rule-type">
                          {rule.transactionType === 'EXPENSE' ? 'Despesa' : 'Receita'}
                        </span>
                      </td>
                      <td>{rule.categoryName}</td>
                      <td className="si-col-optional">{rule.accountName ?? 'Todas as contas'}</td>
                      <td className="si-col-optional" style={{ textAlign: 'right' }}>
                        {rule.priority}
                      </td>
                      <td className="si-col-optional" style={{ textAlign: 'right' }}>
                        {rule.matchCount}
                      </td>
                      <td>
                        <label className="si-checkbox-row">
                          <input
                            type="checkbox"
                            checked={rule.active}
                            onChange={() => toggleActive(rule)}
                            disabled={updateRule.isPending}
                            aria-label={`Regra “${rule.pattern}” ativa`}
                          />
                          <span className="visually-hidden">
                            {rule.active ? 'Ativa' : 'Inativa'}
                          </span>
                        </label>
                      </td>
                      <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                        <button
                          type="button"
                          className="btn btn-ghost"
                          onClick={() => setEditing(rule)}
                        >
                          Editar
                        </button>
                        <button
                          type="button"
                          className="btn btn-ghost btn-icon"
                          onClick={() => deleteRule.mutate(rule.id)}
                          disabled={deleteRule.isPending}
                          aria-label={`Excluir regra “${rule.pattern}”`}
                        >
                          <Trash2 size={16} aria-hidden="true" />
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {(updateRule.isError || deleteRule.isError) && (
            <p className="field-error" role="alert">
              {updateRule.isError
                ? errorMessage(updateRule.error)
                : errorMessage(deleteRule.error)}
            </p>
          )}

          <div className="form-footer">
            <button type="button" className="btn btn-secondary" onClick={onClose}>
              Fechar
            </button>
            <button type="button" className="btn btn-primary" onClick={() => setCreating(true)}>
              <Plus size={16} aria-hidden="true" />
              Nova regra
            </button>
          </div>
        </>
      )}
    </Dialog>
  )
}
