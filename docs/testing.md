# Estratégia de testes

## Camadas

| Camada | Ferramentas | O que protege |
| --- | --- | --- |
| Backend integração | JUnit 5 + MockMvc + Testcontainers (PostgreSQL real) | contratos HTTP, validação, regras de negócio, migrações Flyway |
| Backend unidade | JUnit 5 puro | matemática de datas (`occurrenceIn`, ciclo de fatura), distribuição de parcelas em centavos, derivação de status de fatura e valor presente |
| Frontend unidade | Vitest + Testing Library (jsdom) | formatação pt-BR, normalização de erros da API, aritmética de meses, componentes (labels/erros/interação) |
| E2E | Playwright (Chromium) | fluxos reais UI → API → PostgreSQL, incluindo análise de compra e navegação mobile |

## Backend (`apps/api`)

```bash
./mvnw test      # Windows: .\mvnw.cmd test
```

- `AbstractIntegrationTest` sobe o contexto completo com um PostgreSQL efêmero
  (Testcontainers, `@ServiceConnection`) e roda cada teste em transação com
  rollback — nenhum estado vaza entre testes. **Docker é obrigatório.** Cada teste
  registra usuários reais pelo endpoint público e reusa o cookie de sessão emitido;
  o helper `csrf()` usa o fluxo real de double-submit (cookie + header), não o
  post-processor do spring-security-test.

### Identidade, sessão e posse

- **Autenticação** (`AuthFlowTest`): registro cria usuário + categorias padrão +
  settings atomicamente e estabelece sessão; senha guardada como hash BCrypt;
  normalização de e-mail e rejeição de duplicata case-insensitive; login válido,
  falhas genéricas (senha errada e e-mail inexistente produzem corpos idênticos —
  sem enumeração); logout invalida a sessão; CSRF ausente/ inválido é rejeitado e o
  bootstrap funciona; troca de senha invalida as outras sessões mantendo a atual.
- **Persistência de sessão** (`AuthFlowTest.sessionsArePersistedInTheJdbcStore`):
  verifica a linha em `SPRING_SESSION` indexada pelo principal — prova o store JDBC,
  não sessões em memória.
- **Transacionalidade do registro** (`RegistrationTransactionalityTest`): forçando a
  criação de settings a falhar, nenhum usuário ou categoria permanece (rollback
  atômico); roda fora da transação de rollback do teste para provar o commit real.
- **Ataques por ID direto** (`OwnershipAttackTest`): usuário B tentando ler, listar,
  editar, excluir, referenciar como FK, contribuir em meta, gerenciar opção de
  compra ou analisar recursos de A — tudo resolve para 404 e não altera os dados de
  A; `?userId=` é ignorado; desativação de categoria é isolada por usuário.
- **Isolamento de agregados**: dashboards de dois perfis opostos permanecem
  independentes (`DashboardApiIntegrationTest`); consumo de orçamento não vaza
  (`BudgetIsolationTest`); contexto financeiro e recomendação de compra ignoram os
  dados de outro usuário (`FinancialContextServiceTest`,
  `PurchaseAnalysisEngineTest.anotherUsersFinancesCannotChangeTheRecommendation`);
  insight de outro usuário não aparece para mim.
- **Migração e claim legado**: `LegacyDataMigrationTest` roda a V4 (via Flyway
  programático em containers) contra um banco **com dados v1** — todas as linhas
  sobrevivem sob o dono pendente — e contra um banco limpo — sem usuário legado nem
  seeds globais. `LegacyClaimFlowTest` prova que o dono pendente não autentica, que
  o claim com token válido transfere a identidade e mantém os dados uma única vez,
  que token errado não transfere nada, e que registro comum não herda os dados.
### Cartões de crédito

- **Ciclo de fatura** (`InvoiceCycleCalculatorTest`): compra antes/no dia/depois
  do fechamento; fechamento antes do vencimento e no mês anterior; dia 31 em
  fevereiro, ano bissexto e meses de 30 dias; virada dezembro→janeiro; e duas
  propriedades varridas (fechamentos crescem estritamente com o mês; toda compra
  cai numa fatura cujo fechamento a inclui).
