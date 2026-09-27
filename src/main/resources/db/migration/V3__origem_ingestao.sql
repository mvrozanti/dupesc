ALTER TABLE dlq DROP CONSTRAINT dlq_origem_check;

ALTER TABLE dlq ADD CONSTRAINT dlq_origem_check
    CHECK (origem IN ('OUTBOX','WEBHOOK','LEITOR','INGESTAO'));
