# Importação de extratos

Upload de extratos bancários em CSV ou OFX, com pré-visualização determinística,
detecção de duplicatas, categorização assistida por regras e confirmação
auditável. O domínio vive em `com.finora.api.statementimport` e a interface em
`apps/web/src/features/statement-imports/`.

## Escopo do produto

Importa **extratos de conta bancária** (`CHECKING`, `SAVINGS`) para transações
comuns. Faturas de cartão de crédito são deliberadamente **bloqueadas**: o
parser OFX detecta `CREDITCARDMSGSRSV1`/`CCSTMTRS`/`CCSTMTTRNRS`/`CCACCTFROM` e
recusa o arquivo com `STATEMENT_CARD_NOT_SUPPORTED`, apontando para a área de
Cartões — importar uma fatura como despesas bancárias comuns corromperia o
modelo de fatura/parcela/limite. Tipos de conta OFX diferentes de conta
corrente/poupança também são recusados (`STATEMENT_OFX_ACCOUNT_TYPE`).

## Invariante central

> Linhas parseadas são **apenas pré-visualização**. Depois da confirmação, **um
> item incluído gera no máximo uma transação real**. Reenvios, retentativas e
> confirmações concorrentes nunca duplicam — o backstop é um índice único
> parcial no banco, não uma checagem em memória.

O arquivo enviado nunca é a fonte de verdade financeira; o ledger de
importação (lote + itens) e o vínculo com a transação gerada são a trilha de
auditoria.

> Todo lote tem **exatamente uma moeda efetiva**: a da conta de destino. **Nada é
> convertido em nenhum momento** — o Finora não tem taxas de câmbio, então um
> extrato estrangeiro é lido na moeda da conta ou recusado, nunca reinterpretado.

`valuesAreConverted` é `false` por construção na resposta do lote. O campo existe
para que o cliente **afirme** esse fato em vez de assumi-lo.

## Upload e privacidade

- Tamanho máximo: **5 MB**, reforçado tanto pelo container servlet
  (`spring.servlet.multipart.max-file-size`) quanto pela aplicação.
- Até **10.000 lançamentos** por arquivo, linhas de até 10.000 caracteres,
  campos de até 500 caracteres.
- Conteúdo binário (bytes nulos) é rejeitado antes do parse.
- **CSV**: os bytes brutos ficam em armazenamento temporário limitado
  (`TempStatementStore`) apenas enquanto o usuário configura o mapeamento das
  colunas — nomes de arquivo aleatórios (UUID), nunca o nome original;
  descartados explicitamente após o parse autoritativo e varridos após 24h se
  abandonados.
- **OFX**: não há armazenamento temporário — o parse acontece direto sobre os
  bytes recebidos e o resultado normalizado é o que persiste.
- Nada do conteúdo bruto aparece em log; apenas IDs e mensagens genéricas.
- Nome de arquivo é sanitizado (sem caminho, sem caracteres de controle,
  truncado em 255) antes de virar metadado do lote.

## CSV: decodificação e mapeamento

- Codificação: UTF-8 (com ou sem BOM) ou Windows-1252, detectada ou escolhida
  explicitamente.
- Delimitador: vírgula ou ponto e vírgula; aspas e aspas escapadas suportadas;
  delimitador dentro de valor entre aspas é respeitado.
- CRLF e LF, linhas em branco, espaços nas pontas.
- Formato brasileiro: vírgula decimal, ponto de milhar, sinal negativo.
- Valor único com sinal **ou** colunas separadas de débito/crédito.
- Cabeçalho opcional; padrões de data configuráveis
  (`dd/MM/yyyy`, `dd/MM/yy`, `yyyy-MM-dd`, `dd-MM-yyyy`, `dd.MM.yyyy`,
  `MM/dd/yyyy` — nessa ordem de sugestão, com o padrão brasileiro primeiro).
- Coluna opcional de identificador externo e de memo/referência.
- Nenhuma fórmula é executada — toda célula é tratada como texto puro (não há
  dependência de planilha na base de código).
