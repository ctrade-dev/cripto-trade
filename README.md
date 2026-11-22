# CTrace - Cryptocurrency Trading Application

## Praticas de Desenvolvimento para Agentes

As praticas de desenvolvimento e review do projeto estao centralizadas no diretorio `/.ai`.

Arquivos principais:

- `/.ai/README.md`: guia principal e ordem de leitura
- `/.ai/product-direction.md`: proposito do sistema (operado por IA) e direcao do produto
- `/.ai/architecture.md`: fronteiras e responsabilidades por modulo
- `/.ai/coding-standards.md`: padroes de implementacao
- `/.ai/review.md`: heuristicas de review
- `/.ai/agents/code-review-agent.md`: agente compartilhado de review para Codex e Claude
- `/.ai/skills/pr-review-publisher/`: skill compartilhada para publicar findings em PR
- `/.ai/validation.md`: comandos e estrategia de validacao

Os arquivos `AGENTS.md` e `CLAUDE.md` devem apontar para `/.ai` como fonte canonica dessas regras.

Sistema modular de trading com arquitetura hexagonal para conexão WebSocket com múltiplas exchanges.

## Tecnologias

- **Framework**: Spring Boot 3.5.4
- **Linguagem**: Java 21
- **Build**: Gradle multi-módulo
- **WebSocket**: OkHttp3
- **Database**: PostgreSQL 16
- **Observabilidade**: Prometheus + Grafana + Loki
- **Documentação**: Swagger/OpenAPI 3

## Arquitetura

### Módulos

