# Backlog — Integração Binance Testnet

Trilha de tarefas para habilitar operação com a API da Binance (testnet), pré-requisito para staging e soak test.

## Status das tarefas

| ID  | Título                          | Complexidade | Responsável | Status        |
|-----|---------------------------------|--------------|-------------|---------------|
| T1  | Transaction Recovery            | Alta         | Mateus      | Concluído     |
| T2  | Configuração de perfil testnet  | Média        | Codex       | Concluído     |
| T3  | HMAC-SHA256 Signing             | Alta         | Claude      | Concluído     |
| T4  | User Data Stream                | Alta         | Claude      | Concluído     |
| T5  | REST API Binance                | Alta         | Claude      | Concluído     |
| T6  | Exchange Symbol Filters         | Média        | Codex       | Concluído     |
| T7  | Boot Readiness real             | Média        | Codex       | Concluído     |
| T8  | Kill Switch                     | Média        | Codex       | Concluído     |
| T9  | Observabilidade Docker Compose  | Média        | Codex       | Concluído     |
| T10 | Otimização da suíte de integração | Média      | Codex       | Concluído     |
| T11 | Order Quantity Normalization Before Persist | Média | Codex  | Concluído     |
| T12 | Migração User Data Stream → WebSocket API Binance | Alta | Claude | Concluído |
| T13 | Order Placement via WebSocket API Binance | Alta | Claude | Concluído |
| T14 | Refactor Fronteiras Arquiteturais: Transporte e Negócio | Alta | Claude | Concluído |
| T15 | ExchangeOrderPort: Mover seleção de transporte para adapter-binance | Média | Claude | Concluído |
| T16 | ExchangeAdapterDescriptor: Centralizar capabilities por adapter | Média | Claude | Concluído |
| T17 | Loki: Agregação de Logs                                          | Média | Claude | Concluído |
| T18 | Trade Reconciliation Report                                      | Média | Claude | Concluído |
| T19 | Serializar conciliação por clientOrderId via striped lock        | Baixa | Codex  | Concluído |
| T20 | Preencher `executed_at` com timestamp da exchange no boot recovery | Baixa | Codex | Concluído |
| T21 | Active Error Reporting via Discord                               | Média | Claude | Concluído |
| T22 | Estudo e Desenho do Sistema de Monitoramento                    | Média | Claude | Concluído |
| T23 | Instrumentação: Gaps Críticos de Observabilidade                | Média | Claude | Concluído |
| T24 | Circuit Breaker nas Chamadas REST à Exchange                    | Média | Claude | Concluído |
| T25 | Safe Mode Automático                                            | Média | Claude | Concluído |
| T26 | Rastreamento End-to-End de um Sinal                             | Alta  | Claude | Concluído |
| T27 | Padronização do Retorno das Controllers (camada Spring)         | Média | Claude | Concluído |
| T28 | Camada agnóstica de adapter p/ consulta de saldo e histórico    | Média | Claude | Concluído |
| T29 | Implementar consulta de saldo na exchange                       | Média | Claude | Concluído |
| T30 | Implementar consulta de histórico de trades na exchange         | Média | Claude | Concluído |
| T31 | Unificação do recovery de runtime: cobertura de zombies (`PENDING`) | Baixa | Claude | Concluído |
| T32 | Ação `SHOULD_CANCEL` no contrato da estratégia                  | Alta  | Claude | Concluído |
| T33 | Fundação de cancelamento outbound (`OrderDispatchPort.cancel`)  | Baixa | Claude | Concluído |
| T34 | Preço de ordem dirigido pela estratégia (`limitPrice`)          | Média | Claude | Concluído |
| T35 | Estratégias de cenário para testnet                             | Média | Claude | Concluído |
| T36 | Safety buffer na reserva de capital (documentado vs. não implementado) | Média | Claude | Concluído |
| T37 | Normalização de preço side-aware (BUY floor / SELL ceiling)     | Média | Claude | Concluído |
| T38 | Testes E2E das estratégias de cenário                           | Média | Claude | Concluído |
| T39 | Auditoria design × código (capital, risco, concorrência)        | Média | Claude | Concluído |
| T40 | Revisão do contrato do strategy service antes do adapter        | Média | Claude | Concluído |
| T41 | Extração mínima da estratégia (port, proxy remoto, catálogo)    | Alta  | Claude | Concluído |
| T42 | Mailbox por runner e separação dos executores tick × ordem      | Alta  | Claude | Concluído |
| T43 | Circuit breaker de capital: drawdown 24h                        | Alta  | Claude | Concluído |
| T44 | Proteção de posição independente da estratégia                  | Alta  | Claude | Concluído |
| T45 | Ativação do Safe Mode e níveis `CANCEL_ALL` / `PANIC_SELL`      | Média | Claude | Concluído |
| T46 | Ativo da fee e quantidade líquida da posição                    | Alta  | Claude | Concluído |
| T47 | Um caminho por operação (limpeza pós-T46)                       | Baixa | Claude | Concluído |
| T48 | Proteção LIMIT na liquidação do PANIC_SELL                      | Média | Claude | Concluído |
| T49 | Ciclo de vida do runner por API (start, resume, terminate)      | Alta  | Claude | Concluído |
| T50 | Limites de risco pré-trade (posições, ordens, preço, utilização) | Média | Claude | Concluído |
| T51 | Observabilidade de capital, Safe Mode e lifecycle                | Baixa | Claude | Concluído |
| T52 | MOCK: cancelamento de ordem em preenchimento simulado            | Média | Claude | Concluído |
| T53 | Soak test em testnet (procedimento, critérios, execuções)        | Média | Claude | Pendente  |
| T54 | Catálogo dinâmico de estratégias remotas (cadastro via API)       | Alta  | Claude | Concluído |
| T55 | Operabilidade por IA (API segura, códigos, auditoria, estado consolidado) | Alta | Claude | Concluído |
| T56 | Demanda de market data: conexões e assinaturas automáticas      | Média | Claude | Concluído |
| T57 | Conferência de saldo por exchange (soma dos portfolios × conta)  | Média | Claude | Concluído |
| T58 | Preço para lotes abertos sem runner operando                    | Baixa | Claude | Concluído |
| T59 | Linha do tempo de eventos e relatório de rodada                 | Média | Claude | Concluído |
| T60 | Apps de estratégia para o soak (fora do ctrade)                 | Média | Claude | Concluído |
| T61 | SELL limitada ao lote do runner                                 | Baixa | Claude | Concluído |
| T62 | Capital do contexto pelo teto do runner                         | Baixa | Claude | Concluído |
| TD1 | Telemetria do canal USER_DATA (débito técnico)                  | Baixa | —      | Concluído |

