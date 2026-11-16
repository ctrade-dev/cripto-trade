-- T71: gatilho do HALT automatico no portfolio e na auditoria, para a retomada automatica decidir pelo tipo
-- e nao pelo texto do motivo. Nulo para mudancas manuais e para o que ja estava gravado.
ALTER TABLE portfolios
    ADD COLUMN safe_mode_trigger VARCHAR(40);

ALTER TABLE safe_mode_changes
    ADD COLUMN trigger VARCHAR(40);
