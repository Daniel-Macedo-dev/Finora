# Roadmap

O que **não** está na primeira release, em ordem aproximada de valor. Nada aqui é
promessa de implementação — é direção.

> Autenticação e multiusuário foram **concluídos** — identidade, sessões
> server-side, posse por usuário e isolamento completo. Ver
> [security.md](security.md).
>
> Cartões de crédito e ciclo de fatura foram **concluídos** — cartões com limite,
> faturas determinísticas, parcelamento exato em centavos, pagamentos com estorno
> e integração com orçamentos/dashboard/análise. Ver
> [credit-cards.md](credit-cards.md).
>
> Automação de recorrentes e previsão de caixa foram **concluídas** — definições
> semanais/mensais/anuais com destino em conta ou cartão, ocorrências com
> identidade estável e ciclo de vida completo (executar, retry, pular,
> reagendar, estornar, pausar, encerrar), materialização idempotente com
> catch-up, previsão determinística com caixa de fatura no vencimento e eventos
> prontos para notificação. Ver [recurring-automation.md](recurring-automation.md)
> e [forecast.md](forecast.md).
>
> Conversão assistida de crédito legado foi **concluída** — inventário com
> resumo e filtros, assistente com pré-visualização determinística (parcelas
> exatas, faturas, limite e redistribuição mensal), motor atômico idempotente
> sem contagem dupla, estorno auditável com guarda de liquidação, lote
> independente por item e migração de recorrentes legados sem retroativos. Ver
> [legacy-credit-conversion.md](legacy-credit-conversion.md).
>
> Importação de extratos foi **concluída** — upload de CSV/OFX com
> pré-visualização determinística, parser OFX seguro sem XML/XXE, mapeamento
> de colunas assistido para formatos brasileiros, deduplicação em três níveis
> (arquivo, identidade forte, conteúdo), categorização por regras
> determinísticas, confirmação idempotente por item e desfazer auditável. Ver
> [statement-import.md](statement-import.md).
>
> Entrega de notificações foi **concluída** — caixa persistente owner-scoped,
> revisões de ciclo de vida, preferências, sincronização automática/manual e
> alertas foreground do navegador com privacidade segura por padrão. Ver
> [notifications.md](notifications.md).
>
> Histórico de preços dos itens de desejo foi **concluído** — snapshots manuais
> owner-scoped, captura de opção, idempotência, correção, resumo, alvo, gráfico e
> histórico paginado, sem coleta externa. Ver
> [wishlist-price-history.md](wishlist-price-history.md).
>
> PWA, instalabilidade e modo offline seguro foram **concluídos** — shell
> instalável, Service Worker sem cache de API e cofre IndexedDB criptografado e
> owner-isolated. Ver [pwa-offline.md](pwa-offline.md).

> Fila de mutações offline, idempotência e resolução de conflitos foi
> **concluída** para os domínios que ela sustenta deliberadamente — CRUD offline
> de transações comuns, orçamentos, metas, itens da lista de desejos, opções de
> compra e observações manuais de preço, com fila criptografada, compactação,
> ordenação por dependência, recibos duráveis owner-scoped, versões otimistas,
> conflitos tipados resolvidos pelo usuário e central de sincronização. Fluxos
> com auditoria própria — extratos, cartões, faturas, recorrentes, aportes,
> execução de compra e captura de preço — continuam exigindo conexão **por
> decisão**, não por omissão. Ver [offline-sync.md](offline-sync.md).

## Etapa em andamento

**Multi-moeda — núcleo nativo.** Em andamento; **não concluída**. Ver
[multi-currency-core.md](multi-currency-core.md).

Pronto e validado: catálogo fechado de oito moedas com regra de casas decimais
(JPY sem centavos); `V15__multi_currency_core.sql`, que rotulou todo o razão
existente como BRL **sem alterar um único valor numérico**; moeda base por
usuário com bloqueio de troca depois que existem dados; moeda imutável e
integridade de mesma moeda em contas, lançamentos, cartões, faturas, pagamentos
de fatura, compromissos, metas, itens da lista, opções e execução de compra;
`CurrencyTotals`, o total que se recusa a somar moedas diferentes; compatibilidade
da impressão digital das mutações offline anteriores ao multi-moeda; e o sistema
de formatação por moeda no front.