- **Parcelas** (`InstallmentAllocatorTest`): soma sempre exata, resto de centavos
  nas últimas parcelas, R$ 0,01, valores que não dividem, normalização sub-centavo.
- **Status derivado** (`InvoiceStatusDerivationTest`): os seis estados e a
  precedência PAID → OVERDUE → PARTIALLY_PAID → CLOSED → OPEN → UPCOMING.
- **Compras** (`CardPurchaseApiIntegrationTest`): fatura correta, parcelas
  consecutivas, limite consumido/liberado, cancelamento, edição com regeneração,
  categoria de outro dono, cartão arquivado — com datas fixas no futuro para o
  status nunca virar com o calendário real.
- **Concorrência** (`PurchaseLimitConcurrencyTest`): duas compras simultâneas não
  estouram o limite (lock pessimista no cartão).
- **Pagamentos** (`InvoicePaymentApiIntegrationTest`): compra sozinha não toca a
  conta; pagamento total/parcial reduz o saldo exatamente uma vez e restaura
  limite; overpayment rejeitado; estorno devolve tudo uma vez e não repete;
  ajustes de débito/crédito; fatura vencida; dois usuários isolados.
- **Contabilidade** (`CardAccountingIntegrationTest`): parcelas consomem orçamento
  nos meses das faturas exatamente uma vez; cancelamento remove o consumo;
  dashboard conta parcela uma vez e pagamento nunca; `CREDIT` genérico rejeitado.
- **Migração** (`MigrationFromPopulatedV5Test`): V5 populado → mais recente
  preservando dados e marcando crédito legado; constraint de fatura única; FKs
  cross-owner rejeitadas no banco.
- **Wishlist → compra real** (`WishlistPurchaseExecutionTest`): execução à vista
  e parcelada, retry não duplica, opção/cartão de outro dono inacessíveis.

### Conversão de crédito legado

- **Ciclo de vida pela API** (`LegacyConversionApiIntegrationTest`): inventário
  com resumo e filtros; elegibilidade estruturada (fonte incompatível, gerada
  por recorrente, id alheio como 404); preview determinístico (cronograma,
  centavos exatos, limite, redistribuição mensal, primeira fatura divergente,
  limite insuficiente, data futura); conversão move a despesa sem nunca dobrar
  (dashboard e saldo); falha não deixa rastro; cartão arquivado/alheio;
  estorno restaura exatamente uma vez e reconversão cria registros novos;
  pagamento concluído bloqueia estorno (`CONVERSION_SETTLED`); cancelamento
  direto da compra gerada bloqueado; lote independente e idempotente com
  limite de 50.
- **Posse** (`LegacyConversionOwnershipTest`): inventário nunca vaza fontes de
  outro usuário (mesmo com filtros que casariam); preview/conversão/detalhe/
  estorno de recursos alheios respondem 404 e deixam a vítima intocada.
- **Concorrência real** (`LegacyConversionConcurrencyTest`): threads contra o
  PostgreSQL de verdade — conversões simultâneas produzem exatamente uma
  conversão e uma compra; estornos simultâneos elegem um vencedor (200 + 422)
  e restauram a origem uma vez; conversão × estorno termina num único estado
  consistente (invariantes: no máximo uma conversão ativa, origem ativa ⇔ sem
  conversão ativa, nenhuma compra/parcela órfã); lotes idênticos concorrentes
  nunca duplicam.
- **Contabilidade** (`LegacyConversionAccountingTest`): orçamentos deslocam o
  consumo do mês de origem para os meses das faturas e voltam no estorno —
  sempre uma vez; a previsão troca o efeito de caixa da origem por uma única
  saída de fatura (`CARD_INVOICE`) com o mesmo fecho.
- **Mapeamento de recorrente** (`LegacyCardMappingIntegrationTest`): definição
  legada ganha alvo real sem retroativos (process-due materializa zero
  ocorrências históricas); não-legado e cartão arquivado rejeitados;
  recursos alheios como 404.
- **Migração** (`MigrationFromPopulatedV9Test`): V9 populado → V10 preservando
  linhas, crédito legado financeiramente ativo e constraints da conversão.

