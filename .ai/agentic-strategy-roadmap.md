# Roadmap — Gestão Agêntica de Estratégias

Visão do mantenedor (2026-10-21) e o caminho até ela. Não é uma task: cada marco abaixo vira uma ou
mais tasks em `/.ai/tasks/` quando for priorizado. Ler junto com `product-direction.md`.

## Visão

Um agente (Claude) opera o portfólio de estratégias como um gestor:

1. **Lê o mercado:** classifica o regime atual (tendência, lateral, alta volatilidade...).
2. **Liga e desliga estratégias** pela classificação de cada uma e pelo desempenho recente. Exemplo:
   mercado lateral e as estratégias de tendência perdendo, então desliga essas e liga as de reversão.
3. **Propõe estratégias novas** quando nenhuma disponível serve ao regime atual.
4. **Valida antes de arriscar:** testa a estratégia nova com dados históricos, depois sem capital,
   depois com pouco capital.
5. **Escala o que funciona:** aumenta o capital de quem provou resultado e reduz o de quem piorou.

## Princípios (não mudam com a visão)

- **Proteções ficam no core.** O agente nunca substitui proteção do lote, Safe Mode, limites
  pré-trade nem conciliação. Ele decide **quais estratégias operam e com quanto**; o core decide se cada
  ordem é segura.
- **Estratégia continua só contrato.** Estratégia nova é uma app nova que implementa o contrato
  (`docs/strategy-service/CONTRACT.md`), cadastrada pela T54. O ctrade não ganha lógica de estratégia.
- **O agente age pela API**, com `X-Reason`, auditado em `operator_actions`, como qualquer operador.
- **Ferramentas primeiro, blindagem no fim.** Os marcos antecipam todas as ferramentas de que o agente
  autônomo precisa. As barreiras do que ele pode fazer sozinho (envelope de autonomia, bloco 15) entram
  no último marco, quando o uso real mostrar quais limites fazem sentido. Até lá o agente opera só em
  testnet, e as proteções do core continuam valendo para toda ordem.
- **Toda decisão tem registro e revisão.** Cada decisão grava o motivo, os dados em que se baseou e o
  resultado esperado; depois o agente compara com o resultado real.
- **Testnet primeiro.** O ciclo inteiro roda em testnet antes de tocar capital real. O desempenho na
  testnet é indicativo (livro raso, preços que pulam); a validação de desempenho de verdade só vem com
  produção e capital pequeno.

## O ciclo

```
 observar ──► decidir ──► agir (API) ──► medir ──► revisar a decisão
    │                                                      │
    └──────────── propor estratégia nova ◄─────────────────┘
       backtest rápido ► replay pelo ctrade ► shadow ► canário ► escala ► aposentadoria
```

## Blocos necessários

