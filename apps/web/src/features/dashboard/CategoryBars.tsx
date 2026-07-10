import Money from '../../components/Money'
import { formatPercent } from '../../lib/format'
import type { CategoryShare } from './types'
import './CategoryBars.css'

/**
 * Horizontal share bars for the month's top expense categories — reads better
 * than a pie for comparison and needs no color legend.
 */
export default function CategoryBars({ categories }: { categories: CategoryShare[] }) {
  return (
    <ul className="category-bars">
      {categories.map((category) => (
        <li key={category.categoryId} className="category-bar-row">
          <div className="category-bar-header">
            <span className="category-bar-name">{category.categoryName}</span>
            <span className="category-bar-meta">
              <Money value={category.amount} />{' '}
              <span className="category-bar-percent">
                ({formatPercent(category.percentOfTotal)})
              </span>
            </span>
          </div>
          <div
            className="category-bar-track"
            role="img"
            aria-label={`${category.categoryName}: ${formatPercent(category.percentOfTotal)} das despesas`}
          >
            <div
              className="category-bar-fill"
              style={{ width: `${Math.min(category.percentOfTotal, 100)}%` }}
            />
          </div>
        </li>
      ))}
    </ul>
  )
}
