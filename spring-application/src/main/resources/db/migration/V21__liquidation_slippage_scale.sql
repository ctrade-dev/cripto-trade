-- T53 F29: a politica de liquidacao aceita qualquer afastamento em (0, 1), mas NUMERIC(5, 4) arredondava
-- valores abaixo de 0.0001 para 0, a constraint recusava e o PATCH do runner respondia 500.
-- Mesma escala dos precos (8 casas); a constraint (> 0 e < 1) continua valendo.

ALTER TABLE strategy_runners
    ALTER COLUMN liquidation_max_slippage_percent TYPE NUMERIC(10, 8);
