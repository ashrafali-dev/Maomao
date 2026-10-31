# Maomao

**Maomao** is a native Android companion UI for XINCLI running in Termux. Android owns the interface; Termux stays the only local execution runtime. No Alpine, Ubuntu, PRoot or second Linux distribution is installed.

**Status:** early prototype (0.2.0), not a finished release. Source lives under [android/](android/); the complete phased checklist and explicit limits are in [ROADMAP.md](ROADMAP.md).

## Included

- Native Kotlin + Jetpack Compose screens: Chat, Files, Git, Terminal and Settings.
- Termux `RUN_COMMAND` startup and an authenticated loopback-only Node bridge.
- XINCLI structured `stream-json` input/output, session resume and fork.
- Native tool approvals. Maomao never adds `--dangerously-skip-permissions`.
- Local session metadata, cached chat history and interrupted-session resume.
- V8 heap/RSS telemetry with warning levels at 70%, 80% and 88%.
- Provider settings for Anthropic, OpenAI and OpenAI-compatible endpoints; API keys are encrypted using Android Keystore AES-GCM.
- Workspace browser and UTF-8 text editor with basic lexical syntax highlighting, SHA-256 stale-write checks and pre-save undo checkpoints.
- Git status, diff, local branch list/create/switch, commit, fetch, pull and push.
- Confirmation before commit/switch/pull/push and pattern-detected destructive terminal commands.
- Per-file checkpoints before XINCLI Write/Edit-family file tools; conflict-checked preview and restore.
- A bounded repository map with key files, symbols, imports and dependency edges.

## Build

Open `android/` in Android Studio with JDK 17 and Android SDK 36, or run from the repository root:

```bash
gradle -p android :app:assembleDebug
gradle -p android :app:testDebugUnitTest
```

GitHub Actions also runs Node syntax checks, a bridge integration smoke test, the Android debug build and unit tests. Successful CI runs publish a debug APK artifact.

## One-time Termux setup

1. Install Termux and Node.js.
2. Install your permitted XINCLI CLI copy in Termux. The CLI itself is not redistributed here.
3. In `~/.termux/termux.properties`, set `allow-external-apps = true`.
4. Grant Maomao Android's **Run commands in Termux environment** permission.

Android requires the user to grant the Termux command permission.

## Current limits

- The editor handles regular UTF-8 text files up to 1 MiB. Its highlighting is lexical, not language-server or semantic highlighting.
- Git diff shows tracked changes against HEAD. Untracked paths appear in Git status, but their file contents are not rendered in the diff panel.
- Remote Git actions need a configured remote and working Termux Git credentials.
- Automatic file checkpoints cover recognized XINCLI Write/Edit/MultiEdit/NotebookEdit/CreateFile/FileWrite tools. Arbitrary Bash/shell side effects are not automatically reversible.
- Destructive terminal detection is rule-based and cannot identify every shell indirection. Commands still execute with the Termux user's permissions; inspect unfamiliar commands.
- The repository map is a bounded heuristic scan, not a compiler-backed dependency graph.
- CI tests do not replace physical-device validation of Termux background execution, permissions, API provider compatibility, low-RAM recovery or release signing.

## Open-source inspiration

- [Aider](https://github.com/Aider-AI/aider): repository-map and automated test/repair ideas.
- [Cline](https://github.com/cline/cline): human approvals, checkpoints and undo direction.
- [Goose](https://github.com/block/goose): reusable recipes and task workflows.
- [OpenHands](https://github.com/All-Hands-AI/OpenHands): separation of runtime, events and UI.

These are product patterns, not copied source code.
