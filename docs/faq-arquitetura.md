# FAQ adversarial — as perguntas que vão jogar na gente

Cada entrada tem a pergunta como um crítico hostil formula, a resposta, e — onde
existe — o **limite honesto**: o ponto em que a resposta acaba. Uma FAQ só com
respostas confiantes não serve numa sala hostil; o que ganha credibilidade é
saber onde o desenho ainda não fecha.

Convenção: 🟢 defensável · 🟡 defensável com ressalva · 🔴 não defensável hoje.

---

## 1. Mensageria e a escolha de não usar broker

### 1.1 "Por que Postgres e não Kafka, se já temos Kafka rodando?" 🟢

O volume real é < 500 mil/dia e o gargalo é a CERC a 80 req/s. Kafka no caminho
crítico não toca esse gargalo: adiciona hops, infra nova e arestas M×N entre
produtores e consumidores. E não entrega "não perder" por si — para não perder
entre o commit no banco e a publicação no tópico você precisa de outbox + CDC de
qualquer forma. Aqui a fila **é** o estado: o ack é a transição da linha na mesma
transação que grava a intenção. Kafka entra se/quando houver fan-out real
(vários consumidores independentes do mesmo evento), pendurado no outbox via CDC.
Registrado como ADR-001.

### 1.2 "Então você jogou fora o desenho com vários motores e integradores?" 🟡

Não — uma malha de produtores e consumidores resolve um problema que este serviço
não tem: vários consumidores independentes do mesmo evento. Esta POC tem um
consumidor (o worker) e um destino (a registradora). Quando aparecer o segundo
consumidor real, o outbox já é o log de onde ele lê.

**Limite honesto:** onde já existe Kafka operado por um time, com observabilidade
e runbook maduros, o argumento "infra nova" perde força — o custo marginal de um
tópico num cluster existente é baixo. A defesa que sobra é a de arestas e do ack
transacional, não a de custo de infra.

### 1.3 "Fila em banco não escala." 🟢

`FOR UPDATE SKIP LOCKED` escala por centenas de milhares de itens/dia sem
esforço; o teto prático fica em ordens de magnitude acima do nosso volume. E o
teto que importa aqui não é nosso: é a CERC a 80 req/s. Nenhuma escolha de
mensageria move esse número.

### 1.4 "E o bloat da tabela de fila?" 🟡

`PROCESSADO` fica como trilha de auditoria; autovacuum cobre no volume atual; a
evolução natural é particionamento mensal.

**Limite honesto:** `operacao` e `outbox` crescem sem poda e **não há job de
arquivamento nem partição implementada**. `buscarEnviadosPresos` faz seq scan
sem índice. Em 2 anos de produção isso precisa de partição; hoje é intenção
escrita, não código.

### 1.5 "É mais rápido que Kafka?" 🟡

O teto de throughput é o mesmo, porque o gargalo é a registradora e não a
mensageria. O que muda é o caminho: uma transação e uma chamada HTTP, sem
produce+consume no meio, e sem rebalance de consumer group para causar degrau de
p99. "Mais performático" aqui significa chegar ao teto da registradora gastando
menos infra — não processar mais.

**Limite honesto:** não existe benchmark neste repositório. Essa é uma afirmação
sobre a forma do caminho, não um número medido. Se alguém pedir o p99, a resposta
honesta é "não medimos".

### 1.6 "E se precisar publicar eventos para outros sistemas?" 🟢

O `outbox` já é o log ordenado do que aconteceu. CDC pendurado nele vira fonte de
eventos e Kafka entra como **saída**, sem tocar o caminho crítico. É a mesma
posição do ADR-001: broker quando houver fan-out real, nunca no meio da
escrituração.
---

## 2. Não perder e não duplicar

### 2.1 "Como você prova que não perde webhook?" 🟢

`eventos_recebidos` tem `UNIQUE (registradora, event_id)` e o registro do evento
acontece na mesma transação que aplica os itens. Replay 3× → 1 efeito. Provado
em `WebhookDedupTest` e ao vivo na demo (provas 2 e 4).

### 2.2 "E se a CERC nunca mandar o webhook?" 🟢