- Mapeamento ambíguo ou contraditório é rejeitado com uma mensagem segura
  em vez de um parse silenciosamente errado.
- `PUT /csv-mapping` devolve uma prévia (linhas de amostra, válidas/inválidas)
  sem persistir nada; `POST /reparse` é o parse autoritativo que gera os itens
  reais e descarta os bytes brutos.

## OFX: parser seguro

Não existe parser XML nesta funcionalidade — o `OfxStatementParser` é um
tokenizador de tags escrito à mão, sem DTD, sem resolução de entidades além das
cinco predefinidas (`&amp; &lt; &gt; &quot; &apos;`) e referências numéricas
limitadas, sem acesso externo, sem XInclude, sem rede. Qualquer
`<!DOCTYPE` ou `<!ENTITY` é rejeitado de imediato
(`STATEMENT_OFX_DTD`), mesmo não sendo processável de outra forma — defesa em
profundidade.

- Suporta OFX 1.x (SGML, tags de folha sem fechamento) e OFX 2.x (XML) com o
  mesmo scanner de tags.
- Limites: tamanho da entrada, contagem de entradas, comprimento de tag (64) e
  de valor (500), profundidade de aninhamento (32). Aninhamento malformado
  falha com um código estável, nunca um stack trace.
- `DTPOSTED` é interpretado pelos 8 primeiros dígitos como data local — sufixo
  de fuso nunca move a transação para outro dia, e o resultado independe do
  fuso da máquina que roda o parser.
- `FITID` é a identidade forte quando presente; sem `FITID`, a identidade vem
  do fingerprint de conteúdo.
- `NAME`/`MEMO` alimentam a descrição (com fallback); `CHECKNUM` vira parte do
  memo quando presente.
- Número de conta é exposto apenas como dica mascarada
  (`••••1234`, opcionalmente com o `BANKID`) — nunca a conta completa, e nunca
  usado para vincular automaticamente a uma conta Finora.

## Modelo normalizado

CSV e OFX convergem em um único `StatementEntry`: índice de origem,
identificador externo opcional, data, valor absoluto positivo, tipo
(`INCOME`/`EXPENSE` derivado do sinal — valor zero é inválido e bloqueante),
descrição original e canônica, memo, tipo de origem e problemas de validação.
Todo valor usa `BigDecimal`/`MoneyRules`, nunca ponto flutuante. Transferências
entre contas **não** são inferidas automaticamente: um extrato não prova que a
outra ponta existe em outra conta Finora, então cada lançamento entra como
receita/despesa comum.

## Moeda da importação

A conta de destino é a autoridade sobre a denominação. O que varia entre os lotes
não é a moeda efetiva, e sim a **evidência** por trás dela — e é a evidência que
decide se o usuário precisa confirmar uma suposição antes de criar dinheiro.

`statement_import_batches.currency_source` (enum `StatementCurrencySource`):

| Origem | Quando | `declared_currency` | Exige confirmação |
|---|---|---|---|
| `ACCOUNT` | CSV. O contrato CSV do Finora não tem coluna de moeda, então **escolher a conta já é** a declaração de denominação. | `NULL` | não |
| `FILE` | OFX cujo `CURDEF` foi lido **e** coincide com a conta. | código suportado | não |
| `ACCOUNT_ASSUMED` | OFX que não trouxe `CURDEF` algum. A moeda da conta será usada, mas isso é suposição do Finora, não afirmação do arquivo. | `NULL` | **sim** |
| `LEGACY_UNKNOWN` | Lote OFX criado antes da V16, quando o parser não registrava se havia `CURDEF`. | `NULL` | **sim**, se ainda houver item a materializar |

`LEGACY_UNKNOWN` não é o mesmo que `ACCOUNT_ASSUMED`, e a diferença importa: o
parser da época **nunca olhou** para o `CURDEF`. Dizer que aqueles arquivos não
declararam moeda seria inventar evidência sobre um documento que o Finora já não
tem. A interface reflete isso com texto diferente — "esta importação foi criada
antes de o Finora registrar a moeda declarada pelo arquivo", nunca "o arquivo não
declarou uma moeda".

