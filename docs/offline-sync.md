# Fila de mutações offline, idempotência e resolução de conflitos

## Escopo

O modo offline deixou de ser somente leitura. Um conjunto explícito de operações
seguras pode ser registrado sem conexão, fica criptografado no dispositivo e é
enviado ao servidor quando o aplicativo volta a ter rede.

O servidor continua sendo a autoridade. Nenhuma mutação é duplicada, sobrescrita,
mesclada ou descartada silenciosamente.

### Domínios suportados

| Recurso | Criar | Editar | Excluir |
| --- | --- | --- | --- |
| Transações comuns | sim | sim | sim |
| Orçamentos | sim | sim | sim |
| Metas | sim | sim | sim |
| Itens da lista de desejos | sim | sim | sim |
| Opções de compra | sim | sim | sim |
| Observações manuais de preço | sim (somente histórico) | sim | sim |

### Fluxos deliberadamente não suportados offline

Login, logout, cadastro, perfil, senha, contas, categorias, cartões de crédito,
compras de cartão, pagamentos e ajustes de fatura (e seus estornos), importação
de extratos (upload, mapeamento, confirmação, desfazer), regras de categorização,
ciclo de vida de recorrentes (executar, retry, pular, reagendar, estornar),
aporte em metas, execução de compra da lista de desejos, conversão de crédito
legado, ações de notificação, preferências, captura de preço da opção atual e
atualização da opção vinculada.

Não é omissão. Cada um desses fluxos depende do estado do servidor no instante em
que roda, ou mantém sua própria trilha de auditoria e sua própria âncora de
idempotência. Reproduzi-los horas depois, a partir de uma fila, produziria um
resultado que ninguém pediu.

Transações **geradas** por esses fluxos — importadas, de recorrente, de compra da
lista de desejos, de crédito legado e registros convertidos — também não podem ser
alteradas pelo endpoint de sincronização. Elas são editadas ou desfeitas na área
que as criou.

## Envelope de mutação

```jsonc
{
  "mutations": [
    {
      "clientMutationId": "uuid",     // chave de idempotência estável
      "resourceType": "TRANSACTION",  // lista fechada
      "operation": "CREATE",          // CREATE | UPDATE | DELETE
      "target": { "clientResourceId": "uuid" }, // ou { "serverId": 12 }
      "baseVersion": 3,               // obrigatório em UPDATE/DELETE
      "payload": { }                  // validado por um record tipado
    }
  ]
}
```

O cliente nunca envia método HTTP, rota, nome de classe, repositório, SQL ou
`userId`. O dono vem sempre da sessão autenticada. O despacho é um registro
explícito indexado pelo enum — não há reflexão em lugar nenhum.

`CREATE` exige `clientResourceId` e não aceita `baseVersion`. `UPDATE` e `DELETE`
exigem `baseVersion` e exatamente uma identidade. As duas identidades ao mesmo
tempo são recusadas: um alvo ambíguo poderia ser resolvido de duas formas.

### Limites

- lote padrão: 20 operações; máximo absoluto: 25;
- payload individual: 64 KiB;
- lote inteiro: 512 KiB;
- arquivos e conteúdo binário não são aceitos.

Os limites são verificados antes de qualquer processamento, então um lote grande
demais custa uma recusa e não vinte e cinco transações.

## Identidades estáveis

`clientMutationId` identifica **a requisição**; `clientResourceId` identifica **o
recurso**. Ambos são UUID gerados com `crypto.randomUUID()`. `Math.random`,
timestamps e contadores locais são inutilizáveis aqui: uma chave previsível ou
repetida duplicaria uma escrita financeira ou suprimiria uma real.

V14 adiciona `client_resource_id UUID` nulo em `transactions`, `budgets`, `goals`,
`wishlist_items` e `purchase_options`, com índice único parcial por dono:

```sql
unique (user_id, client_resource_id) where client_resource_id is not null
```

