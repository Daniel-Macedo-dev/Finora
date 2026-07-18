# Conversão assistida de crédito legado

Transações `CREDIT` anteriores à área de Cartões ("crédito legado", nascidas na
migração V7) podem ser convertidas em **compras de cartão reais** — com fatura,
parcelas exatas em centavos e limite — preservando o registro original como
trilha de auditoria. O domínio vive em `com.finora.api.legacyconversion` e a
interface em `apps/web/src/features/legacy-conversions/`.

## Invariante contábil central

> Uma compra histórica **nunca conta duas vezes**. Enquanto a conversão está
> **ativa**, as parcelas geradas são a fonte da despesa e a transação original
> fica **financeiramente inativa** (`transactions.financially_active = FALSE`),
> permanecendo visível apenas como auditoria. O **estorno** devolve o
> reconhecimento à transação original — exatamente uma vez.

Pagar as faturas da compra gerada continua sendo **movimento de caixa**, nunca
uma nova despesa (ver [credit-cards.md](credit-cards.md)).

## Elegibilidade

Uma transação é convertível quando:

- é `EXPENSE` com `legacy_credit = TRUE` e valor positivo;
- está financeiramente ativa (não tem conversão `ACTIVE`);
- não foi gerada por recorrente (`commitment_id IS NULL`) — essas migram pela
  definição recorrente — nem por item de desejo;
- fontes com conversão **estornada** voltam a ser elegíveis
  (`REVERSED_CONVERSION`), mantendo o histórico de conversões anterior.

O serviço de elegibilidade é a autoridade; o inventário
(`GET /api/legacy-conversions`) publica o mesmo veredito por item, com resumo
(elegíveis, convertidas, estornadas, valor pendente), filtros por mês, período,
categoria, faixa de valor e estado, e paginação.

## Pré-visualização determinística

`POST /api/legacy-conversions/preview` calcula, sem persistir nada:

- o cronograma exato de parcelas (distribuição de centavos idêntica à do
  domínio de cartões) com mês de fatura, fechamento e vencimento por parcela;
- o estado de faturas existentes (aviso `INVOICE_CLOSED` para ciclos passados —
  história fechada é permitida, com aviso);
- o efeito no limite (`INSUFFICIENT_CARD_LIMIT` bloqueia);
- a redistribuição mensal da despesa (mês de origem perde, meses de fatura
  ganham — orçamentos da categoria acompanham exatamente esses deltas);
- a explicação de caixa e previsão;
- **avisos** (não impedem) e **bloqueios** (impedem): primeira fatura
  divergente (`FIRST_INVOICE_MISMATCH`), data futura
  (`EFFECTIVE_DATE_IN_FUTURE`), cartão arquivado, limite insuficiente.

O frontend exibe a pré-visualização **verbatim** e nunca recalcula valores.

## Confirmação e atomicidade

`POST /api/legacy-conversions` exige a primeira fatura **explícita**. O motor
(`LegacyConversionEngine`) roda em transação própria (`REQUIRES_NEW`):

1. trava a transação de origem (lock pessimista) — conversões, reconversões e
   estornos do mesmo source serializam;
2. reexecuta a validação completa dentro da transação;
3. cria a compra real pelo domínio de cartões (que trava o cartão e revalida o
   limite);
4. persiste o registro de conversão e desativa financeiramente a origem.

Falhou, nada persiste: sem conversão órfã, sem compra órfã, sem parcela órfã.
Retentativas são **idempotentes**: uma conversão `ACTIVE` existente é retornada
sem criar segunda compra. Backstops no banco: índices únicos parciais
`uq_legacy_conversions_active_source` e `uq_credit_card_purchases_legacy_tx`,
além de chaves compostas `(id, user_id)` contra vínculos entre donos.

## Estorno auditável

`POST /api/legacy-conversions/{id}/reverse`:

- cancela a compra gerada (parcelas incluídas) pelo domínio de cartões;
- reativa a transação original — exatamente uma vez;
- marca a conversão como `REVERSED` com instante e motivo; o registro nunca é
  apagado.

**Guarda de liquidação**: se alguma fatura com parcela ativa da compra gerada
tem pagamento **concluído**, o estorno é bloqueado (`CONVERSION_SETTLED`) — o
dinheiro já se moveu e história liquidada não se reescreve. Ciclos passados
apenas fechados não bloqueiam (avisam). O detalhe
(`GET /api/legacy-conversions/{id}`) publica `reversible` e o motivo do
bloqueio antes de o usuário tentar.

