# Dongpiandi Next

Dongpiandi Next is a planned Android TV frontend derived from Jellystack.
The project keeps the upstream Compose for TV shell and replaces the
Jellyfin/Seerr product flows with Dongpiandi's official account, catalog,
playback, favorites, and watch-history services.

## Status

- Upstream baseline: `Darkatek7/jellystack` at `c6eef175c09c4c413aeede37888f4f8ce978b9a2`
- Current state: project shell and migration plan only
- Target application ID: `com.dongpiandi.next`
- License: GPL-3.0; every distributed derivative must comply with the upstream license

The current code still contains Jellyfin and Seerr screens and behavior. They
must not be presented as Dongpiandi functionality until they are removed or
adapted.

## V1 navigation

Primary navigation:

1. Home
2. Library
3. Search
4. Playlists
5. Me

Secondary pages:

1. Media detail
2. Player
3. Account login
4. Watch history
5. Favorites
6. Playlist detail
7. Weekly schedule
8. Playback settings

The player keeps the Jellystack focus model, OSD, seeking, episode selection,
line selection, error recovery, resume, and progress reporting.

## Product mapping

| Jellystack screen | Dongpiandi mapping |
| --- | --- |
| Home | Home feed and continue watching |
| Library | Catalog with categories, filters, and all titles |
| Search | Catalog search and AI-assisted discovery |
| Discover | Public and personal playlists |
| Settings | Me, account, history, favorites, schedule, and settings |
| Jellyfin detail | Generic media detail and episodes |
| Player | Official playback resolution and cross-device progress |
| Connection | Official account login and service status |

## Removed flows

- Seerr discovery, requests, request status, and Seerr detail
- Jellyfin server URL entry, Quick Connect, and server administration
- Multiple user profiles, profile PINs, and profile switching
- SyncPlay
- Downloads and offline media
- Cast and administration dashboards
- Diagnostics and developer logs in production UI
- Voice search
- Trailer auto-preview
- Unsupported Jellyfin-only media and audio capabilities

## Dongpiandi services

- Login: `POST /v1/users/password/login`
- Session: `GET /v1/users/me`
- Home feed: `GET /v1/feed/home`
- Catalog: `/v1/browse/catalog`
- Search: `/v1/suggest`
- Playback resolution: `GET /v1/playback/resolve/{token}`
- Resume status: `GET /v1/users/me/library/status`
- History: `GET /v1/users/me/history`
- Library sync: `POST /v1/users/me/library/sync`

## Implementation phases

1. Establish a GPL-compliant fork and document the product boundary.
2. Remove unreachable Seerr, Jellyfin-server, profile, SyncPlay, diagnostics,
   and download surfaces from the TV build.
3. Replace the TV application identity and lock visible UI to Simplified Chinese.
4. Add a Dongpiandi repository adapter for home, catalog, search, detail,
   episodes, and playback resolution.
5. Connect account login, encrypted session storage, history, favorites, and
   progress sync.
6. Validate D-pad focus, Back behavior, 720p/1080p layout, real playback,
   resume across two devices, long playback, and network recovery.

## Acceptance gates

- No visible or focusable Seerr/Jellyfin server UI remains.
- All visible UI, errors, empty states, accessibility labels, and player hints
  are Simplified Chinese.
- Existing Dongpiandi accounts can sign in and see synced history.
- A and B devices share playback progress in both directions.
- The player supports line selection, episode selection, seeking, resume,
  autoplay, retry, and progress checkpointing.
- The build passes upstream static checks and TV screenshot tests.
