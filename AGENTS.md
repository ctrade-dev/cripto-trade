# AGENTS.md

## Protocolo Obrigatorio de Inicio

Antes de qualquer implementacao, review, refactor, sugestao arquitetural ou atualizacao de documentacao:

1. Leia `/.ai/README.md`.
2. Siga a matriz "Contexto minimo por tarefa" definida ali.
3. Declare na resposta quais documentos de `/.ai` foram consultados quando a tarefa envolver implementacao, review, refactor, arquitetura ou documentacao.

`/.ai` e a fonte canonica deste repositorio. Este arquivo e apenas uma ponte para agentes que carregam `AGENTS.md` automaticamente.

## Manual de Desenvolvimento (obrigatorio)

Toda implementacao, refactor, review, spec de task ou sugestao arquitetural deve seguir
`/.ai/development-manual.md`. A regra central e a **disposicao de responsabilidades**: decisao de
negocio no `core`, calculo de decisao nas apps de estrategia (fora do ctrade), conhecimento de provider nos `adapter-*`,
mecanica tecnica e composicao no `spring-application`. A segunda regra e **um fluxo, um caminho**
(secao 1.3): poucas bifurcacoes, sem funcoes paralelas que fazem quase a mesma coisa. Use a tabela
de decisao, os anti-padroes rejeitados, o protocolo de trabalho delegado e o checklist pre-PR.

## Fonte Canonica

Use `/.ai/README.md` como indice operacional para:

- fronteiras arquiteturais
- padroes de implementacao
- heuristicas de review
- validacao minima
- atualizacao de documentacao
- agentes e skills compartilhadas

Nao duplique regras detalhadas aqui. Se houver divergencia entre este arquivo e `/.ai`, prevalece `/.ai`.

## Review e Publicacao em PR

Para review de PR ou diff, use tambem:

- `/.ai/review.md`
- `/.ai/agents/code-review-agent.md`

Para publicar findings em PR, use a skill compartilhada:

- `/.ai/skills/pr-review-publisher/`

Reviews devem priorizar correcao, limites arquiteturais, regressao e falta de validacao acima de comentarios apenas de estilo.