O webhook é otimização, não dependência. O reconciliador varre `ENVIADO` há mais
de 30 min e consulta o lote por API. O caminho de corretude é a reconciliação; o
webhook só encurta a latência.

### 2.3 "Dois pods podem registrar a mesma duplicata duas vezes?" 🟢

Não pelo caminho normal: o claim é `FOR UPDATE SKIP LOCKED` com lease, e
`referencia_externa` é `UNIQUE`. Provado em `ConcorrenciaSemDuplicatasTest` e na
prova 5 da demo com 2 pods simultâneos.

### 2.4 "E se o envio demorar mais que o lease?" 🟡

O `lote_id` é gravado numa transação imediatamente após o POST retornar, e
`promoverEnviadosComLote` promove para `ENVIADO` qualquer linha com lease
vencido que já tenha `lote_id` — ou seja, o resultado do primeiro envio não se
perde mais. Com `readTimeout` de 10s e lease de 10 min, a janela é estreita.

**Limite honesto:** a janela não é zero. Se o POST ainda estiver em voo quando o
lease vencer (`lote_id` ainda NULL), a linha volta para `PENDENTE` e pode ser
reenviada; a CERC então rejeita com "duplicata já emitida" (002.CN-008) e o item
vai para revisão manual sem IUD conhecido. É o risco 4 do README, hoje estreito,
não eliminado.

### 2.5 "A migração do legado pode pular operações?" 🟢

Não mais. O checkpoint é cursor por chave (`after_id`), não número de página —
inserção ou remoção no legado durante a migração não desloca a janela. Antes era
offset, e offset sobre coleção mutável pula silenciosamente.

**Limite honesto:** se o legado não devolver `proximo_cursor` numa página com
`tem_mais = true`, o leitor repete a mesma página sem avançar. Não perde dado,
mas também não progride — e nada alerta.

### 2.6 "Qual é a chave de idempotência de ponta a ponta?" 🟢

Quatro, em camadas: `intencao.operacao_legado_id` (entrada),
`operacao.referencia_externa` (nossa chave na registradora),
`eventos_recebidos.event_id` (webhook), `operacao.operation_id`/`titulo.iud`
(retorno da CERC). Cada uma é `UNIQUE` no banco, não checagem em código.

---

## 3. Estados presos e retry

### 3.1 "O que acontece com uma operação que a registradora nunca resolve?" 🟡

O contador `consultas` só sobe quando a consulta **retorna com sucesso** dizendo
`PROCESSANDO`. Consulta que falha — 503, timeout, janela fechada com 423 — não
consome orçamento nenhum. Passando do limite, a operação vai para
`INDETERMINADO`, não `FALHA_PERMANENTE`: o desfecho na registradora é
desconhecido, e afirmar "não registrou" é o que gera título duplicado no
reenvio. `INDETERMINADO` é terminal, entra na DLQ com `lote_id` e referência no
payload, e **o endpoint de reprocesso recusa** — exige conferência humana na
registradora antes de qualquer reenvio.

Coberto por `HardeningTest`: "consulta que falha nao consome o orcamento de
consultas" e "preso em processamento vira INDETERMINADO e nao FALHA_PERMANENTE".

O mesmo vale para lote que a registradora devolve em `ERRO`: o desfecho **por
item** é desconhecido, então ele também vai para `INDETERMINADO`, não é
reenviado. Antes, `ERRO` de lote re-enfileirava tudo cegamente — e como o
reenvio gera um `lote_id` novo, o webhook do lote antigo chegava divergente e
era descartado pelo guard de integridade: a registradora ficava com dois títulos
e nós registrávamos um, com a evidência do primeiro na lixeira.

**Limite honesto:** `consultas-limite` é um número só (12) para todas as
registradoras, e não foi calibrado contra SLA nenhum — ver 3.5. E a conferência
que resolve um `INDETERMINADO` é manual: não há reconciliação por identificador
de título.

### 3.2 "Um erro 4xx derruba a fila?" 🟢

Não, porque 4xx foi separado em duas famílias. 403/404/405/408/415 são erro de
**rota ou acesso** — configuração errada, WAF na frente, credencial trocada:
tratados como transitórios que não consomem tentativas, então um
`DUPE_CERC_BASE_URL` com typo reagenda a fila em vez de drená-la para a DLQ.
400/422 são rejeição de **conteúdo**, e aí o lote é bissectado antes de condenar
ninguém: 200 itens viram 100+100, 50+50, até isolar o item ruim. Só a folha de
tamanho 1 que continua sendo rejeitada vai para a DLQ; os outros 199 seguem.

