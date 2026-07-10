import { useQuery } from '@tanstack/react-query'
import { api } from '../../lib/api'
import type { Account, Category, TransactionType } from './types'

export function useCategories(type?: TransactionType) {
  return useQuery({
    queryKey: ['categories', type ?? 'all'],
    queryFn: () => api.get<Category[]>(`/categories${type ? `?type=${type}` : ''}`),
  })
}

export function useAccounts() {
  return useQuery({
    queryKey: ['accounts'],
    queryFn: () => api.get<Account[]>('/accounts'),
  })
}