## Ordem de execução

```
T1  Transaction Recovery (em andamento)
        ↓
T2  Testnet config          ← desbloqueia tudo
        ↓
T3  HMAC signing            ← base para T4 e T5
       ↓
T4  User Data Stream        T5  REST API
       ↓                         ↓
T6  Symbol Filters  ←────────────┘
       ↓
T7  Boot Readiness real
       ↓
T8  Kill Switch     T9  Observabilidade
       ↓
   staging começa
```

## Ordem de execução

```
T8  Kill Switch     T9  Observabilidade
       ↓
T10  Otimização da suíte de integração
       ↓
T11  Order Quantity Normalization      ← pré-requisito para produção
       ↓
T12  Migração User Data Stream → WS API  ← desbloqueio testnet
       ↓
T13  Order Placement via WS API          ← elimina REST para ordens
       ↓
T14  Refactor fronteiras: transporte     ← reconexão, reações de negócio, HttpClientPort
       ↓
T15  ExchangeOrderPort                  ← seleção de transporte no adapter-binance
       ↓
T16  ExchangeAdapterDescriptor          ← capabilities por adapter, repositório simplificado
       ↓
T17  Loki                               ← agregação de logs, alertas de ERROR
T18  Trade Reconciliation Report        ← auditoria fills Binance vs local (independente)
       ↓
   produção
```

