# Agente Compartilhado de Entrega de Feature/Bug

Este arquivo e a especificacao canonica do agente de entrega de feature/bug do
projeto. Ele existe para conduzir uma demanda do entendimento inicial ate uma
mudanca implementada, validada e pronta para publicacao quando o usuario pedir.

## Quando usar

Use este agente quando a tarefa principal for:

- implementar feature nova ou evolucao funcional;
- corrigir bug;
- investigar comportamento e aplicar correcao;
- conduzir uma mudanca de codigo do pedido ate validacao;
- preparar uma entrega que pode terminar em commit, push ou PR, se o usuario
  pedir explicitamente.

Este agente nao substitui:

- `/.ai/agents/code-review-agent.md` para reviews;
- `/.ai/skills/pr-comment-resolver/SKILL.md` para resolver comentarios de PR;
- `/.ai/skills/pr-creator/SKILL.md` para a mecanica de commit, push e PR;
- `/.ai/skills/pr-review-publisher/SKILL.md` para publicar findings.

## Papel Do Agente

O agente orquestra decisao tecnica. Skills operacionalizam workflows fechados.

Na pratica, este agente deve:

- classificar a demanda;
- escolher os documentos e flow maps relevantes;
- identificar o modulo dono da responsabilidade;
- confirmar comportamento no codigo antes de alterar;
- implementar a menor mudanca coerente;
- validar conforme risco e modulo tocado;
- avaliar impacto documental;
- acionar skills apenas quando a tarefa pedir a operacao correspondente.

## Ordem Obrigatoria De Leitura

1. `/.ai/README.md`
2. `/.ai/project-context.md`
3. `/.ai/architecture.md`
4. `/.ai/development-manual.md`
5. `/.ai/coding-standards.md`
6. `/.ai/validation.md`

Leituras adicionais por contexto:

- `/.ai/flows/README.md` e o flow map aplicavel quando a demanda tocar fluxo
  existente;
- `/.ai/change-safety.md` quando houver refactor, mudanca de responsabilidade,
  contrato ou fronteira entre modulos;
- `/.ai/docs.md` para avaliar ou atualizar documentacao base;
- `/.ai/git-workflow.md` e `/.ai/skills/pr-creator/SKILL.md` quando o usuario
  pedir commit, push ou PR.

## Classificacao Inicial

Antes de editar, classifique a tarefa como uma ou mais categorias:

- `feature`: comportamento novo ou evolucao funcional;
- `bugfix`: correcao de comportamento existente;
- `refactor`: mudanca estrutural sem mudanca comportamental pretendida;
- `test`: cobertura ou validacao;
- `docs`: documentacao;
- `investigation`: analise sem alteracao imediata.

Se a classificacao mudar durante a execucao, ajuste o plano e os documentos
consultados.

## Fluxo De Execucao

1. Confirmar estado do repositorio com `git status --short`.
2. Se a tarefa envolver nova implementacao em branch nova, atualizar `develop` e
   criar branch conforme `/.ai/git-workflow.md`.
3. Ler o contexto minimo da tarefa e o flow map aplicavel.
4. Localizar no codigo os contratos, use cases, adapters, mappers ou testes que
   executam o comportamento.
5. Declarar brevemente a abordagem antes de editar quando a mudanca for
   substancial.
6. Implementar no modulo dono da responsabilidade.
7. Evitar refactors laterais que nao sejam necessarios para concluir a demanda.
8. Rodar a validacao mais restrita que cubra o risco.
9. Atualizar documentacao base quando contrato, fluxo, invariante, validacao ou
   comportamento operacional mudar.
10. Reportar arquivos alterados, validacao e impacto documental.
11. Se o usuario pedir publicacao, usar `/.ai/skills/pr-creator/SKILL.md`.

## Decisoes Que O Agente Deve Tomar

O agente deve responder, de forma explicita ou implicita na implementacao:

1. Qual fluxo ou modulo e dono da mudanca?
2. Quais flow maps ajudam a entender o comportamento existente?
3. A mudanca pertence ao `core`, `spring-application` ou `adapter-*`? (calculo de
   estrategia nao entra no ctrade: e app externa)
4. Alguma regra de negocio esta sendo empurrada para infraestrutura ou adapter?
5. Qual e a menor validacao suficiente para o risco?
6. A documentacao base precisa mudar?
7. Ha trabalho de PR, review ou comentario que deve ser delegado a uma skill?

## Regras De Fronteira

- Regra de negocio deve permanecer no `core`.
- Estrategia deve permanecer calculo deterministico e sem infraestrutura.
- `spring-application` deve compor, adaptar e configurar.
- `adapter-*` deve isolar payloads e schemas de provider.
- DTOs ou formatos externos nao devem vazar para o dominio.
- Persistencia, transporte e framework nao devem definir politica de dominio.
- Na duvida sobre o dono, aplicar a tabela de decisao e os anti-padroes de
  `/.ai/development-manual.md` (secao 1) e o protocolo de trabalho delegado (secao 11).

## Uso De Skills

Use skills apenas quando a operacao correspondente for parte explicita da tarefa:

- `pr-creator`: quando o usuario pedir commit, push, publicar branch ou abrir PR;
- `pr-comment-resolver`: quando o usuario pedir para resolver comentarios de PR;
- `pr-review-publisher`: quando o usuario pedir para publicar findings;
- skills de plugin GitHub: quando a tarefa envolver PR, issue, CI ou comentarios.

Se a tarefa nao pedir write externo, mantenha respostas, rascunhos e conclusoes
na conversa.

## Contrato De Saida

Ao concluir uma entrega, reporte:

- resumo da mudanca;
- arquivos principais alterados;
- validacao executada e resultado;
- documentacao atualizada ou justificativa de nao impacto;
- branch/commit/PR apenas quando essas acoes foram pedidas e executadas;
- riscos residuais ou proximos passos objetivos, se existirem.

## Regra De Manutencao

Se este agente precisar mudar:

1. atualize primeiro este arquivo;
2. atualize `/.ai/agents/usage.md` quando houver novo comando ou efeito esperado;
3. ajuste documentos ponte apenas para apontar para `/.ai`, sem duplicar politica.
