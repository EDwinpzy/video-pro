# Dongpiandi Data

> **Status (2026-11-05):** This module is part of the experimental KMP TV
> migration. The official TV application is
> [`frontend-tv`](../frontend-tv), version `0.3.2` (`versionCode 8`). Do not
> treat `dongpian-data` as the release implementation. See
> [Repository layout and release authority](repository-layout.md).

`dongpian-data` is the TV-only adapter for Dongpiandi's official services. It
keeps request signing, cookies, transport DTOs, and service error mapping away
from Compose state.

## Responsibilities

- Discover the current `movie-card-runtime-*.js` signing key from the official
  HTTPS origin and cache it briefly in memory.
- Sign every API request with the current timestamp and a fresh nonce.
- Keep the account cookie scoped to the official API origin.
- Validate restored sessions with `GET /v1/users/me`.
- Map home, catalog, search, detail, episodes, playlists, playback lines,
  history, favourites, compact resume state, and library sync responses into
  Dongpiandi domain models.
- Reject playback lines that are unresolved, still require parsing, use
  plaintext HTTP, contain URL user-info, or duplicate an accepted URL.

## Verified endpoints

The following signed requests returned HTTP 200 on 2026-10-31 without an
account:

- `GET /v1/feed/home` -> `home.feed`
- `GET /v1/browse/catalog?page=1&limit=2` -> `catalog.browse`
- `GET /v1/browse/catalog?q=...` -> `catalog.browse`
- `GET /v1/catalog/{variantId}` -> `catalog.detail`
- `GET /v1/catalog/{variantId}/episodes?offset=0&limit=5` ->
  `catalog.episodes`
- `GET /v1/playback/resolve/{episodeToken}` -> `playback.resolve`
- `GET /v1/playlists/explore?limit=2` -> `user_playlist.explore`

The playback response returned both resolved and unresolved line options. The
adapter must continue to expose only safe resolved HTTPS lines.

## Session rules

- Passwords are never persisted.
- Only the cookie, SHA-256 account scope, and display name are stored through
  `SecureStoreFactory`.
- Login POST requests are never replayed after an invalid-signature response.
  Stable library-sync operations may refresh the key and retry once.
- Redirects and automatic retries are disabled for the Dongpiandi HTTP client.
- Logs must not contain cookies, passwords, signing keys, response bodies, or
  full URLs.

## Verification

```powershell
.\gradlew.bat :dongpian-data:testDebugUnitTest
.\gradlew.bat :dongpian-data:assembleDebug
```

The unit tests cover the fixed HMAC vector, request headers, signature-key
refresh, login/session restore, response-size limits, playback-line filtering,
catalog mapping, library query encoding, and accepted sync operation IDs.