Coberto por `HardeningTest`: "lote rejeitado e bissectado para isolar so o item
ruim" e "rota errada na registradora nao despeja a fila na dlq".

O bisect só dispara em **rejeição de conteúdo** (400/422, que vem de resposta da
registradora e pode ser de um item). Erro local — registradora fora do registry,
por exemplo — vai direto para a DLQ numa chamada só, em vez de gerar 2n−1
recursões inúteis queimando o teto de taxa.

E o bisect tem prazo: antes de cada chamada ele compara o relógio com
`claimed_until` menos 25% do lease e, se não couber, devolve o resto à fila como
retryável. Sem isso, um lote de 200 todo ruim daria até 399 chamadas × 10s de
`readTimeout` ≈ 66 min contra um lease de 10 min — o lease venceria no meio, o
reconciliador devolveria as linhas, outro pod reenviaria, e o bisect original
carimbaria `lote_id` em cima da operação do outro pod. Era a dupla escrituração
não reconciliável de volta, pela porta dos fundos.

**Limite honesto:** o pior caso do bisect ainda é O(2n) chamadas quando *todo*
item do lote é ruim, dentro do prazo do lease. É uma rajada, só que limitada.

### 3.3 "E o poison-pill: um item ruim travando o lote?" 🟢

Resolvido pelo bisect de 3.2. O item ruim é isolado em ~log₂(n) rodadas e os
demais são escriturados na mesma passagem do worker.

### 3.4 "Por que 429 e 423 não consomem tentativas?" 🟢

Porque não são falha do dado, são falha de janela. `naoEsgota` reagenda sem
queimar o orçamento de tentativas, e a fila drena quando a janela abre. Sem isso
a janela noturna da CERC mandaria o backlog para a DLQ todo dia.

**Limite honesto:** `naoEsgota` não zera mais o contador (só ignora o limite),
então o histórico de tentativas reais é preservado. Mas um item que só receba
423/429 circula indefinidamente sem nunca esgotar — é o comportamento desejado
para janela fechada, e não há teto de tempo absoluto que o tire da fila.

### 3.5 "E se a duplicata não sair de 'em processamento' na B3? Teremos problemas?" 🟡

O mecanismo não é específico da CERC, então a resposta estrutural já existe: o
reconciliador consulta o lote/ticket, `consultas` conta só as consultas que
responderam de fato, e ao passar do limite a operação vai para `INDETERMINADO` +
DLQ + alerta, **sem reenvio automático**. Ou seja: não trava a fila, não vira
duplicidade silenciosa, e alguém é avisado.

Dito isso, sim — há problemas, e vale enumerá-los em vez de dizer "está
coberto":

1. **A duplicata fica em limbo jurídico, não só técnico.** Enquanto o desfecho é
   desconhecido, não se pode afirmar ao financiador que o recebível está
   registrado, nem reenviar sem risco de duplicar o título. `INDETERMINADO`
   nomeia esse limbo em vez de escondê-lo — mas nomear não resolve.
2. **O limite é um número único, e a B3 não é a CERC.** `consultas-limite` é
   global (12 consultas). A B3 RDE trabalha com ticket assíncrono e pode
   legitimamente manter um ticket em processamento por uma janela maior que a
   da CERC. Com um limite só, ou a CERC demora demais para escalar ou a B3
   escala cedo. **O limite precisa ser por registradora, e hoje não é.**
3. **Não há SLA acordado para calibrar o limite.** 12 × 5 min ≈ 1 hora foi
   escolhido por desenho, não contra um tempo máximo de processamento
   contratado. Sem esse número, o limite é arbitrário.
4. **A saída do limbo é manual.** Resolver um `INDETERMINADO` hoje é conferir na
   registradora e corrigir o estado à mão. Uma reconciliação por identificador
   do título (não pelo ticket que travou) fecharia isso automaticamente — se a
   registradora oferecer essa consulta, o que para a B3 ainda não foi verificado.
