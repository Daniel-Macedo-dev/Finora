import { AlertOctagon, AlertTriangle, CheckCircle2, Info } from 'lucide-react'
import { currencyLabel, formatMoney } from '../../lib/money'
import type { AggregateCoverage, Insight, InsightSeverity, InsightsData } from './types'

const INSIGHT_ICONS: Record<InsightSeverity, typeof Info> = {
  POSITIVE: CheckCircle2,
  INFO: Info,
  WARNING: AlertTriangle,
  CRITICAL: AlertOctagon,
}

const INSIGHT_BADGES: Record<InsightSeverity, string> = {
  POSITIVE: 'badge-positive',
  INFO: 'badge-info',
  WARNING: 'badge-warning',
  CRITICAL: 'badge-negative',
}

const SEVERITY_LABELS: Record<InsightSeverity, string> = {
  POSITIVE: 'Oportunidade',
  INFO: 'Informação',
  WARNING: 'Atenção',
  CRITICAL: 'Crítico',
}

/**
 * What a withheld rule meant to the reader.
 *
 * The backend identifiers are a stable contract, not copy — several of them
 * describe one thing a person would recognise, and none of them belongs on
 * screen. Rules sharing a phrase collapse into it, in the order they arrived.
 */
const COVERAGE_GROUPS: Record<string, string> = {
  EXPENSE_INCREASE: 'a comparação de gastos do mês',
  CATEGORY_DOMINANT: 'a comparação de gastos do mês',
  BUDGET_STATUS: 'o acompanhamento de orçamentos',
  COMMITMENT_SHARE_HIGH: 'o peso dos compromissos e parcelas na renda',
  CARD_INSTALLMENT_BURDEN_HIGH: 'o peso dos compromissos e parcelas na renda',
  GOAL_OFF_PACE: 'o ritmo das metas',
  WISHLIST_AFFORDABLE: 'a viabilidade das compras planejadas',
}

function coverageGroups(rules: string[]): string[] {
  const groups: string[] = []
  for (const rule of rules) {
    const label = COVERAGE_GROUPS[rule]
    if (label && !groups.includes(label)) {
      groups.push(label)
    }
  }
  return groups
}

function InsightCard({ insight }: { insight: Insight }) {
  const Icon = INSIGHT_ICONS[insight.severity]
  return (
    <li className="insight-item">
      <span className={`badge ${INSIGHT_BADGES[insight.severity]}`}>
        <Icon size={13} aria-hidden="true" />
        {SEVERITY_LABELS[insight.severity]}
      </span>
      <div>
        <p className="insight-title">{insight.title}</p>
        <p className="insight-message">{insight.message}</p>
        {/* An amount only ever renders with the currency the server sent for
            it; there is no fallback, because a wrong denomination is a wrong
            figure. An absent amount renders nothing at all, never a zero. */}
        {insight.amount !== null && insight.currency !== null && (
          <p className="insight-amount">{formatMoney(insight.amount, insight.currency)}</p>
        )}
      </div>
    </li>
  )
}

/**
 * One bounded explanation for everything the month's currencies withheld.
 *
 * Deliberately not an alert and not a live region: nothing failed, the state is
 * stable for as long as the data is, and interrupting a screen reader to
 * announce a standing limitation is noise. It appears once however many rules
 * were withheld, and the insights that could be produced stay listed beside it.
 */
function CoverageNotice({ coverage }: { coverage: AggregateCoverage }) {
  const groups = coverageGroups(coverage.unavailableRules)
  return (
    <div className="insight-coverage">
      <p className="insight-coverage-title">
        <Info size={15} aria-hidden="true" /> Algumas análises consolidadas ficaram de fora
      </p>
      {/* Two sentences rather than one: the list of groups can hold one item or
          four, and a single clause would have to agree with both. */}
      <p className="insight-coverage-message">
        Existem valores em moedas que o Finora ainda não pode converter com segurança. Ficaram de
        fora: {groups.length > 0 ? groups.join(', ') : 'parte das análises consolidadas'}.
      </p>
      {coverage.missingCurrencies.length > 0 && (
        <p className="insight-coverage-currencies">
          Precisaria converter: {coverage.missingCurrencies.map(currencyLabel).join('; ')}
        </p>
      )}
    </div>
  )
}

/**
 * The month's findings, plus an honest note about the ones that could not be
 * produced. A withheld aggregate never becomes an empty card: it is absent from
 * the list entirely and explained once, above it.
 */
export default function InsightsPanel({ data }: { data: InsightsData }) {
  return (
    <>
      {!data.aggregateCoverage.complete && <CoverageNotice coverage={data.aggregateCoverage} />}
      {data.insights.length === 0 ? (
        <p className="panel-empty">
          Nada digno de nota por enquanto — os insights aparecem conforme os dados evoluem.
        </p>
      ) : (
        <ul className="insight-list">
          {data.insights.map((insight, index) => (
            <InsightCard key={`${insight.type}-${index}`} insight={insight} />
          ))}
        </ul>
      )}
    </>
  )
}
