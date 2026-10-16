# Handoff: Apitomy Axiom as of 2026-10-08

These documents capture the state of Axiom, and what we learned getting here, so that a new session can start
the **integrated, AI-based Developer Experience (DX)** for building workflows together with their action types
and tools, without losing context.

| Document | Read it for |
|----------|-------------|
| [01-axiom-current-state.md](01-axiom-current-state.md) | What Axiom is today: modules, domain model, action types, tools, workflows, events, UI, build |
| [02-configuration-assistant.md](02-configuration-assistant.md) | How the AI Configuration Assistant works end to end: sessions, engines, MCP, reference, validation, apply, UI |
| [03-developer-experience-kickoff.md](03-developer-experience-kickoff.md) | Building blocks for the new DX, gaps, lessons learned, open design questions, how we work |

## Where we stopped

- Branch: `feat/workflow-first` (not merged, not pushed). Head: `ad6a7c01`.
- Local annotated tag: **`checkpoint/flow-2.7-upgrade`**, pointing at that head. Return to it with
  `git switch -c <branch> checkpoint/flow-2.7-upgrade`.
- At the checkpoint, `mvn package` passed all 847 Java tests and the UI's `npx vitest run` passed all 38
  tests.
- The user manually tested the Configuration Assistant and workflows, and reported them as working well.

## Starting the next session

1. Read these three documents, then `CLAUDE.md` (API-first rules) and
   `docs/developer-guide/api-first-development.md`.
2. Use the brainstorming skill before designing the DX. The open questions in document 03 are the agenda.
3. Do not run `./build.sh` or `mvn clean` while the user has `quarkus:dev` running: it deletes `target/` and
   kills the dev server. Use `mvn package` or targeted `mvn -pl app -am test -Dtest=...` instead.