Dois usuários podem gerar o mesmo UUID sem colidir, e um UUID de outro dono
resolve para nada. `wishlist_price_snapshots` já tinha `client_request_id`
owner-scoped desde V13; ele é reutilizado como identidade de cliente em vez de
criar uma segunda coluna redundante. No cliente, o mesmo `clientRequestId` que o
formulário de observação de preço já gerava vira o `clientResourceId` da entrada
na fila — um segundo UUID teria de ser mantido em sincronia com ele para nada.

`purchase_options` ganhou `user_id` desnormalizado, preenchido a partir do item e
preso a ele por chave estrangeira composta. V8 tinha omitido a coluna porque todo
acesso era owner-scoped através do item; resolver uma opção pelo UUID gerado no
cliente é um caminho novo, e ele precisa ser único **por dono**, o que uma coluna
por item não expressa.

## Recibos de mutação

`offline_mutation_receipts` guarda, por dono:

```
user_id, client_mutation_id, resource_type, operation, request_hash,
client_resource_id, resource_id, resource_version, result_code, response_payload
```

com `unique (user_id, client_mutation_id)`.

O recibo é gravado **na mesma transação** da mutação de domínio. Não existe janela
em que o efeito colateral exista sem a sua prova — é exatamente isso que torna
seguro repetir uma requisição cuja resposta se perdeu.

Recibos são gravados **apenas para mutações que mudaram alguma coisa**. Um
conflito ou uma recusa não deixaram nada para ser idempotente, e devem poder ter
sucesso mais tarde se a condição que os bloqueava desaparecer.

### Impressão digital canônica

`request_hash` é um SHA-256 sobre tipo de recurso, operação, alvo, versão base e o
**payload canônico** — a forma tipada, validada e normalizada que o handler
produz, não o corpo bruto. Chaves de objeto são ordenadas e números têm uma única
representação. Assim, aparar um espaço ou mandar `42.000` em vez de `42.00` não
transforma uma retentativa em outra requisição. Nenhum `hashCode` de Java
participa disso.

### As três respostas para a mesma chave

| Situação | Resposta |
| --- | --- |
| Sem recibo | aplica |
| Recibo com a mesma impressão | devolve o resultado guardado, não repete nada |
| Recibo com impressão diferente | `IDEMPOTENCY_KEY_REUSED`; o recibo não é sobrescrito e o novo payload não é aplicado |

### Resposta perdida

1. o servidor confirma a mutação e o recibo;
2. o cliente perde a resposta HTTP;
3. o cliente repete **a mesma** `clientMutationId`;
4. o servidor devolve o resultado original;
5. o efeito financeiro aconteceu exatamente uma vez.

Isso não depende de deduplicação no cliente. O cliente não tem como distinguir
"nunca aplicou" de "aplicou e a resposta sumiu" — por isso quem sabe é o servidor.

## Versões otimistas

V14 adiciona `version BIGINT NOT NULL DEFAULT 0` aos cinco recursos suportados,
mapeado com `@Version`. `wishlist_price_snapshots` já tinha a sua e foi preservada
intacta. `updated_at` **não** é usado como token de conflito: duas escritas no
mesmo tique de relógio comparariam iguais.

`UPDATE` e `DELETE` offline enviam a versão que o usuário realmente viu. O
servidor compara antes de mutar; se diferir, devolve um conflito tipado sem
escrever nada. O bloqueio otimista do JPA continua sendo a segunda linha, cobrindo
a janela entre a comparação e o commit — as três formas em que o Hibernate reporta
essa falha viram o mesmo conflito, porque para o usuário significam a mesma coisa.

As respostas expõem `version` de forma **aditiva**; nenhum campo existente mudou
de nome ou desapareceu, e clientes online atuais continuam funcionando.

## Resultados