5. **E o principal: a B3 não está implementada.** A resposta acima descreve como
   o desenho trata o caso; não é comportamento observado. Não apresentar como
   testado — ver 5.1.

O que dá para afirmar com segurança: nenhum ticket preso na B3 vai bloquear a
fila, sumir em silêncio ou ser reenviado automaticamente. O que não dá: dizer
por quanto tempo esperamos, nem que a saída do limbo seja automática.

---

## 4. Taxa, concorrência e escala horizontal

### 4.1 "Você diz escala horizontal, mas o teto é 80 req/s. N pods não estouram isso?" 🟡

É token bucket em tabela, teto global e não por pod — o limiter in-process do
resilience4j sozinho permitiria N×80. Recarga contínua proporcional ao tempo
decorrido, burst limitado ao teto, e **nada é consumido quando a permissão é
negada**. Cobre `enviar` e `consultar` — a reconciliação pede permissão por lote
e deixa o que não couber para o ciclo seguinte, então envio e consulta dividem o
mesmo teto em vez de somarem dois.

Antes era janela fixa de 1s (80 no fim de uma janela + 80 no começo da seguinte =
160 req em milissegundos) e o `consultar` não passava pelo limiter.

Coberto por `HardeningSegundoRoundTest`: "token bucket e global e nao consome
permissao quando nega" e "token bucket recarrega com o tempo".

Usa `clock_timestamp()` e não `now()`: `now()` é o timestamp da *transação*, e se
alguém um dia envolver o worker numa transação os tokens parariam de recarregar e
o envio travaria por completo.

**Limite honesto:** o teto conta **chamadas HTTP**, e um `enviar` carrega até 200
operações. Se o limite contratado for por operação e não por requisição, o número
está na unidade errada.

### 4.2 "Autoscaling por profundidade de fila não piora o gargalo?" 🟡

Com o teto global em tabela, mais pods não viram mais pressão na CERC — só mais
paralelismo na parte que é nossa (canonicalização, banco). Sem o teto global, a
crítica é procedente: a resposta automática ao backlog seria aumentar a pressão
sobre o gargalo até virar 429.

### 4.3 "Os quatro jobs num processo não competem entre si?" 🟢

`spring.task.scheduling.pool.size: 4`, mais `connectTimeout` 5s e `readTimeout`
10s no cliente HTTP. Antes: pool de 1 thread e zero timeout — um socket pendurado
na CERC parava leitor, worker, reconciliador e alerta ao mesmo tempo, com
`/actuator/health` respondendo UP.

### 4.4 "E se dois pods reconciliarem ao mesmo tempo?" 🟡

Advisory lock de sessão (`pg_try_advisory_lock`) em conexão dedicada, liberado no
`finally`. O lock é de sessão e não de transação justamente para não manter uma
transação aberta durante I/O externo.

Três defesas, porque o lock de sessão sobrevive ao retorno da conexão ao pool:
`pg_advisory_unlock_all()` ao pegar a conexão (ela não pode chegar carregando
lock de ninguém, o que também mata o problema de reentrância do
`pg_try_advisory_lock`); `unlock` no `finally`; e se o `unlock` falhar ou devolver
falso, a conexão é **descartada do pool** (`evictConnection`) com log de erro, em
vez de voltar para a fila carregando o lock — e se o próprio `evictConnection`
falhar (DataSource embrulhado por proxy de tracing, por exemplo), a sessão é
encerrada com `pg_terminate_backend`, que derruba o lock de qualquer jeito. Antes,
um `unlock` falho deixava o lock preso até o `maxLifetime` do Hikari (30 min
default) sem nenhum pod reconciliar e sem alerta.

Coberto por `HardeningSegundoRoundTest`: "advisory lock nao vaza quando o bloco
lanca" e "advisory lock exclui o segundo tomador enquanto esta preso" — ambos
verificam `pg_locks` no fim.

---

## 5. Multi-registradora

### 5.1 "Vocês dizem multi-registradora, mas só a CERC existe." 🟢

Correto, e o README diz isso na primeira tela. O que existe: o port
`RegistradoraPort`, a coluna `operacao.registradora`, o registry e o roteamento
por lote. O que não existe: B3 (mTLS + ticket assíncrono), Núclea (arquivo
SPB/XML) e a **regra** de roteamento.

