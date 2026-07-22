# Finora

Plataforma pessoal de gestão financeira e planejamento de compras. O Finora ajuda a
entender para onde o dinheiro está indo e a decidir **o que, quando e como comprar** —
com análises determinísticas baseadas nos seus próprios dados.

Aplicação **multiusuário**: registro, login e sessão server-side, com cada dado
financeiro pertencente a um usuário autenticado e isolamento completo entre contas.

Interface em português do Brasil; código, banco e API em inglês.

## O que o Finora faz

- **Identidade e conta** — registro, login, logout, sessão server-side persistida,
  perfil e troca de senha. Cada usuário tem suas próprias categorias padrão,
  configurações e dados, totalmente isolados de outros usuários.
- **Transações** — registro de receitas e despesas com categoria, conta, forma de
  pagamento, busca, filtros por mês/tipo/categoria e paginação.
- **Importação de extratos** — upload de CSV ou OFX de contas correntes/poupança
  com pré-visualização determinística, parser OFX seguro (sem XML/XXE),
  mapeamento de colunas para formatos brasileiros, deduplicação em três níveis
  (arquivo, identidade forte, conteúdo), categorização por regras
  determinísticas e confirmação idempotente com desfazer auditável — o arquivo
  bruto nunca é retido além do necessário para o parse. Ver
  [docs/statement-import.md](docs/statement-import.md).
- **Contas** — contas correntes, poupança e dinheiro físico com saldo derivado do
  histórico (nunca armazenado).
- **Cartões de crédito** — cartões com limite, fechamento e vencimento; compras à
  vista ou parceladas com distribuição exata de centavos; faturas mensais com ciclo
  determinístico; pagamento total/parcial com estorno auditável; a despesa conta
  **uma única vez** (nas parcelas do mês da fatura, nunca no pagamento).
- **Orçamentos mensais** — limite por categoria com consumo calculado em tempo real,
  estados saudável / perto do limite / estourado.
- **Recorrentes automatizados** — receitas e despesas com cadência semanal,
  mensal ou anual que viram registros reais: cada ocorrência tem identidade
  estável e ciclo de vida auditável (executar, repetir após falha, pular,
  reagendar, estornar, pausar, encerrar); materialização idempotente em
  transação de conta ou compra de cartão, manual ou automática com catch-up
  após downtime — sem duplicatas mesmo sob concorrência. Ver
  [docs/recurring-automation.md](docs/recurring-automation.md).
- **Previsão de caixa** — projeção determinística da movimentação futura:
  lançamentos registrados, recorrentes projetados e faturas de cartão **no
  vencimento** (nunca na data da compra); menor saldo projetado, primeira data
  de saldo negativo e fluxos sem conta divulgados separadamente. Página
  dedicada com gráfico e linha do tempo + resumo no dashboard. Ver
  [docs/forecast.md](docs/forecast.md).
- **Central de notificações** — caixa persistente com leitura, dispensa,
  adiamento, resolução e reativação; preferências por fonte, sincronização
  automática e alertas opcionais do navegador enquanto o Finora está aberto,
  com valores ocultos por padrão. Ver
  [docs/notifications.md](docs/notifications.md).
- **Metas de poupança** — progresso, aportes e sugestão de contribuição mensal
  para alcançar a data alvo.
- **Lista de desejos + opções de compra** — cada item aceita múltiplas ofertas
  (à vista e parcelado, com frete e taxas validados contra combinações contraditórias).
- **Motor de análise de compra** — comparação determinística entre opções: custo
  nominal, valor presente à taxa de oportunidade configurável, proteção de liquidez
  (reserva mínima de caixa), pressão de parcelas sobre a sobra e a renda médias, e
  recomendação explicada (comprar à vista, parcelar ou aguardar — com estimativa de
  quantos meses faltam). Sem IA, sem score mágico: aritmética auditável e testada.
- **Dashboard** — totais do mês, taxa de poupança, tendência de 6 meses, principais
  categorias, alertas de orçamento, próximos compromissos, metas e transações recentes.
- **Insights determinísticos** — regras como "gastos 20% acima do mês anterior",
  "categoria dominante", "orçamento estourado", "compra da lista de desejos viável".
- **Tema claro / escuro / sistema**, layout responsivo (desktop → 360px) e
  acessibilidade prática (WCAG 2.2 AA nos fluxos principais).

## Stack

