# Video Pro TV 1.0.0 Dongpian-only audit

Date: 2026-11-09

Scope: the official TV application in `frontend-tv/app` only. Root `app-tv`,
`design-tv`, and `dongpian-data` remain experimental KMP reference code and
must not produce the release artifact.

## Product decision

Video Pro TV uses only Dongpiandi's official service at
`https://ai.dongpian20.com`. There is no self-hosted backend, local relay,
Python runtime, private source resolver, or user-configured API base URL in the
official application.

The following flows all use the official service:

- account login and session validation;
- home feeds and category tabs;
- search and catalogue browsing;
- details and episode lists;
- playback tickets and line resolution;
- favourites, history, and danmaku.

The `douban:` prefix in catalogue data is an internal compatibility key returned
by the official catalogue. It is not a separate Douban network integration.

## Removed from the official artifact

- Chaquopy/Python runtime, `BackendService`, embedded backend resources, and
  the old `:backend` process;
- hot update and announcement services;
- licensing, activation, membership, paywall, and related screens;
- live/IPTV, football, hhkan, Douban external-source, demo, remote-API, and
  legacy Vod API source paths;
- local metadata catalogue seeding and old live/IPTV assets;
- the private migration CA, IP migration origin, and release origin gate;
- old API/base-URL settings and cross-source playback fallback chains.

The APK must not contain `python-backend.zip`, the migration certificate,
`BackendService`, `IptvRepository`, or `LiveRepository`.

## Retained TV optimisations

The cleanup intentionally preserves the existing TV and low-tier-device work:

- Media3's 48 MB sample queue cap and the 12 s adjacent-episode preload floor;
- the selected playback engine and selected line remain stable when the first
  frame is slow; a slow first frame does not silently switch engines;
- stable-line memory, playback-ticket refresh, detail/episode prefetch, and
  request sharing;
- continuous same-line retry for stalls, buffering, and slow buffers;
- the last presented frame remains visible across Media3 playlist resets;
- danmaku's 12 s window, two-segment in-memory policy, de-duplication, and
  bounded retry behaviour;
- `TvPerformanceProfile` image, network, animation, and low-tier limits;
- focus-column clamping, focus restoration, constrained-device native focus,
  and short-drama episode navigation;
- Compose strong-skipping and non-skipping group optimisations;
- smart-skip policy through `EffectiveSkipSegmentPolicy`,
  `OfficialSkipPreset`, and `smartSkipEnabled`.

## Playback line fallback

`VodRetryPolicy.HARD_LINE_FAILURE_ATTEMPTS` is `2`. Automatic line switching is
allowed only after two hard failures such as a line-resolution failure or an
explicit "switch line" response. Buffering, stalls, and slow startup remain on
the original line and keep the existing continuous retry behaviour.

The player clears hard-failure history on manual line selection, retry, and
episode changes. This prevents one bad official line from causing an endless
same-line retry while preserving recovery for transient stalls.

## Release boundary and verification

The authoritative version is `frontend-tv/app/build.gradle`: `1.0.0`
(`versionCode 10`), package `com.dongpiandi.videopro`. Build only from
`frontend-tv`:

```powershell
Set-Location .\frontend-tv
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug :app:assembleDebug
```

Set `ANDROID_HOME` and `ANDROID_SDK_ROOT` to the local Android SDK before
running the commands.

Release signing reads `OTV_RELEASE_STORE_PASSWORD` and
`OTV_RELEASE_KEY_PASSWORD` locally. Never commit passwords, keystores, APKs,
device screenshots, or adb logs.

For a release artifact, verify the version, signature, native ABIs, and that
the removed backend/source classes and assets are absent before installing it
over the previous signed build. Record the final APK SHA-256 and keep the APK
outside Git.

## Residual acceptance gaps

The current device evidence covers login, all five home tabs, official
history/favourites, details, episode selection, playback, danmaku loading, and
the hard-failure automatic line switch. Physical remote input, weak-network and
30-minute endurance playback, every focus path, smart-skip interaction, and a
full danmaku visual comparison still require separate acceptance runs.
