import { useState } from 'react'
import { Link } from 'react-router-dom'
import { AlertTriangle, TrendingDown, Wallet } from 'lucide-react'
import PageHeader from '../../components/PageHeader'
import Money from '../../components/Money'
import { EmptyState, ErrorState, LoadingCards } from '../../components/states'
import { formatBRL, formatDate, formatMonth } from '../../lib/format'
import { useAccounts } from '../shared/api'
import BalanceChart from './BalanceChart'
import { useForecast } from './api'
import { FORECAST_SOURCE_LABELS, type ForecastEvent } from './types'
import './forecast.css'

const HORIZONS = [
  { days: 30, label: '30 dias' },
  { days: 90, label: '90 dias' },
  { days: 180, label: '6 meses' },
  { days: 365, label: '12 meses' },
]

function eventRoute(event: ForecastEvent): string | null {
  if (event.invoiceId !== null && event.creditCardId !== null) {
    return `/credit-cards/${event.creditCardId}/invoices/${event.invoiceId}`
  }
  if (event.creditCardId !== null) {
    return `/credit-cards/${event.creditCardId}`
  }
  if (event.commitmentId !== null) {
    return '/commitments'
  }
  if (event.transactionId !== null) {
    return '/transactions'
  }
  return null
}

export default function ForecastPage() {
  const [days, setDays] = useState(90)
  const [accountId, setAccountId] = useState<number | null>(null)
  const accounts = useAccounts()
  const forecast = useForecast(days, accountId)

  const openAccounts = (accounts.data ?? []).filter((account) => !account.archived)

  return (
    <>
      <PageHeader
        title="Previsão de caixa"
        description="Movimentação futura de dinheiro: lançamentos registrados, recorrentes projetados e faturas de cartão no vencimento."
        actions={
          <div className="forecast-controls">
            <div role="group" aria-label="Horizonte da previsão" className="forecast-horizons">
              {HORIZONS.map((horizon) => (
                <button
                  key={horizon.days}
                  type="button"
                  aria-pressed={days === horizon.days}
                  className={`btn ${days === horizon.days ? 'btn-primary' : 'btn-secondary'}`}
                  onClick={() => setDays(horizon.days)}
                >
                  {horizon.label}
                </button>
              ))}
            </div>
            <select
              className="select"
              aria-label="Filtrar por conta"
              value={accountId !== null ? String(accountId) : ''}
              onChange={(event) =>
                setAccountId(event.target.value ? Number(event.target.value) : null)
              }
            >
              <option value="">Todas as contas</option>
              {openAccounts.map((account) => (
                <option key={account.id} value={account.id}>
                  {account.name}
                </option>
              ))}
            </select>
          </div>
        }
      />

      {forecast.isPending ? (
        <LoadingCards count={3} height={120} />
      ) : forecast.isError ? (
        <ErrorState error={forecast.error} onRetry={() => forecast.refetch()} />
      ) : forecast.data ? (
        <>
          <section className="forecast-kpis" aria-label="Resumo da previsão">
            <div className="card forecast-kpi">
              <span className="forecast-kpi-label">
                <Wallet size={16} aria-hidden="true" /> Saldo hoje
              </span>
              <Money value={forecast.data.openingBalance} className="forecast-kpi-value" />
              <span className="stat-footnote">
                Projetado em {formatDate(forecast.data.to)}:{' '}
                <strong>{formatBRL(forecast.data.closingBalance)}</strong>
              </span>
            </div>
            <div className="card forecast-kpi">
              <span className="forecast-kpi-label">
                <TrendingDown size={16} aria-hidden="true" /> Menor saldo projetado
              </span>
              <Money value={forecast.data.lowestBalance} signed className="forecast-kpi-value" />
              <span className="stat-footnote">
                em {formatDate(forecast.data.lowestBalanceDate)}
              </span>
            </div>
            <div className="card forecast-kpi">
              <span className="forecast-kpi-label">Entradas × saídas projetadas</span>
              <span className="forecast-kpi-value">
                {formatBRL(forecast.data.projectedIncome)} ·{' '}
                {formatBRL(
                  forecast.data.projectedAccountExpenses +
                    forecast.data.projectedInvoiceOutflows,
                )}
              </span>
              <span className="stat-footnote">
                Faturas de cartão: {formatBRL(forecast.data.projectedInvoiceOutflows)} no
                vencimento
              </span>
            </div>
          </section>

          {forecast.data.firstNegativeDate && (
            <div className="forecast-warning" role="alert">
              <AlertTriangle size={18} aria-hidden="true" />
              <span>
                O saldo projetado fica <strong>negativo em{' '}
                {formatDate(forecast.data.firstNegativeDate)}</strong>. Considere antecipar
                receitas ou adiar despesas.
              </span>
            </div>
          )}

          {(forecast.data.unassignedInflows > 0 || forecast.data.unassignedOutflows > 0) && (
            <div className="forecast-warning forecast-warning-neutral" role="status">
              <AlertTriangle size={18} aria-hidden="true" />
              <span>
                Fluxos sem conta definida — não afetam o saldo projetado: entradas de{' '}
                {formatBRL(forecast.data.unassignedInflows)} e saídas de{' '}
                {formatBRL(forecast.data.unassignedOutflows)}. Defina contas de destino nos
                recorrentes ou uma conta padrão de pagamento nos cartões.
              </span>
            </div>
          )}

          <section className="card forecast-section" aria-labelledby="forecast-chart-heading">
            <h2 id="forecast-chart-heading" className="cc-section-title">
              Saldo projetado
            </h2>
            <p className="stat-footnote">
              A linha considera apenas valores com conta definida e fontes determinísticas —
              projeções não são garantia de saldo.
            </p>
            <BalanceChart forecast={forecast.data} />
          </section>

          {forecast.data.months.length > 0 && (
            <section className="card forecast-section" aria-labelledby="forecast-months-heading">
              <h2 id="forecast-months-heading" className="cc-section-title">
                Resumo mensal
              </h2>
              <div className="table-wrap">
                <table className="data">
                  <thead>
                    <tr>
                      <th scope="col">Mês</th>
                      <th scope="col" style={{ textAlign: 'right' }}>
                        Entradas
                      </th>
                      <th scope="col" style={{ textAlign: 'right' }}>
                        Saídas
                      </th>
                      <th scope="col" style={{ textAlign: 'right' }}>
                        Saldo ao fim
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {forecast.data.months.map((month) => (
                      <tr key={month.month}>
                        <td>{formatMonth(month.month)}</td>
                        <td style={{ textAlign: 'right' }}>
                          <Money value={month.inflows} />
                        </td>
                        <td style={{ textAlign: 'right' }}>
                          <Money value={month.outflows} />
                        </td>
                        <td style={{ textAlign: 'right' }}>
                          <Money value={month.endBalance} signed />
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </section>
          )}

          <section className="card forecast-section" aria-labelledby="forecast-events-heading">
            <h2 id="forecast-events-heading" className="cc-section-title">
              Eventos projetados
            </h2>
            {forecast.data.events.length === 0 ? (
              <EmptyState
                title="Nenhum evento no horizonte"
                description="Cadastre recorrentes ou registre lançamentos futuros para projetar o caixa."
              />
            ) : (
              <ul className="forecast-timeline">
                {forecast.data.events.map((event, index) => {
                  const route = eventRoute(event)
                  return (
                    <li key={`${event.date}-${event.description}-${index}`}>
                      <span className="forecast-event-date">{formatDate(event.date)}</span>
                      <span className="forecast-event-main">
                        {route ? (
                          <Link to={route} className="cc-invoice-link">
                            {event.description}
                          </Link>
                        ) : (
                          event.description
                        )}
                        <span className="forecast-event-source">
                          {FORECAST_SOURCE_LABELS[event.source]}
                          {event.accountName && ` · ${event.accountName}`}
                          {event.unassigned && ' · sem conta definida'}
                        </span>
                      </span>
                      <span className="forecast-event-amount">
                        <Money value={event.amount} signed />
                        {event.balanceAfter !== null && (
                          <span className="forecast-event-balance">
                            saldo {formatBRL(event.balanceAfter)}
                          </span>
                        )}
                      </span>
                    </li>
                  )
                })}
              </ul>
            )}
          </section>
        </>
      ) : null}
    </>
  )
}
