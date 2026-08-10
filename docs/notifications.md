# Entrega de notificações

## Escopo do produto

O Finora entrega alertas por dois canais: uma caixa de entrada persistente e,
quando o usuário autoriza, notificações nativas do navegador enquanto alguma
aba do Finora está aberta. Não há e-mail, SMS, Web Push, Service Worker ou
entrega com todas as abas fechadas. A caixa de entrada é sempre o fallback
durável.

Notificações são artefatos derivados. Ler, dispensar, adiar ou entregar um
alerta nunca altera transações, recorrentes, ocorrências, faturas, cartões,
contas ou cálculos de previsão.

## Fontes e identidade

O sincronizador reutiliza exclusivamente o `DueEventService`, a mesma fonte de
`GET /api/events/due`: recorrentes próximos/hoje/vencidos/com falha, faturas
próximas/hoje/vencidas e o risco de caixa negativo **de cada moeda**. O `id`
público legado continua disponível; `sourceKey` é a identidade estável de
entrega:

- `COMMITMENT:{commitmentId}:{scheduledDate}`;
- `CARD_INVOICE:{invoiceId}`;
- `FORECAST:INSUFFICIENT_CASH:{moeda}`.

Tipo e severidade não fazem parte dessa chave. PostgreSQL garante uma única
linha por `(user_id, source_key)`.

## Moeda autoritativa

Todo evento monetário carrega a moeda do recurso que é dono do dinheiro. Não há
conversão em lugar nenhum: BRL continua BRL, USD continua USD, JPY continua JPY,
e valores em moedas diferentes nunca são somados.

| Fonte | Moeda vem de |
| --- | --- |
| Recorrente | o próprio compromisso (`commitments.currency`) |
| Fatura de cartão | o cartão que a emite, via o resumo da fatura |
| Risco de caixa | a série daquela moeda na previsão |

A moeda base do usuário **não** é evidência sobre nada disso. Um risco em
dólares de quem tem base em reais é um risco em dólares.

`DueEvent` exige a moeda no construtor, então um valor não consegue viajar sem a
denominação que lhe dá sentido, e a linha persistida — cuja coluna `currency` é
`NOT NULL` desde a V15 — sempre tem o que gravar. A entidade `Notification` não
tem mais inicializador `BRL`: um campo que já vem preenchido nunca obriga o
chamador a resolver o valor real, e foi exatamente assim que dinheiro
estrangeiro passou a ser rotulado como reais.

### A denominação é histórica

`notifications.currency` é imutável (`updatable = false`). Uma notificação diz
quanto era, na moeda em que era; reescrever isso reinterpretaria o histórico em
vez de reformulá-lo.

Se uma fonte reaparecer com moeda diferente da armazenada, a linha não é
rerrotulada: o **valor é retirado** (`amount` nulo) e a revisão avança, porque
uma quantia sob a moeda errada é um número errado, enquanto nenhuma quantia é
apenas menos informação. Essa retirada é o estado terminal — a moeda armazenada
nunca se move, então o descasamento nunca some, e sem um estado durável a
sincronização seguinte re-notificaria a cada quinze minutos para sempre. O
serviço de recorrentes já recusa trocar a moeda de um compromisso, então esse
caminho é defesa em profundidade e não uma jornada alcançável hoje.

## Risco de caixa por moeda

A previsão já é particionada por moeda. Seus campos escalares existem só para o
caso homogêneo e ficam **todos nulos** assim que aparece uma segunda moeda — de
modo que ler o escalar significava que adquirir uma conta em dólares desligava
silenciosamente o alerta de caixa também em reais. O feed passa a ler
`byCurrency`.

- BRL negativo → um evento em BRL;
- USD negativo → um evento em USD;
- os dois negativos → dois eventos independentes, com data e valor próprios.

Nunca existe um saldo sintético somando moedas. Resolver o risco de uma moeda
não resolve o da outra, porque cada um tem a sua própria `sourceKey`.

