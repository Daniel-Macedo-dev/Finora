# Multi-moeda — núcleo nativo

**Estado: em andamento.** Este documento descreve o que já existe. A conversão
entre moedas **não existe** e é a próxima etapa.

Finora deixou de assumir que todo valor é BRL. Cada raiz financeira agora declara
a moeda em que seus valores estão denominados, e valores em moedas diferentes
nunca são somados — porque, sem cotações, somá-los produziria um número que o
usuário tomaria por verdadeiro.

## Catálogo fechado

| Código | Moeda | Casas decimais |
| --- | --- | --- |
| BRL | Real brasileiro | 2 |
| USD | Dólar americano | 2 |
| EUR | Euro | 2 |
| GBP | Libra esterlina | 2 |
| CAD | Dólar canadense | 2 |
| AUD | Dólar australiano | 2 |
| CHF | Franco suíço | 2 |
| JPY | Iene japonês | **0** |

O catálogo é fechado: `CurrencyCode.parse` recusa qualquer outro código com
`CURRENCY_UNSUPPORTED`, e o banco repete o mesmo conjunto em `CHECK`. Não há
criptomoedas, commodities nem moedas com mais de duas casas.

### Regra de casas decimais

O armazenamento continua `NUMERIC(14,2)` para todas as moedas — a etapa não
alargou colunas por especulação. A moeda é que decide quantas casas têm
significado: `MoneyRules.validateScale` recusa `100,50` em JPY com
`CURRENCY_FRACTION_INVALID` em vez de arredondar silenciosamente o que o usuário
digitou, e `MoneyRules.normalize(valor, moeda)` reduz JPY a ienes inteiros antes
de gravar. No front, `parseMoneyInput` aplica a mesma regra.

## Moeda nativa e moeda base

**Moeda nativa** é a moeda de um recurso: a conta, o cartão, o compromisso, a
meta, o item da lista. Todo valor daquele recurso está nela.

**Moeda base** (`app_settings.base_currency`) é a moeda em que ficam os valores
denominados no usuário e não em um recurso: orçamentos, a reserva mínima de caixa
e qualquer consolidação. Usuários existentes e novos começam em BRL.

Trocar a moeda base **não converte nada**. Por isso a troca só é permitida
enquanto não há o que reinterpretar: `BaseCurrencyGuard` recusa com
`BASE_CURRENCY_CHANGE_BLOCKED` assim que existe qualquer dado financeiro
(contas, lançamentos, orçamentos, compromissos e ocorrências, cartões, faturas,
compras, pagamentos, ajustes, metas, itens e opções da lista, histórico de
preços, importações, notificações, recibos de mutação offline) ou uma reserva
mínima de caixa diferente de zero. A verificação é uma única consulta,
owner-scoped: o dado de outro usuário nunca bloqueia nem vaza.

Reenviar a moeda atual é no-op, não bloqueio. Omitir o campo preserva a moeda
atual — um cliente instalado antes do multi-moeda não reverte ninguém para BRL.

## V15 e imutabilidade

`V15__multi_currency_core.sql` **já está publicada e não pode ser editada.**

- Toda linha existente virou BRL.
- **Nenhum valor numérico mudou** — `MigrationFromPopulatedV14Test` prova isso
  contra PostgreSQL real, comparando cada valor antes e depois em todos os
  domínios.
- Os defaults temporários do backfill foram removidos: depois da V15, um insert
  que esqueça a moeda falha em vez de virar BRL silenciosamente. `base_currency`
  mantém o default porque BRL realmente é a moeda base de quem acabou de se
  cadastrar.
- Integridade de mesma moeda também no banco, onde uma FK composta consegue
  expressá-la: `transactions(account_id, currency) → accounts(id, currency)` e
  `credit_cards(default_payment_account_id, currency) → accounts(id, currency)`.
  Lançamentos sem conta não são restringidos — carregam a própria moeda e não têm
  conta com quem concordar.

**A moeda de um recurso é imutável.** Alterá-la reinterpretaria o histórico em vez
de convertê-lo: um saldo de R$ 8.000 viraria US$ 8.000 sem que um único
lançamento fosse editado. Tentativas são recusadas com `CURRENCY_IMMUTABLE`.

## Invariantes por domínio

Em todos os casos a regra é a mesma: **resolver o dono primeiro, comparar a moeda
depois.** Um recurso de outro usuário continua indistinguível de um inexistente
(404), nunca revelado por um erro de moeda.

