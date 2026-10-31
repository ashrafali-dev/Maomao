# Maomao implementation roadmap

This is the persistent checklist. Keep it updated on every implementation change. A task is complete only when code and a test/build record exist.

## Product contract
- [x] Native Kotlin + Jetpack Compose Android UI.
- [x] Termux-only backend; no Alpine, Ubuntu, PRoot or second distro.
- [x] Use Termux RUN_COMMAND so Termux need not be opened manually for every run.
- [x] Keep XINCLI as the agent engine.
- [x] Keep tool permissions user-controlled; never auto-enable dangerous permission bypass.
- [ ] First-run Termux setup wizard and diagnostics.

## Phase 1 — Runtime and chat
- [x] Android project scaffold and GitHub Actions workflow.
- [x] Authenticated Node bridge on 127.0.0.1 only.
- [x] Launch XINCLI through Termux RUN_COMMAND.
- [x] Structured stream-json stdin/stdout.
- [x] Native chat and tool activity display.
- [x] Session metadata and local chat cache.
- [x] Resume by session ID and fork session.
- [x] Allow once / Deny permission prompt.
- [x] Memory telemetry and interrupted-session recovery affordance.
- [ ] Reconnect retry/backoff and bridge health screen.
- [ ] Complete parsing for all stream event variants.
- [ ] Stop/cancel semantics and process cleanup.

## Phase 2 — Memory and recovery
- [x] Heap used/total/limit, RSS, external and array-buffer metrics.
- [x] 70%, 80%, 88% memory warning levels.
- [x] Read-only telemetry; monitor must not kill the CLI.
- [x] Persist session ID, title, workspace and last activity.
- [ ] Atomic task checkpoints: phase, worker IDs, changed files, last confirmed operation.
- [ ] Inspect workspace before retrying interrupted tool actions.
- [ ] Safe pause/stop controls during memory pressure.
- [ ] Device-adaptive agent concurrency governor.
- [ ] Bounded tool output, truncation preview and large-file streaming.
- [ ] Diagnostic timeline: memory, workers, output sizes, exit status and checkpoint.
- [ ] Low-RAM Android OOM tests.

## Phase 3 — Workspace and Git
- [x] Basic Termux workspace listing.
- [x] Basic Git status.
- [x] Basic native terminal.
- [ ] File preview, syntax-highlighted editor and save flow.
- [ ] Diff viewer and changed-file summary.
- [ ] Branch list/create/switch, commit, fetch, pull and push.
- [ ] Confirm destructive Git operations.
- [ ] Shadow-Git-style checkpoint/undo and rollback preview.
- [ ] Aider-inspired repository map (symbols, imports, key files, dependency graph).

## Phase 4 — Agent and context workflow
- [ ] OpenHands-inspired coordinator/worker task board.
- [ ] Worker state, assignments, changed files, errors and completion.
- [ ] Concurrency queue under memory pressure.
- [ ] Context budget, compact/summarize and older-message recall.
- [ ] Tool-output caps and duplicate-output detection.
- [ ] Project memory and searchable chat recall.
- [ ] Task plan with phases/checkpoints.
- [ ] Goose-inspired reusable workflow recipes.
- [ ] Aider-inspired automatic test/lint/repair loop.

## Phase 5 — Native feature coverage
- [ ] Searchable session browser, rename, archive, delete, fork.
- [ ] Full code/file explorer and preview/edit modes.
- [ ] Multiple named terminals and terminal history.
- [ ] GitHub repositories, branches, commits, PRs and CI checks.
- [ ] Model/provider, effort and fallback settings.
- [ ] MCP, plugins, agents, skills and hooks screens.
- [ ] CLI/project config editor.
- [ ] Slash-command autocomplete and native adapters for local JSX commands.
- [ ] Command palette and keyboard shortcuts.
- [ ] Long-task notifications and foreground-service handling.

## Phase 6 — Reliability and release
- [ ] Protocol, auth, malformed-frame, reconnect and replay tests.
- [ ] Gradle lint and dependency verification.
- [ ] Android instrumentation and accessibility tests.
- [ ] Release signing via GitHub secrets only.
- [ ] Versioned APK artifacts and release notes.
- [ ] Privacy review and secret redaction.
- [ ] Shell/path/symlink/workspace security audit.
- [ ] Supported Termux version/setup documentation.

## Open-source inspiration matrix

| Source | Pattern to adapt | Status |
|---|---|---|
| Aider | Repository map; test/repair loop | Planned |
| Cline | Permission prompts; checkpoint/undo | Approval UI started; undo planned |
| Goose | Recipes and task workflows | Planned |
| OpenHands | Runtime/UI separation; worker task board | Bridge/UI split started; task board planned |

## Current implementation boundary

Implemented items are early/basic versions. The roadmap deliberately leaves unimplemented items unchecked. Do not describe Maomao as feature-complete until the acceptance tests pass.
