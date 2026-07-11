# Arquitetura

## Visão geral

Monorepo com dois aplicativos independentes e um PostgreSQL em Docker:

```
apps/api  (Spring Boot 4.1, Java 21)  ←──  apps/web  (React 19 + Vite)
        │ JDBC                                  │ HTTP JSON (/api, cookie de sessão + CSRF)
        ▼
PostgreSQL 16 (docker-compose, porta 5433)
  ├── dados financeiros (por usuário)
  └── SPRING_SESSION (Spring Session JDBC)
```

- O frontend nunca calcula regra financeira: exibe o que a API retorna.
- A API nunca expõe entidades JPA: todo contrato público é DTO (records).
- Erros seguem RFC 9457 (Problem Details) com `code` de regra de negócio e
  `errors[]` de validação de campos.

## Identidade e segurança

O domínio `identity` (`User`, `UserStatus`, `RegistrationService`, `AuthController`,
`ProfileService`, `LegacyClaimService`, `CurrentUserProvider`,
`FinoraUserDetailsService`) integra Spring Security a partir de uma única costura:
`CurrentUserProvider.currentUserId()` resolve o usuário do `SecurityContext` e
falha fechado quando não há autenticação. Todo domínio financeiro depende dela para
o escopo de posse — nunca de parâmetros ou corpo da requisição.

- **Sessão**: server-side, persistida por Spring Session JDBC (`SPRING_SESSION`,
  esquema gerido pela migração V5), cookie HttpOnly `FINORA_SESSION`.
- **Senhas**: BCrypt via `PasswordEncoder`.
- **CSRF**: double-submit (`XSRF-TOKEN` cookie ↔ `X-XSRF-TOKEN` header), habilitado
  para métodos que alteram estado; bootstrap em `GET /api/auth/csrf`.
- **Autorização**: `SecurityConfig` deixa público apenas register/login/claim/csrf;
  todo o resto de `/api/**` exige autenticação e responde 401 Problem Details.
- Detalhes completos em [security.md](security.md).

## Backend

Pacotes por domínio (não por camada técnica):

```
com.finora.api
├── common/        # error model, ProblemDetail handler, MoneyRules, PageResponse,
│                  # CORS, SecurityConfig, AuditableEntity
├── identity/      # usuários, autenticação, sessão, perfil, claim legado
├── account/       # contas com saldo derivado
├── category/      # categorias de receita/despesa (padrões seeded via Flyway)
├── transaction/   # transações + busca com Specifications + agregações
├── budget/        # orçamentos mensais (consumo derivado em leitura)
├── commitment/    # recorrentes + projeção de ocorrências (occurrenceIn)
├── goal/          # metas de poupança e aportes
├── wishlist/      # itens + opções de compra validadas
├── purchaseanalysis/  # FinancialContext + motor determinístico de recomendação
├── dashboard/     # agregação de leitura para a visão geral
├── insight/       # regras determinísticas de insight
└── settings/      # premissas configuráveis (linha singleton app_settings)
```

Cada domínio contém entidade, repositório, DTOs, service e controller. Serviços
conversam entre domínios via services/repositórios públicos (ex.: `BudgetService`
usa `SettingsService`; `InsightService` usa `FinancialContextService`).

### Dinheiro e datas

- Todo valor monetário é `BigDecimal` com `NUMERIC(14,2)` no banco.
- `MoneyRules` centraliza escala (2), arredondamento (HALF_UP) e formatação BRL
  para mensagens; **nenhum cálculo financeiro usa ponto flutuante**.
- Datas de negócio são `LocalDate`; meses trafegam como `YearMonth` (`YYYY-MM`).
- Valores derivados (saldo de conta, consumo de orçamento, progresso de meta)
  são sempre calculados na leitura — nunca armazenados — para não divergirem do
  histórico.

### Persistência e migrações

- Flyway desde o início; `ddl-auto=validate` (o Hibernate nunca cria esquema).
- `V1` core (contas, categorias + seeds, transações), `V2` planejamento
  (orçamentos, recorrentes, metas), `V3` compras (settings, wishlist, opções).
- Invariantes críticas duplicadas como constraints: unicidade orçamento
  (mês, categoria), checks de positividade, consistência à vista × parcelado
  (`ck_options_kind_consistency`), enum checks e FKs. Índices apenas nos padrões
  de consulta reais (data/categoria/conta de transações, mês de orçamento).

## Frontend

```
src/
├── lib/           # api.ts (fetch central + ApiError/NetworkError), format.ts
│                  # (Intl pt-BR), month.ts, theme.ts, useDebouncedValue
├── components/    # AppShell, Dialog (nativo), FormField, FormActions, states,
│                  # Money, MonthPicker, ConfirmDialog
└── features/      # uma pasta por domínio: types.ts + api.ts (hooks React Query)
                   # + páginas/formulários; shared/ para categorias/contas
```

- **Estado do servidor** vive no TanStack Query (staleTime 30s; retry apenas para
  falhas de rede/5xx). Mutations invalidam somente as chaves realmente afetadas.
- **Estado local de UI** (formulários, diálogos, filtros) fica em `useState` no
  componente. Não há store global — não houve necessidade real.
- Rotas com `React.lazy` (code-splitting); Recharts só carrega no dashboard.
- Formatação de moeda/data/percentual é exclusiva de `lib/format.ts` (Intl pt-BR).
- Tema claro/escuro/sistema via `data-theme` + tokens CSS; preferência no
  localStorage (nunca dados financeiros).

## Dados de demonstração e testes

- A aplicação sobe **vazia** (apenas categorias padrão da migração).
- Backend: Testcontainers cria um PostgreSQL efêmero por execução de teste;
  cada teste roda em transação com rollback.
- E2E: os cenários criam e limpam dados **pela API pública** (`e2e/helpers.ts`)
  — não existe endpoint de reset nem backdoor.
- QA visual: `VISUAL_QA=1 npx playwright test e2e/visual-qa.spec.ts` popula um
  conjunto demo determinístico e captura screenshots em 4 viewports
  (`qa-screenshots/`, ignorado pelo Git).

## CORS e configuração

Origens permitidas vêm de `FINORA_CORS_ALLOWED_ORIGINS` (default: dev do Vite),
sem credenciais e sem wildcard. Configuração sensível apenas por variável de
ambiente; `.env` não é versionado.