A ordem de locks do estorno espelha a do motor (origem → conversão → cartão), e
a descoberta da origem usa leitura escalar — estornos concorrentes rejeitam
limpo (`CONVERSION_NOT_ACTIVE`), sem conflito de versão otimista.

## Lote independente

`POST /api/legacy-conversions/batch` (máx. 50 itens): cada item leva **seu
próprio** cartão, data, parcelas e primeira fatura, e roda em transação
independente — a falha de um nunca desfaz o sucesso de outro. Resultado por
item, na ordem de entrada: `SUCCESS`, `ALREADY_CONVERTED` (retentativa
honesta), `FAILED` (com código), `SKIPPED` (duplicado no lote).

## Recorrentes legados

`POST /api/commitments/{id}/legacy-card-mapping` migra uma definição `CREDIT`
projection-only para alvo `CREDIT_CARD_PURCHASE` com cartão real, parcelas e
modo de execução. A migração grava `automation_from = hoje`: o catch-up
automático **nunca retroage** — ocorrências históricas permanecem intocadas e
somente ocorrências futuras ainda não materializadas geram compras. Execução
manual de ocorrência passada continua sendo decisão explícita do usuário.

## Propriedade e concorrência

- Todas as consultas são owner-scoped; ids de outro usuário respondem **404**
  (inventário, elegibilidade, preview, conversão, detalhe, estorno, lote e
  mapeamento) — recursos alheios comportam-se como inexistentes.
- Corridas reais (PostgreSQL): conversões simultâneas colapsam em um único
  registro; estornos simultâneos elegem um vencedor; conversão × estorno
  termina em um único estado consistente. Provado em
  `LegacyConversionConcurrencyTest` com threads contra o banco real.

## API

```
GET  /api/legacy-conversions                       inventário + resumo (filtros, paginação)
GET  /api/legacy-conversions/eligibility/{txId}    veredito estruturado
POST /api/legacy-conversions/preview               pré-visualização determinística
POST /api/legacy-conversions                       confirma (primeira fatura explícita)
GET  /api/legacy-conversions/{id}                  detalhe + reversibilidade
POST /api/legacy-conversions/{id}/reverse          estorno auditável
POST /api/legacy-conversions/batch                 lote independente (máx. 50)
POST /api/commitments/{id}/legacy-card-mapping     migra recorrente legado
```

## Interface

- **`/legacy-credit` (“Crédito legado”)**: resumo, filtros, paginação, seleção
  e ações por linha; estados com rótulos em português (Elegível, Convertida,
  Estornada, Bloqueada), nunca só cor.
- **Assistente em 5 etapas** (Origem → Cartão e compra → Parcelas e faturas →
  Impacto financeiro → Confirmação): dados imutáveis da origem, cronograma e
  impacto vindos do backend, confirmação bloqueada enquanto houver bloqueios e
  botão final explícito sobre a criação de uma compra real. Falha preserva o
  formulário e mostra o motivo do backend.
- **Detalhe**: auditoria completa (compra gerada, cronograma, instantes,
  motivo) e estorno com confirmação explícita ou explicação do bloqueio.
- **Lote**: configuração individual por item, faturas resolvidas pelo backend
  antes da confirmação, resultado por item e retentativa apenas das falhas.
- **Recorrentes**: o selo "Crédito legado" vira ação de migração com aviso
  explícito de não-retroatividade.
- Transações convertidas aparecem como registro protegido ("Convertida em
  compra"), sem ações de edição/exclusão — o backend também as rejeita.

Após conversão/estorno/lote/mapeamento, o frontend invalida inventário,
transações, cartões, orçamentos, dashboard, insights, previsão e contas.

## Migração V10

`V10__legacy_credit_conversion.sql` (imutável): flag `financially_active` com
`CHECK` (só legado pode desativar), horizonte `automation_from` em
`commitments`, vínculo `legacy_transaction_id` em compras e o ledger
`legacy_credit_conversions` com constraints de ciclo de vida
(`status ↔ reversed_at`), parcelas 1–120, primeira fatura dia 1 e FKs de dono
compostas. `MigrationFromPopulatedV9Test` prova o upgrade com dados povoados.

## Limitações conhecidas

- A conversão usa a categoria original da transação; recategorizar exige
  editar após estorno (ou antes de converter).
- Não há conversão parcial de valor: a compra gerada tem o valor exato do
  registro histórico.
- O lote resolve a primeira fatura via uma pré-visualização por item (limitado
  a 50 itens por chamada).