### Importação de extratos

- **Parsers** (`CsvStatementParserTest`, `OfxStatementParserTest`): UTF-8 com e
  sem BOM, Windows-1252, vírgula e ponto e vírgula, aspas e aspas escapadas,
  CRLF/LF, cabeçalho presente/ausente, decimal com vírgula e com ponto, colunas
  separadas de débito/crédito, linhas em branco, colunas obrigatórias
  ausentes, aspas malformadas, linha e valor acima do limite, excesso de
  linhas, valor zero, data/valor inválidos; OFX 1.x (SGML) e 2.x (XML),
  múltiplos `STMTTRN`, `FITID` presente e ausente, fallback `NAME`/`MEMO`,
  sufixo de fuso não move a data, aninhamento malformado, tipo de conta não
  suportado, fatura de cartão detectada e bloqueada, `<!DOCTYPE`/`<!ENTITY`
  rejeitados, limites de entrada/campo — todos com fixtures sintéticas, nunca
  dados reais.
- **Ciclo de vida pela API** (`StatementImportApiIntegrationTest`): upload CSV
  aguardando mapeamento e OFX direto para pré-visualização; mapeamento
  contraditório rejeitado; prévia e parse autoritativo; edição de item antes
  da confirmação; troca de conta de destino reclassificando duplicatas;
  confirmação com resultado por item; retentativa após correção; desfazer
  item e lote; fatura de cartão bloqueada com mensagem apontando para Cartões.
- **Posse** (`StatementImportOwnershipTest`): lote, item e regra de outro
  usuário respondem 404; contagem única de saldo/orçamento/categoria mesmo
  com múltiplos usuários importando extratos parecidos.
- **Concorrência real** (`StatementImportConcurrencyTest`): confirmações
  simultâneas do mesmo item produzem uma única transação; reenvio do mesmo
  arquivo e uploads concorrentes permanecem consistentes — threads contra o
  PostgreSQL de verdade.
- **Contabilidade** (`StatementImportAccountingTest`): saldo de conta,
  dashboard, orçamento e categoria contam a transação importada exatamente
  uma vez; itens excluídos, com falha ou pulados por duplicidade nunca
  contam; desfazer remove o efeito uma única vez.
- **Migração** (`MigrationFromPopulatedV10Test`): banco V10 populado (com
  conversões de crédito legado) → V11 preservando todos os dados; tabelas
  novas nascem vazias; nenhuma transação antiga nasce marcada como
  importada; constraints e índices únicos parciais válidos.

### Recorrentes e previsão

- **Calculadora** (`RecurrenceCalculatorTest`): sequência semanal ancorada;
  dia 31 mensal em fevereiro (bissexto e não bissexto) voltando a 31 em março;
  29/02 anual; fronteiras inclusivas de início/fim; pausado sem ocorrências;
  nenhuma ocorrência antes do início.
- **Materialização em conta** (`RecurringMaterializationTest`): receita e
  despesa com valor/categoria/conta exatos e rastreabilidade; repetição não
  duplica; conta arquivada e destino somente-planejamento falham com erro de
  negócio; retry após falha; estorno restaura o saldo exatamente uma vez e é
  terminal; edição da definição não reescreve histórico; isolamento entre
  usuários em toda a família de mutações.
- **Materialização no cartão** (`RecurringCardMaterializationTest`): compra
  real com parcelas pelo alocador existente na fatura do ciclo correto; limite
  insuficiente falha sem artefato parcial (sem compra órfã, sem parcela, sem
  mutação de fatura) e permanece re-executável; estorno usa o cancelamento do
  domínio de cartões.
- **Processamento** (`RecurringProcessingTest`): process-due materializa tudo
  que venceu (catch-up desde o início), segunda execução não duplica; pausa
  interrompe; ocorrência pulada não regenera; **concorrência real** — dois
  processadores com `CountDownLatch` sobre o mesmo banco não criam dois
  artefatos (constraint de identidade + lock).
