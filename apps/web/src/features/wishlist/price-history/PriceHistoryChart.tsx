import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { formatBRL, formatDate } from '../../../lib/format'
import type { PriceChartResponse } from '../types'

export default function PriceHistoryChart({ data }: { data: PriceChartResponse }) {
  return <>
    <p className="sr-only">Gráfico com {data.points.length} dias, de {formatDate(data.points[0].observedOn)} a {formatDate(data.points.at(-1)!.observedOn)}.</p>
    <div className="price-chart" role="img" aria-label="Linha da evolução dos preços observados">
      <ResponsiveContainer width="100%" height="100%"><LineChart data={data.points}>
        <CartesianGrid strokeDasharray="3 3" /><XAxis dataKey="observedOn" tickFormatter={formatDate} />
        <YAxis tickFormatter={(value) => formatBRL(Number(value))} width={92} />
        <Tooltip formatter={(value) => formatBRL(Number(value))} labelFormatter={(value) => formatDate(String(value))} />
        <Line type="monotone" dataKey="nominalCost" name="Menor preço" stroke="var(--brand)" strokeWidth={3} dot />
      </LineChart></ResponsiveContainer>
    </div>
  </>
}
