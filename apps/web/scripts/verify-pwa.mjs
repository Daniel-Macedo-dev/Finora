import { readFile, stat } from 'node:fs/promises'

const manifest = JSON.parse(await readFile(new URL('../dist/manifest.webmanifest', import.meta.url), 'utf8'))
const sw = await readFile(new URL('../dist/sw.js', import.meta.url), 'utf8')

for (const field of ['name', 'short_name', 'start_url', 'scope', 'display', 'theme_color', 'icons']) {
  if (!manifest[field]) throw new Error(`Manifest field missing: ${field}`)
}
if (!manifest.icons.some((icon) => icon.sizes === '192x192')) throw new Error('192x192 icon missing')
if (!manifest.icons.some((icon) => icon.sizes === '512x512')) throw new Error('512x512 icon missing')
for (const file of ['pwa-192.svg', 'pwa-512.svg', 'pwa-maskable.svg', 'sw.js']) {
  await stat(new URL(`../dist/${file}`, import.meta.url))
}
if (!sw.includes('NetworkOnly') || !sw.includes('/api')) throw new Error('/api NetworkOnly rule missing')
if (!sw.includes('index.html') || !sw.includes('NavigationRoute')) throw new Error('offline navigation fallback missing')
console.log('PWA artifacts verified: manifest, icons, shell fallback, and /api NetworkOnly boundary.')