| Status | Significado |
| --- | --- |
| `APPLIED` | mutação e recibo confirmados juntos |
| `ALREADY_APPLIED` | já tinha sido aplicada; resultado guardado devolvido |
| `CONFLICT` | o estado do servidor mudou; precisa de decisão humana |
| `REJECTED` | inválida de forma permanente; nunca é repetida automaticamente |
| `DEPENDENCY_MISSING` | um pai criado offline ainda não chegou |

Falhas de transporte não aparecem aqui: uma conexão perdida ou um 5xx nunca vira
um desses estados, e o motor de retentativa do cliente repete a mesma
`clientMutationId`.

Cada mutação roda na **sua própria transação**, então uma operação recusada não
desfaz as independentes enviadas junto. A ordem de entrada é preservada e cada
entrada produz exatamente um resultado.

## Conflitos

Tipos: `VERSION_MISMATCH`, `REMOTE_DELETED`, `RESOURCE_ALREADY_EXISTS`,
`IDEMPOTENCY_KEY_REUSED`, `DEPENDENCY_CHANGED`.

A resposta traz o tipo, a versão local, a versão do servidor, um retrato público
do recurso (a mesma forma que o endpoint dele devolve) e as ações válidas. Nunca
traz id de dono, chave estrangeira interna, dado de sessão, stack trace ou
serialização crua de entidade.

**Não existe mesclagem automática e não existe last-write-wins.** O relógio do
cliente não é autoridade: uma alteração offline não vence por ser "mais nova".

### Ações

- **Manter o do servidor** — remove a operação local, mantém o valor do servidor e
  guarda um registro no log local limitado.
- **Aplicar minha alteração** — exige confirmação explícita, gera uma **nova**
  `clientMutationId` e adota a versão que o servidor acabou de informar. Reutilizar
  a chave em conflito pareceria, para o servidor, a mesma requisição chegando com
  conteúdo diferente — que ele corretamente recusa.
- **Editar e tentar de novo** — abre o formulário do domínio com os dois lados
  visíveis; ao salvar, também gera nova chave e nova versão base.
- **Descartar** — remove a operação local; o estado do servidor permanece.

Exclusão remota nunca recria o recurso automaticamente. Conflito de unicidade
nunca transforma um `CREATE` em `UPDATE` silenciosamente — o caso concreto é dois
aparelhos criando o orçamento da mesma categoria e mês.

## Fila local

Entradas guardam `clientMutationId`, tipo, operação, alvo, `clientResourceId`,
`baseVersion`, payload, dependências, status, timestamps, tentativas, próxima
tentativa, último erro e conflito. Tudo criptografado.

Status: `PENDING`, `BLOCKED`, `SYNCING`, `CONFLICT`, `FAILED_RETRYABLE`,
`FAILED_PERMANENT`, `APPLIED`, `DISCARDED`.

Limites: 200 entradas ativas, 100 registros de histórico, 64 KiB por payload,
2 MiB de fila serializada. Ao encher, novas mutações offline são **bloqueadas com
uma mensagem acionável** — a fila nunca descarta a mais antiga por conta própria.

### Compactação

| Sequência local | Resultado |
| --- | --- |
| `CREATE` + `UPDATE` | um `CREATE` com o payload mais recente |
| `CREATE` + `DELETE` | cadeia removida; o servidor nunca soube que existiu |
| `UPDATE` + `UPDATE` | um `UPDATE`, payload mais recente, **versão base original** |
| `UPDATE` + `DELETE` | um `DELETE` com a versão base original |
| `DELETE` + `UPDATE` | recusado até a exclusão ser descartada |

A versão base vem da edição mais antiga superseded, não da mais nova: uma segunda
edição local nunca observou uma versão nova do servidor, e fingir que observou
seria afirmar silenciosamente concordância com uma mudança que ninguém viu.

Compactação nunca cruza recursos diferentes e nunca toca uma entrada que o
servidor já pode ter visto. Uma entrada compactada recebe **nova**
`clientMutationId`, porque é uma requisição diferente das que substituiu.