A chave legada `FORECAST:INSUFFICIENT_CASH` **não** foi reaproveitada para BRL.
Ela era derivada do escalar, que só é preenchido em previsão homogênea — e uma
previsão homogênea pode ser homogênea em dólares. A denominação daquela linha é
desconhecível, então reaproveitar a chave reinterpretaria histórico ambíguo. A
chave simplesmente deixa de ser produzida e a regra de "fonte ausente após
geração completa" a resolve para o histórico, sem apagar nada, enquanto um ciclo
de vida por moeda começa do zero.

O título do evento de caixa **não** nomeia a moeda, de propósito — veja a seção
de privacidade.

## Revisões e ciclo de vida

Cada linha começa na revisão 1. Reaparecimento após resolução, aumento de
severidade, progressão para hoje/vencido/falha ou mudança material da data
incrementam a revisão. A nova revisão volta a ser não lida e elegível ao
navegador; uma escalada cancela o adiamento corrente. Atualizações simples de
título, valor ou rota preservam leitura, dispensa e adiamento.

Leitura, dispensa, entrega no navegador e adiamento guardam a revisão a que se
aplicam. Adiamentos aceitam apenas instantes futuros dentro de 30 dias. Uma
fonte ausente após geração completa é marcada como resolvida, sem exclusão. Se
a geração falhar, nenhuma resolução é executada. Se a fonte voltar, a mesma
linha é reativada, revisionada e preserva o histórico.

## Sincronização e agendamento

`POST /api/notifications/sync` faz catch-up explícito somente para o usuário da
sessão. O job automático percorre usuários `ACTIVE` em páginas limitadas e
isola falhas por usuário. Cada usuário ocupa uma transação própria. Um advisory
lock transacional por proprietário serializa job e sync manual; locks de linha
coordenam ações concorrentes. O lookback de vencidos é limitado a 30 dias e a
janela futura usa `upcomingLeadDays` (1–14).

Configuração:

| Variável | Padrão | Papel |
| --- | --- | --- |
| `FINORA_NOTIFICATIONS_AUTO_SYNC_ENABLED` | `true` | liga o job |
| `FINORA_NOTIFICATIONS_INITIAL_DELAY` | `PT1M` | atraso inicial |
| `FINORA_NOTIFICATIONS_SYNC_INTERVAL` | `PT15M` | intervalo |
| `FINORA_NOTIFICATIONS_USER_BATCH_SIZE` | `100` | usuários por página (máx. 500) |

## Preferências

`GET/PUT /api/notification-preferences` controla a caixa, janela futura,
famílias de fonte, navegador, severidade mínima e exposição de valores. V12
cria defaults para usuários existentes e o registro cria a linha atomicamente.
Desabilitar uma família faz o próximo sync resolver os itens fora do conjunto;
o histórico não é apagado.

Ao habilitar o navegador, o servidor registra um baseline. Revisões anteriores
ao baseline não formam um backlog inesperado. A exposição de valores começa
desligada.

## API da caixa de entrada

- `GET /api/notifications?filter&page&size`: filtros `ACTIVE`, `UNREAD`,
  `SNOOZED`, `DISMISSED`, `RESOLVED`, `ALL`; página de 1–100 itens em envelope
  estável. Cada item traz `currency` junto de `amount`, e o construtor de
  `NotificationResponse` recusa valor sem moeda — nenhum cliente precisa decidir
  quanto vale uma quantia sem rótulo;
- `GET /api/notifications/unread-count`;
- `POST /api/notifications/{id}/read|unread|dismiss|restore|snooze`;
- `POST /api/notifications/read-all`;
- `POST /api/notifications/browser-claims` (até 10 revisões).

Toda mutação exige sessão e CSRF. O proprietário vem da identidade autenticada;
ids de outro usuário respondem 404. `restore` remove apenas a dispensa e nunca
reativa uma fonte resolvida.

## Navegador, privacidade e navegação

A permissão da API `Notification` só é solicitada por clique explícito na tela
de configurações. A interface descreve `default`, `granted`, `denied` e
`unsupported` em português e não repete o prompt após negação.

O claim usa `FOR UPDATE SKIP LOCKED`, marca a revisão atomicamente como entregue
e impede duas abas de receberem a mesma revisão. Entrega não marca como lida.
Texto é renderizado como texto, e cliques aceitam apenas rotas internas
fornecidas pelo servidor. Uma falha da API do navegador não remove o item
persistente.

