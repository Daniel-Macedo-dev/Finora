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
- **Contas** — contas correntes, poupança e dinheiro físico com saldo derivado do
  histórico (nunca armazenado).
- **Orçamentos mensais** — limite por categoria com consumo calculado em tempo real,
  estados saudável / perto do limite / estourado.
- **Compromissos recorrentes** — assinaturas e contas fixas com projeção de
  vencimentos e impacto nos próximos meses.
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
│   ├── api/    # Spring Boot — pacotes por domínio (account, transaction, budget,
│   │           # commitment, goal, wishlist, purchaseanalysis, dashboard, insight…)
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
| Orçamentos | `GET /api/budgets?month=YYYY-MM`, `POST`, `PUT/DELETE /{id}` |
| Recorrentes | `GET/POST /api/commitments`, `GET /api/commitments/upcoming?months=`, `PUT/DELETE /{id}` |
| Metas | `GET/POST /api/goals`, `PUT/DELETE /{id}`, `POST /{id}/contributions` |
| Lista de desejos | `GET/POST /api/wishlist`, `GET/PUT/DELETE /{id}`, `POST/PUT/DELETE /{id}/options[/{optionId}]` |
| Análise | `GET /api/wishlist/{id}/analysis` |
| Dashboard | `GET /api/dashboard?month=YYYY-MM` |
| Insights | `GET /api/insights?month=YYYY-MM` |
| Configurações | `GET/PUT /api/settings` |

Todas as rotas de dados exigem autenticação (sessão via cookie + CSRF). Detalhes de
contratos e invariantes em [`docs/domain-model.md`](docs/domain-model.md); o método
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
- **Sem integração bancária ou coleta de preços** — transações e ofertas são
  registradas manualmente.
- **Cartão de crédito não é modelado como fatura** — compras no crédito são
  transações comuns marcadas com a forma de pagamento.
- **Sem rate limiting distribuído de login** (adequado a um app pessoal same-origin).
- As recomendações são **projeções baseadas nos seus dados e em premissas
  configuráveis** — não são aconselhamento financeiro profissional.

Direções futuras em [`docs/roadmap.md`](docs/roadmap.md).

## Licença

[MIT](LICENSE)
