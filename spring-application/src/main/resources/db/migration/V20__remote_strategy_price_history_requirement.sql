-- T69: historico de precos que a estrategia declarou em /meta. Nulo ate a primeira conferencia.
-- Mudar o requisito sem novo strategy_ref falha a conferencia.

ALTER TABLE remote_strategies ADD COLUMN price_history_ticks INTEGER NULL;
