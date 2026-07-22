import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, type PageResponse, queryString } from '../../lib/api'
import type { BrowserClaim, FinoraNotification, NotificationFilter, NotificationPreferences } from './types'

export const NOTIFICATIONS_KEY = ['notifications'] as const

export function useNotifications(filter: NotificationFilter = 'ACTIVE', page = 0, size = 20) {
  return useQuery({
    queryKey: [...NOTIFICATIONS_KEY, filter, page, size],
    queryFn: () => api.get<PageResponse<FinoraNotification>>(
      `/notifications${queryString({ filter, page, size })}`,
    ),
  })
}

export function useUnreadCount() {
  return useQuery({
    queryKey: [...NOTIFICATIONS_KEY, 'unread-count'],
    queryFn: () => api.get<{ count: number }>('/notifications/unread-count'),
    refetchInterval: () => (document.hidden ? false : 60_000),
  })
}

function useNotificationAction(path: (id: number) => string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => api.post<FinoraNotification>(path(id)),
    onSuccess: () => client.invalidateQueries({ queryKey: NOTIFICATIONS_KEY }),
  })
}

export const useReadNotification = () => useNotificationAction((id) => `/notifications/${id}/read`)
export const useUnreadNotification = () => useNotificationAction((id) => `/notifications/${id}/unread`)
export const useDismissNotification = () => useNotificationAction((id) => `/notifications/${id}/dismiss`)
export const useRestoreNotification = () => useNotificationAction((id) => `/notifications/${id}/restore`)

export function useSnoozeNotification() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ id, until }: { id: number; until: string }) =>
      api.post<FinoraNotification>(`/notifications/${id}/snooze`, { until }),
    onSuccess: () => client.invalidateQueries({ queryKey: NOTIFICATIONS_KEY }),
  })
}

export function useReadAllNotifications() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: () => api.post<void>('/notifications/read-all'),
    onSuccess: () => client.invalidateQueries({ queryKey: NOTIFICATIONS_KEY }),
  })
}

export function useSyncNotifications() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: () => api.post('/notifications/sync'),
    onSuccess: () => client.invalidateQueries({ queryKey: NOTIFICATIONS_KEY }),
  })
}

export function useNotificationPreferences() {
  return useQuery({
    queryKey: ['notification-preferences'],
    queryFn: () => api.get<NotificationPreferences>('/notification-preferences'),
  })
}

export function useUpdateNotificationPreferences() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (request: NotificationPreferences) =>
      api.put<NotificationPreferences>('/notification-preferences', request),
    onSuccess: (data) => {
      client.setQueryData(['notification-preferences'], data)
      client.invalidateQueries({ queryKey: NOTIFICATIONS_KEY })
    },
  })
}

export const claimBrowserNotifications = () =>
  api.post<BrowserClaim[]>('/notifications/browser-claims')
