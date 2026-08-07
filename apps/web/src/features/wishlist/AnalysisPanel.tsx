import {
  CheckCircle2,
  AlertTriangle,
  Clock,
  Info,
  ShieldCheck,
  ShieldAlert,
} from 'lucide-react'
import Money from '../../components/Money'
import { formatPercent } from '../../lib/format'
import { currencyLabel, formatMoney, type CurrencyCode } from '../../lib/money'
import type {
  AvailablePurchaseAnalysis,
  PurchaseAnalysis,
  RecommendationType,
  UnavailablePurchaseAnalysis,
} from './types'
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

/**
 * The analysis needs to compare amounts that are not comparable.
 *
 * <p>Rendered instead of — never alongside — the recommendation, so no
 * conclusion, badge or figure derived from mixed currencies can appear. The
 * item, its options and its price history stay on the page around this panel;
 * only the financial verdict is withheld, and the reason is stated plainly
 * rather than left as an error.
 */
function UnavailableAnalysis({ analysis }: { analysis: UnavailablePurchaseAnalysis }) {
  return (
    <div className="analysis">
      <section className="analysis-unavailable" aria-label="Análise indisponível">
        {/* A heading, like the available panel's sections: this state is part of
            the page outline, not a stray paragraph a screen-reader user has to
            scroll past the analysis to discover. */}
        <h3 className="analysis-unavailable-title">
          <Info size={16} aria-hidden="true" /> Análise financeira indisponível
        </h3>
        <p className="analysis-explanation">
          Esta análise precisa comparar valores em moedas diferentes. O Finora ainda não possui
          cotações para fazer essa conversão sem distorcer os resultados.
        </p>
        <dl className="analysis-unavailable-currencies">
          <div>
            <dt>Moeda do item</dt>
            <dd>{currencyLabel(analysis.itemCurrency)}</dd>
          </div>
          <div>
            <dt>Sua moeda base</dt>
            <dd>{currencyLabel(analysis.baseCurrency)}</dd>
          </div>
          {analysis.missingCurrencies.length > 0 && (
            <div>
              <dt>Precisaria converter</dt>
              <dd>{analysis.missingCurrencies.join(', ')}</dd>
            </div>
          )}
        </dl>
        {analysis.unavailableReasons.length > 0 && (
          <ul className="analysis-unavailable-reasons">
            {analysis.unavailableReasons.map((reason) => (
              <li key={reason.code}>{reason.message}</li>
            ))}
          </ul>
        )}
        <p className="analysis-unavailable-note">
          As opções de compra e o histórico de preços continuam disponíveis nesta página.
        </p>
      </section>
    </div>
  )
}

export default function AnalysisPanel({ analysis }: { analysis: PurchaseAnalysis }) {
  if (analysis.availability === 'EXCHANGE_RATE_REQUIRED') {
    return <UnavailableAnalysis analysis={analysis} />
  }
  return <AvailableAnalysis analysis={analysis} />
}

function AvailableAnalysis({ analysis }: { analysis: AvailablePurchaseAnalysis }) {
  // Available implies the item and the base currency agree, so one currency
  // labels every figure below.
  const currency: CurrencyCode = analysis.itemCurrency
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
              <strong>{formatMoney(analysis.recommendation.requiredAdditionalCash, currency)}</strong>
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
                              : `${option.installmentCount}× de ${formatMoney(option.monthlyBurden, currency)}`}
                          </span>
                        </div>
                      </td>
                      <td style={{ textAlign: 'right' }}>
                        <Money currency={currency} value={option.nominalCost} />
                      </td>
                      <td style={{ textAlign: 'right' }}>
                        <Money currency={currency} value={option.presentValue} />
                      </td>
                      <td style={{ textAlign: 'right' }}>
                        {option.monthlyBurden !== null ? (
                          <Money currency={currency} value={option.monthlyBurden} />
                        ) : (
                          <span className="stat-footnote">—</span>
                        )}
                      </td>
                      <td style={{ textAlign: 'right' }}>
                        <Money currency={currency} value={option.cashAfterPurchase} signed />
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
            <dd>{formatMoney(assumptions.availableCash, currency)}</dd>
          </div>
          <div>
            <dt>Reserva mínima</dt>
            <dd>{formatMoney(assumptions.minimumCashBuffer, currency)}</dd>
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
                ? formatMoney(assumptions.avgMonthlyIncome, currency)
                : 'Sem histórico'}
            </dd>
          </div>
          <div>
            <dt>Sobra média mensal</dt>
            <dd>
              {assumptions.avgMonthlySurplus !== null
                ? formatMoney(assumptions.avgMonthlySurplus, currency)
                : 'Sem histórico'}
            </dd>
          </div>
          <div>
            <dt>Recorrentes do próximo mês</dt>
            <dd>{formatMoney(assumptions.monthlyCommitments, currency)}</dd>
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
