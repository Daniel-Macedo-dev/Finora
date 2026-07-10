/** Theme handling: light / dark / system, persisted in localStorage. */

export type ThemePreference = 'light' | 'dark' | 'system'

const STORAGE_KEY = 'finora.theme'

export function getThemePreference(): ThemePreference {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    if (stored === 'light' || stored === 'dark') {
      return stored
    }
  } catch {
    // storage unavailable
  }
  return 'system'
}

function resolve(preference: ThemePreference): 'light' | 'dark' {
  if (preference === 'system') {
    return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
  }
  return preference
}

export function applyThemePreference(preference: ThemePreference): void {
  try {
    if (preference === 'system') {
      localStorage.removeItem(STORAGE_KEY)
    } else {
      localStorage.setItem(STORAGE_KEY, preference)
    }
  } catch {
    // storage unavailable; still apply visually
  }
  document.documentElement.dataset.theme = resolve(preference)
}

/** Keeps the applied theme in sync with OS changes while preference is "system". */
export function watchSystemTheme(): () => void {
  const media = window.matchMedia('(prefers-color-scheme: dark)')
  const listener = () => {
    if (getThemePreference() === 'system') {
      document.documentElement.dataset.theme = media.matches ? 'dark' : 'light'
    }
  }
  media.addEventListener('change', listener)
  return () => media.removeEventListener('change', listener)
}
