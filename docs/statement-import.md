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

## Fingerprints e deduplicação

Três conceitos distintos, todos versionados (`Fingerprints.PARSER_VERSION`):

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

## Limitações conhecidas

- Transferências entre contas próprias não são detectadas automaticamente;
  cada lado do extrato entra como receita ou despesa comum.
- A importação de faturas de cartão de crédito é deliberadamente fora de
  escopo desta etapa (ver "Escopo do produto").
- Regras de categoria usam apenas correspondência de texto simples
  (`EXACT`/`STARTS_WITH`/`CONTAINS`) — sem regex, sem aprendizado estatístico.
- CSV exige mapeamento manual das colunas na primeira vez; não há detecção
  automática de layout específico de banco.