## Ordem de execução — Observabilidade ativa

```
T22  Estudo do sistema de monitoramento   ← output: monitoring-spec.md (concluído)
       ↓
T21  Active Error Reporting (Discord)      T24  Circuit Breaker REST (independente)
       ↓
T23  Gaps de instrumentação (G1-G5)
       ↓
T25  Safe Mode automático      T26  Trace end-to-end (transactionId no MDC)
```

## Ordem de execução — API HTTP e consultas à exchange

```
T27  Padronização do retorno das controllers   ← envelope ApiError + @RestControllerAdvice (independente)
       (define o padrão de endpoint reusado por T29/T30)

T28  Camada agnóstica de consulta             ← fundação: ports + descriptor (hasTradeHistory/tradeHistory), use cases esqueleto
       ↓
T29  Consulta de saldo                T30  Consulta de histórico de trades
   (reusa queryAccountSnapshot)          (reusa BinanceTradeHistoryAdapter)
```

> T27 é independente, mas define o padrão de resposta que os endpoints opcionais de T29/T30
> devem seguir. T28 é pré-requisito de T29 e T30; ambas reusam infraestrutura Binance já
> existente, sem nova rota REST, e mantêm boot/recovery/reconciliação intactos.

## Ordem de execução — Ciclo de vida de ordem limite aberta

```
T31  Unificação do recovery de runtime (zombie PENDING via query-before-expire)   ← independente
       (estende RecoverStaleTransactions; não cria fluxo paralelo)

T33  Fundação OrderDispatchPort.cancel (verbo outbound, sem gatilho)   ← independente
       ↓
T32  Ação SHOULD_CANCEL na estratégia          ← decisão de negócio: puxar ordem VIVA antes do fill
   (reusa o verbo de cancelamento entregue pela T33)
```

> Separação de responsabilidades em três eixos:
> - **T31 (recovery/zombie)** — o único capital potencialmente preso é a **reserva órfã / zombie**
>   (`PENDING` sem `exchangeOrderId`). T31 cobre isso em runtime com **query-before-expire**: consulta
>   por `clientOrderId` antes de expirar — se a ordem estiver viva (ACK perdido), reconcilia; só libera
>   capital se a exchange não a conhecer. **Não cria fluxo novo**: o motor `RecoverTransactionStatusUseCase`
>   já aceita `PENDING`; basta estender o lote `RecoverStaleTransactions` (hoje só `SUBMITTED`/`PARTIAL`),
>   com a `MissingOrderPolicy` derivada do status (`PENDING`→`APPLY_TERMINAL_FALLBACK`, demais→`REGISTER_DLQ`).
>   Difere do boot (`step3ExpireZombies`), que expira local por ter carência de TTL + varredura única.
> - **T33 (verbo de cancelamento)** — só o mecanismo outbound `OrderDispatchPort.cancel`, sem gatilho.
> - **T32 (alpha)** — para uma ordem **viva**, o capital está *comprometido* (correto), não preso;
>   cancelá-la é decisão de estratégia. Nunca um TTL global que anule estratégias de horizonte longo.

> Tasks de correção/refino já entregues fora da trilha principal: T19 (striped lock na
> conciliação) e T20 (`executed_at` no boot recovery). TD1 permanece como débito técnico
> de telemetria do canal USER_DATA.

## Ordem de execução — Estratégias de cenário para testnet