### 5.2 "Qual registradora recebe qual operação?" 🔴

Pergunta aberta — e é **jurídica**, não técnica: o financiador opera no
escriturador do sacador ou só onde é participante? Enquanto não houver resposta,
`IngestaoService` fixa `CERC` e a coluna tem `DEFAULT 'CERC'`. É a maior questão
em aberto do projeto e não dá para resolver escrevendo código.

### 5.3 "O modelo suporta a mesma duplicata em duas registradoras?" 🟢

Suporta **registrar o fato**, e impede **ter duas ativas** — que são coisas
diferentes, e a distinção é o ponto.

Uma duplicata escritural deve existir em uma registradora só; duas ativas é
exatamente a dupla escrituração que a interoperabilidade entre registradoras
existe para impedir. Então: `titulo` tem `UNIQUE (registradora, duplicata_id)`
(dá para guardar o histórico — portabilidade, cancelamento em A e registro em B)
mais um índice único parcial `(duplicata_id) WHERE ativo`, que garante **no
máximo um título ativo por duplicata, entre todas as registradoras**.

A inserção resolve isso numa instrução só e devolve o que aconteceu: `NOVO`,
`JA_EXISTE` (replay do mesmo IUD, idempotente) ou `CONFLITO_ATIVO` — e neste
último caso o título é gravado como inativo **e o conflito vira incidente na
DLQ com log de erro**. Antes, o `ON CONFLICT DO NOTHING` sem target engolia essa
colisão em silêncio: era o pior comportamento possível, porque perdia justamente
o sinal de que uma duplicata foi escriturada duas vezes.

Coberto por `HardeningSegundoRoundTest`: "mesma duplicata em duas registradoras
guarda os dois titulos com um so ativo" e "registro duplicado detectado pelo
webhook vira incidente na dlq".

**Limite honesto:** dois pods checando o índice ao mesmo tempo podem ambos
calcular "não existe ativo"; o índice único é o desempate e um dos dois recebe
erro. Na prática a reconciliação é single-holder por advisory lock e o webhook é
esporádico, mas a corrida existe. E resolver um `CONFLITO_ATIVO` — decidir qual
registro fica — é manual.

### 5.4 "O circuit breaker é por registradora?" 🟡

As instâncias do resilience4j são nomeadas (`cerc`), mas `ResilientRegistradora`
é um bean único `@Primary` com o breaker fixo em `"cerc"`. Com a segunda
registradora, isso precisa virar uma instância por registradora — senão uma
queda da CERC abre o breaker da B3.

---

## 6. Compliance e domínio

### 6.1 "Quem afirma que o sacador assinou?" 🟡

O campo `assinatura` do payload da CERC vem do dado canônico. Antes era `"S"`
hardcoded no mapper — uma afirmação jurídica feita por código, sem dado por trás.
Isso foi corrigido: onde o legado não fornece, a operação é rejeitada na
canonicalização em vez de assumir.

**Limite honesto:** quem valida que o dado do legado sobre assinatura é
verdadeiro é o legado. Nós não temos evidência de assinatura, só o campo.

### 6.2 "O identificador do título é o número da nota fiscal?" 🟡

O `identificador.fatura` vem do dado do legado. Antes era
`referenciaExterna.take(60)` — um surrogate interno, o que impediria qualquer
terceiro (sacado contestando, financiador conferindo) de casar o registro com a
fatura real, e podia colidir por truncamento.

### 6.3 "Duplicata parcelada? Duplicata de serviço?" 🔴

`parcela` e `tipo` saem do dado canônico, mas **não há teste de parcelamento** e
o fluxo foi validado só com `MERC`. Não afirmar cobertura de SERV nem de
parcelamento.

### 6.4 "Como se cancela ou altera uma duplicata registrada?" 🔴

Não existe caminho. `intencao.operacao_legado_id` é `UNIQUE`, então uma correção
reenviada pelo legado com o mesmo id é **descartada em silêncio** e reportada
como `ja_existentes`. Precisa do endpoint de inativação/PATCH da CERC e de um
segundo tipo de operação no modelo. Registrado como dívida — e é a lacuna
funcional mais visível para quem conhece o domínio.

