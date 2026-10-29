# Arquitetura

## Principios

- O projeto segue clean architecture e arquitetura hexagonal.
- As dependencias devem apontar para dentro, em direcao as regras de negocio.
- Regras de negocio nao devem depender de framework, transporte, persistencia ou schema de provider.
- Cada modulo deve ter responsabilidade unica e clara.

## Dono de cada modulo

### `core/`

Responsavel por:

- entidades de dominio
- value objects
- ports
- use cases
- regras de negocio
- orquestracao de comportamento de dominio

Nao deve conter:

- anotacoes ou abstracoes do Spring
- HTTP, WebSocket, mensageria ou banco
- DTOs especificos de exchange
- detalhes de SDK ou provider

### `strategy/`

Responsavel por:

- logica deterministica de estrategia
- calculo e decisao a partir de entradas explicitas

Nao deve conter:

- persistencia
- rede
- controllers
- clients HTTP
- wiring de infraestrutura
- efeitos colaterais que nao sejam inerentes ao calculo

### `spring-application/`

Responsavel por:

- composicao da aplicacao
- configuracao de beans
- controllers
- adapters de infraestrutura para ports do `core`
- setup de execucao
- traducao entre entradas externas e casos de uso

Nao deve conter:

- regra de negocio que deveria estar no `core`
- calculo de estrategia (fica nas apps de estrategia, fora do ctrade)
- DTOs mantidos como regra permanente da camada

### `adapter-*`

Responsavel por:

- payloads de provider
- request/response DTOs de exchange
- parsing
- mapeamento
- traducao de schemas externos

Nao deve conter:

- regra de negocio
- politica de dominio
- decisao de estrategia
- orquestracao global da aplicacao

## Regras de dependencia

- `core` nao depende de `spring-application` nem de `adapter-*`
- `spring-application` pode compor `core` e adapters
- estrategias nao sao modulo do ctrade: sao apps externas que implementam o contrato (T65)
- `adapter-*` deve depender do contrato necessario, sem empurrar detalhes externos para dentro

## Sinais de violacao

- Um payload de exchange aparece no `core`
- Um controller decide regra de negocio
- Um adapter decide politica de dominio
- Um calculo de estrategia exige acesso direto a infraestrutura
- Uma mudanca pequena exige editar varios modulos por acoplamento indevido

