# Documentação de Arquitetura: Gestão de Capital e Execução (CTrade) v18

> **Escopo V1 — Spot Only:** Esta versão opera exclusivamente em modo spot (custódia total, sem alavancagem). O design de alavancagem e margem para futuros está preservado em [`LEVERAGE_DESIGN.md`](LEVERAGE_DESIGN.md) para referência futura (V2).

---

## Sumário

1. [Visão Geral](#1-visão-geral)
2. [Hierarquia de Domínios](#2-hierarquia-de-domínios)
   - A. Portfolio (O Orquestrador)
   - B. StrategyRunner (O Gerenciador de Execução e Contabilidade)
   - C. TradeStrategy (O Motor de Sinais)
3. [Modelo de Dados e Relacionamentos](#3-modelo-de-dados-e-relacionamentos)
   - 3.1 Considerações
4. [Fluxo de Execução e Materialização (Ciclo de Vida do Sinal)](#4-fluxo-de-execução-e-materialização-ciclo-de-vida-do-sinal)
   - A. Geração do Sinal (TradeStrategy)
   - B. Materialização da Decisão (StrategyRunner)
   - C. Reserva e Execução (Portfolio & Exchange)
   - D. Reconciliação e Execuções Parciais (Matching & Update)
5. [Fluxos de Falha e Resiliência (Fluxo Reverso)](#5-fluxos-de-falha-e-resiliência-fluxo-reverso)
   - A. Negação de Margem (Portfolio Reject)
   - B. Rejeição da Exchange (Order Rejected)
   - C. Expiração ou Cancelamento (Order Expired/Canceled)
6. [Ciclo de Vida da Transação e Resiliência](#6-ciclo-de-vida-da-transação-e-resiliência)
   - A. Mapa de Estados da Transaction
   - B. Matriz de Recuperação e Falhas (Resiliência)
   - C. Responsabilidades de Monitorização
   - D. Protocolo de Recuperação Pós-Crash (Boot Sequence)
7. [Aggregate Boundaries (DDD)](#7-aggregate-boundaries-ddd)
   - A. Aggregate Root: Portfolio
   - B. Aggregate Root: StrategyRunner
   - C. Comunicação entre Agregados: Padrão Híbrido
8. [Governança de Locks e Concorrência](#8-governança-de-locks-e-concorrência)
   - 8.1 Ciclo de Vida do Lock
   - 8.2 Prevenção de Deadlocks
9. [Contabilidade e Precisão Financeira](#9-contabilidade-e-precisão-financeira)
    - 9.1 Política de Taxas (Fees)
    - 9.2 Precisão Decimal e Arredondamento (Rounding Policy)
    - 9.3 Metodologia de Cálculo e Exposição de Preço Médio
10. [Resiliência e Protocolo de Envio](#10-resiliência-e-protocolo-de-envio)
    - 10.1 Protocolo de Idempotência e Resiliência de Envio
    - 10.2 Gestão de Cancelamento de Ordens Parciais
    - 10.3 Filosofia de Reconciliação e Fonte da Verdade
    - 10.4 Escalabilidade de Conectividade (ExchangeAdapter)
11. [Gestão de Risco e Defesa de Capital](#11-gestão-de-risco-e-defesa-de-capital)
    - 11.1 Circuit Breaker Global e Defesa de Capital
    - 11.2 Alocação de Capital e Governança de Concorrência
12. [Modelo de Concorrência e Processamento do Runner](#12-modelo-de-concorrência-e-processamento-do-runner)
    - A. Processamento Sequencial (Strict Serial)
    - B. Gestão de Acúmulo e Drop Policy
    - C. Processamento Durante Ordens em Voo
    - D. Isolamento e Fault Containment
13. [Orquestração do Ciclo de Vida do Runner](#13-orquestração-do-ciclo-de-vida-do-runner)
    - A. Estados do Ciclo de Vida
    - B. Criação e Unicidade
    - C. Encerramento com Posições Abertas (Graceful Shutdown)

---

## 1. Visão Geral

O sistema utiliza uma arquitetura baseada em **Injeção de Contexto**. A lógica de decisão (Estratégia) é separada da lógica de custódia e execução (Runner), permitindo flexibilidade total tanto na entrada de ordens quanto na contabilização de saída.

## 2. Hierarquia de Domínios

### A. Portfolio (O Orquestrador)

* **Responsabilidade:** Gestão do `GlobalBalance`, alocação de margem e **roteamento de eventos/transactions** para os Runners proprietários.

### B. StrategyRunner (O Gerenciador de Execução e Contabilidade)

* **Natureza:** Stateful. Une a Estratégia ao Ativo e gerencia o ciclo de vida das operações (Pending → Submitted → Filled/Partial) através de duas políticas fundamentais (**Execution** e **Accounting**).
* **Gestão de Ordens Pendentes:** Além das `Positions`, o Runner mantém o rastro de ordens enviadas mas ainda não executadas. Ele é o responsável por garantir que toda margem "reservada" no Portfolio seja convertida em uma `Position` ou devolvida em caso de cancelamento, rejeição ou expiração.

#### B.1 Execution Policy (Regra de Entrada/Exposição)

Define como o Runner reage a novos sinais da estratégia:

* **Modo Single:** Ignora novos sinais se já houver qualquer posição aberta. Garante "um tiro por vez".
* **Modo Hedging/Multi:** Permite a abertura de múltiplas `Positions` independentes e simultâneas.
* **Modo Netting/Scaling:** Permite que novos sinais aumentem ou reduzam o volume de uma posição existente.

#### B.2 Accounting Policy (Regra de Saída/Contabilidade)

Define como as `Transactions` de fechamento são casadas com as de abertura:

* **FIFO (First-In, First-Out):** Padrão para Netting.
* **Specific Match:** Padrão para Hedging (vincula a saída a um ID de posição específico).
* **LIFO (Last-In, First-Out):** Alternativa para estratégias específicas.

### C. TradeStrategy (O Motor de Sinais)

* **Natureza:** Stateless. Analisa o mercado e emite `TradeSignal`.

---

## 3. Modelo de Dados e Relacionamentos

| Contexto (Aggregate) | Entidade / VO      | Relacionamento     | Responsabilidade / Atributo Principal                                     |
|----------------------|--------------------|--------------------|---------------------------------------------------------------------------|
| **Portfolio**        | `GlobalBalance`    | Atributo Root      | Saldo consolidado (Available, Reserved, Realized) e auditoria de capital. |
| **Portfolio**        | `MarginAccount`    | 1 : N (Accounts)   | Controle de capital reservado por Exchange/sub-conta (custódia spot).     |
| **StrategyRunner**   | `Position`         | 1 : N (Lotes)      | Exposição líquida, preço médio e PnL acumulado da estratégia.             |
| **StrategyRunner**   | `Transaction`      | 1 : N (Matches)    | Ciclo de vida da ordem (Pending → Submitted → Final).                     |
| **StrategyRunner**   | `TransactionMatch` | N : N (Lots/Trans) | Vinculação definitiva entre ordens de compra e venda (Realização).        |
| **StrategyRunner**   | `ExecutionPolicy`  | Value Object       | Regra de entrada: **Single**, Multi ou Netting.                           |
| **StrategyRunner**   | `AccountingPolicy` | Value Object       | Regra contábil: **FIFO**, LIFO ou Specific Match.                         |
| **StrategyRunner**   | `Fee`              | Value Object       | Custo operacional (valor, asset e tipo) imutável por execução.            |
| **Shared / Cross**   | `ClientOrderId`    | Value Object       | Chave única de roteamento e idempotência (padrão de 32/36 chars).         |

### 3.1 Considerações

* **Nota sobre Liquidação de Taxas (Fee Settlement):** O `AvailableBalance` é o garantidor final de todas as taxas operacionais. Caso uma taxa seja cobrada pela Exchange em um ativo secundário (ex: BNB, FTT), o **Portfolio** realizará uma conversão sintética imediata para a moeda base (ex: USDT) no momento do `TransactionMatch`. O valor equivalente será deduzido do `AvailableBalance`, garantindo que o saldo local reflita o poder de compra real. Em caso de falha na precificação, o débito é registrado na `DustAccount` como Dívida Técnica de Ativo para reconciliação posterior (ver **Seção 9.1** e **9.2.C**).

---

## 4. Fluxo de Execução e Materialização (Ciclo de Vida do Sinal)

O processo de transformação de uma análise técnica em uma operação financeira segue um pipeline rigoroso de materialização e validação:

### A. Geração do Sinal (TradeStrategy)

A estratégia processa os dados de mercado e gera um **`StrategyOutputDto`** contendo o `TradeSignal` de domínio. Este objeto é o "contrato de intenção" (Buy/Sell/Hold) que contém a quantidade e, opcionalmente, um `targetLotId`.

* **Ação:** Define se a decisão é `SHOULD_BUY`, `SHOULD_SELL` ou `SHOULD_HOLD`.
* **Metadados:** Carrega o `reasoning` (motivação) e `confidence` (confiança de 0.0 a 1.0), essenciais para auditoria.
* **Quantidade:** Especifica a `quantity` absoluta (ex: 0.05 BTC), permitindo que a estratégia sugira o tamanho exato da mão.

### B. Materialização da Decisão (StrategyRunner)

O Runner recebe o DTO e prepara o estado interno antes de qualquer comunicação externa:

* **Filtro de Exposição (Execution Policy):** O Runner valida o sinal contra seu estado atual (ex: se for *Single* e já houver posição aberta, o sinal é abortado aqui).
* **Criação da Transação PENDING:** O Runner instancia uma `Transaction` com status `PENDING`. Isso materializa a intenção em um registro persistente com preço e quantidade estimados.
* **Roteamento de Lote:** O Runner verifica o `targetLotId` contido no DTO. Se preenchido (UUID), o Runner direciona a ordem para o fechamento de um lote específico (Hedging/Specific Match); se nulo, delega à `AccountingPolicy` (ex: FIFO).
* **Locking (Matching Provisório):** Se for uma ordem de venda (`SHOULD_SELL`), o Runner identifica os lotes de compra (via `targetLotId` ou FIFO) e os marca como **"em processo de fechamento"**. Isso impede que outros sinais concorrentes tentem liquidar os mesmos lotes.
* **Vínculo de Vida:** Todo lock está obrigatoriamente vinculado a uma `TransactionId`. Não existem locks órfãos.
* **Atomicidade:** O processo de busca e marcação de locks ocorre em uma **transação isolada de banco de dados** (Pessimistic Locking no nível da linha do lote) para evitar que dois sinais concorrentes selecionem o mesmo lote no mesmo milissegundo.
* **Sanidade:** Executa o método `validateTradeParams` para garantir que a `confidence` e a `quantity` estejam dentro dos parâmetros operacionais permitidos.

### C. Reserva e Execução (Portfolio & Exchange)

1. **Capital Request:** O Runner solicita ao **Portfolio** a reserva de capital baseada na transação `PENDING`. O Portfolio move o saldo de *Available* para *Reserved*. A fórmula spot é: `Capital_Requerido = Quantidade * Preço_Estimado`.
2. **Order Dispatch:** Com o estado salvo localmente e a margem garantida, o Runner despacha a ordem para a Exchange utilizando o protocolo de identificação única:
   * **Identificação Única (`clientOrderId`):** Utiliza o formato `{v}{version}{r}{runner_short}{t}{timestamp}{s}{sequence}{type}_{transaction_uuid}` (ex: `v1r01ft1700000000000s001N_8da233214f11b12a`). O prefixo de versão (`v1`) permite que o parser de reconciliação identifique o layout do ID, garantindo compatibilidade com IDs antigos caso o formato evolua.
   * **Validação de Versão (Regra Geral do Parser):** Antes de interpretar qualquer campo do `clientOrderId`, o parser deve extrair e validar o prefixo de versão. Se a versão for desconhecida, o parser deve abortar o processamento e encaminhar a mensagem para a DLQ, evitando roteamento incorreto ou corrupção de estado por interpretação de layout incompatível.
   * **Validação de Segurança:** O Portfolio atua como *guardrail*, validando se o `runner_short` pertence ao Runner solicitante antes do envio para evitar *spoofing* entre estratégias.
3. **Status SUBMITTED:** Ao receber a confirmação de recebimento da Exchange (OrderID), o Runner atualiza a transação local para o estado `SUBMITTED`, confirmando que a ordem está "em voo".
4. **Transaction Routing (Callback Assíncrono):** O Listener de ordens da exchange captura a execução e a entrega ao **Portfolio**, que atua como o ponto de entrada único. Ele realiza o *parsing* do prefixo do `clientOrderId` para identificar o **StrategyRunner** proprietário e rotear a `Transaction` via evento ou comando, eliminando a necessidade de busca exaustiva no banco de dados e permitindo que o Runner inicie sua contabilidade interna imediatamente.

### D. Reconciliação e Execuções Parciais (Matching & Update)

O Runner altera o status da transação para `FILLED` (ou gerencia o estado `PARTIAL`) e inicia o processamento contábil seguindo estas diretrizes:

1. **Tratamento de Partial Fills (Execuções Parciais):**
   * **Contabilidade Incremental:** Para cada evento de execução parcial recebido da Exchange, o Runner registra um `TransactionMatch` proporcional. Isso garante que o PnL seja atualizado em tempo real, mesmo antes da ordem ser 100% completada.
   * **Ajuste de Margem Proporcional:** A cada fatia executada, o Runner notifica o **Portfolio** para converter o `ReservedBalance` correspondente em `RealizedBalance`.
2. **Efetivação do Match:**
   * **Consolidação de Lotes:** Converte os *locks* provisórios criados no passo 4.B em fechamentos definitivos.
   * **Identificação de Lote:** Caso o `targetLotId` tenha sido fornecido no sinal original, o match é vinculado obrigatoriamente a este ID; caso contrário, a `AccountingPolicy` (ex: FIFO) determina o destino.
3. **Sincronização Financeira e PnL:**
   * **Atualização de Saldo:** O Runner envia o sinal de "Confirmação de Execução" ao Portfolio com o preço e `Fees` reais.
   * **Modelagem de Fees:** As taxas são tratadas como **Value Objects** imutáveis anexados a cada `TransactionMatch`. O sistema deve suportar taxas em ativos diferentes do par operado (ex: taxas em BNB para trades de BTC).
   * **Cálculo de PnL Realizado:** O domínio opera exclusivamente com **PnL Líquido**. O lucro ou prejuízo é calculado imediatamente após o match, subtraindo-se as `Fees` da execução (ou da fatia, em caso de `PARTIAL`), e é refletido na `Position` da estratégia para atualizar o PnL acumulado.
4. **Tratamento de Exceções e Auditoria:**
   * **Dead Letter Queue (DLQ):** Caso o ID retornado pela Exchange seja inválido ou o Runner proprietário não seja localizado pelo Portfolio, a transação é enviada para a DLQ para auditoria manual imediata.
   * **Estorno de Margem Remanescente:** Se uma ordem for parcialmente preenchida e o restante cancelado, o Runner instrui o Portfolio a estornar apenas a margem da fatia não executada, garantindo a integridade do saldo.

---

## 5. Fluxos de Falha e Resiliência (Fluxo Reverso)

Para garantir a integridade do `GlobalBalance` e das `Positions`, o sistema implementa protocolos de rollback e limpeza:

### A. Negação de Margem (Portfolio Reject)

Se o `Portfolio` negar o `Capital Request` (por falta de saldo ou violação de risco global):

* **Ação:** O `StrategyRunner` descarta o sinal da estratégia imediatamente.
* **Notificação:** Um evento de `InsufficientFunds` ou `RiskViolation` é gerado para o log, e a `TradeStrategy` permanece em modo de espera pelo próximo ciclo.

### B. Rejeição da Exchange (Order Rejected)

Se a margem foi autorizada pelo Portfolio, mas a Exchange rejeitou a ordem (ex: ativo em leilão, erro de limite):

* **Ação:** O `StrategyRunner` notifica o `Portfolio` sobre a falha.
* **Rollback:** O `Portfolio` realiza o **Estorno da Margem** que havia sido pré-alocada para aquela operação.
* **Cleanup:** Nenhuma `Position` é criada ou alterada no banco de dados.

### C. Expiração ou Cancelamento (Order Expired/Canceled)

Para ordens que foram enviadas mas não executadas (Limit Orders):

* **Cleanup de Matches:** O `StrategyRunner` identifica que a `Transaction` não será concluída e invalida qualquer tentativa de "Match" pendente.
* **Liberação de Margem:** O Runner informa ao `Portfolio` que a ordem foi cancelada, permitindo que a margem reservada retorne ao `AvailableBalance`.

---

## 6. Ciclo de Vida da Transação e Resiliência

O sistema utiliza uma máquina de estados rigorosa para garantir que cada centavo reservado seja rastreável, mesmo em caso de falha sistêmica.

### A. Mapa de Estados da Transaction

| Estado                 | Significado                                  | Ação de Margem (Portfolio)                                                                                                                    |
|------------------------|----------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------|
| **PENDING**            | Intenção criada; Lotes travados localmente.  | **Reserva:** Saldo movido para *Reserved*.                                                                                                    |
| **SUBMITTED**          | Ordem aceita pela Exchange (OrderID gerado). | **Mantém:** Saldo continua em *Reserved*.                                                                                                     |
| **PARTIAL**            | Execução parcial (fatia).                    | **Conversão:** Parte da margem move de *Reserved* para *Realized*. Dedução: `Fees` são subtraídas do `AvailableBalance`.                      |
| **FILLED**             | Execução 100% concluída.                     | **Efetivação:** Toda margem move para *Realized*. **Liquidação**: *Realized* retorna ao `AvailableBalance` com PnL líquido e taxas aplicados. |
| **CANCELED / EXPIRED** | Ordem interrompida ou vencida.               | **Estorno:** Margem remanescente volta para *Available*.                                                                                      |
| **REJECTED**           | Exchange recusou a ordem de imediato.        | **Estorno:** Margem total volta para *Available*.                                                                                             |

### B. Matriz de Recuperação e Falhas (Resiliência)

| Evento de Falha       | Responsável pela Detecção | Ação de Recuperação                                                                                                                               |
|-----------------------|---------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------|
| **Margem Negada**     | Portfolio                 | Interrompe o fluxo no `Capital Request`. O sinal é descartado antes do envio.                                                                     |
| **Ordem Rejeitada**   | Runner (via Portfolio)    | Transação vai para `REJECTED`. Solicita estorno imediato de margem ao Portfolio.                                                                  |
| **Timeout (No Fill)** | StrategyRunner            | Runner envia comando de cancelamento à Exchange e limpa intenção de match/locks.                                                                  |
| **Queda de Conexão**  | Portfolio (Core)          | Ao reconectar, o Listener sincroniza ordens pendentes. O Portfolio roteia as atualizações para os Runners.                                        |
| **Crash do Sistema**  | Portfolio + Runners       | No reinício, Runners buscam ordens em estado `PENDING/SUBMITTED`. Reconciliam com a Exchange; se não encontradas, limpam estado e liberam margem. |

### C. Responsabilidades de Monitorização

* **Watchdog de Timeouts:** O **StrategyRunner** é o dono do relógio. Se uma ordem não transita de `SUBMITTED` para `FILLED` no tempo previsto pela `ExecutionPolicy`, o Runner dispara o fluxo de cancelamento.
* **Integridade de Locks:** Caso uma transação falhe (Rejected/Canceled), o Runner é responsável por desbloquear os lotes de compra que estavam "travados" para aquela venda.

### D. Protocolo de Recuperação Pós-Crash (Boot Sequence)

Quando o sistema reinicia, ele deve entrar em **"Safe Mode"** antes de retomar as operações. O processo segue uma hierarquia de baixo para cima (Infra → Portfolio → Runner):

#### 1. Orquestração da Recuperação

O processo é **descentralizado**. O `Portfolio` inicializa o estado do caixa, mas cada `StrategyRunner` é responsável por reconciliar suas próprias operações "em voo".

#### 2. Passo a Passo do Boot:

1. **Portfolio - Sincronização de Caixa:** O Portfolio conecta-se às Exchanges para atualizar o `GlobalBalance` (Available/Total). Ele assume que qualquer margem em `Reserved` no banco de dados deve ser reavaliada pelos Runners.
2. **Runner - Saneamento de Locks Órfãos:** Antes de reconciliar ordens ativas, o Runner busca todas as transações `PENDING` vinculadas ao seu `RunnerId` que **não possuem** um `ExchangeOrderId` associado (indicando que o crash ocorreu entre a persistência do lock e o envio à Exchange). Essas transações são classificadas como **Zumbis** e sofrem rollback automático: o `ReservedBalance` associado é liberado no Portfolio, a transação é marcada como `FAILED_ON_CRASH` e o lock é removido.
3. **Runner - Identificação do Limbo:** Cada Runner busca no seu `StrategyRunnerRepository` todas as transações restantes nos estados `PENDING`, `SUBMITTED` ou `PARTIAL`.
4. **Runner - Consulta à Exchange:**
   * O Runner usa o `clientOrderId` (que contém o UUID da transação) para consultar o status real na Exchange.
   * **Cenário A (Ordem existe):** O Runner atualiza o status local (ex: de `SUBMITTED` para `FILLED` ou `CANCELED`) e notifica o Portfolio para converter/estornar a margem.
   * **Cenário B (Ordem NÃO existe):** Se a Exchange não reconhece o ID, o Runner assume que o sistema caiu antes do envio ou a Exchange também reiniciou e perdeu o estado. A transação é marcada como `EXPIRED` e a margem é devolvida ao Portfolio.
5. **Runner - Destravamento de Lotes:** Após reconciliar a transação, os locks de lotes associados são limpos conforme a Seção 8.1.

#### 3. Proteção contra Novos Trades (Circuit Breaker)

Enquanto um `StrategyRunner` não finalizar sua reconciliação (Passos 2-4), ele mantém um flag interno `isReconciling = true`.

* Neste estado, o Runner **rejeita automaticamente** qualquer novo `TradeSignal` da estratégia.
* Isso evita o "Double Spending" de margem e garante que novos trades só ocorram após a limpeza do estado anterior.

#### 4. TTL de Reservas no Portfolio (Defesa em Profundidade)

O Saneamento de Locks (Passo 2) depende do reboot do Runner. Para cobrir o cenário em que o Runner permanece inativo por tempo prolongado, o **Portfolio** implementa uma segunda camada de proteção independente:

* **Reservation TTL:** Toda reserva de `ReservedBalance` possui um tempo máximo de vida (TTL) configurável. Se o Runner não confirmar a reserva (via envio da ordem à Exchange) dentro do prazo, o Portfolio libera automaticamente o `ReservedBalance` de volta ao `AvailableBalance`.
* **Escopo:** O TTL aplica-se exclusivamente a reservas vinculadas a transações `PENDING` sem `ExchangeOrderId` — reservas de ordens já submetidas (`SUBMITTED`) são geridas pelo fluxo normal de reconciliação.
* **Independência:** Este mecanismo é responsabilidade do Portfolio e não depende da saúde do Runner. Um Worker periódico do Portfolio verifica reservas expiradas e executa o rollback.

---

## 7. Aggregate Boundaries (DDD)

Para garantir escalabilidade e evitar bloqueios excessivos no banco de dados, o sistema divide as responsabilidades em dois Agregados principais:

### A. Aggregate Root: Portfolio

O Portfolio é o mestre financeiro da conta.

* **Entidades Internas:** `GlobalBalance`, `MarginAccount`.
* **Responsabilidade:** Garantir a consistência atômica do saldo global. Nenhuma margem é reservada ou taxa deduzida sem a atualização síncrona do estado interno do `Portfolio`. É a autoridade final para a liquidação financeira de taxas e ajustes do `GlobalBalance` no momento da confirmação de execução.
* **Persistência:** Possui seu próprio repositório (`PortfolioRepository`). Salva apenas dados de saldo e configurações globais de risco.
* **Conversão de Taxas (Fee Conversion):** Atua como o conversor central de taxas, traduzindo valores de ativos secundários (ex: BNB) para o ativo base do `GlobalBalance` no momento da execução, garantindo a integridade do saldo mesmo sem custódia prévia do ativo da taxa.

### B. Aggregate Root: StrategyRunner

O Runner é o mestre operacional de uma estratégia específica.

* **Entidades Internas:** `Position`, `Transaction`, `TransactionMatch` (contendo o VO `Fee`).
* **Value Objects:** `ExecutionPolicy`, `AccountingPolicy`.
* **Responsabilidade:** Garantir a integridade do ciclo de vida das ordens e a acurácia do PnL da estratégia. Ele referencia o `PortfolioId`, mas não altera o saldo diretamente.
* **Persistência:** Possui seu próprio repositório (`StrategyRunnerRepository`). Isso permite salvar milhões de transações de um robô de alta frequência sem travar a tabela de saldos do Portfolio.

### C. Comunicação entre Agregados: Padrão Híbrido

Para equilibrar a necessidade de consistência imediata com a escalabilidade de execução, o sistema utiliza chamadas síncronas para autorização e eventos assíncronos para liquidação.

#### 1. Capital Request (Runner → Portfolio): Síncrono e Direto

* **Padrão:** Chamada de método direta (Request-Response).
* **Natureza:** **Síncrona**.
* **Justificativa:** O Runner não pode despachar a ordem sem a garantia de que o capital foi reservado. Uma falha aqui deve abortar a transação localmente antes de qualquer interação com a Exchange.
* **Garantia:** Consistência forte (Strong Consistency). Se o Portfolio retornar `OK`, a margem está garantida.

#### 2. Confirmação de Execução (Runner → Portfolio): Assíncrona via Domain Events

* **Padrão:** Domain Events (Message Broker ou Event Bus interno).
* **Natureza:** **Assíncrona**.
* **Justificativa:** Após a ordem ser executada na Exchange, o Runner precisa atualizar sua contabilidade interna rapidamente. A liquidação final no Portfolio (conversão de Reserved para Realized) pode ocorrer milissegundos depois sem afetar a execução do trade.
* **Garantia de Entrega:** **At-least-once** com Idempotência no receptor (Portfolio). O Portfolio deve ignorar notificações de execução para IDs de transação que já foram liquidados.

#### 3. Rollback de Margem (Runner → Portfolio): Assíncrono com Retries

* **Padrão:** Mensageria durável.
* **Natureza:** Assíncrona.
* **Cenário:** Casos de `REJECTED` ou `CANCELED`.
* **Garantia:** O sistema deve garantir que o evento de "Unfreeze Capital" chegue ao Portfolio para evitar capital preso (Starvation).

#### 4. Implicações Arquiteturais

| Operação               | Acoplamento        | Garantia de Consistência                                                | Impacto de Falha                                         |
|------------------------|--------------------|-------------------------------------------------------------------------|----------------------------------------------------------|
| **Reserva de Margem**  | Forte (Síncrono)   | **Atómica**: Saldo bloqueado antes da persistência do `SUBMITTED`.      | Bloqueio imediato do sinal por falta de fundos.          |
| **Liquidação de Fees** | Fraco (Assíncrono) | **Eventual**: O `GlobalBalance` atualiza após o processamento do match. | Discrepância temporária resolvida no próximo checkpoint. |
| **Estorno (Refund)**   | Fraco (Assíncrono) | **Eventual**: O capital retorna ao `Available` após o evento de falha.  | Capital retido temporariamente até ao sucesso do retry.  |

---

## 8. Governança de Locks e Concorrência

Para resolver as brechas de "travamentos infinitos" e disputas de sinais:

### 8.1. Ciclo de Vida do Lock

O Lock não possui um timer independente, ele herda o destino da Transação:

1. **Liberação por Sucesso:** Quando a Transação atinge `FILLED`, o lock é convertido em um `TransactionMatch` definitivo (baixa no estoque).
2. **Liberação por Falha:** Se a Transação for `REJECTED`, `CANCELED` ou `EXPIRED`, o Runner dispara o gatilho de *Unlock* imediato, devolvendo os lotes ao estado "Disponível".
3. **Timeout de Transação (Watchdog):** Como definido na seção 6.C, o Watchdog cancela ordens travadas. Ao cancelar a ordem, o fluxo de "Liberação por Falha" é ativado, garantindo que nenhum lote fique preso por erro de rede ou software.

### 8.2. Prevenção de Deadlocks

Para evitar que o sistema trave quando múltiplos sinais chegam simultaneamente:

* **Fila por Runner:** Cada `StrategyRunner` processa seus sinais de forma sequencial ou utiliza controle de concorrência otimista.
* **Fail-Fast em Lotes Presos:** Se um sinal chega solicitando um `targetLotId` que já possui um lock ativo de outra transação, o sinal é rejeitado imediatamente (`Error: LotAlreadyLocked`), evitando esperas circulares.

---

## 9. Contabilidade e Precisão Financeira

O sistema adota políticas rigorosas para garantir a integridade de valores monetários, desde a captura de taxas até o cálculo de preço médio, eliminando erros de precisão flutuante e assegurando auditabilidade fiscal.

### 9.1 Política de Taxas (Fees)

* **Captura:** As taxas são extraídas do callback da Exchange e nunca estimadas.
* **Impacto:** Afetam simultaneamente o PnL do Runner (visão estratégica) e o `GlobalBalance` do Portfolio (visão financeira).
* **Timing de Dedução:** A liquidação financeira das `Fees` no `GlobalBalance` segue rigorosamente a máquina de estados da transação descrita na **Seção 6.A**, ocorrendo de forma incremental em estados `PARTIAL` e finalizando em `FILLED`.
* **Auditoria:** O Portfolio mantém o rastro de `totalFeesPaid` por Runner para cálculo de eficiência de capital.
* **Conversão de Ativos (Cross-Currency):** O sistema suporta taxas em ativos diferentes do par operado. O **Portfolio** realiza a conversão sintética no momento do `TransactionMatch`, debitando o valor equivalente do `AvailableBalance` na moeda base caso o saldo do ativo da taxa seja insuficiente.
* **Impacto Financeiro:** As taxas afetam simultaneamente o PnL do Runner e o `GlobalBalance`. Em casos de moedas distintas, o Portfolio utiliza a taxa de câmbio do momento do evento para garantir a precisão da auditoria.
* **Mecanismo de Conversão e Oráculo de Preço**:
  * O **ExchangeAdapter** é o provedor oficial do Mark Price (preço de mercado) para conversão.

* **Protocolo de Fallback (Falha de Precificação) — 2 Níveis:**
  1. **Mark Price (tempo real):** Utiliza o Mark Price do `ExchangeAdapter` no momento exato do `TransactionMatch`. Se obtido com sucesso, a conversão é imediata e o GlobalBalance é debitado.
  2. **Dívida Técnica de Ativo (fallback):** Se a obtenção do Mark Price falhar por qualquer motivo, o Portfolio registra uma **Dívida Técnica de Ativo** na `DustAccount`: o débito exato no ativo original (ex: -0.002 BNB) acompanhado do timestamp preciso da transação. O GlobalBalance **não** é debitado até a reconciliação pelo Worker de Sweep. A execução da estratégia nunca é interrompida por falta de cotação de moedas secundárias.
  * **Justificativa:** Preços em cache ou derivados de execuções podem comprometer a integridade do GlobalBalance. O sistema prioriza a precisão do saldo sobre a resolução imediata de fees cross-currency.

* **Reconciliação Histórica Determinística:** Qualquer processo de reconciliação (Worker ou manual via DLQ) é obrigado a utilizar o **preço histórico correspondente ao timestamp original do trade**, não o preço atual. Isso garante que o PnL Líquido seja matematicamente idêntico ao que seria se o sistema estivesse 100% estável no momento da execução — o tempo de processamento não afeta o resultado financeiro.
* **Irreversibilidade:** Uma vez calculada e debitada a taxa sintética no momento do `TransactionMatch`, o valor é final. Não existem ajustes posteriores por oscilação de câmbio, garantindo que o PnL Líquido seja imutável após a efetivação.

### 9.2 Precisão Decimal e Arredondamento (Rounding Policy)

Para evitar erros de precisão flutuante e rejeições por excesso de decimais, o sistema adota uma política de **Alta Precisão Interna com Truncamento na Borda**.

#### A. Tipos de Dados e Precisão Interna

1. **Banimento do tipo `Double/Float`:** Para cálculos financeiros, o sistema proíbe o uso de tipos de ponto flutuante nativos. Todo o domínio (Portfolio, Runner, Transaction) deve utilizar bibliotecas de precisão arbitrária (ex: `BigDecimal` em Java, `Decimal` em Python/C#, ou `Decimal.js/Big.js` em Node.js).
2. **Precisão de Domínio:** Internamente, os cálculos de PnL e `GlobalBalance` devem manter **8 a 12 casas decimais** (independente do ativo) para evitar erros acumulados em múltiplas operações parciais.

#### B. Política de Arredondamento na Borda (Exchange)

O truncamento para os limites da Exchange ocorre no **último momento possível**: dentro do `StrategyRunner`, imediatamente antes do `Order Dispatch`.

1. **Filtros de Ativo (Exchange Info):** O Runner deve carregar as propriedades `lotStepSize` (decimais de quantidade) e `priceTickSize` (decimais de preço) da Exchange.
2. **Direção do Arredondamento:**
* **Quantidade (Quantity):** Sempre arredondar para **baixo** (Floor/Down). É preferível comprar 0.0001 a menos do que ter a ordem rejeitada por "Saldo Insuficiente" devido a um arredondamento para cima.
* **Preço (Price):** Arredondar para **baixo em Compras** (mais conservador) e para **cima em Vendas**, respeitando o `tickSize`.

#### C. Gestão de "Pó" e Resíduos Contábeis (DustAccount)

A `DustAccount` é uma **sub-entidade do Portfolio** que atua como receptor universal de débitos não resolvidos e resíduos operacionais. Seus valores são **excluídos** do cálculo de `AvailableBalance`, garantindo que o capital de giro permaneça "limpo".

* **Fontes de Entrada:**
  1. **Dust de Arredondamento:** No momento do `Match`, se a quantidade restante de um lote for menor que o `minQty` permitido pela Exchange, o Runner marca o lote como **Totalmente Fechado** e envia o resíduo para a `DustAccount`.
  2. **Dívidas Técnicas de Ativo:** Quando a conversão de Fees cross-currency falha (Fallback Crítico da Seção 9.1), o débito no ativo original é registrado na `DustAccount` com o timestamp da transação.

* **Processo de Sweep (Limpeza):** Um Worker de Reconciliação periódico converte os resíduos acumulados na `DustAccount` para a moeda base, utilizando o **preço histórico do timestamp original** (para Dívidas Técnicas) ou o preço atual (para Dust de Arredondamento). O saldo convertido é reintegrado ao `AvailableBalance` como lucro operacional recuperado.

* **Auditoria:** O Portfolio mantém o rastro de acúmulo por origem (Dust vs Dívida Técnica) e por Runner, permitindo identificar estratégias que geram resíduos excessivos.

* **Impacto no PnL:** Micro-diferenças de arredondamento são contabilizadas como perda operacional irrelevante. Dívidas Técnicas pendentes são transparentes para o operador — o patrimônio líquido (Equity) exibe separadamente o saldo operacional e o "pó" a ser processado.

#### D. Implicações Arquiteturais

| Componente         | Responsabilidade                                                                  |
|--------------------|-----------------------------------------------------------------------------------|
| **StrategyRunner** | Conhece as regras de precisão do símbolo e aplica o truncamento antes do envio.   |
| **Portfolio**      | Consolida saldos usando precisão máxima e absorve resíduos na `DustAccount`.      |
| **DustAccount**    | Isola débitos não resolvidos e resíduos do capital de giro operacional.            |
| **Value Objects**  | Quantidades e Preços devem ser imutáveis e encapsular a lógica de arredondamento. |

### 9.3 Metodologia de Cálculo e Exposição de Preço Médio

O sistema adota o modelo de **Preço Médio Ponderado por Execução (WAP)**, garantindo que a `Position` reflita o custo real de aquisição do ativo antes de taxas.

#### A. Fórmula de Cálculo (Weighted Average Price)

O preço médio é calculado exclusivamente sobre o volume executado, seguindo a fórmula:

* **`AvgPrice = Σ (Preço_Execução * Quantidade_Execução) / Σ Quantidade_Execução`**.
* **Fees (Taxas):** No modelo CTrade, as taxas são tratadas como **dedução de saldo (PnL Realizado)** e não são incorporadas ao preço médio do ativo. Isso permite uma visão clara da performance bruta vs. líquida.

#### B. Dinâmica de Atualização

* **Partial Fills:** O `averagePrice` é recalculado em tempo real a cada novo `TransactionMatch` recebido. Se uma ordem de 1 BTC é preenchida em 10 frações, a posição terá 10 atualizações incrementais.
* **Scaling (Aumento de Posição):** No modo `Netting`, novas compras são incorporadas ao preço médio atual. No modo `Hedging` (se suportado), cada posição mantém seu próprio preço médio isolado.
* **Reduções Parciais:** Vendas parciais **não alteram** o preço médio da posição restante; elas apenas reduzem a quantidade e realizam PnL com base no preço médio atual.

#### C. Persistência vs. Cálculo sob Demanda

* **Estado Persistido:** O `averagePrice` deve ser um **campo persistido** na entidade `Position`.
* **Justificativa:** Calcular o preço médio sob demanda através de milhares de `TransactionMatch` seria computacionalmente caro para o motor de decisão. A persistência garante que a Estratégia tenha acesso instantâneo ao valor.
* **Integridade:** Em cada atualização, o sistema deve registrar o `audit_log` do cálculo para permitir reconstrução histórica se necessário.

#### D. Exposição via Context Injection

O `averagePrice` é injetado na Estratégia através do objeto `PositionContext`:

1. O Runner lê o valor persistido.
2. Formata conforme a precisão decimal (Seção 9.2).
3. Disponibiliza como uma propriedade *read-only* para a lógica de decisão.

| Atributo              | Regra de Negócio                                             |
|-----------------------|--------------------------------------------------------------|
| **Inclusão de Fees?** | Não. Fees são registradas separadamente para clareza fiscal. |
| **Persistência?**     | Sim, campo físico na tabela `Positions`.                     |
| **Atualização?**      | Atômica, disparada pelo evento de `TransactionMatch`.        |

---

## 10. Resiliência e Protocolo de Envio

O sistema implementa camadas de proteção para garantir que ordens nunca sejam duplicadas, cancelamentos parciais sejam contabilizados com precisão, e o estado local seja reconciliável com a Exchange em qualquer cenário de falha.

### 10.1 Protocolo de Idempotência e Resiliência de Envio

Para garantir que uma intenção de trade nunca resulte em ordens duplicadas, o sistema adota o padrão de **Idempotência Baseada em Estado Persistido**.

#### A. O Papel do `clientOrderId`

O `clientOrderId` é a **chave primária de idempotência** perante a Exchange.

* **Premissa de Escopo:** Este blueprint assume que todas as exchanges suportadas implementam `clientOrderId`. Exchanges sem este recurso não são compatíveis com a V1.
* **Unicidade:** Como o ID contém o `transaction_uuid` gerado no estado `PENDING`, ele vincula permanentemente uma tentativa de execução a um registro único no banco de dados.
* **Suficiência:** Na maioria das exchanges modernas (Binance, OKX, etc.), o envio de uma ordem com um `clientOrderId` já existente resulta em rejeição automática, prevenindo a duplicidade no lado da Exchange.

#### B. Protocolo de Envio "Persist-First"

O risco de crash entre a geração do ID e a persistência é mitigado pela ordem de operações:

1. **Materialização:** O Runner cria a `Transaction` no banco de dados com status `PENDING`.
2. **Reserva de Margem:** O Portfolio bloqueia o saldo.
3. **Dispatch:** Somente após o banco de dados confirmar a persistência do estado `PENDING` e da reserva, a ordem é enviada à Exchange.

* **Cenário de Crash antes do Dispatch:** No reboot, o Runner verá uma transação `PENDING` sem correspondente na Exchange e poderá cancelá-la com segurança, pois o `clientOrderId` nunca "saiu" do sistema.

#### C. Tratamento de "Acknowledgment Perdido" (Timeout de Rede)

Se o sistema enviar a ordem, a Exchange aceitar, mas a conexão cair antes da resposta (Status `SUBMITTED`), o protocolo de reconciliação assume o controle:

* **Idempotency Store:** O próprio `StrategyRunnerRepository` atua como a loja de idempotência, eliminando a necessidade de um Redis externo para esta função específica.
* **Ação de Recuperação:** O Runner utiliza o `clientOrderId` persistido para consultar o status da ordem na Exchange antes de qualquer tentativa de reenvio.
* **Convergência:** Se a ordem for encontrada na Exchange, o estado local transita diretamente para `SUBMITTED` ou `FILLED`. Se não for encontrada, o Runner assume falha no envio e invalida a transação local.

#### D. Considerações
 
* **Nota de Topologia e Governança:** Os Runners não possuem conexão direta de escrita ou leitura com a API Rest da Exchange; eles utilizam obrigatoriamente o ExchangeAdapter. Esta centralização é o que permite a gestão do Key Pooling e do Rate Limiting, garantindo que o protocolo de idempotência sobreviva a falhas de conectividade ou saturação de quota da API Key.

#### E. Implicações Arquiteturais

| Problema                                        | Solução no Blueprint                                                            |
|-------------------------------------------------|---------------------------------------------------------------------------------|
| **Crash entre geração e persistência?**         | Impossível pelo fluxo: a ordem só é enviada *após* a persistência do `PENDING`. |
| **Duplicação em Retry Manual?**                 | Bloqueada pelo `clientOrderId` único por `transaction_uuid`.                    |
| **Ordem Fantasma (Aceita mas não confirmada)?** | Resolvida pela consulta obrigatória via `clientOrderId` no Boot Sequence.       |

### 10.2 Gestão de Cancelamento de Ordens Parciais

O sistema trata ordens parcialmente executadas que são canceladas como **Transações Finalizadas por Fração**, seguindo um protocolo de liquidação proporcional.

#### A. Modelagem de Estados (Composição de Sub-estados)

Uma `Transaction` não possui múltiplos estados simultâneos, mas sim uma **Máquina de Estados Linear** onde o estado final reflete o último evento financeiro relevante:

* **Fluxo:** `SUBMITTED` → `PARTIAL` → `CANCELED`.
* **Estado Final:** Quando o restante de uma ordem parcial é cancelado, o status final da `Transaction` no banco de dados é `CANCELED`. O sistema identifica a execução parcial pela presença de registros em `TransactionMatch` criados durante a fase `PARTIAL`, que permanecem íntegros e imutáveis. Para fins de observabilidade (logs, dashboards), esse cenário pode ser apresentado como `PARTIALLY_FILLED_CANCELED`, embora não constitua um estado formal da máquina de estados.

#### B. Contabilidade e Preço Médio

* **Preço Médio (Average Price):** O preço médio da `Position` é calculado exclusivamente com base nos `TransactionMatch` efetivados.
* Se uma ordem de 10 BTC a $50.000 executou apenas 2 BTC e foi cancelada, o preço médio é $50.000 sobre o volume de 2 BTC. O volume não executado (8 BTC) é ignorado no cálculo de exposição.

* **Tratamento de Fees:** As exchanges cobram `Fees` apenas sobre o montante **executado**. Portanto, a parte cancelada da ordem não gera custo financeiro nem registro de `Fee` no sistema.

#### C. Fluxo de Liberação de Margem (Estorno Proporcional)

O `StrategyRunner` deve garantir a precisão do saldo no `Portfolio` durante o cancelamento parcial:

1. **Fatia Executada:** No estado `PARTIAL`, a margem proporcional já foi convertida de *Reserved* para *Realized*.
2. **Fatia Cancelada:** Ao receber o evento `CANCELED`, o Runner instrui o `Portfolio` a realizar o **Estorno da Margem Remanescente** (apenas o que não foi executado) de *Reserved* para *Available*.

#### D. Implicações Arquiteturais

| Questão                         | Decisão do Blueprint                                                                                                       |
|---------------------------------|----------------------------------------------------------------------------------------------------------------------------|
| **Estado Atômico ou Composto?** | Atômico com histórico. O estado muda para `CANCELED`, mas os matches parciais já dispararam eventos contábeis definitivos. |
| **Preço Médio?**                | Baseado apenas no volume preenchido (`filledQuantity`) e preço real de execução.                                           |
| **Fees no Cancelamento?**       | Zero. Fees só existem onde há `TransactionMatch`.                                                                          |
| **Segurança de Saldo?**         | O estorno deve ser calculado como: `Margem_Original - Margem_Executada`.                                                   |

### 10.3 Filosofia de Reconciliação e Fonte da Verdade

O protocolo de recuperação pós-crash (Boot Sequence) adota uma filosofia de **Sincronismo Autoritário**, onde a Exchange é a fonte da verdade para a execução financeira, e o Banco de Dados local é a fonte da verdade para a intenção estratégica.

#### A. Hierarquia de Confiança

1. **Exchange (Autoridade Financeira):** Se a Exchange confirma que uma ordem foi executada (`FILLED`), o sistema local deve aceitar esse fato e ajustar as `Positions` e o `GlobalBalance`, mesmo que o estado local estivesse como `PENDING`.
2. **Banco de Dados Local (Autoridade de Intenção):** Se o banco local possui um registro `PENDING`, mas a Exchange não conhece o `clientOrderId`, a intenção é considerada "não materializada" e deve ser descartada para proteger o saldo.

#### B. Protocolo para "Ordens Fantasmas"

"Ordens Fantasmas" são aquelas que existem no banco de dados, mas não na Exchange:

* **Cenário:** O sistema caiu entre a persistência e o dispatch.
* **Ação Automática:** O Runner marca a transação como `EXPIRED`, libera os locks de lotes e solicita o estorno da margem ao `Portfolio`.
* **Justificativa:** É mais seguro perder um sinal de trade (custo de oportunidade) do que manter margem bloqueada para uma ordem que nunca será preenchida (custo de capital).

#### C. Conflitos de Estado no Reboot

| Divergência                                    | Resolução          | Ação do Sistema                                                      |
|------------------------------------------------|--------------------|----------------------------------------------------------------------|
| **Local: `SUBMITTED` / Exchange: Inexistente** | Confia na Exchange | Marca local como `EXPIRED` e libera margem.                          |
| **Local: `PENDING` / Exchange: `FILLED**`      | Confia na Exchange | Transita para `FILLED`, processa `TransactionMatch` e liquida taxas. |
| **Local: `SUBMITTED` / Exchange: `CANCELED**`  | Confia na Exchange | Transita local para `CANCELED` e estorna margem remanescente.        |

#### D. Casos de Intervenção Manual (DLQ)

Se a Exchange retornar um status que o sistema não consegue reconciliar (ex: ordem executada em um símbolo que o Runner não reconhece ou com quantidade divergente), a transação é movida para a **DLQ (Dead Letter Queue)**.

* O `isReconciling` do Runner permanece `true`.
* O sistema aguarda intervenção humana para corrigir o estado e garantir a integridade do `Portfolio`.

### 10.4 Escalabilidade de Conectividade (ExchangeAdapter)

O acesso às APIs das Exchanges é obrigatoriamente centralizado no ExchangeAdapter. Este componente atua como um Gateway inteligente entre os Runners e as Exchanges.

* **Abstração de Chaves (Key Pooling):** O adaptador deve ser capaz de gerenciar múltiplas API Keys de forma transparente. Ele distribui as requisições entre as chaves disponíveis para maximizar a quota de peso (weight) e contornar limites de IP.
* **Priorização por Peso (Weight Control):** O sistema monitora o consumo de limites da Exchange em tempo real. Requisições de execução (ordens e cancelamentos) têm prioridade absoluta. Consultas de dados de mercado (market data) sofrem throttling automático caso o limite atinja 80% da capacidade.
* **Isolamento de Erros:** Falhas de conectividade ou bloqueios de uma chave específica são isolados no Adaptador. Ele deve realizar o _failover_ automático para outra chave saudável no pool sem que o Runner precise reiniciar ou perder o estado da transação.

---

## 11. Gestão de Risco e Defesa de Capital

O sistema implementa mecanismos de proteção em múltiplas camadas para preservar o `GlobalBalance`, desde a interrupção automática por anomalias até a governança de margem e alocação entre Runners concorrentes.

### 11.1 Circuit Breaker Global e Defesa de Capital

O sistema implementa um mecanismo de interrupção em cascata para proteger o `GlobalBalance` contra falhas algorítmicas, erros de execução ou condições extremas de mercado.

#### A. Gatilhos de Ativação (Métricas)

O Circuit Breaker é acionado automaticamente pelo **Portfolio** ao detectar as seguintes anomalias:

1. **Max Daily Drawdown:** Queda do `GlobalBalance` (realizado + flutuante) abaixo de um percentual pré-configurado nas últimas 24h. *Implementado na T43: equity = available + reserved + flutuante ao último preço; limite global de 10%; a janela reinicia no reset manual.*
2. **Rejeições Consecutivas:** Se um Runner acumular $N$ estados `REJECTED` da Exchange em um curto intervalo, sinalizando erro de parâmetro ou falta de liquidez.
3. **Divergência Crítica de Saldo:** Se, durante a reconciliação, a diferença entre o saldo da Exchange e o local exceder o limite de segurança.
4. **Anomalia de Latência:** Timeouts excessivos detectados pelo Watchdog, sugerindo instabilidade na API da Exchange ou infraestrutura.

#### B. Autoridade e Hierarquia

* **Portfolio (Automático):** Possui autoridade para negar todos os `Capital Requests`, efetivamente impedindo novas ordens de todos os Runners.
* **Intervenção Manual (Override):** Através de um comando administrativo, o operador pode acionar o Safe Mode em diferentes níveis de severidade (ver seção B.1).

#### B.1 Níveis do Safe Mode (Protocolo de Intervenção Escalonada)

O Safe Mode opera em **três níveis escaláveis**, onde cada nível inclui todas as ações do anterior:

| Nível | Nome | Ação | Proteção contra Acionamento Acidental |
|-------|------|------|---------------------------------------|
| **1 — Halt** | Bloqueio de Entradas | Recusa toda nova reserva de capital (nenhum BUY). A estratégia continua rodando e **pode vender** — só reduz exposição (decisão da T45; bloquear tudo é o kill switch, que põe os runners em `HALTED`). Ordens em voo continuam seu ciclo normal. | Sem confirmação adicional (baixo risco — apenas bloqueia novas entradas). |
| **2 — Cancel All** | Cancelamento em Massa | Além do Halt, envia cancelamento para todas as ordens LIMIT em voo (`PENDING`/`SUBMITTED`/`PARTIAL`) dos Runners operacionais. A estratégia deixa de ser consultada. | Requer confirmação explícita (`confirm=true` na API). |
| **3 — Panic Sell** | Liquidação Total | Além do Cancel All, gera ordens de fechamento a mercado (Market Orders) para todas as `Positions` abertas. | Requer confirmação explícita com motivo registrado em auditoria. |

* **Acionamento Automático:** Os gatilhos da seção 11.1.A acionam automaticamente o **Nível 1 (Halt)**. A escalação para Níveis 2 e 3 é exclusivamente manual. A retomada é manual, exceto o HALT por exchange fora do ar (11.1.C, T71).
* **Implementação (T45):** `GET/PUT /api/admin/portfolios/{id}/safe-mode`; a execução dos níveis 2 e 3 é reaplicada a cada tick do runner (convergente e retomável após restart). Runners parados pelo kill switch não são conduzidos. A liquidação do nível 3 usa MARKET sem proteção de preço nesta versão (proteção LIMIT: T48).
* **Execução Assíncrona com Feedback:** O comando é disparado de forma assíncrona. O operador acompanha o progresso via status, que mostra o que ainda falta (ordens LIMIT a cancelar, vendas de liquidação em voo, posições abertas, runners fora de operação). O sistema não bloqueia a API administrativa aguardando conclusão.
* **Persistência de Estado:** O nível ativo do Safe Mode é persistido no banco de dados para sobreviver a restarts.

#### C. Protocolo de Bloqueio e Retomada

1. **Estado de Bloqueio:** O Portfolio sinaliza o bloqueio global. Os Runners entram em estado `isReconciling = true` ou `halted`, rejeitando novos `TradeSignal`.
2. **Protocolo de Retomada (Cooldown):**
   * O sistema não retoma automaticamente após um Circuit Breaker de Drawdown ou Divergência.
   * Exige uma **limpeza de estado manual** (auditoria na DLQ) e um comando de "Reset de Risco" para voltar ao estado operacional.
   * Para bloqueios por latência, o sistema pode tentar uma retomada gradual (Warm-up) após $X$ minutos de estabilidade.
   * **Implementação (T71):** o HALT automático por exchange fora do ar (`EXCHANGE_UNAVAILABLE`: o watchdog ficou 3 ciclos sem resposta da exchange) volta a NORMAL sozinho depois de 5 minutos seguidos de exchange saudável (circuito REST fechado, envio liberado, streams conectados, watchdog sem falha), no máximo 3 vezes por portfolio em 24h. É a única retomada automática; qualquer mudança do operador depois do HALT a desliga.
   * A retomada segue o caminho inverso dos níveis: o operador deve reduzir o nível (3→2→1→Normal) explicitamente, garantindo validação em cada etapa.

#### D. Implicações Arquiteturais

| Componente         | Papel no Circuit Breaker                                                     |
|--------------------|------------------------------------------------------------------------------|
| **Portfolio**      | Monitora métricas globais e atua como o gatekeeper de margem.                |
| **StrategyRunner** | Implementa o flag de interrupção e suspende o ciclo de vida de novos sinais. |
| **Watchdog**       | Monitora a saúde da conexão e o tempo de resposta das transações.            |

### 11.2 Alocação de Capital e Governança de Concorrência

O sistema gerencia a escassez de recursos através de uma política de **Priorização por Reserva e Precedência de Chegada**, garantindo que nenhum Runner desestabilize a saúde financeira do `GlobalBalance`.

#### A. Política de Alocação: "First-Come, First-Served" (FIFO)

Por padrão, o Portfolio processa os `Capital Requests` seguindo a ordem cronológica de recebimento:

1. **Atendimento Imediato:** Se o `AvailableBalance` for suficiente para cobrir o `Capital_Requerido` (nominal para `LIMIT`; com buffer de segurança apenas para `MARKET`, quando existir — IG §7.2), a reserva é feita instantaneamente.
2. **Rejeição por Insuficiência:** Se o saldo disponível for menor que o solicitado, o Portfolio **rejeita** o pedido imediatamente (`InsufficientFunds`). O sistema não mantém uma "fila de espera" para evitar que ordens sejam enviadas com preços defasados (Stale Orders).

#### B. Proteção contra Starvation (Fome de Capital)

Para evitar que um Runner de alta frequência consuma todo o capital, o sistema implementa **Limites de Exposição por Runner**:

* **Max Allocation Per Runner:** Cada `StrategyRunner` possui um teto máximo de capital (ex: no máximo 20% do `GlobalBalance`).
* **Hard vs Soft Limits:** 
  * **Hard Limit:** Bloqueio imediato de novos `Capital Requests` se o teto for atingido.
  * **Soft Limit:** Alerta o monitoramento, mas permite a operação se ainda houver capital global ocioso.
* **Max Open Positions:** Número máximo de posições abertas simultaneamente.
* **Max Pending Orders:** Número máximo de ordens SUBMITTED simultâneas
* **Max Cancel Rate:** Limite de cancelamentos por minuto

#### C. Estratégia de "Capital Pooling"

O sistema pode ser configurado em dois modos de visibilidade de capital:

1. **Shared Pool (Padrão):** Todos os Runners competem pelo mesmo saldo. Maximiza a eficiência do capital, mas aumenta o risco de concorrência.
2. **Dedicated Buckets:** O Portfolio reserva fatias fixas do saldo para Runners específicos. Garante que uma estratégia crítica sempre tenha margem, sacrificando a flexibilidade global.

#### D. Implicações Arquiteturais

| Componente          | Responsabilidade                                                                                                |
|---------------------|-----------------------------------------------------------------------------------------------------------------|
| **Portfolio**       | Único ponto de sincronização (lock) para o `GlobalBalance`. Deve ser ultra-rápido para não gargalar os Runners. |
| **StrategyRunner**  | Deve estar preparado para receber um "Não" (Rejeição) e descartar o sinal graciosamente.                        |
| **Circuit Breaker** | Interrompe a alocação se o uso de margem global atingir níveis de risco (ex: 90% do total).                     |

---

## 12. Modelo de Concorrência e Processamento do Runner

O `StrategyRunner` opera sob um modelo de **Fila Sequencial com Política de Descarte (Drop Policy)**, garantindo que a integridade do estado da posição nunca seja corrompida por processamento paralelo.

### A. Processamento Sequencial (Strict Serial)

* **Modelo:** Cada Runner possui sua própria fila de mensagens (mailbox/queue).
* **Execução:** Os sinais são processados um por vez (Single-threaded context). Se dois sinais chegam no mesmo milissegundo, o que for registrado primeiro no barramento de eventos/mensageria terá a precedência.
* **Justificativa:** Operações de trading dependem do estado imediatamente anterior (Ex: Saldo atualizado, Lotes disponíveis). O processamento paralelo exigiria locks complexos que aumentariam a latência e o risco de deadlocks.

### B. Gestão de Acúmulo e Drop Policy

Para evitar que uma estratégia "atropele" a capacidade de execução do sistema, aplicamos as seguintes regras:

1. **Fila Limitada (Capacity 1):** Por padrão, a fila de entrada para novos sinais tem capacidade reduzida. Se o Runner estiver ocupado processando um sinal ou aguardando o `Capital Request`, novos sinais recebidos são **descartados** imediatamente.
2. **Stale Signal Check:** Antes de iniciar a materialização (Seção 4.B), o Runner verifica o timestamp do sinal. Se o sinal tiver mais de $X$ milissegundos, ele é ignorado por estar "obsoleto" (Stale).
3. **Bloqueio por Status:** Se o Runner estiver em estado `isReconciling` ou com uma transação em `PENDING`, ele ignora novos sinais de abertura conforme a **Execution Policy**.

### C. Processamento Durante Ordens em Voo

O comportamento depende da **Execution Policy** definida na Seção 2.B.1:

* **Modo Single:** O Runner ignora qualquer sinal de abertura enquanto houver uma transação `SUBMITTED` ou `PARTIAL`. Ele só processa novos sinais após a finalização (`FILLED/CANCELED`).
* **Modo Netting/Scaling:** O Runner pode aceitar novos sinais para aumentar/diminuir a posição, mas estes entrarão na fila e serão processados sequencialmente, respeitando a atomicidade da margem no Portfolio.

### D. Isolamento e Fault Containment

Para garantir que a falha de um Runner individual não comprometa a disponibilidade do sistema, cada Runner opera dentro de limites de recursos bem definidos:

#### 1. Isolamento de Recursos por Runner

* **Thread/Virtual Thread Dedicada:** Cada Runner executa em sua própria thread (ou virtual thread), impedindo que um Runner em loop ou deadlock bloqueie a execução dos demais.
* **Timeout Forçado:** Toda operação do Runner (processamento de sinal, `Capital Request`, `Order Dispatch`) possui um timeout máximo. Se excedido, a operação é abortada e o Runner transita para `HALTED`.
* **Limite de Memória:** O sistema deve monitorar o consumo de recursos por Runner (ex: número de locks ativos, transações em memória). Acúmulo anormal de estado indica bug e deve disparar alerta.

#### 2. Proteção de Componentes Compartilhados

O **Portfolio** e o **ExchangeAdapter** são componentes centrais compartilhados por todos os Runners. Sua resiliência é crítica:

* **Circuit Breaker por Runner no Portfolio:** Se um Runner específico acumular $N$ chamadas com erro em sequência (ex: requests malformados, timeouts), o Portfolio suspende o atendimento apenas desse Runner sem afetar os demais.
* **Quota no ExchangeAdapter:** O adaptador deve limitar o número de requests simultâneos por Runner, evitando que um Runner saturado consuma todo o pool de conexões ou o rate limit da Exchange em detrimento dos outros.

#### 3. Detecção de Runner Mal Comportado

O sistema mantém métricas de saúde por Runner que alimentam os mecanismos de proteção:

| Métrica | Limite | Ação |
|---------|--------|------|
| **Taxa de erros** | $N$ erros em $T$ segundos | Circuit Breaker do Portfolio suspende o Runner |
| **Tempo de processamento de sinal** | Acima de $X$ms | Alerta + candidato a `HALTED` automático |
| **Locks ativos não resolvidos** | Acima de $N$ | Alerta de possível vazamento de estado |
| **Requests ao ExchangeAdapter** | Acima de $N$/min | Throttling individual do Runner |

#### E. Implicações Arquiteturais

| Desafio                               | Solução no Blueprint                                                                      |
|---------------------------------------|-------------------------------------------------------------------------------------------|
| **Sinais mais rápidos que execução?** | **Drop Policy**: Novos sinais são descartados se o Runner estiver ocupado.                |
| **Sinal demorado?**                   | O Watchdog de Timeout (6.C) cancela a transação, liberando o Runner para o próximo ciclo. |
| **Corrupção de Estado?**              | Evitada pelo modelo de **Actor/Single-thread** por Runner; o estado é local e isolado.    |
| **Runner em loop/crash?**             | Isolamento de thread + Circuit Breaker por Runner protegem o restante do sistema.         |
| **Saturação de recursos compartilhados?** | Quota por Runner no ExchangeAdapter e Portfolio impedem monopolização.                |

---

## 13. Orquestração do Ciclo de Vida do Runner

O ciclo de vida de um `StrategyRunner` é gerido pelo **Portfolio** através de comandos administrativos, garantindo que a alocação de capital e a execução estejam sempre sincronizadas com as diretrizes do operador.

### A. Estados do Ciclo de Vida

Um Runner transita pelos seguintes estados operacionais:

1. **CREATED:** Persistido no banco de dados, mas sem recursos alocados ou conexão ativa com a Exchange.
2. **INITIALIZING (Boot):** Executa a sequência de recuperação (Seção 6.D), reconciliando ordens "em voo" e verificando saldo.
3. **ACTIVE:** Processando sinais e solicitando margem ao Portfolio.
4. **HALTED (Circuit Breaker):** Suspenso por violação de risco; não aceita novos sinais, mas monitora ordens abertas.
5. **TERMINATING:** Fase de encerramento onde o Runner tenta fechar posições e liberar toda a margem reservada.
6. **ARCHIVED:** Estado terminal. Histórico preservado para auditoria, mas impossibilitado de retomar operações.

### B. Criação e Unicidade

* **Instanciação:** Novos Runners são criados via API administrativa, associados obrigatoriamente a um `PortfolioId`.
* **Constraint de Unicidade:** O sistema impede a criação de múltiplos Runners para a mesma combinação de `(StrategyId, Symbol, ExchangeId, PortfolioId)` em estado `ACTIVE` para evitar conflitos de execução e cálculos duplicados de capital. A inclusão do `ExchangeId` permite que a mesma estratégia opere o mesmo símbolo em exchanges diferentes simultaneamente.

### C. Encerramento com Posições Abertas (Graceful Shutdown)

O encerramento de um Runner ativo segue um protocolo de segurança:

1. **Stop New Signals:** O Runner para de processar qualquer sinal de abertura (`SHOULD_BUY`).
2. **Liquidação ou Transferência:**
   * **Opção 1 (Close All):** O operador dispara um comando de liquidação; o Runner gera ordens de fechamento para todas as `Positions`.
   * **Opção 2 (Detach):** Em casos críticos, as posições podem ser "desvinculadas" do Runner e movidas para uma fila de auditoria manual (DLQ), liberando o Runner para arquivamento.
3. **Margem Clear:** O Runner só pode ser movido para `ARCHIVED` quando o Portfolio confirmar que `ReservedMargin` para este `RunnerId` é zero.

### D. Implicações Arquiteturais

| Ação                | Responsável           | Impacto                                                               |
|---------------------|-----------------------|-----------------------------------------------------------------------|
| **Start/Stop**      | Operador Humano / API | Altera o flag `isHalted` ou status operacional no DB.                 |
| **Provisionamento** | Factory Service       | Garante a injeção da `ExecutionPolicy` e `AccountingPolicy` corretas. |
| **Recuperação**     | Runner (Self-healing) | O Runner inicia-se de forma independente após um restart do sistema.  |

---