Um lote `FILE` **não pode existir** sem o código que leu, e nenhuma outra origem
pode carregar um; a restrição vale no construtor da entidade e como `CHECK` no
banco.

### `CURDEF` do OFX

O scanner limitado existente passou a ler o `CURDEF` de **metadados de extrato**.
Nada mudou nos limites de segurança: sem parser XML, sem DTD, sem entidades
externas, com todos os limites de tamanho, profundidade e comprimento intactos.

- Só metadados de extrato declaram a moeda do extrato. Um `<CURDEF>` dentro de um
  `STMTTRN`, um agregado `CURRENCY`/`ORIGCURRENCY` por transação e a palavra num
  `MEMO` são todos ignorados.
- Normalização: espaços em volta são removidos e o código sai em maiúsculas
  canônicas (`usd`, ` USD `, `uSd` todos viram `USD`).
- **Repetição do mesmo código é aceita** — arquivos com vários extratos
  legitimamente repetem o `CURDEF`.
- **Códigos diferentes são recusados** (`STATEMENT_CURRENCY_CONFLICT`). Escolher o
  primeiro ou o último denominaria dinheiro real por uma leitura arbitrária de um
  documento ambíguo.
- Um valor que não tenha a forma de três letras é recusado
  (`STATEMENT_CURRENCY_INVALID`) **sem eco do conteúdo**, que é texto de arquivo
  não validado.
- Um código válido fora do catálogo fechado é recusado (`CURRENCY_UNSUPPORTED`).
  Nunca há remapeamento silencioso para algo que o Finora suporte.

### Recusas e o momento delas

| Situação | Código | Efeito |
|---|---|---|
| `CURDEF` diverge da conta | `STATEMENT_CURRENCY_MISMATCH` | recusado **antes** de o lote existir; nada é persistido |
| `CURDEF` fora do catálogo | `CURRENCY_UNSUPPORTED` | idem |
| Dois `CURDEF` diferentes | `STATEMENT_CURRENCY_CONFLICT` | idem |
| Confirmação sem o consentimento exigido | `STATEMENT_CURRENCY_ACK_REQUIRED` | recusada antes do primeiro item; **nenhum item vira `FAILED`** |

A ordem importa para privacidade tanto quanto para correção: a conta é resolvida
**pelo dono atual** antes de qualquer comparação de moeda. A conta de outra pessoa
continua indistinguível de inexistente e não pode ter a moeda sondada por upload.

### O consentimento não é identidade financeira

`ConfirmRequest.acknowledgeAccountCurrency` é consentimento, não identidade. Ele
**não** entra no hash do arquivo, no fingerprint de conteúdo, na identidade forte,
na classificação de duplicidade, na transação gerada nem no undo. Uma confirmação
consentida é tão idempotente quanto qualquer outra, e o consentimento nunca é
gravado nos itens.

Uma confirmação que **não materializaria nada** (lote concluído, ou só duplicatas
e exclusões restando) não exige o consentimento: não há suposição em jogo, e
recusá-la quebraria uma retentativa idempotente inofensiva.

### Troca de conta de destino

A conta decide a denominação, então trocá-la muda **o que os valores significam**,
nunca o valor deles. A interface diz isso em texto.

- `ACCOUNT` (CSV): permitido. A moeda efetiva passa a ser a da nova conta.
- `FILE`: só entre contas da moeda declarada. Outra moeda é recusada com
  `STATEMENT_CURRENCY_MISMATCH` **antes de qualquer campo mudar** — nada é
  gravado e revertido.
- `ACCOUNT_ASSUMED` / `LEGACY_UNKNOWN`: permitido. A suposição acompanha a conta
  e o consentimento continua exigido; a interface o **limpa**, porque a suposição
  aceita deixou de valer.

Em todos os casos a classificação de duplicidade, que é escopada por conta, é
recalculada como já era, e a precisão da moeda é reavaliada nas duas direções.

