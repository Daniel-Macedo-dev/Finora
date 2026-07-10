import { useState } from 'react'
import { Link } from 'react-router-dom'
import {
  TrendingUp,
  TrendingDown,
  Wallet,
  PiggyBank,
  AlertTriangle,
  Info,
  CheckCircle2,
  AlertOctagon,
} from 'lucide-react'
import PageHeader from '../../components/PageHeader'
import MonthPicker from '../../components/MonthPicker'
import Money from '../../components/Money'
import { EmptyState, ErrorState, LoadingCards } from '../../components/states'
import { currentMonth } from '../../lib/month'
import { formatBRL, formatDate, formatPercent } from '../../lib/format'
import { useDashboard, useInsights } from './api'
import TrendChart from './TrendChart'
import CategoryBars from './CategoryBars'
import type { Insight, InsightSeverity } from './types'
import './dashboard.css'

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

function InsightCard({ insight }: { insight: Insight }) {
  const Icon = INSIGHT_ICONS[insight.severity]
  return (
    <li className="insight-item">
      <span className={`badge ${INSIGHT_BADGES[insight.severity]}`}>
        <Icon size={13} aria-hidden="true" />
        {insight.severity === 'POSITIVE'
          ? 'Oportunidade'
          : insight.severity === 'INFO'
            ? 'Informação'
            : insight.severity === 'WARNING'
              ? 'Atenção'
              : 'Crítico'}
      </span>
      <div>
        <p className="insight-title">{insight.title}</p>
        <p className="insight-message">{insight.message}</p>
      </div>
    </li>
  )
}