- **Forecast** (`ForecastApiIntegrationTest`): saldo de abertura; transações
  futuras reais; recorrentes projetados; fatura corrente e futura no
  vencimento; compra recorrente projetada no ciclo real; pagamento parcial
  reduz a projeção e estorno a restaura; ocorrência materializada substitui a
  projeção; pulada/estornada excluídas; fluxos sem conta separados; primeira
  data negativa; filtro por conta; validação do horizonte máximo.
- **Eventos** (`DueEventApiIntegrationTest`): vencido/vence hoje/em breve,
  falha de execução, caixa insuficiente projetado; validação de range e
  isolamento entre usuários.
- **Migração** (`MigrationFromPopulatedV8Test`): banco V8 populado → V9
  preservando todos os dados; legados migram `MANUAL`/`PROJECTION_ONLY` (nunca
  auto-executam); constraints e FKs de posse do V9 válidas.

- `PurchaseAnalysisEngineTest` fixa a data de referência (2026-07-15) e cobre os
  cenários críticos: à vista mais barato e seguro; à vista violando a reserva;
  parcelado sem juros vencendo à vista com taxa positiva; parcela acima da sobra;
  parcela estourando o teto da renda; `WAIT` com estimativa de meses; ausência de
  histórico; empate preferindo à vista; frete invertendo a opção preferida.
- `PresentValueTest` valida o PV puro, inclusive taxa zero e arredondamento.
- Fronteiras cobertas de propósito: dia 31 em meses curtos e ano bissexto
  (`RecurrenceCalculatorTest`), tolerância exata de reconciliação de parcelas
  (`OptionReconciliationBoundaryTest`), médias com histórico parcial e exclusão
  do mês corrente (`FinancialContextServiceTest`), datas alvo no mês corrente e
  no passado (`GoalContributionEdgeTest`), despesas de outros meses fora do
  consumo do orçamento.

## Frontend (`apps/web`)

```bash
npm run test         # Vitest
npm run typecheck    # tsc -b
npm run lint         # oxlint
```

Testes de comportamento, não de snapshot: `formatBRL/formatDate` (inclusive
imunidade a timezone), `parseMoneyInput` pt-BR, `ApiError`/`NetworkError`,
associação label/erro do `FormField`, navegação do `MonthPicker`, retry do
`ErrorState`; formulário recorrente (campos condicionais por cadência e
destino, sem `CREDIT` genérico, validação de destino, submit completo),
rótulos pt-BR de status de ocorrência, invalidação de queries após
materialização; página de previsão (KPIs, alerta de saldo negativo, fluxos sem
conta, rótulos de fonte por evento, link para fatura, estado vazio); a
experiência de importação de extratos (`statement-imports.test.tsx`) — texto
de privacidade no upload, mapeamento CSV com débito/crédito e delimitador,
renderização da prévia e de linhas inválidas, sugestão e correção de
categoria com salvar-como-regra, revisão de duplicata lado a lado com
duplicata exata bloqueada e possível duplicata com override explícito,
inclusão/exclusão em lote, bloqueio de confirmação, resultado parcial,
retentativa, histórico, confirmação de desfazer e bloqueio, invalidação de
queries, nomes acessíveis.

## E2E (`apps/web/e2e`)

```bash
npm run e2e   # requer a API em :8080 (docker compose up -d + mvnw spring-boot:run)
```

A suíte roda contra o **preview de produção** em `:4173`, não contra o dev server
em `:5173`. A API precisa aceitar essa origem, ou toda requisição não-GET do
navegador volta `403 Invalid CORS request` e os cenários falham no cadastro:

```bash
FINORA_CORS_ALLOWED_ORIGINS=http://localhost:4173 ./mvnw.cmd spring-boot:run
```

O CI já faz isso (`.github/workflows/ci.yml`); localmente é fácil subir a API com
o default de desenvolvimento e interpretar a falha como defeito do produto.

