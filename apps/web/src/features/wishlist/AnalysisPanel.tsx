import {
  CheckCircle2,
  AlertTriangle,
  Clock,
  Info,
  ShieldCheck,
  ShieldAlert,
} from 'lucide-react'
import Money from '../../components/Money'
import { formatBRL, formatPercent } from '../../lib/format'
import type { PurchaseAnalysis, RecommendationType } from './types'
import './AnalysisPanel.css'

const RECOMMENDATION_META: Record<
  RecommendationType,
  { label: string; badge: string; icon: typeof CheckCircle2 }
> = {
  BUY_CASH: { label: 'Comprar à vista', badge: 'badge-positive', icon: CheckCircle2 },
  BUY_INSTALLMENT: { label: 'Comprar parcelado', badge: 'badge-positive', icon: CheckCircle2 },
  WAIT: { label: 'Aguardar', badge: 'badge-warning', icon: Clock },
  NO_OPTIONS: { label: 'Sem opções', badge: 'badge-neutral', icon: Info },
}

export default function AnalysisPanel({ analysis }: { analysis: PurchaseAnalysis }) {
  const meta = RECOMMENDATION_META[analysis.recommendation.type]
  const RecommendationIcon = meta.icon
  const assumptions = analysis.assumptions

  return (
    <div className="analysis">
      <section className="analysis-recommendation" aria-label="Recomendação">
        <span className={`badge ${meta.badge} analysis-recommendation-badge`}>
          <RecommendationIcon size={14} aria-hidden="true" />
          {meta.label}
        </span>
        <p className="analysis-explanation">{analysis.recommendation.explanation}</p>
        {analysis.recommendation.type === 'WAIT' &&
          analysis.recommendation.requiredAdditionalCash !== null && (
            <p className="analysis-wait-detail">
              Faltam aproximadamente{' '}
              <strong>{formatBRL(analysis.recommendation.requiredAdditionalCash)}</strong>
              {analysis.recommendation.estimatedMonthsToAfford !== null && (
                <>
                  {' '}
                  — cerca de{' '}
                  <strong>
                    {analysis.recommendation.estimatedMonthsToAfford}{' '}
                    {analysis.recommendation.estimatedMonthsToAfford === 1 ? 'mês' : 'meses'}
                  </strong>{' '}
                  guardando a sobra média mensal
                </>
              )}
              .
            </p>
          )}
        {analysis.recommendation.warnings.length > 0 && (
          <ul className="analysis-warnings">
            {analysis.recommendation.warnings.map((warning, index) => (
              <li key={index}>
                <AlertTriangle size={14} aria-hidden="true" />
                {warning}
              </li>
            ))}
          </ul>
        )}
      </section>

      {analysis.options.length > 0 && (
        <section aria-label="Comparação de opções">
          <h3 className="analysis-section-title">Comparação das opções</h3>
          <div className="table-wrap">
            <table className="data analysis-table">
              <thead>
                <tr>
                  <th scope="col">Opção</th>
                  <th scope="col" style={{ textAlign: 'right' }}>
                    Custo nominal
                  </th>
                  <th scope="col" style={{ textAlign: 'right' }}>
                    Valor presente
                  </th>
                  <th scope="col" style={{ textAlign: 'right' }}>
                    Peso mensal
                  </th>
                  <th scope="col" style={{ textAlign: 'right' }}>
                    Caixa após compra
                  </th>
                  <th scope="col">Segurança</th>
                </tr>
              </thead>
              <tbody>
                {analysis.options.map((option) => {
                  const recommended =
                    option.optionId === analysis.recommendation.recommendedOptionId
                  return (
                    <tr
                      key={option.optionId}
                      className={recommended ? 'analysis-row-recommended' : ''}
                    >
                      <td>
                        <div className="analysis-option-cell">
                          <span className="analysis-merchant">
                            {option.merchant}
                            {recommended && (
                              <span className="badge badge-positive">Recomendada</span>
                            )}
                          </span>
                          <span className="stat-footnote">
                            {option.kind === 'CASH'
                              ? 'À vista'
                              : `${option.installmentCount}× de ${formatBRL(option.monthlyBurden)}`}
                          </span>
                        </div>
                      </td>
                      <td style={{ textAlign: 'right' }}>
                        <Money value={option.nominalCost} />
                      </td>
                      <td style={{ textAlign: 'right' }}>
                        <Money value={option.presentValue} />
                      </td>
                      <td style={{ textAlign: 'right' }}>
                        {option.monthlyBurden !== null ? (
                          <Money value={option.monthlyBurden} />
                        ) : (
                          <span className="stat-footnote">—</span>
                        )}
                      </td>
                      <td style={{ textAlign: 'right' }}>
                        <Money value={option.cashAfterPurchase} signed />
                      </td>
                      <td>
                        {option.safe ? (
                          <span className="badge badge-positive">
                            <ShieldCheck size={13} aria-hidden="true" />
                            Segura
                          </span>
                        ) : (
                          <span className="badge badge-negative">
                            <ShieldAlert size={13} aria-hidden="true" />
                            Arriscada
                          </span>
                        )}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>

          {analysis.options.some((option) => option.issues.length > 0) && (
            <ul className="analysis-issues" aria-label="Alertas por opção">
              {analysis.options.flatMap((option) =>
                option.issues.map((issue, index) => (
                  <li
                    key={`${option.optionId}-${index}`}
                    className={issue.blocking ? 'issue-blocking' : 'issue-warning'}
                  >
                    {issue.blocking ? (
                      <ShieldAlert size={14} aria-hidden="true" />
                    ) : (
                      <Info size={14} aria-hidden="true" />
                    )}
                    <span>
                      <strong>{option.merchant}:</strong> {issue.message}
                    </span>
                  </li>
                )),
              )}
            </ul>
          )}
        </section>
      )}

      <section aria-label="Premissas da análise">
        <h3 className="analysis-section-title">Premissas usadas</h3>
        <dl className="analysis-assumptions">
          <div>
            <dt>Caixa disponível</dt>
            <dd>{formatBRL(assumptions.availableCash)}</dd>
          </div>
          <div>
            <dt>Reserva mínima</dt>
            <dd>{formatBRL(assumptions.minimumCashBuffer)}</dd>
          </div>
          <div>
            <dt>Taxa de oportunidade</dt>
            <dd>{formatPercent(assumptions.monthlyOpportunityRate * 100)} ao mês</dd>
          </div>
          <div>
            <dt>Comprometimento máx. da renda</dt>
            <dd>{formatPercent(assumptions.maxInstallmentCommitmentRatio * 100)}</dd>
          </div>
          <div>
            <dt>Renda média mensal</dt>
            <dd>
              {assumptions.avgMonthlyIncome !== null
                ? formatBRL(assumptions.avgMonthlyIncome)
                : 'Sem histórico'}
            </dd>
          </div>
          <div>
            <dt>Sobra média mensal</dt>
            <dd>
              {assumptions.avgMonthlySurplus !== null
                ? formatBRL(assumptions.avgMonthlySurplus)
                : 'Sem histórico'}
            </dd>
          </div>
          <div>
            <dt>Recorrentes do próximo mês</dt>
            <dd>{formatBRL(assumptions.monthlyCommitments)}</dd>
          </div>
          <div>
            <dt>Meses de histórico usados</dt>
            <dd>{assumptions.historyMonthsUsed} de 3</dd>
          </div>
        </dl>
        <p className="analysis-disclaimer">
          Análise baseada nos seus dados no Finora e nas premissas configuráveis — é uma projeção,
          não aconselhamento financeiro.
        </p>
      </section>
    </div>
  )
}
