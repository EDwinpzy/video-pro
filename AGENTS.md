# AGENTS.md

> **Official TV app: `frontend-tv/` only.** It is the sole release target
> (`0.3.3`, `versionCode 9`, `com.dongpiandi.videopro`). Root `app-tv` /
> `design-tv` / `dongpian-data` are experimental reference code: never version,
> build for distribution, or publish them. Read `docs/repository-layout.md`
> before touching TV source or version fields.

Guidance for coding agents (and humans) working in this repository. Keep it short and current; details live in `docs/`.

## Agent quick card

- **Official TV source:** `frontend-tv/app` (standalone Gradle project).
- **Official TV commands** (run from `frontend-tv`):

  ```powershell
  .\gradlew.bat :app:compileDebugKotlin
  .\gradlew.bat :app:testDebugUnitTest
  .\gradlew.bat :app:lintDebug :app:assembleDebug
  ```

- **Minimal TV verification:** compile + the nearest unit test. Add
  `:app:lintDebug` and `:app:assembleDebug` before handoff. Device tests only
  for D-pad/focus, playback, permissions, or other device-only behaviour.
- **Do not use root Gradle to verify TV changes.** Root tasks do not cover
  `frontend-tv`; a green root build says nothing about the official app.
- **Root KMP commands** are for the inherited mobile/experimental tree only.
  The root build needs an Android SDK path in `local.properties` or
  `ANDROID_HOME`; a missing SDK is an environment problem, not a source failure.
- **Skip generated/local directories when searching:** `build/`, `.gradle/`,
  `.kotlin/`, `frontend-tv/build/`, `frontend-tv/.gradle/`, `frontend-tv/dist/`,
  `.worktrees/`.
- **Version source:** `frontend-tv/app/build.gradle`.

## Project

Jellystack is a privacy-focused Jellyfin + Seerr client written in Kotlin Multiplatform with Compose.

- **Android phone/tablet** (`app-android`) – stable, published on Google Play.
- **Android TV / Google TV / Fire TV** (`frontend-tv/app`) – official GMS-free TV app.
  Root `app-tv` is an experimental KMP reference and is not released.
- **iOS** (`app-ios`) – experimental, not distributed, only built by a manual workflow.

No Jellystack backend exists: the app talks only to servers the user configures.

## Module map

| Module | What goes here |
| --- | --- |
| `frontend-tv/` | **Official** Android TV app (standalone Gradle build). All TV product code and resources go here. |
| `frontend-tv/voice-core`, `frontend-tv/voice-helper` | Vendor speech integration and optional voice helper used by the official TV app. |
| `shared-network` | Ktor clients and transport DTOs for Jellyfin and Seerr. No UI or persistence. |
| `shared-core` | Repositories, coordinators, settings, auth, secure storage, downloads, domain models, Koin core module (`di/Modules.kt`). |
| `shared-database` | SQLDelight schema (`src/commonMain/sqldelight`), numbered migrations (`migrations/N.sqm`), SQL store implementations. |
| `dongpian-data` | Dongpiandi signed HTTP transport, secure account session, catalog/playback/library repositories. TV migration only; no UI. |
| `players` | Playback resolution, `PlaybackController`, segments, continuation, SyncPlay, Cast abstraction. Shared by phone and TV. |
| `players-cast-google` | Google Cast implementation. Mobile only; must never reach the TV app. |
| `design` | Phone/tablet Compose UI and Compose resources. Composition root: `JellystackRoot.kt`. |
| `design-tv` | Experimental KMP Compose for TV UI. Composition root: `TvJellystackRoot.kt`. Reference only; not released. |
| `design-screenshots` | Screenshot tests for `design` with checked-in reference PNGs. |
| `design-tv-screenshots` | Screenshot tests for the experimental `design-tv` components (fixtures in `TvGoldenFixtures.kt`). Not the official TV gate. |
| `app-android`, `app-tv`, `app-ios` | Thin platform hosts: Activity/lifecycle, permissions, platform Koin module, packaging. Root `app-tv` is experimental; the official TV host is `frontend-tv/app`. |
| `testing`, `tools` | JVM-only leftovers (unused fixtures module, API generator scaffold). Do not build on them without reading `docs/tech-debt.md`. |

