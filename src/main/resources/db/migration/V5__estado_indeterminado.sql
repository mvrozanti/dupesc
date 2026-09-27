ALTER TABLE operacao DROP CONSTRAINT operacao_estado_check;

ALTER TABLE operacao ADD CONSTRAINT operacao_estado_check
    CHECK (estado IN ('PENDENTE','EM_ENVIO','ENVIADO','REGISTRADO','RECUSADO','FALHA_PERMANENTE','INDETERMINADO'));

CREATE INDEX idx_operacao_presas ON operacao (consultas) WHERE estado = 'ENVIADO';
