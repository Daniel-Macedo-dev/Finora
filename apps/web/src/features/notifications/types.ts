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

export interface BrowserClaim {
  id: number
  sourceKey: string
  revision: number
  title: string
  amount: number | null
  route: string
}