Dependency direction: `app-* → design / design-tv → players → shared-core → shared-network`; `design-tv → dongpian-data → shared-core / shared-network`; `shared-database → shared-core`. Code needed by both phone and TV UI belongs in `players` or `shared-core`, not in a UI module. See `docs/architecture.md`.

Naming quirks to respect (not to "fix" in passing): library packages are `dev.jellystack.*`, app packages are `app.jellystack.mobile` / `app.jellystack.tv` with Kotlin under `src/main/java`. KMP modules use `src/commonMain|androidMain|iosMain`; Android-only modules use `src/main/kotlin`.

## Commands

JDK 17, Android SDK 36. Run **root** Gradle commands from the repository root;
the official TV app is a separate build under `frontend-tv` (see the quick card
above).

```bash
./gradlew :app-android:assembleDebug          # phone build
./gradlew :app-tv:assembleDebug               # experimental KMP TV only; never a release artifact
./gradlew spotlessApply                       # format before committing
./gradlew spotlessCheck detekt                # static checks
./gradlew :<module>:testDebugUnitTest         # unit tests of one module
./gradlew :shared-network:jvmTest             # shared-network uses its JVM target for tests
./gradlew :dongpian-data:testDebugUnitTest    # Dongpiandi transport/session repositories
./gradlew :design-screenshots:updateDebugScreenshotTest   # after intended visual changes
./gradlew :design-tv-screenshots:updateDebugScreenshotTest   # same for TV UI
```

The full CI gate is listed in `docs/building.md` and mirrors `.github/workflows/ci.yml`. Before finishing a change, run `spotlessCheck detekt` plus the unit tests of every module you touched; for UI changes also validate screenshots.

For `frontend-tv`, run its own wrapper from `frontend-tv`; root
`spotlessCheck`/`detekt` and root tests do not validate it. Its full gate is in
`frontend-tv/README.md`.

## Git and GitHub

- Every completed update must be committed and pushed to GitHub. Do not leave finished work only in the local working tree.
- Keep commits focused. Stage only the files or hunks that belong to the update; never include unrelated user changes, generated files, secrets, APKs, or AABs just to make the tree clean.
- Work on a short-lived branch from `main`. Branches created by Codex use `codex/<topic>`; push the branch and open a pull request. Do not push directly to protected `main`.
- Use focused commit messages such as `docs: add versioning policy`, `fix(tv): handle playback failure`, or `test(shared-core): cover request rules`.
- Before handoff, verify that the update's files are committed, the branch is pushed, and `git status -sb` does not show the branch as ahead of its remote.
- A successful push is not a release. Bump versions, create release tags, and publish notes only when following [Versioning and releases](docs/versioning.md).

## Architecture rules

- Jellyfin is authoritative for library identity, playback, progress, favourites, watched state, streams, and availability. Seerr only enriches discovery and requests and must never block Jellyfin playback.
- Transport DTOs from `shared-network` do not reach UI state; map them in `shared-core`.
- Prefer common code. Platform behaviour goes behind a common interface or `AppPlatformCapabilities`.
- Resolve dependencies through Koin. Do not hand-construct repositories, coordinators, or `HttpClient`s inside composables or activities.
- **Do not grow the god files** `design/.../JellystackRoot.kt`, `design/.../jellyfin/JellyfinBrowseScreen.kt`, and `players/.../PlaybackController.kt`. Put new state logic in a separate state holder/coordinator and new composables in their own files; extracting code out of these files is welcome.
- Do not duplicate phone logic into `design-tv` (or the reverse). If both need it, move it to `players` or `shared-core` first.
- Schema changes need a new `shared-database/.../migrations/N.sqm`; never edit an existing migration.

## Code conventions

