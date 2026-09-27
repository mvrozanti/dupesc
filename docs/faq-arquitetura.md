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

**Limite honesto:** o pior caso do bisect é O(2n) chamadas HTTP quando *todo*
item do lote é ruim. Isso é autolimitado pelo teto de taxa (o excedente
reagenda), mas é uma rajada.

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

Existe um token bucket em tabela (`rate_limit`) que dá teto global, não por pod —
o limiter in-process do resilience4j sozinho permitiria N×80.

**Limite honesto, dois furos:**

- O limiter global guarda só `enviar`. O `consultar` da reconciliação — que é o
  caminho de maior volume depois de uma indisponibilidade — **não passa pelo
  limiter**. Envio e consulta somados podem dobrar o teto.
- É janela fixa de 1s, não token bucket: 80 no fim de uma janela e 80 no começo
  da seguinte são 160 req num intervalo de milissegundos.

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

**Limite honesto:** se o `pg_advisory_unlock` falhar, a conexão volta para o pool
**ainda com o lock**, e como o pool não fecha a conexão física, o lock só cai
quando o Hikari retira a conexão por `maxLifetime` (30 min default). Nessa
janela, nenhum pod reconcilia e nada alerta. Pior: se a mesma conexão for
reusada, `pg_try_advisory_lock` é reentrante e o contador sobe — um `unlock` não
solta. Falta `pg_advisory_unlock_all` no checkout da conexão.

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

### 5.3 "O modelo suporta a mesma duplicata em duas registradoras?" 🔴

Não. `titulo.duplicata_id` é `UNIQUE`, então uma duplicata não pode ter dois
IUDs — e o `ON CONFLICT DO NOTHING` sem target faz a segunda inserção **falhar
em silêncio**, sem erro. Pré-requisito da multi-registradora: trocar por
`UNIQUE (registradora, duplicata_id)`. Está registrado como dívida.

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

**Limite honesto:** o guard casa pelo prefixo `poc-`. Qualquer outro valor fraco
passa, e a senha do banco não é verificada.

### 7.3 "O HMAC do webhook protege contra replay?" 🟡

Não diretamente — a assinatura não cobre timestamp nem nonce. O que barra replay
é o dedup por `event_id`. Em produção a recomendação é mTLS + IP allowlist, com
alerta na taxa de 401 (`dupe.webhook.401`).

### 7.4 "Um webhook assinado pode marcar qualquer coisa como registrada?" 🟢

Não mais: `validarLote` confere o `lote_id` do evento contra o
`operacao.lote_id` antes de aplicar. Antes, qualquer corpo assinado podia marcar
qualquer operação como `REGISTRADO` com IUD arbitrário.

**Limite honesto:** a divergência de `lote_id` levanta exceção, a transação
inteira do webhook reverte — **inclusive a linha de dedup** — e o evento vai para
a DLQ. Então um único item com `lote_id` defasado descarta o webhook inteiro,
inclusive os itens legítimos, e cada retry da CERC gera uma nova linha na DLQ.

---

## 8. Operação

### 8.1 "Quando algo falha, quem descobre e como?" 🟡

Gauges Micrometer em `/actuator/prometheus`: `dupe.fila.pendente`,
`dupe.fila.idade_ms`, `dupe.dlq.abertos`, contador `dupe.webhook.401`. Alertas:
fila > 10k, idade > 15 min, DLQ > 0.

**Limite honesto:** `dupe.fila.pendente` conta `PENDENTE` incluindo itens
reagendados para o futuro. Durante as 12 horas de janela fechada da CERC, o
backlog inteiro é `PENDENTE` e o alerta de fila toca a noite toda. E um item
reprocessado da DLQ mantém o `criado_em` original, então a idade dispara
permanentemente depois de qualquer reprocesso.

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

**Limite honesto:** continuam sem teste o rate limiter em tabela, o
`CredenciaisGuard`, o vazamento de advisory lock (4.4) e o caminho de
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

### 10.3 "Quem mantém isso quando você sair?" 🔴

Pergunta aberta. Hoje: 2.900 linhas de Kotlin, sem comentário por decisão de
estilo, com o "por quê" no README e em `docs/`. Não há segundo mantenedor nem
runbook de plantão.

### 10.4 "Quanto disso é código de IA?" 🟢

A arquitetura foi revisada adversarialmente e o registro está em
`docs/revisao-hardening.md`: cada crítica, a melhoria aplicada e o status.
Os itens que continuam 🔴 estão nesta FAQ com nome e mecanismo — o que não dá
para fazer com slideware.

---

## Índice do que NÃO defender

Levar para a sala como "conhecido e priorizado", nunca como resolvido:

| # | Item | Gravidade |
|---|---|---|
| 4.4 | Advisory lock de sessão pode vazar por até 30 min | recuperação para sem alerta |
| 4.1 | `consultar` fora do teto global de taxa | 2× o limite da registradora |
| 6.4 | Sem cancelamento/alteração de duplicata | lacuna funcional |
| 5.2 / 5.3 | Roteamento (jurídico) e `titulo.duplicata_id UNIQUE` | bloqueia multi-registradora |
| 3.5 | `consultas-limite` global, sem SLA por registradora; saída do limbo é manual | calibração pendente |
| 7.1 | Chave estática sem identidade nem auditoria numa superfície com dado pessoal | endurecer antes de produção |
| 1.4 | Sem poda nem partição de `operacao`/`outbox` | 🕐 problema de 2 anos |
| 2.4 | Janela estreita de reenvio se o POST passar do lease | residual, não eliminado |
| 8.4 | RTO/RPO de desenho, sem game day | não medido |
| 9.2 | Rate limiter, guard de credencial e 4.4 sem teste | dívida assumida |

**Corrigidos no segundo round** (não estão mais na lista acima): `/api/dlq` sem
autenticação, janela 423 condenando lote em voo, 4xx despejando o lote na DLQ,
reprocesso deixando operação órfã, poison-pill sem bisect, e o `CAST` de texto
para `jsonb` que quebrava todo caminho de falha permanente. Registro em
[`revisao-hardening.md`](revisao-hardening.md).
