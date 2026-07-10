# Modelo de domínio

## Entidades e relacionamentos

```
Category 1──n Transaction n──1 Account (opcional)
Category 1──n Budget (unique: mês + categoria)
Category 1──n Commitment
Category 0..1──n WishlistItem 1──n PurchaseOption
Goal (independente)
AppSettings (linha singleton id=1)
```

## Conceitos e invariantes

### Conta (`accounts`)
Tipos: `CHECKING`, `SAVINGS`, `CASH`, `OTHER`. Guarda apenas o **saldo inicial**;
o saldo atual é derivado (`inicial + receitas − despesas` da conta). Nome único.
Conta com transações **não pode ser excluída** (`ACCOUNT_HAS_TRANSACTIONS`) —
arquive para preservar o histórico. Cartão de crédito não é modelado como conta.

### Categoria (`categories`)
`INCOME` ou `EXPENSE`; única por (nome, tipo). O tipo é imutável após a criação
(histórico permaneceria inconsistente). Categorias com transações não podem ser
excluídas — desative (`active=false`); inativas continuam válidas no histórico e
somem apenas dos formulários de criação. Um conjunto padrão em pt-BR é criado
pela migração V1 (`is_default=true`).

### Transação (`transactions`)
`type` (`INCOME`/`EXPENSE`) + **valor sempre positivo** (check no banco). O sinal
é semântico, nunca aritmético no armazenamento. A categoria deve ter o mesmo tipo
da transação (`CATEGORY_TYPE_MISMATCH`). Conta, forma de pagamento
(`PIX`, `DEBIT`, `CREDIT`, `CASH`, `BANK_TRANSFER`, `OTHER`) e observações são
opcionais. Busca paginada (máx. 100/página) com filtros combinados por AND.

### Orçamento (`budgets`)
Par (mês, categoria de despesa) único — o banco também garante
(`uq_budgets_month_category`). Guarda apenas o limite; consumo, restante,
percentual e status são derivados das transações do mês na leitura. Status:
`HEALTHY` < limiar de alerta ≤ `WARNING` < 100% ≤ `EXCEEDED` (limiar em
`app_settings.budget_warning_threshold`). Mês e categoria são imutáveis.

### Compromisso recorrente (`commitments`)
`MONTHLY` (com `due_day` obrigatório, ajustado ao tamanho do mês — dia 31 vira
28/29 em fevereiro) ou `YEARLY` (aniversário da data de início). Período
`[start_date, end_date?]`; inativo sai de todas as projeções. `occurrenceIn(mês)`
é a única fonte de verdade das ocorrências — usada por projeções, dashboard e
análise de compra. Compromissos **não geram transações automaticamente** nesta
release: são dados de planejamento.

### Meta (`goals`)
`current_amount` é gerido pela aplicação (aportes via
`POST /goals/{id}/contributions`; retirada = valor negativo, nunca abaixo de
zero). `COMPLETED` quando atual ≥ alvo; arquivamento manual. Sugestão mensal =
restante ÷ meses até a data alvo (mínimo 1; null sem data futura).

### Item de desejo (`wishlist_items`) e opções (`purchase_options`)
Status: `PLANNING → MONITORING → READY_TO_BUY → PURCHASED / ARCHIVED` (transições
livres — o usuário manda). Prioridade: `LOW/MEDIUM/HIGH/ESSENTIAL`. Cada opção é
`CASH` ou `INSTALLMENT`:

- `CASH` **não pode** carregar parcelas (`OPTION_CASH_WITH_INSTALLMENTS`);
- `INSTALLMENT` exige nº e valor de parcela, e `n × parcela` deve conferir com o
  total anunciado dentro de `±0,01 × n` (`OPTION_INSTALLMENTS_DONT_RECONCILE`);
- o banco espelha a regra (`ck_options_kind_consistency`).

`nominalCost = preço + frete + taxas`. Excluir o item remove as opções (cascade).

### Configurações (`app_settings`, linha única)
| Campo | Default | Papel |
| --- | --- | --- |
| `minimum_cash_buffer` | 0 | caixa que nunca deve ser violado por compra à vista |
| `max_installment_commitment_ratio` | 0,30 | teto de (parcela + recorrentes) ÷ renda média |
| `monthly_opportunity_rate` | 0 | taxa mensal para valor presente (0 = comparação nominal) |
| `budget_warning_threshold` | 0,80 | consumo que dispara o estado WARNING |

Defaults conservadores, visíveis na tela de configurações e usados de forma
explícita na resposta da análise (`assumptions`).
