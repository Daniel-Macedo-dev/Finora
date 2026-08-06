import {
  Area,
  AreaChart,
  CartesianGrid,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { formatDate } from '../../lib/format'
import { formatMoney } from '../../lib/money'
import type { ForecastCurrencySummary, ForecastEvent } from './types'

interface ChartPoint {
  date: string
  balance: number
}

interface BalanceChartProps {
  summary: ForecastCurrencySummary
  /** Every event of the forecast; only this currency's are plotted. */
  events: ForecastEvent[]
  from: string
  to: string
}

/**
 * Projected balance line over the horizon, for one currency.
 *
 * <p>Points come straight from the backend's per-event running balance — the
 * chart adds no financial math. One axis carries one denomination: events of
 * another currency belong to another chart, because plotting them together
 * would make the taller line look like the larger sum.
 */
export default function BalanceChart({ summary, events, from, to }: BalanceChartProps) {
  const currency = summary.currency
  const mine = events.filter((event) => event.currency === currency)
  const points: ChartPoint[] = [{ date: from, balance: summary.openingBalance }]
  for (const event of mine) {
    if (event.balanceAfter !== null) {
      points.push({ date: event.date, balance: event.balanceAfter })
    }
  }
  points.push({ date: to, balance: summary.closingBalance })

  if (points.length <= 2 && mine.length === 0) {
    return (
      <p className="panel-empty">
        Sem eventos projetados no horizonte — o saldo permanece em{' '}
        {formatMoney(summary.openingBalance, currency)}.
      </p>
    )
  }

  const hasNegative = summary.lowestBalance < 0

  return (
    <div
      style={{ width: '100%', height: 260 }}
      role="img"
      aria-label={`Saldo projetado em ${currency}: de ${formatMoney(
        summary.openingBalance,
        currency,
      )} em ${formatDate(from)} até ${formatMoney(
        summary.closingBalance,
        currency,
      )} em ${formatDate(to)}; menor saldo ${formatMoney(
        summary.lowestBalance,
        currency,
      )}. Os valores estão detalhados no resumo mensal.`}
    >
      <ResponsiveContainer>
        <AreaChart data={points} margin={{ top: 8, right: 8, bottom: 0, left: 8 }}>
          <defs>
            <linearGradient id={`forecast-balance-fill-${currency}`} x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor="var(--chart-income)" stopOpacity={0.25} />
              <stop offset="100%" stopColor="var(--chart-income)" stopOpacity={0.02} />
            </linearGradient>
          </defs>
          <CartesianGrid stroke="var(--chart-grid)" vertical={false} />
          <XAxis
            dataKey="date"
            tickFormatter={(value: string) => formatDate(value).slice(0, 5)}
            stroke="var(--chart-axis)"
            tickLine={false}
            fontSize={12}
            minTickGap={32}
          />
          <YAxis
            stroke="var(--chart-axis)"
            tickLine={false}
            axisLine={false}
            fontSize={12}
            width={72}
            tickFormatter={(value: number) =>
              Math.abs(value) >= 1000 ? `${Math.round(value / 1000)} mil` : String(value)
            }
          />
          <Tooltip
            formatter={(value) => [
              formatMoney(Number(value), currency),
              `Saldo projetado (${currency})`,
            ]}
            labelFormatter={(label) => formatDate(String(label))}
            contentStyle={{
              background: 'var(--bg-surface)',
              border: '1px solid var(--border)',
              borderRadius: 10,
              color: 'var(--ink-primary)',
            }}
          />
          {hasNegative && (
            <ReferenceLine y={0} stroke="var(--negative)" strokeDasharray="4 4" />
          )}
          <Area
            type="stepAfter"
            dataKey="balance"
            stroke="var(--chart-income)"
            strokeWidth={2}
            fill={`url(#forecast-balance-fill-${currency})`}
            isAnimationActive={false}
          />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  )
}