### 6.5 "Valor monetário: como vocês tratam escala e arredondamento?" 🟡

`BigDecimal` com escala normalizada antes de serializar. Sem isso, Jackson pode
emitir notação científica para valores com expoente e a CERC receber algo que não
parece dinheiro.

---

## 7. Segurança

### 7.1 "O que impede alguém de chamar os endpoints de vocês?" 🟡

Os três endpoints validam credencial na aplicação, com comparação em tempo
constante (`ChaveApi`): `/api/legado/operacoes` e `/api/dlq` por `X-Api-Key`
(chaves distintas — ingestão e administração não compartilham segredo),
`/webhook/{registradora}` por HMAC. `GET /api/dlq` devolve payload de operação
(CPF/CNPJ, nome, e-mail, chave PIX, IBAN), então é tratado como superfície de
dado pessoal: exige a chave de admin e tem `limite` limitado a 500.

Coberto por `HardeningTest`: "dlq nao responde sem chave de api" e "reprocessar
sem chave de api nao altera estado".

**Limite honesto:** é chave estática em header, não identidade — não há usuário,
papel nem trilha de quem listou ou reprocessou o quê. Para uma superfície que
expõe dado pessoal, o certo é autenticação real com auditoria. E a defesa em
profundidade (regra de path no ALB, mTLS, allowlist) não está declarada neste
repo.

### 7.2 "Secrets no repositório?" 🟢

Nenhum. Os defaults `poc-*` existem para a demo, e `CredenciaisGuard` aborta o
boot se um deles aparecer fora dos perfis `demo`/`test`/`mock-*`. Em produção:
Secrets Manager.

O guard rejeita placeholders conhecidos (`poc-`, `dupesc`, `changeme`, `secret`,
`password`, `test`, `admin`), valores em branco e qualquer segredo com menos de 16
caracteres — e cobre também `spring.datasource.password` e o `client-id`.

**Limite honesto:** é lista de proibidos com piso de tamanho, não medida de
entropia. Um segredo ruim de 16+ caracteres passa.

### 7.3 "O HMAC do webhook protege contra replay?" 🟡

Não diretamente — a assinatura não cobre timestamp nem nonce. O que barra replay
é o dedup por `event_id`. Em produção a recomendação é mTLS + IP allowlist, com
alerta na taxa de 401 (`dupe.webhook.401`).

### 7.4 "Um webhook assinado pode marcar qualquer coisa como registrada?" 🟢

Não mais: `validarLote` confere o `lote_id` do evento contra o
`operacao.lote_id` antes de aplicar. Antes, qualquer corpo assinado podia marcar
qualquer operação como `REGISTRADO` com IUD arbitrário.

Divergência de `lote_id` agora descarta **só o item**: ele vai para a DLQ com o
motivo, os itens legítimos do mesmo webhook são aplicados, e a linha de dedup
commita. Antes a divergência levantava exceção e revertia a transação inteira
— inclusive o dedup — então cada retry da registradora gerava uma nova linha na
DLQ para o mesmo evento, indefinidamente.

Coberto por `HardeningSegundoRoundTest`: "lote_id divergente descarta so o item e
preserva o dedup do evento".

---

## 8. Operação

### 8.1 "Quando algo falha, quem descobre e como?" 🟡

Gauges Micrometer em `/actuator/prometheus`: `dupe.fila.pendente`,
`dupe.fila.idade_ms`, `dupe.dlq.abertos`, contador `dupe.webhook.401`. Alertas:
fila > 10k, idade > 15 min, DLQ > 0.

A idade conta de `coalesce(reenfileirado_em, criado_em)`, então um item
reprocessado da DLQ conta do reprocesso e não do `criado_em` original — antes o
alerta de idade disparava para sempre depois de qualquer reprocesso. A consulta
também devolve `null` de verdade com fila vazia (antes `rs.getDouble` mapeava
NULL para 0.0 e o contrato `Long?` era mentira).

**Limite honesto:** `dupe.fila.pendente` conta `PENDENTE` incluindo itens
reagendados para o futuro. Durante as 12 horas de janela fechada da registradora,
o backlog inteiro é `PENDENTE` e o alerta de fila toca a noite toda — falta
separar "pendente agora" de "pendente reagendado".

