-- T74: metadata da decisao da estrategia que originou a ordem (ex.: exitKind, stop), em JSON, para auditoria e para
-- comparar o staging com o backtest. Null nas ordens anteriores e nas do proprio ctrade (liquidacao).
ALTER TABLE transactions ADD COLUMN strategy_metadata TEXT;