```
T34  Preço de ordem dirigido pela estratégia (limitPrice no contrato)   ← fundação
       (habilita ordens que descansam / marketable de forma determinística)
       ↓
T35  Estratégias de cenário (resting-buy-cancel, filled-buy-resting-sell-cancel,
     immediate-round-trip, over-allocation-reject)   + teto de ciclos (maxCycles)
   (reusa SHOULD_CANCEL da T32; registro condicional por flag, isolado de produção)
       ↓
T38  Testes E2E por estratégia (sobe app → ativa com teto de ciclos → valida → sem excesso)
   (pré-requisito: comportamento do MOCK para ordens resting — override determinístico ou
    ensinar o MOCK a honrar marketable-vs-resting)
```

> Objetivo: testes repetíveis em testnet cobrindo cada ramo de
> `signal.evaluated.total{decision}` (BUY→CANCEL, fill→SELL→CANCEL, round-trip feliz,
> REJECTED_CAPITAL). T34 é pré-requisito dos cenários com ordem descansando/marketable;
> o cenário REJECTED_CAPITAL não depende de T34. Cenário de boot/recovery de ordem órfã
> fica como procedimento operacional (runbook), sem código novo.

## Débito — Divergência design vs. código no capital

```
T36  Safety buffer na reserva de capital   ← concluída (2026-10-16)
```

> **Resolução (2026-10-16):** §7.2.3 implementado (devolução do excedente no fill final de BUY) e
> **Opção B** decidida: sem buffer enquanto só houver `LIMIT`; a task que introduzir `MARKET` deve
> entregar o buffer junto. Documentos corrigidos; detalhes na spec da T36. O texto abaixo é o
> histórico da divergência.

> Divergência detectada ao especificar a T34: `docs/IMPLEMENTATION_GUIDE.md` §7.2.1–7.2.3 e o
> Javadoc de `CapitalRequest` descrevem um safety buffer (`quantity × price × 1.005`/`1.001`) que
> **não está implementado** — `TradeIntentFactory` reserva `quantity × price` sem multiplicador.
> T36 exige decidir entre **implementar** o buffer (com a devolução do excedente §7.2.3) ou
> **remover do design**; independente das estratégias, mas toca o mesmo `TradeIntentFactory` da T34.
>
> **Escopo ampliado por evidência (2026-09-18, e2e da T38):** a devolução do excedente §7.2.3
> **não existe**. A reserva é feita ao preço-limite e a baixa ao preço executado, então
> `quantity × (limite − executado)` fica preso em `reserved` para sempre — toda BUY `LIMIT` que
> executa melhor que o limite acumula resíduo. Não é hipótese nem artefato do mock: é capital
> preso em produção. Consequência para a decisão: §7.2.3 precisa ser implementado **tanto na
> Opção A quanto na B**; o buffer só muda o tamanho do excedente, não a necessidade de devolvê-lo.
> Mecânica completa em `.ai/tasks/T36-capital-reservation-safety-buffer.md`, seção "Evidência".
> A reconciliação documental de nomenclatura/campos do contrato (`TradingDecision` ↔
> `StrategyOutputDto`) é entregável da própria T34, não uma task separada.
>
> **T37** (normalização de preço side-aware) saiu do review do PR #132: `normalizePrice` usa HALF_UP
> (side-agnostic), podendo deslocar um preço-limite até meio tick contra o trader. Correção correta é
> BUY→floor / SELL→ceiling, mas exige propagar o lado ao port `OrderQuantityNormalizerPort` (afeta todas
> as ordens), fora do escopo da T34. Não é regressão — o HALF_UP já existia para preço de mercado.

## Ordem de execução — Prontidão para operação com estratégia out-of-process