| Camada | Tecnologia |
| --- | --- |
| Backend | Java 21 · Spring Boot 4.1 (Web MVC, Validation, Data JPA, Security, Session JDBC) · Flyway |
| Banco | PostgreSQL 16 (Docker Compose) · sessões persistidas via Spring Session JDBC |
| Frontend | React 19 · TypeScript · Vite · TanStack Query · React Router · Recharts |
| Testes | JUnit 5 + MockMvc + Testcontainers · Vitest + Testing Library · Playwright |
| CI | GitHub Actions (backend, frontend e E2E) |

## Estrutura

```
Finora/
├── apps/
│   ├── api/    # Spring Boot — pacotes por domínio (account, transaction, creditcard,
│   │           # budget, commitment, goal, wishlist, purchaseanalysis, dashboard…)
│   └── web/    # React — features por domínio + lib (api, format) + components
├── docs/       # arquitetura, domínio, análise de compra, testes, roadmap
├── scripts/    # verify.ps1 (validação completa)
├── .github/workflows/ci.yml
└── docker-compose.yml
```

## Como rodar

Requisitos: **Java 21**, **Node 20+**, **Docker** (Compose v2). Maven e browsers do
Playwright são obtidos automaticamente.

```bash
# 1. Banco de dados (porta 5433 para não conflitar com um PostgreSQL nativo)
docker compose up -d

# 2. API (http://localhost:8080/api)
cd apps/api
./mvnw spring-boot:run        # Windows: .\mvnw.cmd spring-boot:run

# 3. Frontend (http://localhost:5173, com proxy para a API)
cd apps/web
npm install
npm run dev
```

Configuração via variáveis de ambiente (`.env.example` documenta todas). As
migrações Flyway criam o esquema e as categorias padrão na primeira subida.

## Testes e verificação

```bash
# Backend (usa Testcontainers — requer Docker)
cd apps/api && ./mvnw test

# Frontend
cd apps/web
npm run lint && npm run typecheck && npm run test && npm run build

# E2E (requer API rodando; o Vite é iniciado automaticamente)
npm run e2e

# Tudo de uma vez (Windows)
pwsh scripts/verify.ps1        # adicione -E2E para incluir o Playwright
```

## API

Endpoints principais (JSON, erros em RFC 9457 Problem Details):

| Recurso | Rotas |
| --- | --- |
| Autenticação | `POST /api/auth/register`, `POST /api/auth/login`, `POST /api/auth/logout`, `GET /api/auth/me`, `GET /api/auth/csrf` |
| Perfil | `PUT /api/profile`, `POST /api/profile/password` |
| Contas | `GET/POST /api/accounts`, `GET/PUT/DELETE /api/accounts/{id}` |
| Categorias | `GET/POST /api/categories`, `GET/PUT/DELETE /api/categories/{id}` |
| Transações | `GET /api/transactions?month=YYYY-MM&type=&categoryId=&search=&page=`, `POST`, `PUT/DELETE /{id}` |
| Importação de extratos | `POST/GET /api/statement-imports`, `GET/PATCH /{id}`, `PUT /{id}/csv-mapping`, `POST /{id}/reparse`, `PATCH /{id}/items/{itemId}`, `POST /{id}/confirm`, `POST /{id}/undo`, `POST /{id}/items/{itemId}/undo` |
| Regras de categoria | `GET/POST /api/category-mapping-rules`, `PUT/DELETE /{id}` |
| Cartões | `GET/POST /api/credit-cards`, `GET/PUT/DELETE /{id}`, `POST /{id}/archive`, `POST /{id}/unarchive` |
| Compras no cartão | `GET/POST /api/credit-cards/{id}/purchases`, `GET/PUT /{purchaseId}`, `POST /{purchaseId}/cancel` |
| Faturas | `GET /api/credit-cards/{id}/invoices`, `GET /{invoiceId}`, `POST /{invoiceId}/payments`, `POST /{invoiceId}/payments/{payId}/reverse`, `POST /{invoiceId}/adjustments[/{adjId}/reverse]` |
| Orçamentos | `GET /api/budgets?month=YYYY-MM`, `POST`, `PUT/DELETE /{id}` |
| Recorrentes | `GET/POST /api/commitments`, `GET /api/commitments/upcoming?months=`, `PUT/DELETE /{id}` |
| Metas | `GET/POST /api/goals`, `PUT/DELETE /{id}`, `POST /{id}/contributions` |
| Lista de desejos | `GET/POST /api/wishlist`, `GET/PUT/DELETE /{id}`, `POST/PUT/DELETE /{id}/options[/{optionId}]`, `POST /{id}/purchase` |
| Análise | `GET /api/wishlist/{id}/analysis` |
| Dashboard | `GET /api/dashboard?month=YYYY-MM` |
| Insights | `GET /api/insights?month=YYYY-MM` |
| Configurações | `GET/PUT /api/settings` |

