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
}

export interface GoalRequest {
  name: string
  targetAmount: number
  currentAmount?: number
  targetDate?: string | null
  archived?: boolean
}
