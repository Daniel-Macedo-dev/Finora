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
import { formatBRL, formatDate } from '../../lib/format'
import type { Forecast } from './types'

interface ChartPoint {
  date: string
  balance: number
}

/**
 * Projected balance line over the horizon. Points come straight from the
 * backend's per-event running balance — the chart adds no financial math.
 */
export default function BalanceChart({ forecast }: { forecast: Forecast }) {
  const points: ChartPoint[] = [{ date: forecast.from, balance: forecast.openingBalance }]
  for (const event of forecast.events) {
    if (event.balanceAfter !== null) {
      points.push({ date: event.date, balance: event.balanceAfter })
    }
  }
  points.push({ date: forecast.to, balance: forecast.closingBalance })

  if (points.length <= 2 && forecast.events.length === 0) {
    return (
      <p className="panel-empty">
        Sem eventos projetados no horizonte — o saldo permanece em{' '}
        {formatBRL(forecast.openingBalance)}.
      </p>
    )
  }

  const hasNegative = forecast.lowestBalance < 0

  return (
    <div
      style={{ width: '100%', height: 260 }}
      role="img"
      aria-label={`Saldo projetado de ${formatBRL(forecast.openingBalance)} em ${formatDate(
        forecast.from,
      )} até ${formatBRL(forecast.closingBalance)} em ${formatDate(forecast.to)}; menor saldo ${formatBRL(
        forecast.lowestBalance,
      )}. Os valores estão detalhados no resumo mensal.`}
    >
      <ResponsiveContainer>
        <AreaChart data={points} margin={{ top: 8, right: 8, bottom: 0, left: 8 }}>
          <defs>
            <linearGradient id="forecast-balance-fill" x1="0" y1="0" x2="0" y2="1">
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
            formatter={(value) => [formatBRL(Number(value)), 'Saldo projetado']}
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
            fill="url(#forecast-balance-fill)"
            isAnimationActive={false}
          />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  )
}
