# Video Pro

Video Pro is a GPL-3.0 Android TV client derived from Jellystack. The official
TV application lives in [`frontend-tv`](frontend-tv). The root Kotlin
Multiplatform tree is retained for upstream mobile code, shared components, and
experimental migration work.

## Current release

| Item | Value |
| --- | --- |
| Release source | [`frontend-tv`](frontend-tv) |
| Current version | `1.0.0` (`versionCode 10`) |
| Version authority | `frontend-tv/app/build.gradle` |
| Android package | `com.dongpiandi.videopro` |
| Release status | Current Android TV application |
| Experimental only | Root `app-tv` and `design-tv` KMP migration |
| Local artifact policy | `frontend-tv/dist/` is ignored and never committed |

`frontend-tv` restores the complete legacy-oriented TV frontend as the
authoritative application. It keeps the native account, password, and search
inputs; remembered credentials and automatic sign-in; the startup and Home
loading gate; remote-focus navigation; playback; system voice dictation; and
the low-tier TV performance work.

The root `app-tv` module also uses `com.dongpiandi.videopro`, but it is an
earlier experimental KMP shell. Do not build, version, or publish a store
artifact from it. See [Repository layout and release authority](docs/repository-layout.md)
for the complete boundary and [Versioning and releases](docs/versioning.md)
before changing either version field.

## Login recovery

Account discovery uses a domestic DNS-over-HTTPS resolver and falls back to the
system resolver. This avoids intermittent login failures when router DNS
returns an unreachable rotating CDN address. Invalid credentials still produce
a normal server-side `401`; transport failures are reported separately.

## Quick start

Requirements: JDK 17 and the Android SDK configured for the `frontend-tv`
project. Run commands from the repository root in PowerShell:

```powershell
Set-Location .\frontend-tv
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The debug APK is written under `frontend-tv/build/optimal-tv/app/outputs/apk/`.
Device tests are described in [`frontend-tv/README.md`](frontend-tv/README.md).

The root KMP project remains available for upstream mobile development and the
experimental TV migration:

```powershell
.\gradlew.bat :app-android:assembleDebug
.\gradlew.bat :app-tv:assembleDebug
```

These KMP commands do not validate or produce the official TV release.

## Repository map

| Path | Responsibility |
| --- | --- |
| `frontend-tv/` | Official standalone Android TV application and release source |
| `frontend-tv/docs/` | TV performance and acceptance records |
| `app-android/`, `design/`, `players/`, `shared-*` | Inherited KMP mobile and shared code |
| `app-tv/`, `design-tv/`, `dongpian-data/` | Experimental KMP TV migration; not a release target |
| `docs/` | Repository-level architecture, build, TV, privacy, and release documentation |
| `scripts/` | Root KMP verification and migration tooling |

Generated Gradle output, `local.properties`, APK/AAB files, keystores, logs,
captures, and `frontend-tv/dist/` are local artifacts and remain untracked.

## Documentation

- [Documentation index](docs/README.md)
- [Repository layout and release authority](docs/repository-layout.md)
- [Building and verification](docs/building.md)
- [Versioning and releases](docs/versioning.md)
- [Android TV and Fire TV](docs/android-tv.md)
- [Current implementation handoff](HANDOFF.md)
- [Release notes](RELEASE_NOTES.md)
- [Privacy](docs/privacy.md), [permissions](docs/permissions.md), and the
  [German privacy policy](privacy-policy-de)

## Upstream and license

This repository is a derivative of [Jellystack](https://github.com/Darkatek7/jellystack),
copyright its upstream contributors, and is licensed under
[GPL-3.0](LICENSE). See [Third-Party Notices](THIRD_PARTY_NOTICES.md).
Jellystack and Video Pro are independent community projects and are not
affiliated with or endorsed by Jellyfin, the Jellyfin project, Seerr, or their
maintainers.