Isolamento por identidade: cada cenário **registra um usuário único** (e-mail
determinístico) via UI, então enxerga apenas os próprios dados — não há mais limpeza
global destrutiva por API aberta (que seria um bypass de posse). Cenários: registrar
e entrar no app; registrar receita/despesa e conferir lista + dashboard; orçamento
com consumo/estado e prevenção de duplicidade; planejar compra com opções à
vista/parcelada e conferir recomendação; **cartões** (criar cartão; compra à vista
e parcelada com fatura/limite corretos; pagar fatura total e parcial com saldo
reduzido uma única vez; estorno; execução de wishlist no cartão sem duplicar;
crédito legado preservado e novo crédito genérico redirecionado; fluxo mobile a
390px); **isolamento entre usuários** (B não vê os
dados de A pela UI nem por ID direto na API, e sua análise de compra é inacessível);
**ciclo de sessão** (expiração leva ao login e permite reentrar; troca de senha
mantém a sessão atual e permite login com a nova); navegação e autenticação mobile
(390px: registro, drawer, criar transação, menu de usuário, logout);
**sincronização offline** (`offline-sync.spec.ts`, 29 jornadas: criar offline sem
nenhuma requisição sair do navegador, indicador do shell, aviso de totais
desatualizados, cofre bloqueado sem replay, recarregar preservando a fila
criptografada, sincronizar exatamente uma vez, resposta perdida sem duplicar,
criar-e-excluir sem escrita no servidor, orçamento e meta offline, aporte e
cartões bloqueados, saída com pendências exigindo decisão, descarte confirmado
removendo o cofre, item de desejo criado offline visível como pendente, opção de
compra nomeando um item que só existe na fila, observação de preço nomeando essa
opção, a cadeia inteira sincronizando na ordem de dependência, conflito com
comparação legível, aplicar-local com confirmação, manter-servidor sem enviar
nada, falha permanente acionável, falha temporária reenviada com a mesma
identidade, duas abas do mesmo dono sem duplicar o efeito, isolamento entre donos
e a central a 390px);
**recorrentes** (criar despesa e receita recorrentes; preview de ocorrências;
executar manualmente com estorno exato e terminal; pular/reativar/reagendar
mantendo a identidade; "Processar vencidos" idempotente — segunda execução não
muda saldo; compra recorrente parcelada na fatura correta; falha por limite
visível e re-executável após corrigir a causa; isolamento entre usuários);
**previsão** (recorrentes projetados; caixa de cartão aplicado na data de
vencimento da fatura — provado que todo evento projetado de cartão cai no dia
de vencimento, nunca na data da compra; alerta de saldo negativo; fluxos sem
conta; ocorrência materializada substitui a projeção sem contagem dupla;
seção de caixa futuro no dashboard; jornada mobile de recorrentes e previsão);
**conversão de crédito legado** (inventário com filtros; assistente completo com
cronograma determinístico e conversão sem contagem dupla — verificado no
dashboard antes/depois; retentativa idempotente devolvendo a mesma conversão;
parcelamento em três faturas com alocação conferida; limite insuficiente
bloqueando a confirmação; estorno com motivo restaurando a origem e bloqueio
após pagamento concluído; lote com sucesso e falha convivendo e retentativa só
das falhas; mapeamento de recorrente legado sem retroativos; isolamento entre
usuários; e o fluxo principal completo a 390px — fontes legadas são forjadas
via SQL no contêiner, exatamente como a migração V7 as criou);
**importação de extratos** (`statement-imports.spec.ts`: jornada CSV completa
com mapeamento, categorização, efeito contábil e reenvio idempotente
detectando duplicatas; colunas separadas de débito/crédito; possível
duplicata contra transação manual exigindo override explícito; correção de
categoria salva como regra e reaproveitada em outra conta; OFX XML, OFX
malformado bloqueado com mensagem segura e fatura de cartão redirecionada
para Cartões; falha parcial retentável até tudo importar; desfazer item e
lote preservando o ledger de auditoria; isolamento entre usuários; e o fluxo
CSV principal completo a 390px — arquivos sintéticos em memória, datas
fixas em 2026-06, sem dados bancários reais).
As datas dos cenários recorrentes são calculadas por offset a partir de hoje —
as asserções derivam dos mesmos offsets, então o resultado independe do dia de
execução. Localizadores acessíveis (roles e labels), sem seletores CSS frágeis.

### QA visual

```bash
VISUAL_QA=1 npx playwright test e2e/visual-qa.spec.ts
```

