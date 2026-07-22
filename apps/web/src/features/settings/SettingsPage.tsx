import { useEffect, useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import PageHeader from '../../components/PageHeader'
import FormField from '../../components/FormField'
import { ErrorState, LoadingCards, errorMessage } from '../../components/states'
import { api } from '../../lib/api'
import { parseMoneyInput } from '../../lib/format'
import { applyThemePreference, getThemePreference, type ThemePreference } from '../../lib/theme'
import './settings.css'
import NotificationPreferences from '../notifications/NotificationPreferences'

interface Settings {
  minimumCashBuffer: number
  maxInstallmentCommitmentRatio: number
  monthlyOpportunityRate: number
  budgetWarningThreshold: number
}

interface FormState {
  buffer: string
  maxRatio: string
  rate: string
  warning: string
}

function toForm(settings: Settings): FormState {
  return {
    buffer: settings.minimumCashBuffer.toFixed(2).replace('.', ','),
    maxRatio: String(Math.round(settings.maxInstallmentCommitmentRatio * 100)),
    rate: String(settings.monthlyOpportunityRate * 100).replace('.', ','),
    warning: String(Math.round(settings.budgetWarningThreshold * 100)),
  }
}

export default function SettingsPage() {
  const queryClient = useQueryClient()
  const settings = useQuery({
    queryKey: ['settings'],
    queryFn: () => api.get<Settings>('/settings'),
  })

  const [form, setForm] = useState<FormState | null>(null)
  const [formError, setFormError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)
  const [theme, setTheme] = useState<ThemePreference>(getThemePreference())

  useEffect(() => {
    if (settings.data && form === null) {
      setForm(toForm(settings.data))
    }
  }, [settings.data, form])

  const updateMutation = useMutation({
    mutationFn: (request: Settings) => api.put<Settings>('/settings', request),
    onSuccess: (data) => {
      queryClient.setQueryData(['settings'], data)
      queryClient.invalidateQueries({ queryKey: ['budgets'] })
      queryClient.invalidateQueries({ queryKey: ['insights'] })
      setSaved(true)
    },
  })

  function handleThemeChange(preference: ThemePreference) {
    setTheme(preference)
    applyThemePreference(preference)
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (!form) {
      return
    }
    setSaved(false)
    const buffer = parseMoneyInput(form.buffer)
    const maxRatio = Number(form.maxRatio)
    const rate = parseMoneyInput(form.rate)
    const warning = Number(form.warning)
    if (buffer === null || buffer < 0) {
      setFormError('A reserva mínima não pode ser negativa.')
      return
    }
    if (!Number.isFinite(maxRatio) || maxRatio < 0 || maxRatio > 100) {
      setFormError('O comprometimento máximo deve estar entre 0% e 100%.')
      return
    }
    if (rate === null || rate < 0 || rate > 20) {
      setFormError('A taxa de oportunidade deve estar entre 0% e 20% ao mês.')
      return
    }
    if (!Number.isFinite(warning) || warning < 0 || warning > 100) {
      setFormError('O alerta de orçamento deve estar entre 0% e 100%.')
      return
    }
    setFormError(null)
    updateMutation.mutate({
      minimumCashBuffer: buffer,
      maxInstallmentCommitmentRatio: maxRatio / 100,
      monthlyOpportunityRate: rate / 100,
      budgetWarningThreshold: warning / 100,
    })
  }

  return (
    <>
      <PageHeader
        title="Configurações"
        description="Preferências de aparência e as premissas usadas nos cálculos financeiros."
      />

      <section className="card settings-section" aria-label="Aparência">
        <h2 className="panel-title">Aparência</h2>
        <div role="group" aria-label="Tema" className="theme-options">
          {(
            [
              ['light', 'Claro'],
              ['dark', 'Escuro'],
              ['system', 'Sistema'],
            ] as Array<[ThemePreference, string]>
          ).map(([value, label]) => (
            <button
              key={value}
              type="button"
              aria-pressed={theme === value}
              className={`btn ${theme === value ? 'btn-primary' : 'btn-secondary'}`}
              onClick={() => handleThemeChange(value)}
            >
              {label}
            </button>
          ))}
        </div>
      </section>

      <section className="card settings-section" aria-label="Premissas financeiras">
        <h2 className="panel-title">Premissas dos cálculos</h2>
        <p className="settings-note">
          Esses valores alimentam a análise de compras e os alertas de orçamento. As
          recomendações do Finora são projeções baseadas nos seus dados e nessas premissas — não
          são aconselhamento financeiro profissional.
        </p>
        {settings.isPending || form === null ? (
          settings.isError ? (
            <ErrorState error={settings.error} onRetry={() => settings.refetch()} />
          ) : (
            <LoadingCards count={2} height={56} />
          )
        ) : (
          <form onSubmit={handleSubmit} noValidate>
            <div className="settings-grid">
              <FormField
                label="Reserva mínima de caixa (R$)"
                hint="Uma compra à vista nunca deve deixar o caixa abaixo desse valor."
              >
                <input
                  className="input"
                  inputMode="decimal"
                  value={form.buffer}
                  onChange={(event) =>
                    setForm((state) => state && { ...state, buffer: event.target.value })
                  }
                />
              </FormField>
              <FormField
                label="Comprometimento máximo da renda (%)"
                hint="Parcelas + recorrentes não devem passar desse percentual da renda média."
              >
                <input
                  className="input"
                  type="number"
                  min={0}
                  max={100}
                  value={form.maxRatio}
                  onChange={(event) =>
                    setForm((state) => state && { ...state, maxRatio: event.target.value })
                  }
                />
              </FormField>
              <FormField
                label="Taxa de oportunidade mensal (%)"
                hint="Rendimento que seu dinheiro teria se ficasse aplicado. 0 compara apenas valores nominais."
              >
                <input
                  className="input"
                  inputMode="decimal"
                  value={form.rate}
                  onChange={(event) =>
                    setForm((state) => state && { ...state, rate: event.target.value })
                  }
                />
              </FormField>
              <FormField
                label="Alerta de orçamento (%)"
                hint="Percentual de consumo em que um orçamento entra em atenção."
              >
                <input
                  className="input"
                  type="number"
                  min={0}
                  max={100}
                  value={form.warning}
                  onChange={(event) =>
                    setForm((state) => state && { ...state, warning: event.target.value })
                  }
                />
              </FormField>
            </div>
            {(formError || updateMutation.error) && (
              <div role="alert" className="field-error settings-feedback">
                {formError ?? errorMessage(updateMutation.error)}
              </div>
            )}
            {saved && !formError && !updateMutation.error && (
              <p role="status" className="settings-saved settings-feedback">
                Configurações salvas.
              </p>
            )}
            <div className="settings-actions">
              <button
                type="submit"
                className="btn btn-primary"
                disabled={updateMutation.isPending}
              >
                {updateMutation.isPending ? 'Salvando…' : 'Salvar premissas'}
              </button>
            </div>
          </form>
        )}
      </section>
      <section className="card settings-section" aria-label="Notificações">
        <h2 className="panel-title">Notificações</h2>
        <NotificationPreferences />
      </section>
    </>
  )
}
