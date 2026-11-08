# Video Pro TV frontend

This is the authoritative TV application. Version `0.3.2` (`versionCode 8`)
restores the complete legacy frontend after the experimental KMP TV shell and
keeps all login, native-input, startup, focus, playback, and low-tier
performance work.

The version in `app/build.gradle` is authoritative. Repository-wide release
rules, the experimental KMP boundary, and the migration-certificate policy are
documented in
[`docs/repository-layout.md`](../docs/repository-layout.md) and
[`docs/versioning.md`](../docs/versioning.md).

Home tabs are movie, series, domestic animation, short drama, and variety,
followed by search and account. Each hero shows up to five recent titles from
its own category. The first recommendation section uses the official total
ranking filtered by that category and displays two rows; other sections use
one row. Movie and series sections use the corresponding official hot lists.
Domestic animation uses Douban metadata, preserving the returned content
type. Short drama hot/new sections use the official short drama catalogue;
the short drama tab has no Douban rating section.
Variety keeps only the category total ranking below its recent-history hero.

After sign-in, an animated startup screen waits for all five category feeds and
their card/hero artwork. Progress is shown while loading. Failed requests or the
three-minute loading deadline show a remote-focusable retry action; they never
bypass the gate into a partially loaded home. Completed feeds are reused when
switching tabs. Playback streams are resolved when a title is opened.
Every cold launch and return from the background revalidates the five feeds with
the server, including bypassing the animation feed's local response cache. Equal
card data reuses the loaded feed and cached artwork; changed rows are replaced and
new artwork is loaded before the curtain is removed. Foreground refresh keeps the
existing navigation/player mounted so the current episode is preserved.

This directory is a direct migration of the My TV Android TV film and search
frontend. The film home, catalog, detail, player, search, and focus navigation
remain in their original source layout. Account and search text entry use
framework `EditText` controls, so text editing, candidates, and IME presentation
are owned by the projector's installed input method rather than an in-app
keyboard.

The application shell has four intentional changes:

- The app identity is `com.dongpiandi.videopro`.
- The initial screen is the migrated film home; the top bar exposes film and
  the existing search entry.
- A Dongpiandi account login gate is rendered before the migrated frontend.
- The login gate can remember credentials for automatic sign-in. Credentials
  are stored with Android Keystore-backed `EncryptedSharedPreferences`; no
  plaintext credentials are written to app preferences or logs.

Build from this directory:

```powershell
.\gradlew.bat :app:assembleDebug
```

The APK is written to `build\optimal-tv\app\outputs\apk\debug\app-debug.apk`.

`app/src/main/assets/backend/python-backend.zip` is a required runtime asset.
It is intentionally tracked so a fresh checkout can produce a working TV
build; an APK without it has no local relay or source resolver.

Account discovery uses a domestic DNS-over-HTTPS lookup with the system resolver
as a fallback. This avoids intermittent login failures caused by a router DNS
answer that points at an unreachable rotating CDN node.

Run this frontend's checks from this directory with JDK 17:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

This is an independent Gradle project; checks in the parent KMP project do not
validate it. Read the lint report even when the task succeeds, because this
frontend currently sets `abortOnError false`. Device tests require an explicitly
selected Android TV device or emulator. Preserve the installed app's signing
identity and use replacement installs; do not uninstall or clear account data
to work around a signature mismatch.

For projector performance acceptance, build the same local variant with the
release runtime's non-debuggable setting:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest -PprojectorRuntime=true
```

This optional setting retains the local backend/update configuration and signing
selection. Ordinary debug builds remain debuggable. The acceptance APK cannot use
`run-as`, and debug-only route/fixture injection is disabled. Measure startup and
frames on this variant separately from debuggable builds; do not mix their results.

## Voice search intent

The global voice helper and other same-signature assistants can open the search
flow through `VoiceSearchActivity`:

- Action: `com.dongpiandi.videopro.action.OPEN_SEARCH`
- Required permission: `com.dongpiandi.videopro.permission.VOICE_SEARCH`
  (`signature`)
- Extra `query`: normalized search text, limited to 80 characters
- Extra `openFirstResult`: optional Boolean, default `false`

Use `openFirstResult=true` only for explicit view/play commands such as
“播放” or “我要看”. Those requests may open the first result after it loads.
Explicit search commands such as “搜索” or “帮我找” must leave the user on the
result page.

## Remote focus ownership

Ordinary D-pad navigation and confirmation are handled by Compose focus and
clickable nodes. The Activity does not intercept all direction events.
`TvDirectionKeyHandler` consumes only explicit page actions (for example hero
selection and player seeking); an unhandled direction continues through native
focus traversal. Each consumed direction release is paired with its press so
one release cannot execute a second action.

`dpadFocusable` keeps the existing visual treatment and uses a single native
clickable focus target. Disabled controls cannot activate. The legacy `OtvNav`
registry remains for chrome observations and legacy harness tests; production
input no longer invokes its geometric search or geometry-based focus repair.
The player owns media play/pause keys and its hidden control host forwards only
its own page actions. Home and volume remain system keys.
Player seek acceleration reads native key down/event times before local focus
handlers consume the event. Release and a fresh press reset the hold duration;
it does not depend on the legacy navigation dispatcher.

The Compose BOM is 2024.09.03, providing Foundation 1.7 focus-driven lazy scrolling.
The legacy geometry harness is not proof of native focus behaviour. Use
`NativeComposeFocusDeviceTest` through the focus runner and also exercise real
pages using window-level remote events. Page restoration, dynamic content and
modal focus must be checked independently.

Use `-PbuildOutputRoot=<local-directory>` when build outputs need another disk.
The default output path is unchanged. On Windows, keep the source and output
paths on the same drive letter; a local directory junction can point that output
location to another disk. This avoids cross-drive relative-path failures in the
Android unit-test tooling.
