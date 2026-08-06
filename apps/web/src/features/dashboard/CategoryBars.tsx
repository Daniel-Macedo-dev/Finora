import Money from '../../components/Money'
import { formatPercent } from '../../lib/format'
import type { CategoryShare } from './types'
import './CategoryBars.css'

/**
 * Horizontal share bars for the month's top expense categories — reads better
 * than a pie for comparison and needs no color legend.
 *
 * <p>A share is measured against the month's expenses <em>in that same
 * currency</em>, which is a ratio between comparable operands. The currency is
 * therefore part of every row's label rather than an unstated assumption, and a
 * row whose share cannot be measured shows no bar instead of a misleading one.
 */
export default function CategoryBars({ categories }: { categories: CategoryShare[] }) {
  return (
    <ul className="category-bars">
      {categories.map((category) => {
        const percent = category.percentOfTotal
        const label =
          percent === null
            ? `${category.categoryName}: participação indisponível`
            : `${category.categoryName}: ${formatPercent(percent)} das despesas em ${category.currency}`
        return (
          <li key={`${category.categoryId}-${category.currency}`} className="category-bar-row">
            <div className="category-bar-header">
              <span className="category-bar-name">
                {category.categoryName}{' '}
                <span className="currency-total-code">{category.currency}</span>
              </span>
              <span className="category-bar-meta">
                <Money value={category.amount} currency={category.currency} />{' '}
                {percent !== null && (
                  <span className="category-bar-percent">({formatPercent(percent)})</span>
                )}
              </span>
            </div>
            <div className="category-bar-track" role="img" aria-label={label}>
              {percent !== null && (
                <div
                  className="category-bar-fill"
                  style={{ width: `${Math.min(percent, 100)}%` }}
                />
              )}
            </div>
          </li>
        )
      })}
    </ul>
  )
}
