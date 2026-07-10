import { lazy } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import AppShell from './components/AppShell'

const DashboardPage = lazy(() => import('./features/dashboard/DashboardPage'))
const TransactionsPage = lazy(() => import('./features/transactions/TransactionsPage'))
const BudgetsPage = lazy(() => import('./features/budgets/BudgetsPage'))
const CommitmentsPage = lazy(() => import('./features/commitments/CommitmentsPage'))
const GoalsPage = lazy(() => import('./features/goals/GoalsPage'))
const WishlistPage = lazy(() => import('./features/wishlist/WishlistPage'))
const WishlistItemPage = lazy(() => import('./features/wishlist/WishlistItemPage'))
const SettingsPage = lazy(() => import('./features/settings/SettingsPage'))

export default function App() {
  return (
      <Routes>
        <Route element={<AppShell />}>
          <Route index element={<Navigate to="/dashboard" replace />} />
          <Route path="/dashboard" element={<DashboardPage />} />
          <Route path="/transactions" element={<TransactionsPage />} />
          <Route path="/budgets" element={<BudgetsPage />} />
          <Route path="/commitments" element={<CommitmentsPage />} />
          <Route path="/goals" element={<GoalsPage />} />
          <Route path="/wishlist" element={<WishlistPage />} />
          <Route path="/wishlist/:id" element={<WishlistItemPage />} />
          <Route path="/settings" element={<SettingsPage />} />
          <Route path="*" element={<Navigate to="/dashboard" replace />} />
        </Route>
      </Routes>
  )
}