```
T36  Devolução do excedente da reserva   T37  Preço side-aware      ← correção financeira (já mapeadas)
T46  Ativo da fee + quantidade líquida   ← BUY com fee em base hoje deixa a SELL do lote sem saldo na exchange
T39  Auditoria design × código            ← concluída; gerou T49, T50, T51
T49  Ciclo de vida do runner por API     ← concluída
       ↓
T40  Revisão do contrato (warm-up por runner, tickId, sem retry, idade da decisão)
       ↓
T41  Extração mínima: StrategyEvaluationPort + RemoteTradingStrategy + catálogo estático
       (código pode andar em paralelo à T42; ATIVAR em ambiente real só após T42 e T44)
T42  Mailbox por runner + executores separados (fill nunca espera tick)

T45  Ativação do Safe Mode + CANCEL_ALL / PANIC_SELL   ← pré-requisito de T25 e T43 (concluída)
T48  Proteção LIMIT no PANIC_SELL (política no cadastro do runner) ← concluída
       ↓
T43  Drawdown 24h + marcação a mercado (concluída; perda diária descartada)     T25  Safe Mode automático
T44  Proteção do lote (stop 5% MARKET; concluída)                   T24  Circuit breaker REST
       ↓
   perfil de produção + soak testnet com estratégia remota → canário em produção
```

> Origem: avaliação de prontidão de 2026-10-15. O ponto central é que a extração mínima (T40–T42)
> entra no caminho crítico, mas o loop autônomo de promoção (shadow, canário automático, máquina de
> estados de versão, backtest) **fica fora** do go-live.
>
> Achados que motivaram as tasks:
> - Nenhum limite baseado em perda existe no código; o design (BLUEPRINT §11.1.A, IG `DrawdownMonitor`)
>   não foi implementado → T43.
> - `Portfolio.setSafeModeStatus` não tem chamador: o Safe Mode não pode ser ativado nem manualmente,
>   e `CANCEL_ALL`/`PANIC_SELL` não têm efeito → T45 (a afirmação de ativação manual na T25 estava errada).
> - `Position.updateCurrentPrice` não tem chamador: `unrealizedPnl` é sempre zero, inclusive no
>   contexto enviado às estratégias → T43 (marcação a mercado) e T40 (contrato).
> - Com a estratégia fora do processo, indisponibilidade = HOLD, o que impede qualquer saída de
>   posição → T44.
> - Ticks e fills dividem o `messageProcessingExecutor` (2–4 threads, `CallerRunsPolicy`); o
>   Actor/Mailbox do IG §12 não existe → T42.
> - Warm-up do SMA é compartilhado entre runners e o contrato repete a chave só por `strategyRef` → T40.
> - IG/Blueprint descrevem como decididas várias coisas que não estão no código (mesma classe da T36) → T39.
>
> **T52** (MOCK: cancelamento durante preenchimento simulado) saiu da T48: o MOCK publica fills depois
> do `CANCELED` e debita o saldo da execução inteira. Não afeta exchange real; afeta E2E que cancelam
> ordem marketable.
>
> **Ideia em espera (2026-10-20):** MOCK como app externo, no mesmo papel do app de estratégia. Adiado
> para depois do soak (T53): hoje os E2E controlam o MOCK dentro do processo (preço de referência,
> overrides, reset) e um container a mais na CI traz novos pontos de instabilidade.
>
> Fora desta trilha, ainda sem task: perfil `application-prod.yml` e gestão de segredos
> (`GO_LIVE_CHECKLIST` itens 8–9), harness de backtest/modo shadow e limpeza de documentação
> desatualizada.

## Critério de pronto da trilha

- Aplicação conecta na Binance testnet via WebSocket e recebe price updates.
- Ordens são submetidas via REST com assinatura válida.
- Fills, cancels e rejects chegam via User Data Stream e são processados corretamente.
- Boot recovery consulta ordens abertas na testnet via REST.
- Nenhuma ordem é enviada violando filtros de símbolo (stepSize, minNotional, tickSize).
- Kill switch interrompe todos os runners sem efeito financeiro residual.
- Métricas visíveis no Grafana durante staging.
- Logs de ERROR agregados no Loki e acessíveis por `clientOrderId` no Grafana.
- Relatório de reconciliação mostra `binanceOnly=0` e `localOnly=0` após sessão sem falhas.