### Precisão e moedas sem centavos

`MoneyRules` é a autoridade única da regra e da mensagem. BRL, USD, EUR, GBP, CAD,
AUD e CHF aceitam até 2 casas; **JPY aceita 0**.

`JPY 100,50` **nunca** é arredondado para 101. A linha é marcada inválida com
`CURRENCY_FRACTION_INVALID` — na pré-visualização do mapeamento, na
pré-visualização autoritativa e novamente na materialização como defesa em
profundidade — e a edição do valor é recusada em vez de arredondada. O veredito de
precisão **não** mexe na escolha de inclusão do usuário: uma linha pode virar
inválida só porque a conta de destino mudou, sem edição alguma, e `INVALID` já
impede a materialização por conta própria.

Na apresentação, uma moeda de zero casas é formatada sem decimais para os valores
inteiros que normalmente carrega; um valor que **de fato** traga fração é impresso
como está, para não exibir uma quantia diferente da que está armazenada logo acima
da mensagem que pede a correção.

## Fingerprints e deduplicação

Duas versões distintas, e confundi-las é caro:

- **`Fingerprints.VERSION`** (1) é a composição do fingerprint — a lista de
  valores que entram no hash. É **identidade financeira**: decide se duas linhas
  são a mesma linha, e portanto se uma importação é duplicata. Movê-la sem
  necessidade tornaria todo fingerprint armazenado incomparável e reimportaria
  dinheiro já importado.
- **`Fingerprints.PARSER_VERSION`** (2) registra qual parser produziu um lote. A
  versão 2 acrescentou o `CURDEF` à saída do parser, que é saída observável nova
  — mas o `CURDEF` **não contribui** para o fingerprint de conteúdo, então a
  identidade de linha não mudou e `VERSION` permaneceu 1.

Três conceitos de identidade, distintos:

- **Hash do arquivo** (`SHA-256` dos bytes): identifica reenvio do mesmo
  arquivo (`fileAlreadyImported` no detalhe do lote).
- **Identidade forte**: `FITID` do OFX ou coluna de ID mapeada no CSV,
  combinada com dono + conta + tipo de origem. Tem backstop de índice único
  parcial no banco — é o que bloqueia duplicata exata.
- **Fingerprint de conteúdo**: dono + conta + data + tipo + valor normalizado
  + descrição canônica, para linhas sem identificador confiável.

Classificação por item:

| Situação | Significado |
|---|---|
| `UNIQUE` | Sem qualquer correspondência. |
| `EXACT_DUPLICATE` | Identidade forte já importada nesta conta — **bloqueado** por padrão. |
| `POSSIBLE_DUPLICATE` | Fingerprint de conteúdo bate com uma transação existente (manual ou importada sem ID forte) dentro de uma janela de 3 dias — exige decisão explícita (pular ou importar mesmo assim). |
| `DUPLICATE_WITHIN_FILE` | Repetição da mesma identidade/fingerprint dentro do próprio arquivo. |

Toda a classificação roda em consultas em lote (nunca uma consulta por linha):
IDs externos já importados, fingerprints já importados e o pool de transações
candidatas no período (com margem de 3 dias) são carregados de uma vez.
Reenviar o mesmo arquivo mostra duplicatas exatas para linhas com identidade
forte e possíveis duplicatas para as demais — nunca importa nada de novo sem
decisão explícita.

## Regras de categoria

Motor determinístico, sem IA e sem regex do usuário (evita ReDoS): cada regra
compara `EXACT`, `STARTS_WITH` ou `CONTAINS` contra a descrição normalizada (ou
o memo). Precedência, do mais para o menos específico:

1. regra com conta específica antes de regra global;
2. tipo de transação compatível;
3. maior prioridade explícita;
4. operação mais específica (`EXACT` > `STARTS_WITH` > `CONTAINS`);
5. padrão normalizado mais longo;
6. ID estável como desempate.