Concluído nesta fase: o contrato de totais passou a separar **duas perguntas**
que antes compartilhavam uma única flag `complete` — se um conjunto é homogêneo
(e portanto tem um total nativo somável) e se ele está inteiramente na moeda base
(e portanto admite uma análise em moeda base). Um razão inteiramente em USD tem um
total real em USD; ele não é uma resposta em BRL. Sobre isso vieram: saldos de
conta agrupados por moeda em `GET /accounts/overview`, com duas consultas
agrupadas no lugar de duas por conta; o painel inteiro sem aritmética mista —
saldos, receitas, despesas, resultado, mês anterior, dívida e limite de cartão e
despesa reconhecida de cartão agora são totais agrupados, a taxa de poupança e a
variação mensal ficam **indisponíveis** quando qualquer operando não está completo
em moeda base, as participações por categoria passam a ser por categoria e moeda,
e a tendência vira uma série homogênea por moeda; e orçamentos com estado
`INCOMPLETE`, que nunca aparecem como saudáveis quando a categoria tem gastos em
outra moeda — o restante e a porcentagem ficam nulos em vez de subestimados.

Também concluído: o **esquema de dados criptografados V3**. O envelope continua
em `VAULT_SCHEMA_VERSION = 2` — nada de PBKDF2, AES-GCM, salt, IV ou formato do
registro mudou — e só o payload dentro do texto cifrado avançou para
`DATA_SCHEMA_VERSION = 3`. A detecção de migração passou a considerar as duas
versões, porque olhando só o envelope um registro V2 com payload V2 era
declarado atualizado. A cadeia V1 → V2 → V3 roda em memória no desbloqueio,
rotula cada formato de query conhecido como BRL (nunca como a moeda base atual),
descarta os derivados que não podem ser consertados e **não toca na fila**: o
payload canônico de uma mutação enfileirada é o que o servidor já resumiu num
recibo. Versão futura falha fechado.

Também concluído: a **previsão por moeda**. O serviço roda uma corrida
independente por denominação numa única passagem ordenada — saldo de abertura,
série, menor saldo, primeira data negativa e resumo mensal separados — e cada
evento carrega a moeda derivada do seu recurso de origem (lançamento,
compromisso ou cartão), nunca da requisição. Uma previsão filtrada por conta é
homogênea por construção e mantém todos os escalares; uma previsão mista deixa
todos eles nulos em vez de enviar um número misto. Um saldo negativo em USD não
marca mais o saldo em BRL. Com isso o painel deixou de esconder o caixa futuro e
passou a mostrar um saldo projetado por moeda.

Também concluído: o **contexto financeiro de compra ciente de moeda** e a
**disponibilidade tipada da análise de compra**. Um contexto paralelo
(`PurchaseFinancialContext`) agrupa caixa, médias históricas, compromissos e
obrigações de cartão pela moeda real de cada valor, com denominadores de média
independentes por moeda e cobertura de moeda base por dimensão. A análise passou
a decidir elegibilidade **antes** de qualquer subtração ou razão: um item
estrangeiro, ou um contexto incompleto em moeda base, devolve
`EXCHANGE_RATE_REQUIRED` — HTTP 200, sem premissas, sem opções e sem `BUY` ou
`WAIT`. Um usuário sem histórico nenhum continua recebendo a análise de caixa de
sempre; um cujo histórico existe só em outra moeda não, porque isso não é
ausência. `FinancialContext` continua existindo apenas para `InsightService`,
cuja migração é a próxima tarefa.

**Segurança de moeda e disponibilidade da análise de compra — concluída.** O QA
que faltava fechou a fatia: 11 jornadas Playwright dedicadas
(`purchase-analysis-multi-currency.spec.ts`), a matriz visual de 16 capturas
(dois estados × quatro viewports × dois temas, todas inspecionadas) e as sete
revisões. A auditoria confirmou cada afirmação publicada contra o código, e as
revisões acharam três defeitos reais, todos corrigidos:

- as mensagens em pt-BR da análise disponível eram formatadas com o helper BRL
  obsoleto, então um usuário com moeda base em dólares ou ienes leria os próprios
  valores com `R$` — um valor errado, não um símbolo feio;
- o construtor canônico de `AnalysisResponse` aceitava as combinações que a
  documentação dizia serem impossíveis; agora as recusa;
- o estado indisponível se anunciava como parágrafo, não como cabeçalho, ficando
  fora do sumário da página para quem navega por títulos.

Também entrou a cobertura que faltava: compromissos e obrigações de cartão
agrupados por moeda nunca tinham sido testados em nenhuma das duas direções, e o
teste de disponibilidade enviava uma `referenceDate` que o endpoint ignora — os
cenários estavam presos a meses fixos de 2026 contra o relógio real e teriam
passado pelo motivo errado antes de falhar por data.

Ainda em aberto nesta etapa: importação CSV/OFX com CURDEF e reconhecimento
explícito, moeda nas notificações, proteção da troca de moeda base entre
dispositivos no replay do servidor, guarda local da moeda base nas configurações,
interface completa de moeda (seletores, formulários, tela de moeda base) — o QA
visual mostrou o efeito concreto disso: na página de um item em USD os preços
fora do painel de análise ainda saem com `R$` — e o E2E e o QA visual de
fechamento da etapa multi-moeda inteira.