| Domínio | Regra |
| --- | --- |
| Conta | Moeda imutável. Omitida no cadastro = moeda base. Saldo calculado só com movimentos daquela moeda. |
| Lançamento | Com conta, herda a moeda da conta; código divergente é recusado (`ACCOUNT_CURRENCY_MISMATCH`). Sem conta, carrega a própria (base como fallback). Não muda na edição nem ao mudar de conta. |
| Cartão | Moeda imutável. Conta padrão de pagamento precisa ter a mesma moeda (`CARD_CURRENCY_MISMATCH`). |
| Compra, parcela, fatura, ajuste | Herdam a moeda do cartão; não têm coluna própria. |
| Pagamento de fatura | Exige conta na moeda da fatura (`INVOICE_PAYMENT_CURRENCY_MISMATCH`). **A recusa acontece antes de qualquer efeito financeiro** — sem linha de pagamento, sem mudança no valor em aberto, sem devolução de limite, sem movimento de saldo. |
| Compromisso | Alvo conta/cartão impõe a moeda; divergência explícita é recusada (`COMMITMENT_CURRENCY_MISMATCH`). Só `PROJECTION_ONLY` escolhe livremente. Repontar para um alvo de outra moeda é recusado. |
| Meta | Moeda imutável; alvo, saldo e aportes compartilham. Percentual continua válido porque numerador e denominador estão na mesma moeda. |
| Item da lista | Moeda imutável; opções, frete, taxas e snapshots herdam. |
| Opção parcelada | Só aceita cartão da mesma moeda (`WISHLIST_CURRENCY_MISMATCH`). |
| Execução de compra | À vista com conta exige mesma moeda; sem conta carrega a do item; parcelada exige cartão da mesma moeda. |

## Agregados seguros

`CurrencyTotals` é a representação compartilhada de um total que se recusa a
mentir:

- `byCurrency` — totais nativos agrupados, sempre disponíveis;
- `complete` — falso quando há moeda estrangeira envolvida;
- `total` — **ausente** quando `complete` é falso;
- `unconvertedCurrencies` — exatamente o que uma etapa de câmbio precisaria
  converter.

Somar as moedas, ou descartar as estrangeiras e apresentar o resto como total,
produziriam igualmente um número sobre o qual o usuário agiria. O agrupamento é
limitado pelo catálogo fechado, então nunca cresce com o tamanho do razão.

Hoje isso já cobre a janela de compromissos futuros e o total projetado do painel.
No front, `CurrencyTotal` mostra uma linha por moeda e diz explicitamente que a
consolidação está indisponível.

## Compatibilidade offline

**Impressão digital de requisições.** Uma mutação enfileirada antes do
multi-moeda não tem o campo `currency`, e seu recibo foi gerado sobre um formato
sem esse campo. Incluir `"currency":null` mudaria o hash, e o reenvio de uma
mutação já aplicada cuja resposta se perdeu voltaria como
`IDEMPOTENCY_KEY_REUSED` — o usuário seria informado de que uma despesa que ele
realmente fez foi recusada, e a entrada ficaria presa na fila para sempre.

`RequestFingerprint` omite do hash apenas os campos introduzidos depois que o
formato foi congelado, e apenas quando ausentes. Ignorar nulos em geral não era
opção: `notes` e `accountId` são legitimamente nulos hoje e estão dentro dos
hashes existentes. Uma moeda explícita **sempre** entra no hash, então um pedido
em USD — ou mesmo um BRL explícito — nunca se confunde com um BRL implícito
antigo.

`clientMutationId` e `clientResourceId` não são regenerados em nenhuma hipótese.

Os handlers offline de lançamento, meta e item da lista preservam a ausência do
campo pelo mesmo motivo.

## Segurança

- Moeda validada em toda fronteira de confiança; nenhuma string arbitrária chega
  ao banco.
- Nunca se confia na moeda vinda do cliente quando ela pode ser derivada de um
  recurso do dono.
- Sem conversão, sem provedor externo, sem segredo novo.
- Nenhum payload financeiro é registrado em log.

## Limitações conhecidas

- **Não existe cotação.** Nenhuma conversão, nenhum total consolidado entre
  moedas, nenhuma análise de compra em moeda estrangeira.
- Pagamento de fatura entre moedas é recusado, não convertido.
- Não há ganho/perda cambial, spread, taxa ou reavaliação.
- A migração dos dados criptografados antigos, os demais agregados mistos
  (painel completo, orçamentos, previsão, insights, análise de compra), a
  importação com CURDEF e a interface completa de moeda ainda não estão prontos —
  ver o roadmap.

## Próxima etapa

Razão histórico de câmbio, conversão determinística e analytics em moeda base.