### 8.2 "O que o operador faz com um item na DLQ?" 🟡

`GET /api/dlq` lista e `POST /api/dlq/{id}/reprocessar` reenfileira. O reprocesso
é atômico: os dois updates (operação para `PENDENTE`, fila para `PENDENTE`) e a
baixa da linha da DLQ acontecem na mesma transação, e se qualquer um não se
aplicar **a transação reverte inteira** e o operador recebe 409 com o motivo.
Não existe mais o caminho que deixava a operação em `PENDENTE` sem linha de fila.
Itens `INDETERMINADO` são recusados de propósito (ver 3.1) e carregam `lote_id` e
referência no payload para a conferência.

Coberto por `HardeningTest`: "indeterminado nao reprocessa e nao deixa a operacao
orfa".

**Limite honesto:** reprocesso é um a um, sem operação em lote, e a DLQ de origem
`WEBHOOK`/`LEITOR`/`INGESTAO` continua sem caminho automatizado — só `OUTBOX`
reprocessa.

### 8.3 "Como vocês fazem deploy sem perder o que está em voo?" 🟡

Os pods são stateless; o que estava claimed volta pela expiração de lease e o
`lote_id` já persistido evita reenvio. Rolling deploy normal.

**Limite honesto:** não há shutdown hook que devolva os claims do pod que está
descendo, então o que ele tinha em mãos espera o lease vencer (até 10 min) antes
de outro pod pegar.

### 8.4 "Qual é o RTO/RPO?" 🟡

RPO zero para o que commitou: tudo é uma linha no Postgres, com Multi-AZ e PITR.
RTO é o tempo de subir pod novo mais a expiração de lease.

**Limite honesto:** não foi feito game day. Os números são de desenho, não
medidos.

### 8.5 "Banco único não é ponto único de falha?" 🟡

É um ponto de falha, sim — mitigado com RDS Multi-AZ e PITR. O contraponto é que,
como a fila **é** o banco, não há um segundo sistema para sincronizar: uma fonte
de falha a menos, não a mais. Com broker, um Postgres indisponível continuaria
derrubando a escrituração, só que agora com a fila divergindo do estado.

**Limite honesto:** "mitigado" não é "não é". Um failover de AZ é medido em
dezenas de segundos a minutos, durante os quais nada é escriturado — e isso nunca
foi exercitado aqui (ver 8.4).
---

## 9. Prova e qualidade

### 9.1 "Isso foi testado ou é slideware?" 🟢

13 testes de integração com Testcontainers (Postgres real) e WireMock, mais 5
provas ao vivo em `scripts/demo.sh` com 2 pods: legado → registrada; webhook
deduplicado (3× → 1); `docker kill` no meio do envio com o reconciliador do outro
pod resolvendo; 2 pods simultâneos com zero duplicatas.

### 9.2 "Os bugs que vocês acabaram de corrigir estão cobertos por teste?" 🟡

Os quatro bloqueadores do segundo round estão: `HardeningTest` cobre autenticação
da DLQ, orçamento de consultas, `INDETERMINADO`, atomicidade do reprocesso,
bisect do lote e rota errada — 7 testes, 20 no total. Foi escrevendo esses testes
que apareceu um bug latente que nenhuma revisão por leitura tinha pego:
`falhaPermanente` fazia `CAST('texto' AS jsonb)` numa coluna JSONB, então **todo
caminho de falha permanente lançava exceção em vez de gravar** — inclusive
"tentativas esgotadas → DLQ", que nunca funcionou de verdade.

O terceiro round acrescentou `HardeningSegundoRoundTest` (8 testes): vazamento e
exclusão do advisory lock, token bucket global e recarga, `lote_id` divergente
preservando o dedup, título por registradora com um só ativo, incidente de
registro duplicado na DLQ, e idade da fila pelo reenfileiramento. Escrever esses
testes achou um segundo bug latente: `idadePendenteMaisAntigoMs` devolvia 0 em
vez de `null` com fila vazia. Total: 28 testes.

O quarto round acrescentou `HardeningQuartoRoundTest` (8 testes) e, mais
importante, **rodou cada teste novo contra o código anterior para confirmar que
falhava**: 6 dos 8 falharam, os outros 2 são guardas de regressão. Total: 36
testes.