O fechamento da fila offline resolveu as duas pendências que a mantinham aberta.

**Ações destrutivas com o cofre bloqueado.** Sair da conta com o cofre bloqueado
apagava a cópia local sem aviso, porque a fila é ilegível enquanto o cofre está
fechado — e, como a chave só vive em memória, qualquer recarregamento leva a esse
estado. A saída anotada aqui antes era um marcador em texto claro no registro
criptografado; ela foi recusada, porque moveria a existência de trabalho pendente
para fora da fronteira de criptografia. O que entrou no lugar é uma regra
conservadora derivada em memória: um cofre que existe mas está bloqueado ou
ilegível é tratado como se pudesse conter alterações não sincronizadas, com duas
confirmações explícitas antes de qualquer exclusão. O formato do cofre continua o
V2, não há contagem nem booleano legível fora do texto cifrado, e um cofre
bloqueado e vazio avisa à toa — falso positivo aceito de propósito. As três
telas capazes de apagar o registro passam pelo mesmo diálogo.

**QA visual.** A suíte de captura foi dividida em quatro grupos por tema, cada um
com sua própria conta e contexto, rerodáveis isoladamente. Os treze estados
exigidos (mais o descarte de uma linha da fila) foram capturados nos quatro
viewports e nos dois temas: **112 capturas, todas inspecionadas**. A inspeção
encontrou e corrigiu seis defeitos reais — rolagem horizontal de 39px na lista de
transações a 390px, contraste do botão destrutivo abaixo de AA no tema escuro,
o sino de notificações caindo sobre o título da página, os controles fixos da
shell cobrindo as ações do cabeçalho e o botão do banner de conexão, e a
comparação de conflito empurrando a coluna da alteração offline para fora da tela
a 390px.

Verificação verde no fechamento desta etapa: backend `test` e `verify`
(**591 testes**, nenhuma falha), lint, typecheck, **292 testes unitários**, build
e verificação de PWA no frontend, `scripts/verify.ps1`, as suítes de regressão
rodadas separadamente (insights, análise de compra, dashboard, orçamentos,
cartões, lista de desejos, previsão e isolamento), as suítes focadas
`insights-multi-currency.spec.ts` (14 de 14) e
`purchase-analysis-multi-currency.spec.ts` (11 de 11), e a suíte E2E completa
(**148 aprovados, 20 pulados** — as suítes visuais, que exigem `VISUAL_QA=1` — e
nenhuma falha).

**Insights cientes de cobertura e remoção do contexto escalar — concluído.**
Os insights eram o último consumidor do `FinancialContext` currency-blind, e
cada regra agregada ali lia números produzidos somando denominações: despesas em
dólar dobradas numa única porcentagem de crescimento contra reais, uma categoria
em reais declarada dominante sobre gastos que o denominador não enxergava,
compromissos em dólar divididos por renda em reais, uma meta em dólar medida
contra a sobra em reais, e um item em dólar declarado viável a partir do caixa em
reais depois de subtrair a reserva de um valor que nunca foi de uma moeda só.
Valores nativos de cartão e fatura saíam com símbolo de real qualquer que fosse o
cartão.

As regras nativas — fatura vencida, fatura a vencer, limite comprometido —
continuam sempre visíveis e passaram a ser enunciadas na moeda do cartão. As
agregadas só rodam com operandos provadamente completos em moeda base; quando não
estão, o Finora se cala e diz por quê, uma vez, em `aggregateCoverage`. Esse
silêncio é distinguível do comum: sem mês anterior, sem histórico, sem meta ou
sem item candidato, nada é reportado, porque uma conta vazia não pode parecer
quebrada. Todo valor monetário carrega agora a moeda em que está, garantido pelo
construtor. Ver [insights.md](insights.md).

Com isso o contexto escalar perdeu o último consumidor e foi **removido**, não
depreciado. A implementação ciente de moeda foi promovida a contexto financeiro
canônico em `com.finora.api.financialcontext`, renomeada em vez de copiada, e a
análise de compra e os insights leem a mesma. Sobrou uma implementação.

**Próxima tarefa desta etapa:** moeda na importação de extratos e CURDEF do OFX,
com moeda nas notificações logo em seguida. Os dois tocam superfícies diferentes
— parser e caixa de entrada — e acoplá-los num commit só não traz ganho.

## Próxima grande etapa

**Razão histórico de câmbio, conversão determinística e analytics em moeda base.**
Só começa depois que o núcleo multi-moeda estiver concluído.

## Depois disso

- Relatórios anuais e exportação.

## Fora de cobertura deliberadamente

- Scraping de e-commerce ou automação de navegador para coletar preços.
- "IA financeira" — as recomendações continuarão determinísticas e explicáveis.
- Microserviços, filas ou infraestrutura distribuída sem necessidade medida.
