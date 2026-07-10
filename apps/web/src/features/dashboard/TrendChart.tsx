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
import { formatBRL, formatMonth } from '../../lib/format'
import type { MonthTrendPoint } from './types'

const SHORT_MONTHS = ['jan', 'fev', 'mar', 'abr', 'mai', 'jun', 'jul', 'ago', 'set', 'out', 'nov', 'dez']

function shortMonth(month: string): string {
  const [, monthPart] = month.split('-')
  return SHORT_MONTHS[Number(monthPart) - 1] ?? month
}

export default function TrendChart({ points }: { points: MonthTrendPoint[] }) {
  const hasData = points.some((point) => point.income > 0 || point.expense > 0)
  if (!hasData) {
    return <p className="panel-empty">Ainda não há dados suficientes para o gráfico.</p>
  }
  return (
    <div style={{ width: '100%', height: 240 }}>
      <ResponsiveContainer>
        <BarChart data={points} margin={{ top: 8, right: 8, bottom: 0, left: 8 }} barGap={2}>
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
            formatter={(value) => formatBRL(Number(value))}
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
                {value === 'income' ? 'Receitas' : 'Despesas'}
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
