ALTER TABLE operacao ADD COLUMN registradora TEXT NOT NULL DEFAULT 'CERC';

CREATE INDEX idx_operacao_recon ON operacao (registradora, enviado_em)
    WHERE estado = 'ENVIADO' AND lote_id IS NOT NULL;
