# Maomao

Maomao is a native Android companion UI for XINCLI running in Termux. Android owns the interface; Termux remains the local runtime. No Alpine, Ubuntu, PRoot, or extra Linux distribution is installed.

**Status:** early prototype (0.1.0), not a finished release. The Android project snapshot is stored in `maomao-android-source.tar.gz`. GitHub Actions extracts it and runs the Android build/tests.

## Included in this first implementation

- Native Compose shell with Chat, Files, Git, Terminal and Settings tabs.
- Termux `RUN_COMMAND` launch path for a background local bridge.
- Authenticated bridge restricted to `127.0.0.1`.
- XINCLI structured `stream-json` input/output, session resume and fork.
- Tool approval prompts; no automatic `--dangerously-skip-permissions`.
- Local session metadata and chat cache, interrupted-session resume affordance.
- V8 heap/RSS monitoring with 70%, 80% and 88% warning levels.
- Basic workspace listing, Git status and Termux command execution.
- Full feature-by-feature checklist in [ROADMAP.md](ROADMAP.md).

## Build locally

Download and extract `maomao-android-source.tar.gz` from this repository, then open the extracted folder in Android Studio (JDK 17, Android SDK 36). Or run:

```bash
gradle :app:assembleDebug
gradle :app:testDebugUnitTest
```

GitHub Actions runs the same checks for pushes and pull requests.

## One-time Termux setup

Install Termux and Node.js, then install XINCLI with its own installer. In `~/.termux/termux.properties`, set:

```properties
allow-external-apps = true
```

Grant Maomao the Android **Run commands in Termux environment** permission. Android will not silently grant this permission.

## Safety and limits

The bridge listens only on loopback and uses a random app token. The memory preload only reports metrics; it does not terminate XINCLI or claim to repair a memory leak. A crash recovery action resumes the saved session, but an interrupted tool operation must be checked before retrying. The terminal runs commands as the Termux user.

The XINCLI CLI source is not redistributed in this repository; install your own permitted copy in Termux.

## Open-source product inspiration

- [Aider](https://github.com/Aider-AI/aider): repository map and test/repair loop ideas.
- [Cline](https://github.com/cline/cline): human approval, checkpoints and undo direction.
- [Goose](https://github.com/block/goose): reusable recipes and task workflows.
- [OpenHands](https://github.com/All-Hands-AI/OpenHands): separation of runtime, events and UI.

These are product patterns, not copied source code.