| # | Bloco | Existe hoje | Falta |
|---|---|---|---|
| 1 | **Histórico de eventos** (quando cada runner ligou/desligou e por quê) | Safe Mode, ações do operador, DLQ | Transições do runner e boot: **T59** |
| 2 | **Desempenho por runner e por estratégia, por janela** | `positions.realized_pnl`/`closed_at`; `equity_snapshots` por portfolio | Consulta por runner e por estratégia (todos os runners dela): PnL realizado e flutuante, taxas, nº de operações, acerto, drawdown, tempo exposto, por janela (24h, 7d, 30d) e por regime |
| 3 | **Dados de mercado históricos** | Só o último preço por símbolo | Candles (klines) armazenados ou buscados na exchange pelo adapter; base para regime e backtest |
| 4 | **Classificação de regime** | Nada | App externa de regime com contrato próprio (decisão 2); o ctrade guarda e expõe o histórico de regimes |
| 5 | **Perfil da estratégia** | Catálogo com nome, versão, URL, status | Regimes declarados em que a estratégia funciona, e o perfil **medido** (desempenho por regime, do bloco 2) |
| 6 | **Drenagem: desligar sem largar o lote** | `halt` para tudo, inclusive a proteção do lote (T58: "halt não negocia nada") | Estado próprio do runner (decisão 3): para de abrir posição, mas a proteção e a saída da estratégia continuam até zerar. Sem isso, "desligar por regime" deixa lote aberto sem proteção |
| 7 | **Shadow** (estratégia opera sem capital) | Nada | Modo do runner em que a decisão é registrada com execução simulada no preço do momento, sem ordem na exchange; desempenho no mesmo formato do bloco 2 |
| 8 | **Canário e escala** | `max_allocation_percent` e `dedicated_budget` por runner, alteráveis por `PATCH` | Níveis de capital (shadow → canário → normal), critérios de promoção e rebaixamento |
| 9 | **Backtest rápido (nível 1)** | Nada | Ferramenta que reproduz candles históricos contra o **mesmo contrato HTTP** da estratégia, com fills simulados (preço do candle + taxa + slippage configuráveis). Só a estratégia, sem o ctrade: minutos para meses de dados, para filtrar ideias e testar muitas combinações de parâmetros |
| 10 | **Diário de decisões do agente** | `operator_actions` (comando + motivo) | Registro da decisão (dados de entrada, regime, hipótese, resultado esperado) e da revisão posterior |
| 11 | **Onde o agente roda** | Nada | VPS com execução agendada (horas, não minutos), acesso à API e ao diário |
| 12 | **Esteira de apps de estratégia** | Cinco apps em `ctrade-dev/ctrade-strategies` (T60), build e subida manuais na VPS (T63) | Como o agente escreve, versiona, sobe e derruba uma app de estratégia na VPS, fora do ctrade |
| 13 | **Relógio controlável** | O ctrade usa o relógio do sistema: 124 `Instant.now()` no core e 16 tarefas agendadas no Spring | Relógio injetado: o real em produção; no replay, o tempo dos dados, avançado pelo disparador. Inclui as tarefas agendadas (drawdown, conciliação, balanço, preço velho) disparando pelo tempo simulado. Mecânica técnica, não regra de negócio; também deixa os testes de integração mais rápidos e determinísticos |
| 14 | **Replay pelo ctrade (nível 2)** | `adapter-mock` (exchange simulada no processo) | **Exchange de replay** como app externa, plugada no mesmo lugar da Binance e da Coinbase: dados históricos prontos antes, envio comandado por outra aplicação (o disparador), fills simulados. Testa a estratégia pelo **fluxo produtivo inteiro** (limites pré-trade, proteção do lote, Safe Mode, capital disputado, conciliação). Roda numa instância do ctrade com banco próprio e descartável, nunca junto da testnet ou da produção. Depende do bloco 13 para rodar acelerado. Absorve a ideia em espera "MOCK como app externo" |
| 15 | **Envelope de autonomia (blindagem)** | Limites do runbook para o operador | Regras do core que recusam com código estável o que passa do permitido ao agente (capital de canário, realocação por dia, promoção sem aprovação). Definido no fim, com base no uso real (decisão 4) |

## Marcos

Cada marco entrega valor sozinho e destrava o seguinte.

| Marco | Entrega | Blocos | Destrava |
|---|---|---|---|
| **M0** | Histórico de eventos | 1 | Saber quando cada estratégia operou (T59) |
| **M1** | Medição | 2 | "Qual estratégia está rendendo, e desde quando" pela API |
| **M2** | Mercado e regime | 3, 4 | "Em que regime estamos e em qual estávamos" |
| **M3** | Regulação com as estratégias existentes | 5, 6, 10, 11 | Agente liga, drena e desliga estratégias por regime e desempenho, com diário. **Primeira versão da visão** |
| **M4** | Shadow, canário e escala | 7, 8 | Estratégia nova entra sem capital e sobe por critério; o agente realoca capital |
| **M5** | Backtest rápido | 9 | Filtrar ideias e parâmetros antes de qualquer execução no ctrade |
| **M6** | Relógio controlável e replay | 13, 14 | Validar a candidata pelo fluxo produtivo inteiro, acelerado, antes do shadow |
| **M7** | Proposta de estratégias | 12 | Agente escreve a app, faz backtest, replay, shadow, canário e escala. **Visão completa** |
| **M8** | Blindagem | 15 | Envelope de autonomia no core; pré-requisito para o agente gerir capital em produção |