A pré-visualização mostra a regra usada, o padrão e uma classificação de
confiança **determinística** (derivada da operação — nunca uma porcentagem
estatística). O usuário pode aceitar, escolher outra categoria, aplicar a
mesma categoria a linhas semelhantes selecionadas e salvar uma correção como
nova regra. `GET/POST/PUT/DELETE /api/category-mapping-rules` são owner-scoped.

## Pré-visualização e edição

O upload nunca cria transações. `GET /api/statement-imports/{id}` é
autoritativo: totais (linhas, inválidas, duplicatas, sem categoria), cada item
normalizado, motivo de duplicata, sugestão e categoria selecionada, estado de
inclusão e se é importável agora. `PATCH /items/{itemId}` permite, antes da
confirmação: incluir/excluir, trocar categoria, corrigir data/descrição/tipo/
valor, decidir sobre duplicata possível e salvar a correção como regra — os
valores originais do parse ficam preservados separadamente para auditoria.
Trocar a conta de destino (`PATCH /{id}`, só antes da confirmação) reexecuta
deduplicação e sugestões de categoria do zero.

## Confirmação e materialização

`POST /api/statement-imports/{id}/confirm` (opcionalmente com uma lista de
`itemIds`, no máximo **500** por requisição — o frontend faz o chunking acima
disso) materializa cada item **independentemente**, em sua própria transação
(`REQUIRES_NEW`): uma linha ruim nunca desfaz as vizinhas válidas, e nenhuma
transação órfã sobrevive a uma falha. A materialização reusa as regras do
domínio de transações diretamente (nunca chama HTTP interno): posse de conta e
categoria, compatibilidade de tipo, normalização monetária. O método de
pagamento é `OTHER` — um extrato bancário nunca prova o instrumento, e nunca é
o `CREDIT` legado. Cada transação gerada carrega o vínculo imutável
`statement_import_item_id`, protegido por índice único parcial contra dupla
materialização sob concorrência.

A moeda é definida **explicitamente** a partir da conta de destino:

```java
MoneyRules.validateScale(item.getAmount(), account.getCurrency());
transaction.setAccount(account);
transaction.setCurrency(account.getCurrency());   // nunca o default da entidade
```

Não é o default `BRL` de `Transaction`, não é a `baseCurrency` do usuário, não é a
data do lote, o nome do arquivo, a descrição do OFX ou qualquer configuração: a
conta em que o dinheiro se move **é** o significado do valor importado. Antes
desta etapa a linha faltava, e com a FK composta `(account_id, currency)` da V15
no lugar o resultado não era uma linha mal rotulada — era um insert recusado,
item por item, reportado como conflito genérico. O defeito de integridade e o
defeito de usabilidade eram a mesma linha ausente.

Um lote cuja denominação é suposição (`ACCOUNT_ASSUMED`, `LEGACY_UNKNOWN`) exige
`acknowledgeAccountCurrency` antes de qualquer materialização — recusado antes do
primeiro item, sem marcar linha alguma como `FAILED`, porque quem está inválido é
o **pedido**, não o dado.

Cada item recebe um resultado estruturado: `SUCCESS`, `FAILED`, `SKIPPED`,
`EXACT_DUPLICATE`, `ALREADY_IMPORTED`, `BLOCKED`, `UNDONE` ou `ALREADY_UNDONE`,
com código e mensagem seguros em português. Confirmar de novo é idempotente —
um item já `IMPORTED` devolve `ALREADY_IMPORTED` com a transação existente em
vez de criar outra; uma corrida de índice único é recuperada lendo o vencedor
em vez de reportar falha espúria.

## Desfazer (undo)

`POST /{id}/items/{itemId}/undo` e `POST /{id}/undo` removem o efeito
financeiro sem apagar o ledger de importação: a transação gerada é excluída, o
item vira `UNDONE` (estado terminal) e permanece como registro de auditoria. A
identidade forte é liberada deliberadamente — um reenvio futuro pode importar
a linha de novo, por decisão explícita do usuário. Um item cuja transação
virou âncora de outra área (vínculo com recorrente, item de lista de desejos,
ou já financeiramente inativa) tem o undo **bloqueado** com motivo explicado —
nunca corrompe o domínio dono do vínculo. Desfazer duas vezes é idempotente
(`ALREADY_UNDONE`). O lote só vira `UNDONE` quando nenhum item confirmado
permanece `IMPORTED`.

