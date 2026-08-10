import type { CurrencyCode } from '../../lib/money'

export type NotificationSeverity = 'INFO' | 'WARNING' | 'CRITICAL'
export type NotificationFilter = 'ACTIVE' | 'UNREAD' | 'SNOOZED' | 'DISMISSED' | 'RESOLVED' | 'ALL'

export interface FinoraNotification {
  id: number
  sourceKey: string
  type: string
  severity: NotificationSeverity
  eventDate: string
  title: string
  amount: number | null
  /** Denomination of `amount`, sent by the server so nothing has to guess. */
  currency: CurrencyCode
  resourceType: string
  resourceId: number | null
  route: string
  revision: number
  unread: boolean
  dismissed: boolean
  snoozed: boolean
  snoozedUntil: string | null
  firstSeenAt: string
  lastSeenAt: string
  resolvedAt: string | null
}

export interface NotificationPreferences {
  enabled: boolean
  upcomingLeadDays: number
  recurringDueEnabled: boolean
  invoiceDueEnabled: boolean
  executionFailureEnabled: boolean
  cashRiskEnabled: boolean
  browserEnabled: boolean
  browserMinimumSeverity: NotificationSeverity
  browserShowAmounts: boolean
  browserEnabledAt: string | null
}

/**
 * One foreground browser delivery.
 *
 * `amount` and `currency` arrive together or not at all — when the user keeps
 * amounts private the server sends neither, because naming the currency of a
 * withheld sum gives away most of what withholding it protected. There is no
 * source key: tabs de-duplicate on `id` + `revision`, and a source key can name
 * the very denomination the claim is allowed to hide.
 */
export interface BrowserClaim {
  id: number
  revision: number
  title: string
  amount: number | null
  currency: CurrencyCode | null
  route: string
}