Semeia dados demo determinísticos e captura os estados principais em
1440/1280/768/390px, claro e escuro, em `qa-screenshots/`
(ignorado pelo Git). Inclui a área recorrente e a previsão: lista de
recorrentes, formulário, histórico de ocorrências, ocorrência com falha,
diálogo de reagendamento, previsão com saldo negativo e seção de caixa futuro
do dashboard — desktop e mobile, claro e escuro.

Os estados de sincronização offline ficam separados no mesmo arquivo, porque
exigem cofre desbloqueado e navegador desconectado — condições sob as quais o
restante da suíte não pode rodar. São **quatro grupos por tema**, cada um com sua
própria conta e seu próprio contexto de navegador, rerodáveis isoladamente:

```powershell
$env:VISUAL_QA = "1"
npx playwright test e2e/visual-qa.spec.ts -g "sincronização offline"
npx playwright test e2e/visual-qa.spec.ts -g "estados de conflito \(dark\)"
Remove-Item Env:VISUAL_QA
```

| Grupo | Estados |
| --- | --- |
| base e pendentes | central vazia; linha pendente com descrição longa; exclusão pendente; item e opção criados offline; fila com dependências |
| ações destrutivas | cofre bloqueado com trabalho pendente; aviso de saída bloqueado; confirmação destrutiva final; aviso de saída com pendências conhecidas; descarte de uma alteração da fila |
| falhas | falha temporária; falha permanente com mensagem longa |
| conflito | conflito; comparação servidor/local aberta |

Os treze estados exigidos, mais o descarte de uma linha da fila, em quatro
viewports (1440, 1280, 768, 390) e dois temas: **112 capturas**.

Nada é fotografado no escuro. Antes de cada quadro o teste confere o tema no
documento renderizado (não a preferência armazenada), lê a largura de volta da
janela (não a assume do resize), exige o marcador do estado na tela, exige que
nenhum esqueleto reste e espera as animações terminarem em vez de dormir um
tempo fixo. Cada quadro também afirma que nem a página nem o diálogo aberto
rolam na horizontal — a falha que uma miniatura esconde melhor. Uma falha nomeia
o estado, o viewport e o tema.

A entrega de notificações acrescenta migração PostgreSQL populada V11→V12,
identidade estável de eventos, API/ownership/lifecycle, quatro testes reais de
corrência com threads e Testcontainers, scheduler em lotes, testes Vitest do
sino/painel/preferências/browser e 20 jornadas Playwright em
`notifications.spec.ts`. O QA visual inclui sino, painel, histórico e settings
nos quatro viewports e dois temas.

## CI

### PWA e offline

`vaultCrypto.test.ts`, `vaultStorage.test.ts`, `queryPersistence.test.ts` e
`api.test.ts` cobrem criptografia autenticada, corrupção, storage, allowlist e
mutação bloqueada antes da rede. Após `npm run build`, `npm run verify:pwa` valida
manifesto/ícones/SW/fallback e `/api` NetworkOnly. `pwa-offline.spec.ts` contém 12
jornadas Chromium no preview de produção, inclusive reload sem rede e ausência de
plaintext/JSON de API nos storages.

### Sincronização offline

Backend, com PostgreSQL real: `MigrationFromPopulatedV13Test` migra um banco V13
populado (dois donos, transação importada, gerada por recorrente e de crédito
legado inativa, orçamentos, metas, itens, opções, snapshot com versão própria,
notificações e importação) e prova que nada se moveu, que versões começam em zero,
que a versão de snapshot é preservada e que nenhuma identidade de cliente ou recibo
é fabricado. `OfflineSyncContractTest` cobre a forma do endpoint (autenticação,
CSRF, lote vazio/grande demais, payload grande demais, tipo e operação fora da
lista, alvo ambíguo/ausente, versão base obrigatória, ordem e sucesso parcial).
`OfflineSyncIdempotencyTest` cobre resposta perdida, chave reutilizada com conteúdo
diferente, diferenças de formatação que não são diferenças de conteúdo, donos
independentes com a mesma chave e ausência de recibo para recusas.
`OfflineSyncConflictTest` cobre versão desatualizada, exclusão remota, unicidade de
orçamento, dependência ausente e a ausência de vazamento no retrato do servidor.
`OfflineSyncDomainTest` cobre o ciclo completo de cada domínio, as cinco recusas de
registros protegidos e a impossibilidade de contrabandear a atualização da opção
vinculada. `OfflineSyncOwnershipTest` prova que "não é seu" e "não existe" são
indistinguíveis. `OfflineSyncConcurrencyTest` roda corridas reais: criações
idênticas simultâneas, duas criações com a mesma identidade de recurso, duas
edições sobre a mesma versão base, edição contra exclusão, orçamentos duplicados e
o lote inteiro enviado duas vezes ao mesmo tempo.