Excluir um pai criado offline cancela os filhos enfileirados sob ele. Um pai
recusado de forma permanente deixa os filhos bloqueados e visíveis, não órfãos
invisíveis.

## Ordenação e dependências

O motor envia pais antes de filhos, aproveitando que o servidor confirma cada
mutação do lote separadamente: um filho consegue resolver um pai aplicado momentos
antes na mesma requisição.

```
item da lista de desejos (offline)
  └─ opção de compra referenciando o clientResourceId do item
       └─ observação de preço referenciando o clientResourceId da opção
```

Um ciclo não se resolve esperando, então vira uma falha permanente explicada em
vez de um lote que se reenvia para sempre.

## Mapeamentos de recurso

Depois de `APPLIED` ou `ALREADY_APPLIED`, o cliente guarda
`clientResourceId → serverId` criptografado. Mapeamentos são owner-bound,
idempotentes e coletados quando nenhuma operação ativa ou histórica precisa mais
deles. Mapeamentos de outro dono nunca são aceitos nem mesclados.

## Replay

Só acontece com **todas** estas condições verdadeiras: aplicativo aberto, cofre
desbloqueado, navegador online, sessão do servidor válida e do mesmo dono, e
nenhum outro replay em andamento.

Gatilhos: "Sincronizar agora", retorno de conectividade, revalidação de sessão do
mesmo dono e retomada de foco. **Não há polling** e nada dispara por render.

Não há replay no Service Worker e não há Background Sync: a chave de criptografia
existe apenas na memória do aplicativo, então nenhum dos dois conseguiria decifrar
a fila.

### Retentativas

Automáticas apenas para falha de rede, 5xx e indisponibilidade temporária, com
backoff exponencial e jitter: mínimo ~2 s, máximo ~5 min, até 8 tentativas antes
de exigir ação do usuário.

**Nunca** automáticas para recusa de validação, recusa por posse, operação não
suportada, conflito de chave de idempotência, conflito de versão ou exclusão
remota. Repetir qualquer uma delas falharia de forma idêntica para sempre.

Um 401 ou sessão expirada pausa o replay e leva ao fluxo de segurança existente —
a fila **não** é descartada.

Quando a rede falha depois do envio, as entradas voltam para "retentável", não
para "falhou": assumir falha seria um palpite, e assumir sucesso esconderia
trabalho real.

## Multi-aba

Um único replay por perfil de navegador, via `navigator.locks` quando disponível,
mais uma guarda em processo. Sem Web Locks, os recibos do servidor absorvem o que
duas abas conseguirem enviar. `BroadcastChannel` transporta apenas contagens e
transições de estado — nunca payload financeiro, nunca id de dono.

Ao receber esse aviso, a segunda aba **relê** o cofre em vez de iniciar o próprio
replay: a fila é compartilhada, então a resposta certa é atualizar a visão, nunca
reenviar as mesmas mutações. A releitura usa a chave que aquela aba já tem em
memória — sem pedir a senha offline de novo — e falha fechada em silêncio: um
registro que a chave não abre deixa a visão como está, em vez de derrubar a
sessão desbloqueada.

## Preparação de dados offline

Além do conjunto já persistido na etapa anterior, a lista de consultas inclui as
categorias do dono nas três chaves que os formulários usam (`all`, `EXPENSE` e
`INCOME`). `useCategories(type)` guarda em cache por tipo, e os formulários que
enfileiram pedem por tipo — cachear só a lista sem tipo deixava os selects vazios
offline e, como categoria é obrigatória, nada podia ser enfileirado.

Continuam fora: conteúdo de arquivos de extrato, coleções de itens de importação,
páginas ilimitadas de transações, histórico ilimitado de faturas e notificações,
cache de mutação, erros de API, valores de CSRF e cookies de sessão.

## Cofre V2, dados V3

