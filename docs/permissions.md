# Android Permissions

> **Scope (2026-11-05):** The official TV application is
> [`frontend-tv`](../frontend-tv). The inherited KMP and experimental modules
> have separate manifests and permission gates; the details below do not
> substitute for checking the official APK's merged manifest. See
> [Repository layout and release authority](repository-layout.md).

The release manifest is checked against an allowlist by `:app-android:verifyReleaseManifestPermissions`.

| Permission | Purpose | When requested |
| --- | --- | --- |
| `INTERNET` | Direct Jellyfin/Seerr connections and media streaming | Install-time normal permission |
| `ACCESS_NETWORK_STATE` | Select Wi-Fi/mobile streaming and download behavior | Install-time normal permission |
| `RECORD_AUDIO` | On Android 13+, transcribe a held voice-key press into the focused editor through the optional system dictation service | Only after choosing "系统语音听写" |
| `NEARBY_WIFI_DEVICES` | Discover Cast devices on Android 13+ | Only after choosing Cast |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | Legacy Cast discovery on Android 12 and earlier; both are capped at API 32 | Only after choosing Cast |
| `POST_NOTIFICATIONS` | Cast playback controls | Explained and requested only in an active Cast session |
| `FOREGROUND_SERVICE` | Cast session control required by Google Cast components | No runtime prompt |
| `USE_BIOMETRIC`, `USE_FINGERPRINT` | Optional App Lock | Only after enabling App Lock |

Jellystack does not request phone state, device identity, broad photo/media access, or read/write external-storage permissions. Downloads use app-specific storage.

Google Play can show permissions from older published versions in its historical “all versions” view. The manifest of the current 0.15.1 bundle is authoritative for current installs.

The TV system dictation service requests the global filtered key stream while it is enabled, so it
can receive the remote voice key before the factory assistant. It claims only the voice-key
down/up pair, and only while an accessibility input-method editor is active and `RECORD_AUDIO` is
granted; all other editor state is passed through. It does not request window-content retrieval.
Android requires the user to enable the service manually in
Settings > Accessibility. Disabling the service immediately removes key filtering and recording.
Recognition is performed by the installed JMGO iFlytek engine, whose original configuration may
contact the vendor speech service; Video Pro does not add another upload destination.