Pré-requisitos de todos: a trilha do soak (T53) estável e **várias apps de estratégia** que
implementem o contrato (hoje as cinco apps do soak, T60). M3 com uma estratégia só não tem o que
regular.

## Decisões registradas (2026-10-21)

1. **Ordem dos marcos:** M0 → M1 → M2 → M3 primeiro; M4–M8 avaliados depois. M3 já entrega a
   regulação pedida com estratégias escritas à mão.
2. **Regime calculado por app externa** com contrato próprio, como as estratégias: o ctrade só guarda e
   expõe o regime; o classificador evolui sem deploy do ctrade. Rejeitados: o agente calcular a cada
   execução (sem histórico consistente) e o cálculo no core (análise fora do ctrade).
3. **Drenagem é estado próprio do runner**, para "parar de abrir" e "parar tudo" não virarem o mesmo
   botão.
4. **Envelope de autonomia no fim:** primeiro antecipar ao máximo as ferramentas do agente autônomo;
   barreiras e blindagem perto do fim do desenvolvimento (M8). As regras e números serão definidos lá.
5. **Backtest em dois níveis:**
   - nível 1, rápido, só a estratégia pelo contrato HTTP (bloco 9), para filtrar ideias;
   - nível 2, a proposta do mantenedor: uma exchange de replay como app externa, plugada como a
     Binance, com dados prontos e envio comandado por outra aplicação, para testar pelo fluxo produtivo
     com entrada simulada (bloco 14). Para rodar acelerado sem distorcer as regras de tempo, depende do
     relógio controlável (bloco 13).

## Riscos

- **Overfitting:** estratégia proposta e calibrada no mesmo histórico parece ótima e falha ao vivo.
  Mitigação: janelas fora da amostra no backtest e o shadow como teste obrigatório.
- **Gangorra:** ligar e desligar a cada mudança curta de regime gera custo e operações pela metade.
  Mitigação: tempo mínimo em cada estado e histerese na mudança de regime.
- **Amostra pequena:** desempenho de poucas operações é ruído. Critérios de promoção exigem número
  mínimo de operações, não só PnL.
- **Testnet engana:** fills e liquidez da testnet não representam o mercado; desempenho medido lá só
  valida o mecanismo, não a estratégia.
- **Fill simulado é aproximado:** com candles não há livro de ofertas; backtest e replay executam por
  preço do candle, taxa e slippage configurados. Resultado bom no backtest é condição necessária, não
  suficiente.
- **Relógio controlável mexe em muito código:** 124 pontos no core e as tarefas agendadas. Mitigação:
  task própria, só mecânica (sem mudar regra), com a suíte inteira como rede; um fluxo, um caminho (o
  relógio injetado substitui `Instant.now()`, sem convivência dos dois).
- **Agente sem contexto decide mal:** cada execução lê o diário e o histórico (blocos 1, 2 e 10) antes
  de decidir. Até a blindagem (M8), o agente opera só em testnet.

## Fontes

- `product-direction.md` (visão e estado atual, divisão de responsabilidades, ideias em espera)
- `tasks/T41-remote-strategy-minimal-extraction.md`, `tasks/T54-dynamic-strategy-catalog.md` (contrato
  e catálogo)
- `tasks/T58-price-for-open-lots.md` (halt não negocia; motivação do bloco 6)
- `tasks/T59-event-timeline.md` (M0)