- Follow ktlint (`.editorconfig`) and detekt (`config/detekt/detekt.yml`). Fix findings instead of adding `@Suppress`; never add file-level suppressions for complexity rules.
- Coroutines: never swallow `CancellationException` – rethrow it before catching `Throwable`/`Exception`, and avoid `runCatching` around suspending calls. No `runBlocking` or `GlobalScope` in production code.
- Logging: use `JellystackLog` and pass URLs/identifiers through `sanitizeUrl` / `sanitizeIdentifier`. Never log tokens, passwords, cookies, or full server URLs.
- Strings: every user-visible string exists in English **and** German – `design/src/commonMain/composeResources/values{,-de}/strings.xml` for phone UI, `app-*/src/main/res/values{,-de}/strings.xml` for app hosts, `design-tv/.../TvStrings.kt` for TV. No hardcoded UI text.
- Use "Seerr" in new names and UI copy. Existing `Jellyseerr*` types stay until a dedicated rename; wire-level identifiers keep their server names.
- No new analytics, telemetry, remote logging, or external services without prior design discussion.

## Testing policy

Write tests for behaviour that can break: coordinators, reducers, resolvers, request/permission rules, focus logic, migrations, HTTP contracts (MockEngine).

`frontend-tv` is not covered by root tests. For TV changes, run its unit tests
from `frontend-tv`; root module tests only cover the KMP mobile/experimental
tree.

Do **not** add tests that:

- assert constants, dimensions, colours, enum names, or copy text,
- test a fake or a one-line expression,
- recompute layout arithmetic (use screenshot tests for visuals),
- duplicate a unit-tested rule again in an instrumented test.

Instrumented tests (`androidTest` / `androidInstrumentedTest`) are only for device-only behaviour: D-pad focus, accessibility, system bars, rotation/recreation, permission flows, and a few end-to-end wiring smoke tests.

Style: camelCase test names, `runTest` for coroutines, no real dispatchers or `delay()` for synchronisation, reuse existing fakes in the module (`shared-core/.../testing/InMemorySettings.kt`, `security/FakeSecureStore.kt`) instead of writing new copies.

## Privacy, security, test data

- Use only fictional data and reserved domains (`example.com`, `example.org`, `example.invalid`). Never commit real server URLs, tokens, cookies, logs, UI dumps, or personal media.
- Request Android permissions only at the feature trigger and document them in `docs/permissions.md`. The TV manifest allowlist is enforced by `:app-tv:verifyTvReleaseManifestPermissions`.
- Never commit `local.properties`, `keystore.properties`, keystores, APKs, or AABs.
- Do not commit agent artefacts (plans, QA logs, scratch captures) or absolute local paths.

## Releases

- Version names, Android version codes, tag names, and release preparation follow `docs/versioning.md`.
- Mobile keeps `applicationId = app.jellystack.mobile`; the Video Pro TV target uses `com.dongpiandi.videopro`. If their application IDs ever become the same again, a `versionCode` may never be reused across either app.
- Release notes: `RELEASE_NOTES.md` plus store/Discord texts in `docs/releases/`.
- GitHub releases contain source and notes only; store bundles stay local.

## Known pitfalls

- The plain `detekt` task currently analyses only `src/main|test` folders, not KMP source sets (`commonMain`, …). A clean `detekt` run does not mean KMP code is clean.
- `gradle.properties` sets `org.gradle.workers.max=1` for Windows file-locking; builds are slower than necessary on Linux/macOS.
- `rootProject.name` determines the generated Compose resources package (`jellystack_mobile.design.generated.resources`); renaming it breaks every resource import.
- `design-tv` and `app-tv` instrumented tests run in CI on a TV emulator but are non-blocking, because that emulator crashes at random (`docs/tech-debt.md` 2.18). Run them on a TV emulator or device locally when changing TV focus behaviour and before every TV release.
- The root KMP build and `frontend-tv` are separate Gradle builds. Root
  `:app-tv:*` tasks do not build or test the official TV app.
- `frontend-tv` sets `abortOnError false` for lint; read the lint report even
  when `lintDebug` succeeds.

## More

- `docs/architecture.md` – modules and rules
- `docs/building.md` – all commands
- `docs/android-tv.md` – TV builds, devices, store submission
- `docs/versioning.md` – version names, version codes, tags, and release preparation
- `docs/tech-debt.md` – known problems and the cleanup backlog; check it before large refactors
