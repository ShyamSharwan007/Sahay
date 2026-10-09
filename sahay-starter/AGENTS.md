# AGENTS.md — rules for every AI agent working in this repo

Project: **Sahay** — an offline-first Android app that keeps foreign tourists safe during floods and cyclones,
plus a FastAPI backend and a small admin web dashboard. 24-hour hackathon, 4 people working in parallel.

Read `docs/CONTRACTS.md` (all specs) and, for any UI work, `docs/DESIGN.md` before writing code.

## Ownership — you may ONLY create or edit files in your owner's folders

| Folder | Owner |
|---|---|
| `android/app`, `android/core/designsystem`, `android/settings.gradle.kts`, `android/build.gradle.kts`, `android/gradle/**`, `android/gradle.properties`, `android/keystore/**`, `.gitignore` | Person A |
| `android/engine` | Person B |
| `backend/`, `samples/`, `docs/wire_test_vectors.json` | Person C |
| `android/comms`, `admin/`, `README.md`, `demo/` | Person D |
| `android/core/contracts`, `docs/CONTRACTS.md`, `docs/DESIGN.md`, `AGENTS.md`, `CLAUDE.md` | FROZEN — do not edit unless the prompt says "contract change approved". Exception: Person A may change the two values in `SahayConfig` (`BASE_URL`, `SMS_GATEWAY_NUMBER`) |

Phase 0 exception: Person A's scaffold prompt (A0) creates the empty `engine` and `comms` modules, including their `Fake*` classes. From then on, those modules belong to B and D.

The prompt you receive always states which person you are working for. If it does not, ask.

## Hard rules

1. Never create, edit, move, rename or reformat files outside your owner's folders. No "drive-by" fixes in other modules.
2. The only shared file you may touch is `android/gradle/libs.versions.toml`, and only to APPEND a missing entry (pull first, keep the diff tiny).
3. Code only against the models and interfaces in `android/core/contracts` and the API, wire and pack specs in `docs/CONTRACTS.md`.
   If something you need is missing or wrong, STOP and add a short request to `docs/CONTRACT_REQUESTS.md` (this file is append-only for everyone) instead of changing the contract.
4. Use the latest stable versions of libraries. Android: Kotlin 2.x, Jetpack Compose, Material 3, Hilt (KSP), minSdk 26.
5. Offline first: nothing critical may require internet. Never block the UI thread or the UI on a network call. Every network call has a timeout (10 s) and a graceful fallback.
6. Handle denied permissions, no GPS fix, no SIM, airplane mode and empty data without crashing. Show a clear empty/error state.
7. Keep fake data only in `Fake*` classes inside your own module. No hard-coded fake data in real implementations.
8. Never commit secrets (`.env`, service-account JSON, private keys). `google-services.json` and the shared debug/release keystore ARE committed on purpose.
9. After each task: build or test your module (e.g. `./gradlew :engine:assembleDebug`, `pytest`, `npm run build`), fix every error, then reply with a short list of files changed and anything the other people need to know.
10. Commit messages start with your person letter: `[A] ...`, `[B] ...`, `[C] ...`, `[D] ...`.
11. Write clean, small, well-named functions with brief comments for non-obvious logic. Handle edge cases explicitly — judges score code quality and robustness (35%).
