# FAQ — objeções à arquitetura do dupesc

Dadas implementações corretas dos adapters B3 e CERC, o resto da solução está
pronto. Abaixo, as objeções esperadas e as respostas.

## Escala

**Como pode não escalar?**
Escala horizontalmente: N pods competem pelas linhas do `outbox` via
`FOR UPDATE SKIP LOCKED` — sem coordenador, sem rebalance de consumer group. O
gargalo é a CERC (80 req/s por token), não o nosso código; o Postgres aguenta
ordens de grandeza acima do volume (< 500 mil/dia).

**E se o Postgres virar gargalo?**
Não vira nesse volume. Se um dia virar, a fila é uma tabela — a evolução é
particionar/shardar o `outbox`, ou seja, SQL, não uma arquitetura nova.

**E o autoscaling não aumenta pressão sobre o gargalo?**
Não, porque o rate limiter é distribuído (token bucket no Postgres, teto global
de 80 req/s) — subir pod não aumenta o número de requisições contra a CERC.

## Consistência

**Como garante que não perde duplicata?**
Por construção: a ingestão grava `intencao + operacao + outbox` na mesma
transação antes de qualquer chamada externa. Se o pod morre no meio, a linha
continua na fila e a reconciliação retoma.

**Como garante que não duplica?**
Unique constraint em `operacao_legado_id`, `referencia_externa`, `operation_id`
e `(registradora, event_id)` + guarda de estado em toda transição. Webhook
repetido processa uma vez; envio reclamado por um pod só.

**É consistência transacional forte entre sistemas?**
Não — a CERC é externa e assíncrona, ninguém tem 2PC aqui. O que se entrega é
entrega garantida + idempotência (effectively-once), não atomicidade distribuída.

## Performance

**É mais rápido que Kafka?**
Mesmo teto de throughput (o gargalo é a CERC), latência menor (1 transação +
HTTP, sem 2× produce/consume no caminho crítico) e p99 estável (sem rebalance).
"Mais performático" = chega no teto da CERC gastando menos, não processa mais que Kafka.

## Multi-registradora

**Como podem reclamar que só tem CERC?**
Não podem no nível arquitetura. `RegistradoraPort`, a coluna `operacao.registradora`
e o `RegistradoraRegistry` já existem; B3 e Núclea entram como pacotes de cliente
(o mesmo que trocar o IP de um endpoint). O que falta é o corpo dos adapters, que é
escrita, não desenho.

**E a regra de roteamento (qual operação vai pra qual registradora)?**
É dado/config, não arquitetura. A coluna já existe; a regra entra num único ponto
de ingestão, quando o jurídico fechar a resposta.

**O `titulo.duplicata_id UNIQUE` não bloqueia registrar a mesma duplicata em duas registradoras?**
Já foi tratado: `UNIQUE (registradora, duplicata_id)` para histórico + índice único
parcial `(duplicata_id) WHERE ativo` — no máximo um título ativo, sem engolir a
dupla escrituração.

## Fan-out e eventos

**E se precisar publicar eventos para outros sistemas?**
CDC no `outbox` vira fonte de eventos; Kafka entra como saída, pendurado no que já
existe, sem tocar o núcleo nem o caminho crítico.

## Operação

**Banco único não é ponto único de falha?**
RDS multi-AZ + PITR. E por ser também a fila, não há um segundo sistema para
sincronizar — uma fonte de falha a menos, não a mais.

**O que ainda pode derrubar?**
Nada interno. Só o externo: a janela operacional e o rate limit da CERC, e a
questão jurídica de roteamento. Nenhum é da arquitetura.