## Propriedade e concorrência

- Toda consulta é owner-scoped (`userId`); lote, item, conta, categoria ou
  regra de outro usuário respondem **404**, nunca 403 — sem confirmar
  existência alheia.
- `StatementImportOwnershipTest` prova isolamento entre usuários e contagem
  única na contabilidade (saldo, orçamento, categoria).
- `StatementImportConcurrencyTest` roda contra PostgreSQL real: confirmações
  concorrentes do mesmo item produzem uma única transação; reenvio do mesmo
  arquivo e uploads concorrentes permanecem consistentes.

## API

```
POST   /api/statement-imports                        upload (multipart)
GET    /api/statement-imports                         histórico paginado
GET    /api/statement-imports/{id}                     detalhe autoritativo
PUT    /api/statement-imports/{id}/csv-mapping          prévia de mapeamento
POST   /api/statement-imports/{id}/reparse              parse autoritativo (CSV)
PATCH  /api/statement-imports/{id}                       troca de conta de destino
PATCH  /api/statement-imports/{id}/items/{itemId}        edição pré-confirmação
POST   /api/statement-imports/{id}/confirm               confirmação idempotente
POST   /api/statement-imports/{id}/undo                  desfazer o lote
POST   /api/statement-imports/{id}/items/{itemId}/undo   desfazer um item

GET    /api/category-mapping-rules
POST   /api/category-mapping-rules
PUT    /api/category-mapping-rules/{id}
DELETE /api/category-mapping-rules/{id}
```

### Moeda nas respostas

O detalhe do lote traz um bloco `currency` com `accountCurrency`,
`currencySource`, `declaredCurrency`, `effectiveCurrency`, `valuesAreConverted`
(sempre `false`) e `currencyAcknowledgementRequired`. `effectiveCurrency` é igual
a `accountCurrency` em todas as origens — um lote `FILE` só existe quando a
declaração coincidiu.

Valor monetário nunca viaja sem denominação onde possa ser renderizado sozinho:
`ItemResponse.currency` (o endpoint de edição devolve um item isolado),
`BatchTotals.currency` (os totais viajam dentro de `ConfirmResponse`, que não tem
outra moeda), `MatchedTransactionSummary.currency` (da própria transação casada) e
`MappingPreviewResponse.accountCurrency`. O histórico expõe `accountCurrency`,
`currencySource` e `declaredCurrency` por lote.

`POST /{id}/confirm` aceita `{ itemIds?, acknowledgeAccountCurrency? }`. Erros
próprios desta etapa: `STATEMENT_CURRENCY_MISMATCH`, `CURRENCY_UNSUPPORTED`,
`STATEMENT_CURRENCY_CONFLICT`, `STATEMENT_CURRENCY_INVALID`,
`STATEMENT_CURRENCY_ACK_REQUIRED` e `CURRENCY_FRACTION_INVALID`.

## Interface

- **`/statement-imports` ("Importar extrato")**: rota autenticada com lazy
  loading, alcançável pela navegação principal.
- **Upload**: conta de destino, seleção de arquivo, explicação de formatos,
  limite de tamanho e de que o arquivo bruto não é retido.
- **Mapeamento CSV**: linhas representativas, configuração de codificação,
  delimitador, cabeçalho, colunas, padrão de data e separadores — sempre com
  prévia gerada pelo backend, nunca cálculo só no frontend.
- **Pré-visualização**: totais, filtro por situação, busca por descrição,
  inclusão/exclusão em lote e por linha, edição por item, revisão de
  duplicata lado a lado (lançamento do extrato vs. transação existente) com
  ações explícitas "Pular" / "Importar mesmo assim".
