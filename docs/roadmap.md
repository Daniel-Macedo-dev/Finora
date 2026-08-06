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

Ainda em aberto nesta etapa: previsão por moeda (hoje o painel esconde o caixa
futuro quando existe qualquer moeda estrangeira, em vez de mostrar um saldo
misto), supressão de insights agregados, análise de compra com
`EXCHANGE_RATE_REQUIRED`, importação CSV/OFX com CURDEF e reconhecimento
explícito, moeda nas notificações, migração dos dados criptografados para o
esquema de dados V3 com proteção da fila offline e da troca de moeda base entre
dispositivos, interface completa de moeda (seletores, formulários, tela de moeda
base), jornadas E2E de multi-moeda, matriz de QA visual e as revisões.

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

Verificação verde: backend `test` e `verify` (377 testes), lint, typecheck,
216 testes unitários, build e verificação de PWA no frontend, `scripts/verify.ps1`,
a suíte focada `offline-sync.spec.ts` (35 de 35) e a suíte E2E completa
(123 aprovados, 10 pulados — as suítes visuais, que exigem `VISUAL_QA=1` — e
nenhuma falha).

## Próxima grande etapa

**Razão histórico de câmbio, conversão determinística e analytics em moeda base.**
Só começa depois que o núcleo multi-moeda estiver concluído.

## Depois disso

- Relatórios anuais e exportação.

## Fora de cobertura deliberadamente

- Scraping de e-commerce ou automação de navegador para coletar preços.
- "IA financeira" — as recomendações continuarão determinísticas e explicáveis.
- Microserviços, filas ou infraestrutura distribuída sem necessidade medida.
