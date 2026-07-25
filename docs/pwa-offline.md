# PWA e acesso offline seguro

## Escopo

O Finora é instalável e pode abrir o shell sem rede. O acesso a dados financeiros
offline é opcional, local, criptografado e estritamente somente leitura. Esta etapa
não contém outbox, replay, sincronização em background ou resolução de conflitos.

## Instalação e Service Worker

`vite-plugin-pwa` 1.3 usa Workbox para gerar `manifest.webmanifest`, `sw.js` e um
precache revisionado do HTML, JavaScript, CSS, fontes e ícones locais. O manifesto
declara pt-BR, escopo raiz, modo standalone, cores da marca e ícones 192, 512 e
maskable derivados do favicon do Finora.

Navegações recebem `index.html` como fallback. `/api` e `/api/**` são
`NetworkOnly`; JSON de autenticação, CSRF, perfil ou finanças nunca entra em Cache
Storage. Caches estáticos obsoletos são removidos na ativação. Um worker novo fica
aguardando, a interface anuncia a atualização e só envia `SKIP_WAITING` após a
ação “Atualizar agora”; o helper do Workbox recarrega após a troca do controller.

## Cofre criptografado

O opt-in “Ativar acesso offline neste dispositivo” exige uma senha local própria,
com no mínimo 12 caracteres e confirmação. Ela não é a senha da conta, não é
recuperável e nunca é persistida. Perder a senha exige excluir e recriar a cópia.

Há exatamente um registro em IndexedDB por perfil do navegador. O formato v1 usa:

- PBKDF2-HMAC-SHA-256, 310.000 iterações e salt aleatório de 128 bits;
- AES-256-GCM e IV aleatório novo de 96 bits em toda escrita;
- metadados versionados de cofre/dados, KDF, salt, IV, ciphertext e timestamps;
- identidade estável do proprietário somente dentro do ciphertext autenticado.

A `CryptoKey` derivada existe apenas durante a operação criptográfica; senha e
chave não são gravadas. Falha de autenticação GCM, senha errada, Base64 inválido ou
schema desconhecido produzem a mesma mensagem genérica e mantêm o cofre bloqueado.
O usuário pode tentar novamente ou excluir explicitamente a cópia; não há fallback
em texto claro nem reset silencioso.

## Dataset e identidade

“Atualizar dados offline” busca um conjunto limitado: perfil dentro do payload,
dashboard e orçamentos do mês atual, contas, página 0 de transações (20), resumos de
cartões, recorrentes e próximos três meses, previsão de 90 dias, metas, resumo da
wishlist, primeira página de notificações ativas e preferências. Imports, detalhes
de fatura, histórico de preços, uploads e páginas ilimitadas são excluídos.

Somente queries bem-sucedidas da allowlist são serializadas; erros e mutation cache
nunca são persistidos. A allowlist é validada novamente na hidratação e timestamps
originais são preservados. A troca é atômica: o registro válido anterior só é
substituído depois de criptografia e verificação local bem-sucedidas.

O modo `UNLOCKED_OFFLINE` não representa uma sessão autenticada no servidor. Exibe
proprietário, timestamp e aviso de desatualização, desabilita controles de escrita
e bloqueia POST/PUT/PATCH/DELETE no cliente central antes de CSRF ou `fetch`.
Conteúdo não preparado recebe uma mensagem específica.

Na reconexão, `/auth/me` volta a ser autoridade. Mesmo proprietário retorna ao modo
online e revalida as queries; sessão expirada bloqueia e limpa dados descriptografados;
outro proprietário nunca é mesclado. Um cofre existente precisa ser excluído antes
de outro ser criado.

## Ciclo de vida e falhas

Estados: `ABSENT`, `LOCKED`, `UNLOCKING`, `UNLOCKED_ONLINE`, `UNLOCKED_OFFLINE` e
`CORRUPTED` (além do carregamento inicial). Bloquear limpa QueryClient e identidade
descriptografada, mantendo apenas ciphertext. Há bloqueio por 15 minutos de
inatividade e após cinco minutos em background. Logout limpa o QueryClient e tenta
excluir o cofre mesmo se o logout do servidor falhar. Desativar exige confirmação e
não altera o servidor.

IndexedDB indisponível, transação abortada e quota excedida são apresentados como
falha de armazenamento; atualização falha não apaga o registro anterior. O tamanho
criptografado é aproximado. O navegador ou sistema operacional pode remover dados.
Não há garantia contra dispositivo/navegador totalmente comprometido.

## Testes e navegadores

Vitest cobre criptografia, adulteração, IV/salt novos, schemas, armazenamento,
allowlist/hidratação e bloqueio central de mutações. `npm run verify:pwa` prova os
artefatos, fallback e `/api` NetworkOnly. `e2e/pwa-offline.spec.ts` executa 12
jornadas Chromium contra o preview de produção. Instalação real varia por navegador;
iOS usa “Adicionar à Tela de Início”. Não há inspeção automatizada da UI do sistema.

## Exclusões explícitas

Sem gravações offline, drafts, outbox, replay, Background Sync, conflitos,
idempotency keys de sync, Web Push, importação de extrato offline ou endpoint/backend
novo. O servidor continua autoritativo. A próxima etapa é “Offline mutation outbox,
idempotency and conflict resolution”.