- **Confirmação**: resumo antes de confirmar (incluídos, excluídos,
  duplicatas bloqueadas, entradas/saídas, efeito líquido), botão que declara
  explicitamente que transações reais serão criadas, resultado por item após
  confirmar, retentativa apenas dos itens com falha.
- **Histórico e detalhe**: lotes paginados com filtro por conta, detalhe com
  itens, vínculos de transação gerada, duplicatas e decisões de categoria.
- **Desfazer**: confirmação explícita que explica o efeito financeiro antes de
  agir; motivo de bloqueio quando aplicável.
- Depois de confirmar/desfazer, o frontend invalida importações, transações,
  contas, orçamentos, dashboard, insights, previsão e regras de categoria.
- Tabelas largas (itens do extrato, histórico) rolam horizontalmente dentro de
  um contêiner próprio (`.table-wrap`) em vez de alargar a página — a
  suíte `statement-imports.spec.ts` prova o fluxo primário sem scroll
  horizontal da página em 390px.

## Migração V11

`V11__statement_import.sql` (imutável a partir daqui): tabelas
`statement_import_batches`, `statement_import_items` e
`category_mapping_rules`, com FKs de dono compostas `(id, user_id)`, índices
por usuário+data, usuário+conta, status de lote, status de item, identificador
externo, fingerprint e transação gerada, e os índices únicos parciais que
sustentam a proteção contra duplicata exata e dupla materialização.
`MigrationFromPopulatedV10Test` prova que dados existentes (usuários, contas,
categorias, transações, orçamentos, compromissos, cartões, faturas, conversões
de crédito legado) sobrevivem à migração e que nenhuma transação antiga nasce
marcada como importada.

## Migração V16

`V16__statement_import_currency_metadata.sql` acrescenta
`statement_import_batches.currency_source` (`NOT NULL` depois do backfill, com
`CHECK` sobre o enum) e `declared_currency` (`VARCHAR(3)`, `NULL`, restrito ao
catálogo fechado), mais o `CHECK` que amarra os dois: `FILE` exige o código,
qualquer outra origem exige `NULL`.

Backfill determinístico: CSV existente vira `ACCOUNT` (era exatamente o que já
era), OFX existente vira `LEGACY_UNKNOWN` — nunca `ACCOUNT_ASSUMED`, que
afirmaria que aqueles arquivos omitiram o `CURDEF`. Nenhum default sobrevive ao
backfill: depois da V16 a aplicação classifica cada lote explicitamente, como a
V15 já fez para as colunas de moeda.

A migração também traz um reparo **estreitamente delimitado** da denominação de
transações geradas por importação: para linhas com `statement_import_item_id`
não nulo, do mesmo dono da conta, cuja moeda discorda da conta, a moeda passa a
ser a da conta. Só o rótulo se move, e só para a conta que já é a fonte de
verdade dele — valor, data, tipo, categoria, conta, descrição, notas e a
identidade de importação ficam intactos, e nenhuma transação fora da importação é
considerada. **Não é conversão**: o Finora não tem taxas.

Numa linhagem que carrega a FK composta `fk_transactions_account_currency` da V15
esse `UPDATE` não encontra nada e é no-op — a FK torna a linha impersistível. Ele
roda de todo modo porque "não havia nada a reparar" precisa ser um resultado
verificado, não uma suposição: um dump restaurado ou uma linhagem sem a restrição
poderia carregar tal linha. `MigrationFromPopulatedV15Test` cria a linha sintética
removendo a FK, prova o reparo e então **reinstala a FK** para demonstrar que o
estado reparado é consistente com ela.

## Limitações conhecidas

- Transferências entre contas próprias não são detectadas automaticamente;
  cada lado do extrato entra como receita ou despesa comum.
- A importação de faturas de cartão de crédito é deliberadamente fora de
  escopo desta etapa (ver "Escopo do produto").
- Regras de categoria usam apenas correspondência de texto simples
  (`EXACT`/`STARTS_WITH`/`CONTAINS`) — sem regex, sem aprendizado estatístico.
- CSV exige mapeamento manual das colunas na primeira vez; não há detecção
  automática de layout específico de banco.
