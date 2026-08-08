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
mentir. Ele responde a **duas perguntas diferentes**, porque confundi-las é
exatamente como um número estrangeiro vira uma conclusão em moeda base:

| Campo | Pergunta |
| --- | --- |
| `byCurrency` | quanto há em cada moeda — sempre disponível |
| `homogeneous` / `homogeneousCurrency` | todos os valores compartilham uma moeda? |
| `nativeTotal` | o total somável nessa moeda; **ausente** quando há mistura |
| `baseComplete` | tudo já está na moeda base do usuário? |
| `baseTotal` | o total em moeda base; **ausente** quando `baseComplete` é falso |
| `unconvertedCurrencies` | exatamente o que uma etapa de câmbio precisaria converter |

Um conjunto inteiramente em USD tem um `nativeTotal` real e útil. Ele **não** é
uma análise completa em BRL: `baseTotal` continua ausente. Somar as moedas, ou
descartar as estrangeiras e apresentar o resto como total, produziriam igualmente
um número sobre o qual o usuário agiria. O agrupamento é limitado pelo catálogo
fechado, ordenado pela posição no catálogo (um `EnumMap`), então nunca cresce com
o tamanho do razão e dois conjuntos iguais serializam igual.

### Fluxos versus fotografias

`of(...)` trata **qualquer** lançamento como presença, mesmo quando os valores
de uma moeda se anulam: +100 e −100 USD são dois eventos em datas diferentes, que
um razão de câmbio futuro converteria por cotações diferentes — a soma em BRL não
é zero e não pode ser assumida.

`ofSnapshots(...)` descarta uma moeda cujo total pontual é exatamente zero. Uma
conta em USD zerada converte para zero sob qualquer cotação, então ela não pode
tornar indisponível uma análise em BRL que de resto está completa. É usado para
saldos e limites, nunca para fluxos.

### Onde já está aplicado

- **Contas** — `GET /accounts/overview` devolve a lista mais os saldos agrupados,
  ativos e arquivados separados. Os saldos vêm de duas consultas agrupadas, não de
  duas por conta.
- **Painel** — saldos, receitas, despesas, resultado do mês, despesa do mês
  anterior, despesa reconhecida de cartão, limite disponível e dívida de cartão
  são todos `CurrencyTotals`. As **razões derivadas** (taxa de poupança, variação
  contra o mês anterior) são nulas a menos que os dois operandos estejam completos
  em moeda base — uma taxa calculada só sobre a fatia em BRL seria lida como uma
  afirmação sobre o mês inteiro. As participações por categoria passam a ser por
  categoria **e** moeda, e a porcentagem é medida contra as despesas daquela mesma
  moeda. A tendência vira **uma série homogênea por moeda**, cada uma com seu
  próprio eixo.
- **Previsão e caixa futuro** — a previsão roda uma corrida independente por
  moeda: saldo de abertura, série, menor saldo e primeira data negativa
  separados, cada evento carregando a moeda derivada do seu recurso de origem.
  Uma previsão filtrada por conta é homogênea por construção e mantém todos os
  escalares. O painel consome esses resumos diretamente — um saldo projetado por
  moeda, sem consolidação. Ver [forecast.md](forecast.md).
- **Compromissos futuros** — total projetado agrupado.
- **Importação de extratos** — todo lote tem exatamente uma moeda efetiva, a da
  conta de destino, e nada é convertido. O `CURDEF` do OFX é lido e **confrontado**
  com a conta; divergência e moeda fora do catálogo são recusadas antes de o lote
  existir. Um arquivo que não declarou moeda, e um lote anterior ao registro dessa
  evidência, exigem confirmação explícita antes de materializar. Toda transação
  gerada recebe a moeda da conta explicitamente. Ver
  [statement-import.md](statement-import.md).

No front, `CurrencyTotal` (rodapés) e `CurrencyStat` (números de destaque)
distinguem os três casos: completo em moeda base, homogêneo mas estrangeiro
(dito explicitamente), e misto (uma linha por moeda, sem consolidação). Um total
indisponível nunca é renderizado como zero nem como um travessão sozinho.

## Orçamentos incompletos

Orçamentos são denominados na moeda base — não há moeda editável por orçamento,
porque um limite descolado da moeda da análise ao redor não significaria nada.

Quando a mesma categoria e mês têm gastos em outra moeda, o consumo em moeda base
é um **piso**, não uma resposta. Tratar o gasto estrangeiro como zero deixaria um
orçamento realmente estourado marcado como saudável, que é exatamente a
tranquilidade sobre a qual alguém agiria. Nesse caso:

- o estado vira `INCOMPLETE` — não é uma quarta severidade, é a ausência de nota;
- `consumedAmount` continua reportando a parte conhecida em moeda base;
- `consumedTotals` reporta cada moeda separadamente;
- `remainingAmount` e `percentUsed` ficam **nulos**, porque um valor subestimado
  é lido como um valor completo;
- o resumo do mês expõe `incompleteCount`, e `totalRemaining`/`percentUsed` do
  resumo também ficam nulos enquanto houver qualquer orçamento incompleto.

Vale para lançamentos comuns, parcelas de cartão e ajustes de fatura. O consumo de
todos os orçamentos do mês vem de três consultas agrupadas — nunca três por
orçamento.

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
- **Análise de compra** — o contexto de compra é ciente de moeda e a análise tem
  disponibilidade tipada: um item estrangeiro ou um contexto incompleto em moeda
  base devolve `EXCHANGE_RATE_REQUIRED` em vez de `BUY`/`WAIT`, e as mensagens de
  uma análise disponível são formatadas na moeda base real, não em BRL. Ver
  [purchase-analysis.md](purchase-analysis.md).
- **Insights** — regras nativas de cartão continuam visíveis e na moeda do
  cartão sob qualquer razão misto; regras agregadas só rodam com operandos
  completos em moeda base e, quando retidas, aparecem em `aggregateCoverage`.
  Ver [insights.md](insights.md).
- **Importação de extratos** — moeda por lote, `CURDEF` do OFX, confirmação da
  suposição, precisão por moeda e denominação explícita na materialização. Ver
  [statement-import.md](statement-import.md).
- Moeda nas notificações e a interface completa de moeda ainda não estão prontas —
  ver o roadmap.

## Próxima etapa

Moeda nas notificações e privacidade das reivindicações de navegador. Depois
disso, o fechamento da interface de moeda e a remoção dos últimos fallbacks em
BRL fixo — ver [multi-currency-fallbacks.md](multi-currency-fallbacks.md).

O núcleo multi-moeda **segue em andamento**. Razão histórico de câmbio, conversão
determinística e analytics em moeda base só começam depois que ele fechar.
