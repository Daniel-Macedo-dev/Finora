import { useState } from 'react'
import { Link } from 'react-router-dom'
import { AlertTriangle, TrendingDown, Wallet } from 'lucide-react'
import PageHeader from '../../components/PageHeader'
import Money from '../../components/Money'
import { EmptyState, ErrorState, LoadingCards } from '../../components/states'
import { formatDate, formatMonth } from '../../lib/format'
import { currencyLabel, formatMoney } from '../../lib/money'
import { useAccounts } from '../shared/api'
import BalanceChart from './BalanceChart'
import { useForecast } from './api'
import {
  FORECAST_SOURCE_LABELS,
  type Forecast,
  type ForecastCurrencySummary,
  type ForecastEvent,
} from './types'
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

/**
 * Everything that belongs to one denomination: its balances, its warning, its
 * chart and its monthly table.
 *
 * <p>Rendering these per currency is the whole point. A single set of cards
 * would have to show one number, and the only way to produce one across
 * currencies is to invent it.
 */
function CurrencySection({
  summary,
  forecast,
  showHeading,
}: {
  summary: ForecastCurrencySummary
  forecast: Forecast
  showHeading: boolean
}) {
  const currency = summary.currency
  const outflows = summary.accountExpenses + summary.invoiceOutflows
  return (
    <section aria-label={`Previsão em ${currency}`}>
      {showHeading && (
        <h2 className="forecast-currency-heading">{currencyLabel(currency)}</h2>
      )}

      <div className="forecast-kpis">
        <div className="card forecast-kpi">
          <span className="forecast-kpi-label">
            <Wallet size={16} aria-hidden="true" /> Saldo hoje{' '}
            <span className="currency-total-code">{currency}</span>
          </span>
          <Money
            value={summary.openingBalance}
            currency={currency}
            className="forecast-kpi-value"
          />
          <span className="stat-footnote">
            Projetado em {formatDate(forecast.to)}:{' '}
            <strong>{formatMoney(summary.closingBalance, currency)}</strong>
          </span>
        </div>
        <div className="card forecast-kpi">
          <span className="forecast-kpi-label">
            <TrendingDown size={16} aria-hidden="true" /> Menor saldo projetado{' '}
            <span className="currency-total-code">{currency}</span>
          </span>
          <Money
            value={summary.lowestBalance}
            currency={currency}
            signed
            className="forecast-kpi-value"
          />
          <span className="stat-footnote">em {formatDate(summary.lowestBalanceDate)}</span>
        </div>
        <div className="card forecast-kpi">
          <span className="forecast-kpi-label">
            Entradas × saídas projetadas{' '}
            <span className="currency-total-code">{currency}</span>
          </span>
          <span className="forecast-kpi-value">
            {formatMoney(summary.income, currency)} · {formatMoney(outflows, currency)}
          </span>
          <span className="stat-footnote">
            Faturas de cartão: {formatMoney(summary.invoiceOutflows, currency)} no vencimento
          </span>
        </div>
      </div>

      {summary.firstNegativeDate && (
        <div className="forecast-warning" role="alert">
          <AlertTriangle size={18} aria-hidden="true" />
          <span>
            O saldo projetado em <strong>{currency}</strong> fica{' '}
            <strong>negativo em {formatDate(summary.firstNegativeDate)}</strong>. Considere
            antecipar receitas ou adiar despesas.
          </span>
        </div>
      )}

      {(summary.unassignedInflows > 0 || summary.unassignedOutflows > 0) && (
        <div className="forecast-warning forecast-warning-neutral" role="status">
          <AlertTriangle size={18} aria-hidden="true" />
          <span>
            Fluxos em {currency} sem conta definida — não afetam o saldo projetado: entradas de{' '}
            {formatMoney(summary.unassignedInflows, currency)} e saídas de{' '}
            {formatMoney(summary.unassignedOutflows, currency)}. Defina contas de destino nos
            recorrentes ou uma conta padrão de pagamento nos cartões.
          </span>
        </div>
      )}

      <section className="card forecast-section">
        <h3 className="cc-section-title">Saldo projetado ({currency})</h3>
        <p className="stat-footnote">
          A linha considera apenas valores em {currency} com conta definida e fontes
          determinísticas — projeções não são garantia de saldo.
        </p>
        <BalanceChart
          summary={summary}
          events={forecast.events}
          from={forecast.from}
          to={forecast.to}
        />
      </section>

      {summary.months.length > 0 && (
        <section className="card forecast-section">
          <h3 className="cc-section-title">Resumo mensal ({currency})</h3>
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
                {summary.months.map((month) => (
                  <tr key={month.month}>
                    <td>{formatMonth(month.month)}</td>
                    <td style={{ textAlign: 'right' }}>
                      <Money value={month.inflows} currency={currency} />
                    </td>
                    <td style={{ textAlign: 'right' }}>
                      <Money value={month.outflows} currency={currency} />
                    </td>
                    <td style={{ textAlign: 'right' }}>
                      <Money value={month.endBalance} currency={currency} signed />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
    </section>
  )
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
                  {account.name} · {account.currency}
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
          {forecast.data.byCurrency.length > 1 && (
            <p className="forecast-multi-note" role="note">
              Esta previsão envolve {forecast.data.byCurrency.length} moedas. Cada uma tem seu
              próprio saldo, gráfico e resumo — os valores não são somados entre moedas, porque
              isso exigiria cotações, que ainda não existem no Finora.
            </p>
          )}

          {forecast.data.byCurrency.map((summary) => (
            <CurrencySection
              key={summary.currency}
              summary={summary}
              forecast={forecast.data}
              showHeading={forecast.data.byCurrency.length > 1}
            />
          ))}

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
                        <Money value={event.amount} currency={event.currency} signed />
                        {event.balanceAfter !== null && (
                          <span className="forecast-event-balance">
                            saldo {formatMoney(event.balanceAfter, event.currency)}
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
