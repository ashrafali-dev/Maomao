# Maomao

**Maomao** is a native Android companion UI for XINCLI running in Termux. Android owns the interface; Termux stays the only local execution runtime. No Alpine, Ubuntu, PRoot or second Linux distribution is installed.

**Status:** early prototype (0.1.0), not a finished release. The complete Android source is browseable under [android/](android/). The persistent feature checklist is [ROADMAP.md](ROADMAP.md).

## Included in this first implementation

- Kotlin + Jetpack Compose native shell with Chat, Files, Git, Terminal and Settings tabs.
- Termux `RUN_COMMAND` launch path for a background local bridge.
- Authenticated bridge restricted to `127.0.0.1`.
- XINCLI structured `stream-json` input/output, session resume and fork.
- Native tool approval prompts; no automatic `--dangerously-skip-permissions`.
- Local session metadata and chat cache, plus interrupted-session resume affordance.
- V8 heap/RSS telemetry with warning levels at 70%, 80% and 88%.
- Basic workspace listing, Git status and Termux command execution.
- Full phased checklist in [ROADMAP.md](ROADMAP.md).

## Build

Open `android/` in Android Studio with JDK 17 and Android SDK 36, or run from the repository root:

```bash
gradle -p android :app:assembleDebug
gradle -p android :app:testDebugUnitTest
```

GitHub Actions runs both checks for branch pushes and pull requests.

## One-time Termux setup

1. Install Termux and Node.js.
2. Install your permitted XINCLI CLI copy in Termux (the CLI itself is not bundled here).
3. In `~/.termux/termux.properties`, set `allow-external-apps = true`.
4. Grant Maomao Android's **Run commands in Termux environment** permission.

Android requires the user to grant the Termux command permission. Maomao does not silently bypass it.

## Safety and limits

The bridge listens only on loopback and uses a random app token. The memory preload reports process metrics; it does not kill XINCLI or claim to fix a memory leak. After a crash, the app offers to resume a saved session, but an interrupted tool operation must be checked before retrying. Terminal commands run with the Termux user's permissions.

## Open-source inspiration

- [Aider](https://github.com/Aider-AI/aider): repository-map and automated test/repair ideas.
- [Cline](https://github.com/cline/cline): human approvals, checkpoints and undo direction.
- [Goose](https://github.com/block/goose): reusable recipes and task workflows.
- [OpenHands](https://github.com/All-Hands-AI/OpenHands): separation of runtime, events and UI.

These are product patterns, not copied source code.
