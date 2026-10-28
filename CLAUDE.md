# CLAUDE.md

This file is the Claude Code bridge for this repository.

## Mandatory Startup Protocol

Before any implementation, review, refactor, architectural suggestion, or documentation update:

1. Read `/.ai/README.md`.
2. Follow the "Contexto minimo por tarefa" matrix defined there.
3. State which `/.ai` documents were consulted when the task involves implementation, review, refactor, architecture, or documentation.

`/.ai` is the canonical source for this repository. This file should only adapt that policy for Claude Code and must not redefine detailed project rules.

## Development Manual (mandatory)

Every implementation, refactor, review, task spec, or architectural suggestion must follow
`/.ai/development-manual.md`. Its core rule is **responsibility placement**: business decisions in
`core`, decision math in external strategy apps (contract only), provider knowledge in `adapter-*`, technical mechanics and
composition in `spring-application`. Its second rule is **one flow, one path** (section 1.3): few
branches, no parallel near-duplicate functions; migrate callers and remove the old path. Use its
decision table, rejected anti-patterns, delegated-work protocol, and pre-PR checklist.

## Canonical Source

Use `/.ai/README.md` as the operational index for:

- architecture boundaries
- implementation standards
- review heuristics
- minimum validation
- documentation updates
- shared agents and skills

Do not duplicate detailed rules here. If this file diverges from `/.ai`, `/.ai` takes precedence.

## Review and PR Publication

For PR or diff reviews, also use:

- `/.ai/review.md`
- `/.ai/agents/code-review-agent.md`

To publish review findings back to a PR, use the shared skill:

- `/.ai/skills/pr-review-publisher/`

Reviews should prioritize correctness, architecture boundaries, regressions, and missing validation over style-only comments.