- **core/**: Domínio, DTOs, ports, use cases
- **spring-application/**: Controllers REST, services, configuração Spring
- **adapter-binance/**: Integração Binance WebSocket
- **adapter-coinbase/**: Integração Coinbase WebSocket
- **adapter-mock/**: Simulação para desenvolvimento
- **strategy/**: Algoritmos de trading (SMA implementado)

## Estrutura

```
ctrade/
├── core/                           # Domínio e contratos
├── spring-application/             # Aplicação Spring Boot
├── adapter-binance/               # Integração Binance
├── adapter-coinbase/              # Integração Coinbase
├── adapter-mock/                  # Mock para testes
├── strategy/                      # Estratégias de trading
├── build.gradle                   # Build root
└── settings.gradle                # Configuração módulos
```

## Funcionalidades

- WebSocket management (connect/disconnect/status)
- Market data streaming (ticker, book, trades)
- Order management via WebSocket
- Multiple exchange support (Binance, Coinbase, Mock)
- Event-driven architecture
- Strategy framework (SMA implementado)

## Como Executar

### Pré-requisitos
- Java 21
- Gradle

### Comandos

```bash
# Build todos os módulos
./gradlew build

# Executar aplicação
./gradlew :spring-application:bootRun

# Build módulo específico
./gradlew :core:build
```

### Padrao local para Gradle

Para execucao local e pelos agentes, o projeto passa a usar um unico cache Gradle do repositorio:

- `GRADLE_USER_HOME=.gradle-local`
- nao criar diretorios paralelos como `.gradle-local-pr`, `.gradle-local-ci` ou equivalentes
- em caso de lock transitório, reutilizar o mesmo cache com retry

Use o wrapper documentado abaixo no PowerShell:

```powershell
./scripts/gradle-run.ps1 -q :core:compileJava
./scripts/gradle-run.ps1 -q :spring-application:test
./scripts/gradle-run.ps1 build
```

O script `scripts/gradle-run.ps1` fixa `GRADLE_USER_HOME` em `.gradle-local` e faz retry curto quando encontrar lock de execucao concorrente.

### Variáveis de ambiente

| Variável | Obrigatória | Default | Descrição |
|---|---|---|---|
| `BINANCE_API_KEY` | Não* | `""` | API Key da Binance. Sem ela o adapter Binance não é carregado |
| `BINANCE_API_SECRET` | Não* | `""` | API Secret da Binance. Obrigatório junto com `BINANCE_API_KEY` |
| `LOKI_URL` | Não | `http://loki:3100` | URL do Loki para envio de logs. Usar `http://localhost:3100` ao rodar fora do Docker |
| `SPRING_PROFILES_ACTIVE` | Não | _(nenhum)_ | Perfis Spring ativos: um de exchange (`testnet`) e, para operar como produção, `prod` (ex.: `testnet,prod`). Ver "Perfis" abaixo |
| `DISCORD_WEBHOOK_URL` | Com `prod` | `""` | Webhook do canal de alertas. Vazio desliga as notificações; com o perfil `prod` o ctrade não sobe sem ele |
| `DB_HOST` | Não | `localhost` | Host do PostgreSQL |
| `DB_PORT` | Não | `5432` | Porta do PostgreSQL |
| `DB_NAME` | Não | `ctrade` | Nome do banco |
| `DB_SCHEMA` | Não | `public` | Schema do ambiente no banco (`staging`, `soak`). O Flyway migra e o ctrade lê e grava só nele (T74) |
| `DB_USERNAME` | Não | `ctrade` | Usuário do banco |
| `DB_PASSWORD` | Não | `ctrade123` | Senha do banco |
| `ADMIN_API_KEY` | Não | `""` | Chave para endpoints administrativos protegidos |

*O par `BINANCE_API_KEY` + `BINANCE_API_SECRET` é necessário para qualquer operação real com a Binance.

#### Perfis

Dois eixos, combinados em `SPRING_PROFILES_ACTIVE` (T74):

| Perfil | Eixo | O que muda |
|---|---|---|
| _(nenhum)_ | exchange | Binance real (endereços padrão do `application.yml`) |
| `testnet` | exchange | Endereços da Binance Spot Testnet |
| `prod` | operação | Log `INFO` (sem o `DEBUG` do soak), boot fase 2 em `FAIL_FAST` com consulta de saldo obrigatória, webhook do Discord obrigatório |

Soak e desenvolvimento: `testnet`. Staging: `testnet,prod`. Produção (fase B da T74): `prod`, com os endereços
padrão.

#### Exemplo — IntelliJ Run Configuration

Adicione em **Run > Edit Configurations > Environment variables**:

```
BINANCE_API_KEY=sua-chave
BINANCE_API_SECRET=seu-secret
SPRING_PROFILES_ACTIVE=testnet
LOKI_URL=http://localhost:3100
```

#### Exemplo — linha de comando

```bash
BINANCE_API_KEY=sua-chave \
BINANCE_API_SECRET=seu-secret \
SPRING_PROFILES_ACTIVE=testnet \
LOKI_URL=http://localhost:3100 \
./gradlew :spring-application:bootRun
```

### Stack de infra (Docker)

```bash
# Apenas banco de dados
docker compose up -d

# Banco + observabilidade (Prometheus, Grafana, Loki)
docker compose --profile observability up -d

# Stack completa incluindo app containerizada
docker compose --profile observability --profile app up -d
```

Grafana disponível em `http://localhost:3000` (admin / ctrade123).

### Configuração

- **Porta**: 8080
- **Swagger**: `/swagger-ui/index.html`

## APIs

### Principais Endpoints

Toda a API (`/api/**`, leitura e escrita) exige o cabeçalho `X-Admin-Key` com o valor de
`ADMIN_API_KEY` (T55). `/actuator` (health, métricas) fica fora. Não há endpoint de ordem direta:
toda ordem nasce de uma transação do core, pelo runner.

```bash
# WebSocket Management
POST /api/websocket/connect
POST /api/websocket/disconnect
GET  /api/websocket/stats

# Market Data
POST /api/websocket/exchange/market-data/subscribe
POST /api/websocket/exchange/market-data/unsubscribe

# Exchange Queries
GET  /api/exchanges/{exchange}/balances          # ?asset=USDT filtra um ativo
GET  /api/exchanges/{exchange}/trades            # ?symbol=BTCUSDT&from=...&to=...

# Operacoes de um portfolio, por requestedAt, com a decisao da estrategia (reasoning, strategyMetadata; T74)
GET  /api/portfolios/{id}/transactions           # ?from=2026-11-01T00:00:00Z&to=2026-12-01T00:00:00Z (opcionais)
```

### Documentação
- **Swagger UI**: `http://localhost:8080/swagger-ui/index.html`
- **OpenAPI Spec**: `http://localhost:8080/v3/api-docs`

## Status

### Implementado
- Sistema WebSocket multi-exchange
- Market data streaming
- Order management
- Strategy framework (SMA)

### Em desenvolvimento
- Sistema P&L
- Performance metrics
- Risk management
- Backtesting engine


