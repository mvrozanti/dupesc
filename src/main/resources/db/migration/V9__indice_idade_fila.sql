CREATE INDEX idx_outbox_idade ON outbox ((coalesce(reenfileirado_em, criado_em)))
    WHERE status = 'PENDENTE';