`VAULT_SCHEMA_VERSION = 2`, `DATA_SCHEMA_VERSION = 3`.

As duas versões são deliberadamente independentes. O **envelope** descreve como
o registro é cifrado — PBKDF2, contagem de iterações, AES-GCM, formato de salt e
IV, formato do registro no IndexedDB — e nada disso mudou, então ele continua em
V2. Os **dados** descrevem o formato do payload dentro do texto cifrado, e esse
sim mudou: todo recurso em cache agora nomeia a moeda em que seus valores estão.

Por isso a detecção de migração passou a olhar as duas. Ela olhava só o
envelope, e um registro V2 contendo payload V2 era declarado atualizado — os
dados anteriores ao multi-moeda nunca seriam rotulados.

### Migração V1 → V2 → V3

A cadeia é determinística e roda em memória no desbloqueio; um cofre muito
antigo chega ao formato atual sem casos especiais por par de versões.

**V1 → V2.** Um cofre V1 é dado válido — apenas anterior à fila. Dono, timestamp
e queries são preservados, com fila vazia ao lado.

**V2 → V3.** Um cofre anterior ao multi-moeda guarda valores sem moeda. Eles são
BRL: todo valor que o razão continha era, e a `V15` rotulou o lado servidor
exatamente assim sem alterar um único número. O rótulo é a constante `BRL`, e
**não** a moeda base atual do usuário — uma cópia preparada quando a base era BRL
não vira dólar porque a base mudou depois.

A migração é **por formato de query conhecido**, nunca uma varredura recursiva
que carimba `currency` em qualquer objeto com `amount`. Uma varredura rotularia
objetos aninhados que não entende e continuaria fazendo isso, erradamente, à
medida que novos formatos aparecessem.

| Query | Tratamento |
| --- | --- |
| `settings` | recebe `baseCurrency = BRL` quando ausente |
| `accounts`, `transactions`, `credit-cards`, `goals`, `commitments` (lista) | cada linha recebe `currency = BRL` quando ausente |
| `wishlist` (lista, detalhe, histórico) | o item recebe `BRL`; opções e observações **herdam** a moeda do item |
| `notifications` | rotuladas só quando a linha realmente tem `amount` |
| `categories`, `notification-preferences` | intocadas — não há dinheiro nelas |
| `dashboard`, `budgets`, `forecast`, `insights`, `commitments/upcoming` | **descartadas** |

Os derivados são descartados porque não podem ser consertados. Um painel em
cache guarda escalares produzidos somando o que houvesse; um resumo de
orçamento guarda um consumo sem qualquer noção de completude. Nenhum dos dois
vira o formato novo sem inventar as partes que nunca foram registradas. Perder
uma visão em cache custa **preparar a cópia offline de novo, online**;
fabricá-la custaria uma decisão tomada sobre um número errado.

### A fila não é tocada

O `outbox` atravessa a migração byte a byte. O payload de uma mutação
enfileirada é a requisição canônica que o servidor já resumiu num recibo:
acrescentar um campo mudaria esse hash, e um reenvio cuja resposta se perdeu
voltaria como `IDEMPOTENCY_KEY_REUSED`. A moeda de uma mutação antiga é
interpretada como BRL **na leitura** — projeção local e exibição de conflito —
sem nunca ser escrita na requisição.

Preservados sem alteração: `clientMutationId`, `clientResourceId`, `target`,
`baseVersion`, `operation`, `dependencies`, `status`, `attemptCount`,
`nextAttemptAt`, `lastError`, `conflict`, `createdAt`, `updatedAt`, mapeamentos
de recurso e histórico de sincronização.

### Reescrita atômica e falha fechada

A reescrita só acontece depois de decifrar corretamente, com a mesma chave, IV
novo, `createdAt` preservado e `updatedAt` atualizado. Se a escrita falhar, o
registro anterior continua legível e a migração é tentada de novo no próximo
desbloqueio — nada é apagado e nenhuma mutação pendente é perdida.

