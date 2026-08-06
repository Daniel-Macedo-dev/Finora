import {
  Bar,
  BarChart,
  CartesianGrid,
  Legend,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { formatMonth } from '../../lib/format'
import { currencyLabel, formatMoney, type CurrencyCode } from '../../lib/money'
import type { MonthTrendSeries } from './types'

const SHORT_MONTHS = ['jan', 'fev', 'mar', 'abr', 'mai', 'jun', 'jul', 'ago', 'set', 'out', 'nov', 'dez']

function shortMonth(month: string): string {
  const [, monthPart] = month.split('-')
  return SHORT_MONTHS[Number(monthPart) - 1] ?? month
}

function SeriesChart({ series }: { series: MonthTrendSeries }) {
  const currency: CurrencyCode = series.currency
  return (
    <div style={{ width: '100%', height: 240 }}>
      <ResponsiveContainer>
        <BarChart data={series.points} margin={{ top: 8, right: 8, bottom: 0, left: 8 }} barGap={2}>
          <CartesianGrid stroke="var(--chart-grid)" vertical={false} />
          <XAxis
            dataKey="month"
            tickFormatter={shortMonth}
            stroke="var(--chart-axis)"
            tickLine={false}
            fontSize={12}
          />
          <YAxis
            stroke="var(--chart-axis)"
            tickLine={false}
            axisLine={false}
            fontSize={12}
            width={72}
            tickFormatter={(value: number) =>
              value >= 1000 ? `${Math.round(value / 1000)} mil` : String(value)
            }
          />
          <Tooltip
            formatter={(value) => formatMoney(Number(value), currency)}
            labelFormatter={(label) => formatMonth(String(label))}
            contentStyle={{
              background: 'var(--bg-surface)',
              border: '1px solid var(--border)',
              borderRadius: 10,
              color: 'var(--ink-primary)',
            }}
          />
          <Legend
            formatter={(value) => (
              <span style={{ color: 'var(--ink-secondary)' }}>
                {value === 'income' ? 'Receitas' : 'Despesas'} ({currency})
              </span>
            )}
          />
          <Bar
            dataKey="income"
            name="income"
            fill="var(--chart-income)"
            radius={[4, 4, 0, 0]}
            maxBarSize={28}
          />
          <Bar
            dataKey="expense"
            name="expense"
            fill="var(--chart-expense)"
            radius={[4, 4, 0, 0]}
            maxBarSize={28}
          />
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}

/**
 * One chart per currency.
 *
 * <p>A bar axis carries a single denomination. Plotting reais and dollars on
 * the same scale would make the taller bar look like the larger sum, so each
 * currency gets its own chart with its own axis and an explicit heading — the
 * denomination is never left to the reader to infer.
 */
export default function TrendChart({ series }: { series: MonthTrendSeries[] }) {
  const withData = series.filter((entry) =>
    entry.points.some((point) => point.income > 0 || point.expense > 0),
  )
  if (withData.length === 0) {
    return <p className="panel-empty">Ainda não há dados suficientes para o gráfico.</p>
  }
  if (withData.length === 1) {
    return (
      <>
        <p className="stat-footnote">Valores em {currencyLabel(withData[0].currency)}.</p>
        <SeriesChart series={withData[0]} />
      </>
    )
  }
  return (
    <>
      {withData.map((entry) => (
        <section key={entry.currency} aria-label={`Evolução em ${entry.currency}`}>
          <h3 className="trend-series-title">{currencyLabel(entry.currency)}</h3>
          <SeriesChart series={entry} />
        </section>
      ))}
      <p className="stat-footnote" role="note">
        Uma série por moeda. Os valores não são somados entre moedas — isso exigiria cotações, que
        ainda não existem no Finora.
      </p>
    </>
  )
}