export default function DashboardPage() {
  const [month, setMonth] = useState(currentMonth())
  const dashboard = useDashboard(month)
  const insights = useInsights(month)

  if (dashboard.isPending) {
    return (
      <>
        <PageHeader title="Visão geral" />
        <LoadingCards count={4} height={110} />
      </>
    )
  }

  if (dashboard.isError) {
    return (
      <>
        <PageHeader title="Visão geral" />
        <ErrorState error={dashboard.error} onRetry={() => dashboard.refetch()} />
      </>
    )
  }

  const data = dashboard.data
  const hasAnyData =
    data.income > 0 ||
    data.expense > 0 ||
    data.recentTransactions.length > 0 ||
    data.totalBalance !== 0

  return (
    <>
      <PageHeader
        title="Visão geral"
        description="Resumo do mês com base nas transações registradas."
        actions={<MonthPicker month={month} onChange={setMonth} />}
      />

      {!hasAnyData ? (
        <EmptyState
          title="Sem dados por enquanto"
          description="Registre suas primeiras transações para ver o resumo financeiro do mês."
          action={
            <Link className="btn btn-primary" to="/transactions">
              Registrar transações
            </Link>
          }
        />
      ) : (
        <div className="dash-grid">
          <section className="dash-stats" aria-label="Indicadores do mês">
            <div className="card stat-card">
              <span className="stat-label">
                <Wallet size={15} aria-hidden="true" /> Saldo total
              </span>
              <span className="stat-value">
                <Money value={data.totalBalance} />
              </span>
              <span className="stat-footnote">Somando todas as contas ativas</span>
            </div>
            <div className="card stat-card">
              <span className="stat-label">
                <TrendingUp size={15} aria-hidden="true" /> Receitas
              </span>
              <span className="stat-value stat-income">
                <Money value={data.income} />
              </span>
              <span className="stat-footnote">
                {data.savingsRate !== null
                  ? `Taxa de poupança: ${formatPercent(data.savingsRate)}`
                  : 'Sem receitas neste mês'}
              </span>
            </div>
            <div className="card stat-card">
              <span className="stat-label">
                <TrendingDown size={15} aria-hidden="true" /> Despesas
              </span>
              <span className="stat-value stat-expense">
                <Money value={data.expense} />
              </span>
              <span className="stat-footnote">
                {data.expenseVariationPercent !== null
                  ? `${data.expenseVariationPercent > 0 ? '+' : ''}${formatPercent(
                      data.expenseVariationPercent,
                    )} vs mês anterior`
                  : 'Sem base de comparação anterior'}
              </span>
            </div>
            <div className="card stat-card">
              <span className="stat-label">
                <PiggyBank size={15} aria-hidden="true" /> Resultado do mês
              </span>
              <span className="stat-value">
                <Money value={data.monthResult} signed />
              </span>
              <span className="stat-footnote">Receitas menos despesas</span>
            </div>
          </section>

          <section className="card dash-panel" aria-label="Evolução mensal">
            <h2 className="panel-title">Evolução (últimos 6 meses)</h2>
            <TrendChart points={data.trend} />
          </section>

          <section className="card dash-panel" aria-label="Principais categorias">
            <h2 className="panel-title">Onde o dinheiro foi</h2>
            {data.topCategories.length === 0 ? (
              <p className="panel-empty">Nenhuma despesa registrada neste mês.</p>
            ) : (
              <CategoryBars categories={data.topCategories} />
            )}
          </section>

          <section className="card dash-panel" aria-label="Orçamentos">
            <h2 className="panel-title">Orçamentos do mês</h2>
            {data.budgets.budgetCount === 0 ? (
              <p className="panel-empty">
                Nenhum orçamento definido. <Link to="/budgets">Criar orçamentos</Link>
              </p>
            ) : (
              <>
                <div className="budget-overview">
                  <div>
                    <span className="stat-footnote">Consumido</span>
                    <p className="budget-overview-value">
                      {formatBRL(data.budgets.totalConsumed)}{' '}
                      <span className="stat-footnote">
                        de {formatBRL(data.budgets.totalLimit)} (
                        {formatPercent(data.budgets.percentUsed)})
                      </span>
                    </p>
                  </div>
                  <div className="budget-overview-badges">
                    {data.budgets.exceededCount > 0 && (
                      <span className="badge badge-negative">
                        <AlertOctagon size={13} aria-hidden="true" />
                        {data.budgets.exceededCount} estourado(s)
                      </span>
                    )}
                    {data.budgets.warningCount > 0 && (
                      <span className="badge badge-warning">
                        <AlertTriangle size={13} aria-hidden="true" />
                        {data.budgets.warningCount} perto do limite
                      </span>
                    )}
                    {data.budgets.exceededCount === 0 && data.budgets.warningCount === 0 && (
                      <span className="badge badge-positive">
                        <CheckCircle2 size={13} aria-hidden="true" />
                        Tudo sob controle
                      </span>
                    )}
                  </div>
                </div>
                <Link to="/budgets" className="panel-link">
                  Ver orçamentos
                </Link>
              </>
            )}
          </section>

          <section className="card dash-panel" aria-label="Próximos compromissos">
            <h2 className="panel-title">Próximos compromissos</h2>
            {data.upcomingCommitments.length === 0 ? (
              <p className="panel-empty">
                Nenhum compromisso recorrente próximo.{' '}
                <Link to="/commitments">Cadastrar recorrentes</Link>
              </p>
            ) : (
              <>
                <ul className="mini-list">
                  {data.upcomingCommitments.slice(0, 5).map((commitment) => (
                    <li key={`${commitment.commitmentId}-${commitment.dueDate}`}>
                      <span className="mini-list-main">{commitment.description}</span>
                      <span className="mini-list-meta">{formatDate(commitment.dueDate)}</span>
                      <Money value={commitment.amount} />
                    </li>
                  ))}
                </ul>
                <p className="stat-footnote">
                  Total projetado: {formatBRL(data.upcomingCommitmentsTotal)}
                </p>
              </>
            )}
          </section>

          <section className="card dash-panel" aria-label="Metas">
            <h2 className="panel-title">Metas</h2>
            {data.goals.length === 0 ? (
              <p className="panel-empty">
                Nenhuma meta criada. <Link to="/goals">Criar metas</Link>
              </p>
            ) : (
              <ul className="mini-list">
                {data.goals.slice(0, 4).map((goal) => (
                  <li key={goal.id}>
                    <span className="mini-list-main">{goal.name}</span>
                    <progress
                      max={100}
                      value={goal.percentAchieved}
                      aria-label={`${goal.name}: ${formatPercent(goal.percentAchieved)} alcançado`}
                    />
                    <span className="mini-list-meta">{formatPercent(goal.percentAchieved)}</span>
                  </li>
                ))}
              </ul>
            )}
          </section>

          <section className="card dash-panel dash-panel-wide" aria-label="Insights">
            <h2 className="panel-title">Insights</h2>
            {insights.isPending ? (
              <p className="panel-empty">Analisando dados…</p>
            ) : insights.isError ? (
              <p className="panel-empty">Não foi possível gerar os insights agora.</p>
            ) : insights.data.insights.length === 0 ? (
              <p className="panel-empty">
                Nada digno de nota por enquanto — os insights aparecem conforme os dados evoluem.
              </p>
            ) : (
              <ul className="insight-list">
                {insights.data.insights.map((insight, index) => (
                  <InsightCard key={`${insight.type}-${index}`} insight={insight} />
                ))}
              </ul>
            )}
          </section>

          <section className="card dash-panel dash-panel-wide" aria-label="Transações recentes">
            <h2 className="panel-title">Transações recentes</h2>
            {data.recentTransactions.length === 0 ? (
              <p className="panel-empty">Nenhuma transação registrada ainda.</p>
            ) : (
              <div className="table-wrap">
                <table className="data">
                  <thead>
                    <tr>
                      <th scope="col">Data</th>
                      <th scope="col">Descrição</th>
                      <th scope="col">Categoria</th>
                      <th scope="col" style={{ textAlign: 'right' }}>
                        Valor
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.recentTransactions.slice(0, 8).map((transaction) => (
                      <tr key={transaction.id}>
                        <td>{formatDate(transaction.date)}</td>
                        <td>{transaction.description}</td>
                        <td>
                          <span className="badge badge-neutral">
                            {transaction.category.name}
                          </span>
                        </td>
                        <td style={{ textAlign: 'right' }}>
                          <Money
                            value={
                              transaction.type === 'EXPENSE'
                                ? -transaction.amount
                                : transaction.amount
                            }
                            signed
                          />
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>
        </div>
      )}
    </>
  )
}