Um `dataSchemaVersion` **futuro** desconhecido falha fechado: adivinhar um
formato mais novo poderia descartar silenciosamente trabalho que não existe em
nenhum outro lugar. Nenhum metadado de migração aparece em texto claro.

### Fronteira de criptografia

PBKDF2-HMAC-SHA-256 com 310 000 iterações e AES-256-GCM, sem mudanças nos
parâmetros. O desbloqueio agora devolve uma sessão com a chave derivada, porque
enfileirar uma mutação reescreve o cofre e pedir a senha offline a cada registro
não é um produto usável.

- a chave é **não extraível** e vive só em memória (um ref, nunca estado, nunca
  storage);
- a senha continua descartada logo após a derivação;
- o salt é autenticado e versionado; **cada reescrita usa um IV novo**;
- bloquear, sair e o timer de inatividade limpam a chave;
- uma escrita malsucedida preserva o cofre anterior.

## Projeção local

O último retrato do servidor e as operações pendentes ficam separados. A lista que
o usuário vê é a projeção de uma sobre a outra:

```
retrato do servidor + operações locais ordenadas = visão local
```

Como o retrato não é reescrito, descartar uma alteração restaura a visão anterior
exatamente, sem nada para desfazer. Recursos criados localmente aparecem
rotulados; editados mostram os valores pendentes; excluídos continuam visíveis e
marcados — uma linha que some antes de sincronizar é indistinguível de perda de
dados.

Rótulos: `Pendente`, `Sincronizando`, `Conflito`, `Falha`, `Exclusão pendente`,
`Criado offline`. Estado **nunca** é comunicado só por cor.

### Onde a projeção aparece

| Tela | O que é projetado | O que **não** é recalculado |
| --- | --- | --- |
| Transações | linhas criadas, editadas e excluídas | totais do período |
| Orçamentos | limite e existência do orçamento | consumo, percentual e status |
| Metas | nome, alvo, valor atual, data, arquivamento, restante e percentual | sugestão de aporte mensal |
| Lista de desejos | itens criados e editados, contagem de opções na fila | melhor custo, preço observado, mínimo histórico |
| Item da lista de desejos | opções criadas, editadas e excluídas | análise de compra |
| Histórico de preços | observações criadas offline | série, KPIs e gráfico |

Orçamento e lista de desejos não recalculam suas figuras derivadas de propósito:
elas dependem de registros que podem estar na própria fila, e uma barra saudável
sobre uma categoria já estourada seria pior do que dizer que o número ainda não
chegou. Metas são a exceção — restante e percentual saem dos dois valores que a
pessoa acabou de digitar, e não de nenhum outro registro financeiro.

Um item criado offline recebe um id local negativo e **tem página de detalhe**:
é ali que opções de compra e observações de preço são cadastradas, e as duas
precisam poder nomear um pai que ainda não tem id no servidor. Nessa página, a
análise de compra, a captura de preço da opção e a execução da compra ficam
desabilitadas com o motivo explicado, porque todas dependem de estado que só o
servidor tem.

### Limitação deliberada dos totais derivados

Dashboard, saldos, previsão, insights e totais entre domínios continuam baseados
no último retrato do servidor. Enquanto houver pendências, as telas mostram:

> Alguns totais ainda não incluem alterações offline pendentes.

Recalcular isso no navegador significaria manter um segundo motor financeiro, e um
motor sutilmente diferente do real é pior do que um número honestamente
desatualizado.

## Central de sincronização

`/offline-sync` mostra contagens (pendentes, bloqueadas, conflitos, falhas),
estado de conexão e do cofre, última sincronização, ação manual e a lista de
operações com recurso, tipo, horário, tentativas, próxima tentativa, último erro
seguro e estado de dependência, além de tentar de novo, editar, resolver e
descartar. Nunca exibe payload cru, JSON interno ou id de dono.

