import { lazy } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import AppShell from './components/AppShell'
import RequireAuth from './features/auth/RequireAuth'

const LoginPage = lazy(() => import('./features/auth/LoginPage'))
const RegisterPage = lazy(() => import('./features/auth/RegisterPage'))
const DashboardPage = lazy(() => import('./features/dashboard/DashboardPage'))
const TransactionsPage = lazy(() => import('./features/transactions/TransactionsPage'))
const StatementImportsPage = lazy(
  () => import('./features/statement-imports/StatementImportsPage'),
)
const CreditCardsPage = lazy(() => import('./features/credit-cards/CreditCardsPage'))
const LegacyConversionsPage = lazy(
  () => import('./features/legacy-conversions/LegacyConversionsPage'),
)
const CreditCardDetailPage = lazy(() => import('./features/credit-cards/CreditCardDetailPage'))
const InvoiceDetailPage = lazy(() => import('./features/credit-cards/InvoiceDetailPage'))
const BudgetsPage = lazy(() => import('./features/budgets/BudgetsPage'))
const CommitmentsPage = lazy(() => import('./features/commitments/CommitmentsPage'))
const ForecastPage = lazy(() => import('./features/forecast/ForecastPage'))
const GoalsPage = lazy(() => import('./features/goals/GoalsPage'))
const WishlistPage = lazy(() => import('./features/wishlist/WishlistPage'))
const WishlistItemPage = lazy(() => import('./features/wishlist/WishlistItemPage'))
const SettingsPage = lazy(() => import('./features/settings/SettingsPage'))
const ProfilePage = lazy(() => import('./features/profile/ProfilePage'))
const NotificationsPage = lazy(() => import('./features/notifications/NotificationsPage'))

export default function App() {
  return (
    <Routes>
      {/* Public authentication pages render outside the financial shell. */}
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />

      <Route
        element={
          <RequireAuth>
            <AppShell />
          </RequireAuth>
        }
      >
        <Route index element={<Navigate to="/dashboard" replace />} />
        <Route path="/dashboard" element={<DashboardPage />} />
        <Route path="/transactions" element={<TransactionsPage />} />
        <Route path="/statement-imports" element={<StatementImportsPage />} />
        <Route path="/credit-cards" element={<CreditCardsPage />} />
        <Route path="/credit-cards/:cardId" element={<CreditCardDetailPage />} />
        <Route path="/credit-cards/:cardId/invoices/:invoiceId" element={<InvoiceDetailPage />} />
        <Route path="/legacy-credit" element={<LegacyConversionsPage />} />
        <Route path="/budgets" element={<BudgetsPage />} />
        <Route path="/commitments" element={<CommitmentsPage />} />
        <Route path="/forecast" element={<ForecastPage />} />
        <Route path="/goals" element={<GoalsPage />} />
        <Route path="/wishlist" element={<WishlistPage />} />
        <Route path="/wishlist/:id" element={<WishlistItemPage />} />
        <Route path="/settings" element={<SettingsPage />} />
        <Route path="/profile" element={<ProfilePage />} />
        <Route path="/notifications" element={<NotificationsPage />} />
        <Route path="*" element={<Navigate to="/dashboard" replace />} />
      </Route>
    </Routes>
  )
}
