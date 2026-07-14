# Estratégia de testes

## Camadas

| Camada | Ferramentas | O que protege |
| --- | --- | --- |
| Backend integração | JUnit 5 + MockMvc + Testcontainers (PostgreSQL real) | contratos HTTP, validação, regras de negócio, migrações Flyway |
| Backend unidade | JUnit 5 puro | matemática de datas (`occurrenceIn`, ciclo de fatura), distribuição de parcelas em centavos, derivação de status de fatura e valor presente |
| Frontend unidade | Vitest + Testing Library (jsdom) | formatação pt-BR, normalização de erros da API, aritmética de meses, componentes (labels/erros/interação) |
| E2E | Playwright (Chromium) | fluxos reais UI → API → PostgreSQL, incluindo análise de compra e navegação mobile |

## Backend (`apps/api`)

```bash
./mvnw test      # Windows: .\mvnw.cmd test
```

- `AbstractIntegrationTest` sobe o contexto completo com um PostgreSQL efêmero
  (Testcontainers, `@ServiceConnection`) e roda cada teste em transação com
  rollback — nenhum estado vaza entre testes. **Docker é obrigatório.** Cada teste
  registra usuários reais pelo endpoint público e reusa o cookie de sessão emitido;
  o helper `csrf()` usa o fluxo real de double-submit (cookie + header), não o
  post-processor do spring-security-test.

### Identidade, sessão e posse

- **Autenticação** (`AuthFlowTest`): registro cria usuário + categorias padrão +
  settings atomicamente e estabelece sessão; senha guardada como hash BCrypt;
  normalização de e-mail e rejeição de duplicata case-insensitive; login válido,
  falhas genéricas (senha errada e e-mail inexistente produzem corpos idênticos —
  sem enumeração); logout invalida a sessão; CSRF ausente/ inválido é rejeitado e o
  bootstrap funciona; troca de senha invalida as outras sessões mantendo a atual.
- **Persistência de sessão** (`AuthFlowTest.sessionsArePersistedInTheJdbcStore`):
  verifica a linha em `SPRING_SESSION` indexada pelo principal — prova o store JDBC,
  não sessões em memória.
- **Transacionalidade do registro** (`RegistrationTransactionalityTest`): forçando a
  criação de settings a falhar, nenhum usuário ou categoria permanece (rollback
  atômico); roda fora da transação de rollback do teste para provar o commit real.
- **Ataques por ID direto** (`OwnershipAttackTest`): usuário B tentando ler, listar,
  editar, excluir, referenciar como FK, contribuir em meta, gerenciar opção de
  compra ou analisar recursos de A — tudo resolve para 404 e não altera os dados de
  A; `?userId=` é ignorado; desativação de categoria é isolada por usuário.
- **Isolamento de agregados**: dashboards de dois perfis opostos permanecem
  independentes (`DashboardApiIntegrationTest`); consumo de orçamento não vaza
  (`BudgetIsolationTest`); contexto financeiro e recomendação de compra ignoram os
  dados de outro usuário (`FinancialContextServiceTest`,
  `PurchaseAnalysisEngineTest.anotherUsersFinancesCannotChangeTheRecommendation`);
  insight de outro usuário não aparece para mim.
- **Migração e claim legado**: `LegacyDataMigrationTest` roda a V4 (via Flyway
  programático em containers) contra um banco **com dados v1** — todas as linhas
  sobrevivem sob o dono pendente — e contra um banco limpo — sem usuário legado nem
  seeds globais. `LegacyClaimFlowTest` prova que o dono pendente não autentica, que
  o claim com token válido transfere a identidade e mantém os dados uma única vez,
  que token errado não transfere nada, e que registro comum não herda os dados.
### Cartões de crédito

- **Ciclo de fatura** (`InvoiceCycleCalculatorTest`): compra antes/no dia/depois
  do fechamento; fechamento antes do vencimento e no mês anterior; dia 31 em
  fevereiro, ano bissexto e meses de 30 dias; virada dezembro→janeiro; e duas
  propriedades varridas (fechamentos crescem estritamente com o mês; toda compra
  cai numa fatura cujo fechamento a inclui).
- **Parcelas** (`InstallmentAllocatorTest`): soma sempre exata, resto de centavos
  nas últimas parcelas, R$ 0,01, valores que não dividem, normalização sub-centavo.
- **Status derivado** (`InvoiceStatusDerivationTest`): os seis estados e a
  precedência PAID → OVERDUE → PARTIALLY_PAID → CLOSED → OPEN → UPCOMING.