Um indicador compacto aparece no shell **apenas quando há algo a dizer**, com
contagem limitada a `99+` e o motivo no nome acessível.

### Quais telas aceitam ação offline

O shell decide por rota. Em `/transactions`, `/budgets`, `/goals`, `/offline-sync`
e `/wishlist` (inclusive a página de um item) tudo fica habilitado **exceto** os
controles marcados como online-only, que ficam desabilitados com o motivo no
`title`. Nas demais rotas preparadas, a página inteira continua somente leitura,
para que um fluxo que nunca foi desenhado para replay não comece por acidente.

Essa decisão existe em um lugar só. A recusa do cliente de API a qualquer
requisição insegura continua sendo a fronteira real; o shell apenas explica antes
que a pessoa tente.

## Saída, bloqueio e remoção do cofre

Bloquear preserva fila, mapeamentos e conflitos criptografados, para o replay
imediatamente e limpa a chave e os dados decifrados da memória.

Sair da conta apaga a cópia local — inclusive trabalho que o servidor nunca viu.
Toda ação capaz de apagar o registro criptografado passa pelo mesmo diálogo:
sair da conta, desativar o acesso offline e descartar uma cópia ilegível pela
tela de desbloqueio.

### O que o aplicativo sabe antes de apagar

A chave de decifragem só existe em memória, então o estado normal depois de
qualquer recarregamento é um cofre que existe, guarda texto cifrado e não pode
ser aberto. Nesse estado a fila decifrada está vazia porque não há com o que
decifrá-la — não porque ela esteja vazia. Tratar as duas coisas como iguais é o
que permitia apagar semanas de trabalho offline atrás de um "Sair" genérico.

O risco é derivado do estado que o provedor já tem, sem ler o armazenamento e sem
decifrar nada:

| Estado do cofre | Risco | Comportamento |
| --- | --- | --- |
| ausente | `NO_LOCAL_COPY` | sai normalmente; não há cópia local |
| desbloqueado, fila vazia | `KNOWN_SAFE` | sai normalmente; desativar o acesso offline pede a confirmação comum, sem afirmar pendências |
| desbloqueado, com pendências | `KNOWN_PENDING` | aviso preciso, com as contagens reais |
| bloqueado | `UNKNOWN_LOCKED` | aviso conservador de incerteza |
| ilegível | `UNKNOWN_CORRUPTED` | aviso conservador, explicando que o conteúdo não pôde ser verificado |
| carregando ou desbloqueando | `BUSY` | ação destrutiva desabilitada, com aviso acessível de progresso |

**Não existe marcador em texto claro.** Nenhuma contagem, nenhum booleano,
nenhum identificador de mutação, tipo de recurso, dono, carimbo de tempo ou
status sai de dentro do texto cifrado autenticado — fechar essa lacuna com um
marcador legível teria movido a existência de trabalho pendente para fora da
fronteira de criptografia. O formato do cofre continua o V2; não houve V3.

**Um cofre bloqueado e vazio avisa à toa, e isso é a política.** O falso positivo
custa um diálogo; o contrário custa alterações que não existem em nenhum outro
lugar.

### Os dois passos

O primeiro aviso nunca apaga nada. Ele oferece cancelar, `Desbloquear e
verificar` — que leva à central de sincronização e ao formulário de desbloqueio
que já existe lá, sem criar um terceiro formulário de senha — e a ação
destrutiva, que apenas abre a segunda confirmação. Só a segunda, que diz
`Essa ação não pode ser desfeita.`, está ligada à exclusão.

Cancelar, desbloquear e verificar, e o primeiro passo destrutivo preservam o
registro criptografado intacto.

Se o usuário confirmar, a limpeza local acontece mesmo que o logout no servidor
falhe: uma saída que a pessoa pediu não pode deixar dados decifrados no
dispositivo porque a rede caiu. Se a remoção do IndexedDB falhar, o diálogo diz
isso e permanece aberto — o aplicativo nunca navega para o login relatando uma
exclusão que não aconteceu.

