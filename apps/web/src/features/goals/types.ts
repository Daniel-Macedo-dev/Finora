export type GoalStatus = 'IN_PROGRESS' | 'COMPLETED' | 'ARCHIVED'

export interface Goal {
  id: number
  name: string
  targetAmount: number
  currentAmount: number
  remainingAmount: number
  percentAchieved: number
  targetDate: string | null
  status: GoalStatus
  suggestedMonthlyContribution: number | null
  /** Optimistic version; an offline edit sends the one the user actually saw. */
  version: number
}

export interface GoalRequest {
  name: string
  targetAmount: number
  currentAmount?: number
  targetDate?: string | null
  archived?: boolean
}