Frontend: `queue.test.ts` (compactação, cancelamento em cascata, limites,
ordenação e ciclos), `replay.test.ts` (aplicação, já aplicado, perda de conexão,
backoff, recusa permanente, conflito, dependência e lote limitado),
`projection.test.ts` (sobreposição pendente sem reescrever o retrato do servidor) e
`VaultProvider.test.tsx` (ciclo real de habilitar, enfileirar, persistir, bloquear,
desbloquear, resolver e remover). `offline-sync.spec.ts` contém 35 jornadas
Chromium, incluindo perda de resposta simulada abortando a resposta depois do
commit, conflito entre dois contextos reais e isolamento entre donos.

`destructiveRisk.test.tsx` cobre a proteção das ações destrutivas com 22 casos:
cofre ausente e cofre desbloqueado e vazio saem direto; cofre desbloqueado com
pendências mostra as contagens exatas; cofre bloqueado — **inclusive quando sua
fila cifrada está de fato vazia** — mostra sempre o aviso conservador e não
inventa número nenhum; cofre ilegível avisa que o conteúdo não pôde ser
verificado; `LOADING` e `UNLOCKING` desabilitam a saída destrutiva; cancelar,
desbloquear e verificar e o primeiro passo destrutivo não chamam logout nem
remoção; só a confirmação final remove; a limpeza local acontece com logout de
servidor falho; uma remoção que falhou é relatada em vez de comemorada; desativar
o acesso offline usa a mesma proteção; os dados do servidor nunca são descritos
como apagados; e nenhum botão do diálogo compartilha nome acessível com outro.

As seis jornadas de `Ações destrutivas com o cofre bloqueado` chegam ao estado
recarregando a página, e não construindo-o: a chave só vive em memória, então uma
carga comum é o que produz um cofre bloqueado guardando trabalho que o servidor
nunca viu. Elas leem o registro criptografado direto do IndexedDB em vez de
confiar na tela, provam que a fila **não** é enviada enquanto o diálogo está
aberto, e usam `context.route` para simular o logout de servidor com falha.

Uma armadilha que custou caro e vale registrar: o Service Worker registra rotas
`NetworkOnly` para `/api` em **todos** os métodos, então um lote de replay é
emitido *pelo worker*, não pela página — e `page.route` não enxerga requisição de
service worker. Toda interceptação do endpoint de sincronização precisa ser
`context.route`. Com `page.route` a interceptação simplesmente não acontecia, e o
cenário passava ou falhava conforme o worker já tivesse assumido aquela
navegação.

Reconectar dispara um replay por si só. Os cenários que precisam controlar a
primeira tentativa desligam **"sincronizar automaticamente ao reconectar"** pelo
próprio checkbox de configurações, em vez de disputar corrida com ele.

A cópia offline é um retrato do momento em que foi criada. Uma linha criada no
servidor depois disso não existe offline até o usuário pedir a cópia de novo —
jornadas que editam offline uma linha criada online precisam de
"Atualizar dados offline" antes.

O histórico de preços acrescenta migração populada V12→V13, lifecycle e posse
via MockMvc, regressão da análise e corridas com threads/Testcontainers. Vitest
cobre tendências, alvo e UUID; Playwright cobre o fluxo real e responsividade.

`.github/workflows/ci.yml`: job de backend (`mvnw verify` com Testcontainers),
job de frontend (lint, typecheck, testes, build) e job E2E (serviço PostgreSQL,
API em background, Playwright) — travas de merge naturais para `main`.