### O par valor + moeda

A entrega no navegador é a superfície menos privada do produto: sai da página
autenticada e é desenhada pelo sistema operacional. Nela, `amount` e `currency`
são um par indivisível, garantido no construtor de `BrowserClaimResponse` em vez
de confiado ao chamador:

| `browserShowAmounts` | `amount` | `currency` |
| --- | --- | --- |
| `true` | o valor | a moeda dele |
| `false` | ausente | ausente |

Acrescentar moeda a toda notificação **não** pode produzir
`{"amount": null, "currency": "USD"}`: saber que a quantia escondida é em
dólares é boa parte do que esconder a quantia protegia.

Três consequências disso, todas deliberadas:

- **O claim não carrega `sourceKey`.** A identidade de risco de caixa nomeia a
  moeda, então transportá-la vazaria a denominação de um valor omitido. As abas
  se desduplicam por `id` + `revision`, que já é único por proprietário; a caixa
  de entrada autenticada continua expondo `sourceKey` normalmente.
- **Nenhum título nomeia moeda.** O título vai para o navegador em qualquer
  configuração, então o evento de caixa mantém `Saldo projetado fica negativo em
  {data}`. O preço é que dois riscos com a mesma data compartilham o título; eles
  se distinguem pelos valores, que são texto real e são anunciados por leitor de
  tela.
- **O corpo genérico é usado quando falta qualquer metade do par.** A página não
  renderiza uma quantia que não consegue denominar, mesmo que o servidor um dia
  envie uma.

Valores expostos são formatados por `formatMoney` (`src/lib/money.ts`), a mesma
função da interface: USD nunca sai com `R$` e JPY não ganha centavos. Nenhum
arquivo de produção em `features/notifications/` usa mais `formatBRL`, e um
teste com `import.meta.glob` falha se ele voltar.

## Persistência, falhas e limitações

V12 cria `notification_preferences` e `notifications`, com FKs de proprietário,
checks de enum/revisão/limites, versionamento otimista e índices alinhados às
consultas de ativo, não lido, claim e histórico resolvido. Metadados de recurso
servem somente para auditoria/navegação e não são autoridade financeira.

A V15 acrescentou `notifications.currency` `NOT NULL` e rotulou como BRL tudo que
existia — correto, porque o Finora era de moeda única até ali. O que ela não
podia corrigir era a aplicação: o inicializador `BRL` da entidade gravou BRL em
toda notificação criada **depois** da V15, qualquer que fosse o dinheiro. A
**V17** repara essa janela, sem alterar esquema e com todo `UPDATE` escopado por
`user_id` além do id do recurso (a tabela não tem FK para a origem, então juntar
só pelo id deixaria o compromisso de um dono rerrotular a notificação de outro):

| Tipo | Reparo | Por quê |
| --- | --- | --- |
| `COMMITMENT` | moeda do compromisso | o registro existe e é a autoridade |
| `CARD_INVOICE` | moeda do cartão da fatura | idem |
| `FORECAST` | nada é rerrotulado; o **valor** é retirado | a denominação é irrecuperável |

A notificação de previsão não nomeia recurso e a chave legada não nomeia moeda,
e a projeção que a gerou não existe mais. Nenhuma moeda é inventada: o que sai é
a **afirmação**, porque uma quantia sob uma moeda que ninguém consegue atestar é
um número errado. A exceção é comprovada, não otimista — uma linha criada antes
da V15 rodar é BRL de fato, e quando a V15 rodou está gravado no próprio banco,
em `flyway_schema_history.installed_on`. A ambiguidade de fuso na fronteira é
resolvida para o lado da retirada.

Essas linhas de qualquer forma vão fechar: a chave de risco de caixa agora nomeia
a moeda, então a chave antiga deixa de ser produzida e a próxima sincronização a
resolve para o histórico, sem apagar nada.

Foreground browser delivery é best-effort. A garantia do produto é a caixa de
entrada autenticada persistente entre reloads, sessões e reinícios.