- **Compras** (`CardPurchaseApiIntegrationTest`): fatura correta, parcelas
  consecutivas, limite consumido/liberado, cancelamento, edição com regeneração,
  categoria de outro dono, cartão arquivado — com datas fixas no futuro para o
  status nunca virar com o calendário real.
- **Concorrência** (`PurchaseLimitConcurrencyTest`): duas compras simultâneas não
  estouram o limite (lock pessimista no cartão).
- **Pagamentos** (`InvoicePaymentApiIntegrationTest`): compra sozinha não toca a
  conta; pagamento total/parcial reduz o saldo exatamente uma vez e restaura
  limite; overpayment rejeitado; estorno devolve tudo uma vez e não repete;
  ajustes de débito/crédito; fatura vencida; dois usuários isolados.
- **Contabilidade** (`CardAccountingIntegrationTest`): parcelas consomem orçamento
  nos meses das faturas exatamente uma vez; cancelamento remove o consumo;
  dashboard conta parcela uma vez e pagamento nunca; `CREDIT` genérico rejeitado.
- **Migração** (`MigrationFromPopulatedV5Test`): V5 populado → mais recente
  preservando dados e marcando crédito legado; constraint de fatura única; FKs
  cross-owner rejeitadas no banco.
- **Wishlist → compra real** (`WishlistPurchaseExecutionTest`): execução à vista
  e parcelada, retry não duplica, opção/cartão de outro dono inacessíveis.

- `PurchaseAnalysisEngineTest` fixa a data de referência (2026-07-15) e cobre os
  cenários críticos: à vista mais barato e seguro; à vista violando a reserva;
  parcelado sem juros vencendo à vista com taxa positiva; parcela acima da sobra;
  parcela estourando o teto da renda; `WAIT` com estimativa de meses; ausência de
  histórico; empate preferindo à vista; frete invertendo a opção preferida.
- `PresentValueTest` valida o PV puro, inclusive taxa zero e arredondamento.
- Fronteiras cobertas de propósito: dia 31 em meses curtos e ano bissexto
  (`CommitmentOccurrenceTest`), tolerância exata de reconciliação de parcelas
  (`OptionReconciliationBoundaryTest`), médias com histórico parcial e exclusão
  do mês corrente (`FinancialContextServiceTest`), datas alvo no mês corrente e
  no passado (`GoalContributionEdgeTest`), despesas de outros meses fora do
  consumo do orçamento.

## Frontend (`apps/web`)

```bash
npm run test         # Vitest
npm run typecheck    # tsc -b
npm run lint         # oxlint
```

Testes de comportamento, não de snapshot: `formatBRL/formatDate` (inclusive
imunidade a timezone), `parseMoneyInput` pt-BR, `ApiError`/`NetworkError`,
associação label/erro do `FormField`, navegação do `MonthPicker`, retry do
`ErrorState`.

## E2E (`apps/web/e2e`)

```bash
npm run e2e   # requer a API em :8080 (docker compose up -d + mvnw spring-boot:run)
```

Isolamento por identidade: cada cenário **registra um usuário único** (e-mail
determinístico) via UI, então enxerga apenas os próprios dados — não há mais limpeza
global destrutiva por API aberta (que seria um bypass de posse). Cenários: registrar
e entrar no app; registrar receita/despesa e conferir lista + dashboard; orçamento
com consumo/estado e prevenção de duplicidade; planejar compra com opções à
vista/parcelada e conferir recomendação; **cartões** (criar cartão; compra à vista
e parcelada com fatura/limite corretos; pagar fatura total e parcial com saldo
reduzido uma única vez; estorno; execução de wishlist no cartão sem duplicar;
crédito legado preservado e novo crédito genérico redirecionado; fluxo mobile a
390px); **isolamento entre usuários** (B não vê os
dados de A pela UI nem por ID direto na API, e sua análise de compra é inacessível);
**ciclo de sessão** (expiração leva ao login e permite reentrar; troca de senha
mantém a sessão atual e permite login com a nova); navegação e autenticação mobile
(390px: registro, drawer, criar transação, menu de usuário, logout).
Localizadores acessíveis (roles e labels), sem seletores CSS frágeis.

### QA visual

```bash
VISUAL_QA=1 npx playwright test e2e/visual-qa.spec.ts
```

Semeia dados demo determinísticos e captura os estados principais em
1440/1024/768/390px + tema escuro + estado vazio, em `qa-screenshots/`
(ignorado pelo Git).

## CI

`.github/workflows/ci.yml`: job de backend (`mvnw verify` com Testcontainers),
job de frontend (lint, typecheck, testes, build) e job E2E (serviço PostgreSQL,
API em background, Playwright) — travas de merge naturais para `main`.