Em todos os casos a interface deixa explícito que **os dados já enviados ao
servidor não são apagados**.

### Cofre ilegível

Um cofre que não decifra recebe o mesmo fluxo, com a diferença de que o texto
explica que o conteúdo não pôde ser verificado — sem afirmar que há pendências.
Desbloquear com sucesso não é exigido para apagar: quem perdeu a senha ainda
precisa de um caminho de recuperação. Ele só é explícito e confirmado duas vezes.
A central de sincronização também aceita nova tentativa de senha nesse estado,
porque a causa mais comum de um cofre ilegível é uma senha digitada errada.

Perder a senha offline ou corromper o cofre significa perder as alterações
pendentes. A interface diz isso, e não sugere que elas existem no servidor.

## Garantias de segurança

- sessão do servidor é a autoridade; o dono vem sempre dela;
- nenhum `userId` vem do cliente; CSRF continua obrigatório;
- `/api` e `/api/**` continuam `NetworkOnly`;
- o Service Worker nunca recebe a fila decifrada; Cache Storage e localStorage não
  contêm dado de mutação;
- o IndexedDB guarda apenas texto cifrado;
- consultas de recibo são owner-scoped por construção — não existe busca só por
  `clientMutationId`;
- recibos são imutáveis; a mesma chave por dois donos é segura;
- id de servidor ou de cliente de outro dono se comporta como ausente, de forma
  indistinguível de inexistente — a diferença transformaria o endpoint em um
  oráculo de existência sobre as finanças alheias;
- rotas, tipos de recurso e operações arbitrárias não podem ser enfileirados;
- registros financeiros gerados não podem ser mutados pelo endpoint;
- não há despacho por reflexão nem desserialização Java insegura;
- payloads completos não são logados em produção; apenas identificadores seguros e
  códigos de resultado.

## Limites de desempenho

Lotes limitados, ordenação estável, compactação local, invalidação seletiva de
query, linhas colapsadas, formulários de conflito renderizados sob demanda,
índices owner-scoped, uma transação por mutação, retentativa limitada e mensagens
de broadcast mínimas.

Custo real da etapa no bundle de produção, medido com `vite build` antes
(`e3488a4`) e depois:

| Artefato | Antes | Depois | Diferença |
| --- | --- | --- | --- |
| `index.js` | 267,56 kB (gzip 85,72 kB) | 285,80 kB (gzip 91,14 kB) | +18,24 kB (+5,42 kB gzip) |
| `index.css` | 16,26 kB (gzip 4,46 kB) | 16,83 kB (gzip 4,55 kB) | +0,57 kB (+0,09 kB gzip) |
| Precache | 80 entradas · 1010,02 KiB | 80 entradas · 1057,82 KiB | +47,80 KiB |

A central de sincronização é carregada sob demanda como rota; o que entrou no
chunk principal é a fila, o motor de replay e a projeção, que qualquer tela com
pendências precisa.

## Limitações conhecidas

- a sincronização acontece somente com o aplicativo aberto e o cofre desbloqueado;
- não há replay em Service Worker nem Background Sync;
- totais derivados não incluem pendências (avisado na interface);
- observações de preço offline são somente histórico;
- não há mesclagem automática de conflitos, por decisão;
- a cópia local pode ser removida pelo navegador ou pelo sistema operacional;
- com o cofre bloqueado o aplicativo não sabe se há pendências e avisa como se
  houvesse; um cofre bloqueado e vazio mostra o aviso à toa, por decisão;
- se a exclusão local falhar depois de um logout confirmado, a sessão do servidor
  já terminou e o registro criptografado permanece no dispositivo até que a
  exclusão seja repetida. Outro dono não consegue lê-lo, mas também não consegue
  criar a própria cópia offline nesse perfil de navegador antes de removê-lo.
