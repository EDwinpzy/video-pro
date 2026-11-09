# Repository layout and release authority

This page is the authoritative map for the whole repository. The current TV
release is [`frontend-tv`](../frontend-tv), version `1.0.1`
(`versionCode 11`), with package ID `com.dongpiandi.videopro`.

## Release boundary

| Product | Source | Version source | Status |
| --- | --- | --- | --- |
| Video Pro TV | `frontend-tv/app` | `frontend-tv/app/build.gradle` | Official TV application and release target |
| Experimental KMP TV | root `app-tv`, `design-tv`, `dongpian-data` | `app-tv/build.gradle.kts` | Reference implementation only; must not be released |
| Jellystack mobile | root `app-android`, `design` | `app-android/build.gradle.kts` | Inherited upstream mobile line |

The root `app-tv` module currently carries the same package ID as the official
application but an older version (`0.3.0`, code `6`). Building or uploading it
would overwrite the official application with the wrong implementation. Only
`frontend-tv` may produce the Video Pro TV artifact.

## Directory responsibilities

| Path | Responsibility |
| --- | --- |
| `frontend-tv/` | Independent Gradle build for the official Android TV app |
| `frontend-tv/app/` | Application code, account/login gate, catalog, player, focus, startup gate, and TV resources |
| `frontend-tv/voice-core/` | Vendor speech-recognition integration shared by the TV app |
| `frontend-tv/voice-helper/` | Optional voice-assistant helper application |
| `frontend-tv/dictation-probe/` | Device probe for the system-dictation path |
| `frontend-tv/docs/` | TV performance and acceptance records |
| `frontend-tv/tools/` | Local verification scripts and media-tooling helpers |
| `app-android/`, `design/`, `players/`, `shared-*` | Inherited KMP mobile and shared code |
| `app-tv/`, `design-tv/`, `dongpian-data/` | Experimental KMP TV migration and its data adapter |
| `docs/` | Cross-project documentation and release policy |
| `scripts/` | Root KMP verification and migration tooling |
| `store-assets/` | Distribution artwork; not a build product |

`frontend-tv` is a standalone Gradle project. Root Gradle tasks do not test or
assemble it. Run its verification commands from its own directory:

```powershell
Set-Location .\frontend-tv
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Add `:app:assembleDebugAndroidTest` when the instrumented test APK is needed.
The optional `-PprojectorRuntime=true` variant matches the release runtime for
performance acceptance without changing service or signing configuration. See
[`frontend-tv/README.md`](../frontend-tv/README.md) for device and focus-test
details.

## Generated and local files

The following are intentionally untracked:

- `**/build/`, `**/.gradle/`, `**/.kotlin/`, and `frontend-tv/app/.cxx/`
- `local.properties`, `keystore.properties`, keystores, and signing passwords
- `frontend-tv/dist/`, APKs, AABs, logs, screenshots, captures, and local QA data
- Root `.worktrees/`, personal diagnostic bundles, and adb/capture tools

Do not clean the working tree by committing generated files or unrelated
in-progress work.

## Network trust policy

The official TV application has no private certificate or migration origin.
It trusts only the platform CA store and uses the public official HTTPS
service. Debug and release variants do not select a separate IP origin, and a
release artifact must not contain the former migration CA or its IP address.

Keep `mytv_migration_ca`, `allowIpReleaseOrigin`, and IP-based API overrides
out of the official `frontend-tv` build. Any future private trust anchor needs
a new security review and must never be copied into Git with a private key.

## Source-of-truth map

| Question | Authoritative place |
| --- | --- |
| Current TV version and package | `frontend-tv/app/build.gradle` |
| Official TV build and tests | `frontend-tv/README.md` |
| Product behavior and retained features | `frontend-tv/README.md` |
| Dongpian-only scope and retained optimisations | `docs/tv-dongpian-only-audit.md` |
| Cross-version user-facing history | `RELEASE_NOTES.md` |
| Version, tag, and release policy | `docs/versioning.md` |
| Inherited KMP architecture | `docs/architecture.md` |
| Generated/local artifact policy | `.gitignore` and `frontend-tv/.gitignore` |
