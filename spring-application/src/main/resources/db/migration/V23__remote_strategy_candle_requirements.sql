-- T72: series de candles que a estrategia declara em /meta (contrato 2.1), gravadas na primeira conferencia junto com
-- price_history_ticks. JSON: [{"interval":"1h","count":200}]. Nula ate a primeira conferencia.
ALTER TABLE remote_strategies ADD COLUMN candle_requirements TEXT NULL;