**Limite honesto:** continuam sem teste o `CredenciaisGuard` e o caminho de
cancelamento — que não existe.

### 9.3 "Qual a diferença entre esta POC e produção de verdade?" 🟡

Honestamente: os itens 🔴 desta FAQ, mais ambiente da CERC, secrets reais,
ALB/WAF e o game day. O caminho felizes e os de falha principais estão provados;
os caminhos de recuperação recém-escritos não estão.

---

## 10. Perguntas de gente que não quer que isso ande

### 10.1 "Isso não é só um CRUD com uma fila?" 🟢

O CRUD é a parte fácil. O que o serviço resolve é a semântica de "exatamente uma
escrituração" contra uma API externa com janela operacional, rate limit, resposta
assíncrona por lote e rejeição parcial — com dois pods, sem perder e sem
duplicar. Quem chama isso de CRUD não leu o modelo de estados.

### 10.2 "Por que não estender o serviço de duplicatas que já existe?" 🟡

Porque a fronteira é diferente: um serviço de gestão de duplicatas é dono da
duplicata no domínio; este é dono do **fato registral** — o que foi escriturado,
em qual registradora, com qual IUD. Misturar os dois faz a máquina de estados da
escrituração virar coluna de status no agregado da duplicata, que é exatamente de
onde vêm reenvios e duplicidade.

**Limite honesto:** é um serviço a mais para manter, e a discussão de fronteira é
legítima. O argumento técnico é o ack transacional; o argumento organizacional
(quem é dono, quem está de plantão) não foi resolvido.

### 10.3 "Quanto disso é código de IA?" 🟢

A arquitetura foi revisada adversarialmente e o registro está em
`docs/revisao-hardening.md`: cada crítica, a melhoria aplicada e o status.
Os itens que continuam 🔴 estão nesta FAQ com nome e mecanismo — o que não dá
para fazer com slideware.

---

## Índice do que NÃO defender

Levar para a sala como "conhecido e priorizado", nunca como resolvido:

| # | Item | Gravidade |
|---|---|---|
| 6.4 | Sem cancelamento/alteração de duplicata | lacuna funcional |
| 5.2 | Roteamento entre registradoras — questão jurídica, não técnica | bloqueia multi-registradora |
| 6.3 | Parcelamento e duplicata de serviço sem teste | não afirmar cobertura |
| 3.5 | `consultas-limite` global, sem SLA por registradora; saída do limbo é manual | calibração pendente |
| 7.1 | Chave estática sem identidade nem auditoria numa superfície com dado pessoal | endurecer antes de produção |
| 8.1 | Alerta de fila toca a noite toda na janela fechada | ruído operacional |
| 1.4 | Sem poda nem partição de `operacao`/`outbox` | problema de 2 anos |
| 1.5 | "Mais rápido que Kafka" sem benchmark | afirmação não medida |
| 2.4 | Janela estreita de reenvio se o POST passar do lease | residual, não eliminado |
| 8.4 / 8.5 | RTO/RPO de desenho, failover de AZ nunca exercitado | não medido |
| 9.2 | `CredenciaisGuard` sem teste | dívida assumida |

**Corrigidos nos rounds 2, 3 e 4** (não estão mais na lista): `/api/dlq` sem
autenticação, janela 423 condenando lote em voo, 4xx despejando o lote na DLQ,
reprocesso deixando operação órfã, poison-pill sem bisect, vazamento de advisory
lock, `consultar` fora do teto de taxa, janela fixa em vez de token bucket,
`lote_id` divergente enchendo a DLQ a cada retry, dupla escrituração engolida em
silêncio, idade da fila depois de reprocesso, e dois bugs latentes que só
apareceram ao escrever os testes (`CAST` de texto para `jsonb` em
`falhaPermanente`, e `Long?` que nunca era null); e, no quarto round, lote em
`ERRO` reenviado cegamente, bisect sem prazo estourando o lease, bisect disparando
em erro de rota, corrida de título derrubando a reconciliação inteira, consulta de
idade sem índice, e o guard de credencial que recusava `client-id` legítimo.
Registro em [`revisao-hardening.md`](revisao-hardening.md).
