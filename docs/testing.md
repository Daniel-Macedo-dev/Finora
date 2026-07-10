# Estratégia de testes

## Camadas

| Camada | Ferramentas | O que protege |
| --- | --- | --- |
| Backend integração | JUnit 5 + MockMvc + Testcontainers (PostgreSQL real) | contratos HTTP, validação, regras de negócio, migrações Flyway |
| Backend unidade | JUnit 5 puro | matemática de datas (`occurrenceIn`) e valor presente |
| Frontend unidade | Vitest + Testing Library (jsdom) | formatação pt-BR, normalização de erros da API, aritmética de meses, componentes (labels/erros/interação) |
| E2E | Playwright (Chromium) | fluxos reais UI → API → PostgreSQL, incluindo análise de compra e navegação mobile |

## Backend (`apps/api`)

```bash
./mvnw test      # Windows: .\mvnw.cmd test
```

- `AbstractIntegrationTest` sobe o contexto completo com um PostgreSQL efêmero
  (Testcontainers, `@ServiceConnection`) e roda cada teste em transação com
  rollback — nenhum estado vaza entre testes. **Docker é obrigatório.**
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

Cenários: registrar receita/despesa e conferir lista + totais do dashboard;
orçamento com consumo/estado e prevenção de duplicidade; planejar compra com
opções à vista/parcelada e conferir recomendação explicada; compra insegura
gerando `WAIT` com estimativa de meses; smoke de navegação mobile (390px) criando
transação. Determinismo: cada cenário limpa os dados **pela API pública**
(`helpers.ts`) e semeia o próprio contexto; localizadores acessíveis (roles e
labels), sem seletores CSS frágeis.

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
