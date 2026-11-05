# Finora: entrada para agentes

Antes de trabalhar aqui, leia o `README.md` e depois `docs/roadmap.md`, nas
seções "Etapa em andamento" e "Próxima tarefa desta etapa". Em seguida leia
`docs/architecture.md`, `docs/testing.md`, `docs/security.md` e o doc do domínio
que você vai tocar (`docs/<domínio>.md`). Confronte com o estado atual do Git
antes de agir: os docs às vezes ficam atrás do código. Se discordarem, vale o
código, e a divergência é registrada junto com a mudança.

Esta entrada não autoriza implementação, gasto, publicação, tag ou release. O
trabalho é delimitado por um pedido explícito do mantenedor.

## Estrutura

- `apps/api`: Spring Boot 4.1 com Java 21 e Maven wrapper. Pacotes por domínio
  em `com.finora.api.*`. Migrações Flyway em `src/main/resources/db/migration`.
- `apps/web`: React 19, Vite e TypeScript. `src/features/<domínio>`,
  `src/components`, `src/lib` e `src/offline` (cofre e fila). E2E em `e2e/`.
- `docs/`: arquitetura, domínios, testes, segurança e roadmap, em pt-BR.
- `scripts/verify.ps1`: validação local. `.github/workflows/ci.yml`: backend,
  frontend e e2e.
- `docker-compose.yml`: PostgreSQL 16 na porta 5433.

## Comandos (da raiz)

- Instalar o web: `npm --prefix apps/web ci`.
- Web: `npm --prefix apps/web run lint`, `run typecheck`, `run test`,
  `run build` e, depois do build, `run verify:pwa`.
- API: `apps/api/mvnw -f apps/api/pom.xml -B verify` (no Windows,
  `apps\api\mvnw.cmd`). Exige Docker (Testcontainers).
- E2E: `docker compose up -d`, depois a API com a origem
  `http://localhost:4173` no CORS (`FINORA_CORS_ALLOWED_ORIGINS`), depois
  `npm --prefix apps/web run e2e`. Ver `docs/testing.md`, seção E2E.
- QA visual: `VISUAL_QA=1` nas suítes `e2e/*visual*.spec.ts`; as capturas vão
  para `qa-screenshots/`, que não é versionado.
- O aceite usa a forma do CI (`mvnw verify` e `verify:pwa`), não só o
  `scripts/verify.ps1`.

## Convenções do repositório

- Interface em português do Brasil. Código, banco e API em inglês. Docs em pt-BR.
- Commits em inglês: uma frase no imperativo, com maiúscula inicial, sem ponto
  final e sem prefixo `feat:`/`fix:`. Corpo explicando o porquê quando a mudança
  não é óbvia. Uma unidade lógica por commit.
- O dono do dado vem sempre de `CurrentUserProvider`, nunca do corpo ou da
  query. Recurso de outro usuário responde 404.
- Dinheiro em `BigDecimal`, sem ponto flutuante. Toda quantia anda com a sua
  moeda. Moedas diferentes não se somam (`CurrencyTotals`).
- Código novo de UI não usa `formatBRL` nem `<Money>` sem `currency` (ver
  `docs/multi-currency-fallbacks.md`).
- O frontend não calcula regra financeira: exibe o que a API devolve. A API
  expõe DTOs (records) e erros RFC 9457.
- Esquema só por Flyway (`ddl-auto: validate`). Migração existente não se
  edita: mudança de esquema vira uma migração nova, com teste a partir de um
  banco populado, como as existentes.
- Lógica de datas recebe o `Clock` injetado. Testes usam datas relativas ao
  relógio, não ao calendário: o CI roda em UTC e a máquina local pode estar em
  outro fuso.
- Testes de comportamento, não de snapshot. E2E com localizadores acessíveis
  (roles e labels), dados criados pela API pública e fixtures sintéticas.
- Ao fechar uma fatia, atualize o doc do domínio, `docs/testing.md` e
  `docs/roadmap.md`.
- EditorConfig: LF, 2 espaços, Java com 4; `.cmd`/`.ps1` em CRLF.

## Limites

- Repositório público: nada de dado financeiro real, extrato, credencial ou
  nota interna de ferramenta no Git.
- Não ler, copiar nem commitar segredos (`.env`, `.env.local`, tokens). Só o
  `.env.example` é referência.
- Sem push forçado, rebase de `main`, reescrita de histórico ou troca de
  remoto.
- Sem coleta externa de preços, scraping nem "IA financeira" (fora de escopo
  no `docs/roadmap.md`).
- Sem afrouxar posse, CSRF ou sessão para fazer um teste passar.
