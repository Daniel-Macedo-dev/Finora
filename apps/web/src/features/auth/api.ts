import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, ApiError } from '../../lib/api'
import type { AuthUser, LoginRequest, RegisterRequest } from './types'

export const AUTH_ME_KEY = ['auth', 'me'] as const

/**
 * Server-owned authentication state. `data === null` means "definitely not
 * authenticated"; a network failure stays an error so the UI can distinguish
 * "logged out" from "API unreachable".
 */
export function useCurrentUser() {
  return useQuery<AuthUser | null>({
    queryKey: AUTH_ME_KEY,
    queryFn: async () => {
      try {
        return await api.get<AuthUser>('/auth/me')
      } catch (error) {
        if (error instanceof ApiError && error.isUnauthenticated) {
          return null
        }
        throw error
      }
    },
    staleTime: 5 * 60_000,
    retry: false,
  })
}

/** Clears every cached financial dataset when the authenticated user changes. */
function resetForNewIdentity(queryClient: ReturnType<typeof useQueryClient>, user: AuthUser | null) {
  queryClient.clear()
  queryClient.setQueryData(AUTH_ME_KEY, user)
}

export function useLogin() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (request: LoginRequest) => api.post<AuthUser>('/auth/login', request),
    onSuccess: (user) => resetForNewIdentity(queryClient, user),
  })
}

export function useRegister() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (request: RegisterRequest) => api.post<AuthUser>('/auth/register', request),
    onSuccess: (user) => resetForNewIdentity(queryClient, user),
  })
}

export function useLogout() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => api.post<void>('/auth/logout'),
    onSettled: () => {
      // Even if the request failed the local state must not keep stale data.
      resetForNewIdentity(queryClient, null)
    },
  })
}

export function useUpdateProfile() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (request: { displayName: string }) => api.put<AuthUser>('/profile', request),
    onSuccess: (user) => queryClient.setQueryData(AUTH_ME_KEY, user),
  })
}

export function useChangePassword() {
  return useMutation({
    mutationFn: (request: { currentPassword: string; newPassword: string }) =>
      api.post<AuthUser>('/profile/password', request),
  })
}
