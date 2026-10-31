# Maomao implementation roadmap

This is the persistent checklist for the whole project. Keep this file up to date in every implementation PR. Items are intentionally ordered by dependency and user impact; do not silently drop unfinished work.

## Product contract

- [x] Native Android app UI (Kotlin + Jetpack Compose), not a terminal wrapper.
- [x] Termux is the backend/runtime; no Alpine, Ubuntu, PRoot, or extra distro download.
- [x] Use Termux `RUN_COMMAND` so the user does not have to manually open Termux for every run.
- [x] Keep XINCLI as the execution engine; do not reimplement its agent engine.
- [x] Keep tool permissions user-controlled; never enable `--dangerously-skip-permissions` by default.
- [ ] Complete first-run Termux setup wizard and diagnostics.

## Phase 1 — Native runtime and chat foundation

- [x] Create Android app scaffold and GitHub Actions build workflow.
- [x] Add authenticated loopback Node bridge, bound to `127.0.0.1` only.
- [x] Launch XINCLI through Termux `RUN_COMMAND`.
- [x] Use XINCLI structured `stream-json` input/output (`-p`, `--verbose`).
- [x] Add native chat screen and display assistant/tool activity events.
- [x] Add per-session local chat cache and session metadata index.
- [x] Resume by session ID without showing CLI commands to the user.
- [x] Add fork-session action so the original transcript is preserved.
- [x] Add control-request approval UI (Allow once / Deny).
- [x] Add memory telemetry using `process.memoryUsage()` and V8 heap limit.
- [x] Add interrupted-session detection and safe resume affordance after a process crash.
- [x] Add five-attempt exponential reconnect retry/backoff and restore the live session ID from the bridge handshake; explicit bridge health/diagnostic screen remains pending.
- [ ] Parse all stream event variants, including tool results, partial messages, and elicitation.
- [ ] Add reliable stop/cancel semantics and process cleanup UI.

## Phase 2 — Memory safety and crash recovery

- [x] Track heap used, heap total, V8 heap limit, RSS, external memory, and array buffers.
- [x] Warn at 70%, 80%, and 88% of the V8 heap limit (watch/high/critical).
- [x] Keep memory instrumentation read-only; it must never terminate the CLI on its own.
- [x] Save session ID, title, workspace path, and last activity in app-private storage.
- [ ] Add atomic checkpoint records for current task, phase, active worker IDs, changed files, and last confirmed operation.
- [ ] Add file-system recovery check before retrying an interrupted tool action.
- [x] Block new user prompts and terminal commands at the 88% critical heap threshold (never force-kill an in-flight CLI task).
- [ ] Add safe pause/stop controls for high-memory sessions and worker-aware graceful yielding.
- [ ] Add device-adaptive agent concurrency governor; do not assume a fixed RAM size.
- [ ] Add bounded output readers, truncation previews, and large-file streaming.
- [ ] Add diagnostics view with memory timeline, worker count, large tool output warnings, exit code, and last checkpoint.
- [ ] Add crash/OOM instrumentation tests on a real low-RAM Android device.

## Phase 3 — Workspace and Git tools

- [x] Add basic Termux workspace file listing.
- [x] Add basic Git working-tree status.
- [x] Add simple native command terminal.
- [ ] Add file preview, code viewer, and editor.
- [ ] Add diff viewer and changed-file summary.
- [ ] Add branch list, switch, create, commit, fetch, pull, and push controls.
- [ ] Add explicit confirmation for destructive Git actions and commands.
- [ ] Add Shadow-Git-style checkpoint/undo mechanism before agent edits.
- [ ] Add rollback preview and conflict-safe restore.
- [ ] Add repository map (symbol index, imports, key files, and dependency graph) inspired by Aider.

## Phase 4 — Agent workflow and context management

- [ ] Add native task board for coordinator/worker agents inspired by OpenHands.
- [ ] Show worker state, assigned task, changed files, errors, and completion events.
- [ ] Add worker concurrency policy and queueing under memory pressure.
- [ ] Add context budget monitor, compact/summarize controls, and older-message recall.
- [ ] Add tool-output size caps and duplicate-output detection.
- [ ] Add project-level memory and searchable chat recall.
- [ ] Add task plans with phases and checkpoints.
- [ ] Add Goose-style reusable workflow recipes for common developer tasks.
- [ ] Add automatic test, lint, and repair loop inspired by Aider.

## Phase 5 — Native feature coverage

- [ ] Add session browser with search, rename, archive, delete, and branch/fork controls.
- [ ] Add code/file explorer with syntax highlighting and edit/preview modes.
- [ ] Add terminal history and multiple named terminals.
- [ ] Add full GitHub manager (repositories, branches, commits, PRs, checks).
- [ ] Add model/provider selector, effort controls, and fallback settings.
- [ ] Add MCP server management, plugin/agent management, skills, and hooks UI.
- [ ] Add settings editor for CLI config and project-level config.
- [ ] Add slash-command autocomplete and native adapters for local JSX commands.
- [ ] Add command palette and keyboard shortcuts for tablets/keyboards.
- [ ] Add task notifications and foreground-service handling for long jobs.

## Phase 6 — Reliability, release, and security

- [ ] Add full protocol contract tests between Kotlin and the Node bridge.
- [ ] Add bridge authentication, malformed-frame, reconnect, and replay-buffer tests.
- [ ] Add Gradle lint and dependency verification.
- [ ] Add Android instrumentation tests and accessibility checks.
- [ ] Add release signing configuration via GitHub secrets (never commit signing keys).
- [ ] Add APK artifact publishing and versioned release notes.
- [ ] Add privacy review: local-only bridge, no analytics by default, secrets redaction.
- [ ] Audit shell command execution, file path handling, symlinks, and workspace boundaries.
- [ ] Document supported Termux versions and one-time configuration.

## Open-source product patterns used

| Project | Pattern used in Maomao | What is not copied |
|---|---|---|
| Aider | Repository map, test/repair loop ideas | Aider source or agent implementation |
| Cline | Human approval, checkpoint/undo direction | Cline source or VS Code extension UI |
| Goose | Reusable workflow recipes | Goose source or runtime |
| OpenHands | Runtime/UI boundary, task board and worker visibility | OpenHands source or cloud execution backend |

## Acceptance rule

An item is marked complete only when its implementation exists in the repo and its test/build status is recorded. A UI placeholder or roadmap paragraph alone does not count as implementation.