Todas as rotas de dados exigem autenticação (sessão via cookie + CSRF). Detalhes de
contratos e invariantes em [`docs/domain-model.md`](docs/domain-model.md); cartões,
faturas e parcelamento em [`docs/credit-cards.md`](docs/credit-cards.md); o método
da análise de compra em [`docs/purchase-analysis.md`](docs/purchase-analysis.md); o
modelo de segurança e posse em [`docs/security.md`](docs/security.md).

## Autenticação e posse dos dados

- Sessão server-side (Spring Security + Spring Session JDBC) por cookie HttpOnly;
  senhas com BCrypt; CSRF double-submit para o SPA. Nenhum token em `localStorage`.
- Cada dado financeiro pertence a um usuário; leituras diretas, agregações
  (dashboard, orçamentos, contexto financeiro, análise de compra, insights) e
  referências de chave estrangeira são todas escopadas ao dono autenticado.
- Recurso de outro usuário resolve para **404**; credenciais inválidas retornam um
  erro genérico (sem enumeração de usuários).
- Dados de uma instalação v1 anterior são preservados sob um dono legado pendente e
  migrados por um fluxo de claim controlado por variável de ambiente. Ver
  [`docs/security.md`](docs/security.md).

## Status e limitações

Segunda release: multiusuário com autenticação por sessão. Ainda de uso local:

- **Sem verificação de e-mail, recuperação de senha, MFA ou OAuth.**
- **Sem integração bancária ao vivo (Open Finance), com emissor de cartão ou
  coleta de preços** — compras de cartão e ofertas são registradas
  manualmente; transações podem ser digitadas ou importadas de um arquivo
  CSV/OFX exportado do próprio banco (ver
  [`docs/statement-import.md`](docs/statement-import.md)), nunca por conexão
  direta com a instituição financeira.
- **Transações antigas com forma de pagamento `CREDIT` são preservadas como
  "crédito legado"** e podem ser **convertidas de forma assistida** em compras
  de cartão reais (fatura, parcelas e limite), mantendo o registro original
  como auditoria — ver
  [`docs/legacy-credit-conversion.md`](docs/legacy-credit-conversion.md);
  novas compras no crédito passam pela área de Cartões.
- **Sem rate limiting distribuído de login** (adequado a um app pessoal same-origin).
- As recomendações são **projeções baseadas nos seus dados e em premissas
  configuráveis** — não são aconselhamento financeiro profissional.

Direções futuras em [`docs/roadmap.md`](docs/roadmap.md).

## Endpoints de notificações

Todos exigem sessão; mutações também exigem CSRF. O usuário é sempre derivado
da sessão, nunca do corpo ou query string.

### GET /api/notification-preferences

Retorna preferências de caixa, fontes e navegador do proprietário atual.

### PUT /api/notification-preferences

Valida e atualiza antecedência (1–14 dias), fontes, severidade mínima,
habilitação do navegador e privacidade de valores.

### POST /api/notifications/sync

Executa catch-up imediato e retorna contagens `created`, `updated`, `escalated`,
`resolved`, `reactivated` e `unchanged`.

### GET /api/notifications

Retorna envelope paginado estável. Aceita `filter`, `page` e `size` (máx. 100).

### GET /api/notifications/unread-count

Conta revisões ativas, visíveis e não lidas sem carregar a caixa.

### POST /api/notifications/{id}/{action}

Ações idempotentes `read`, `unread`, `dismiss`, `restore` e `snooze`; ids de
outro proprietário respondem 404. `snooze` recebe `{ until: instant }` dentro
dos próximos 30 dias. `POST /api/notifications/read-all` cobre a caixa ativa.

### POST /api/notifications/browser-claims

Reivindica atomicamente até dez revisões elegíveis para alertas foreground,
sem marcá-las como lidas. Valores só aparecem quando autorizados.

## Licença

[MIT](LICENSE)
