# Versioning and releases

This document is the source of truth for version names, Android version codes, release tags, and release-note placement. Build commands live in [Building](building.md); TV packaging and store steps live in [Android TV and Fire TV](android-tv.md).

## Release lines

| Product | `applicationId` | Version source | Release status |
| --- | --- | --- | --- |
| Jellystack mobile | `app.jellystack.mobile` | `app-android/build.gradle.kts` | Inherited upstream mobile app; future fork uploads follow this policy |
| Video Pro TV | `com.dongpiandi.videopro` | `app-tv/build.gradle.kts` | Active Android TV release line |
| iOS | Not distributed | `app-ios` build configuration | Experimental; no supported App Store release |

Version names and codes belong to an `applicationId`. The Video Pro TV line has a separate application identity from the historical Jellystack TV releases, which used `app.jellystack.mobile`. Its `0.x` version is therefore not a downgrade of the old TV `0.17.x` line, and neither sequence should be used to reset the other.

Historical tags such as `v0.16.0`, `v0.16.0-tv-beta.*`, and `v0.17.*-tv` describe upstream Jellystack releases. Keep them immutable. Do not move, delete, or repurpose them for Video Pro.

## Version names

Use `MAJOR.MINOR.PATCH` for store builds and append `-beta.N` only for a pre-release. Product, store, and channel belong to the `applicationId` and release tag, not to the version name.

| Change | Version-name bump |
| --- | --- |
| Bug fix, performance, security, copy, or build-only correction | `PATCH` |
| Backwards-compatible feature or visible workflow | `MINOR` |
| Removal, incompatible behavior, required user action, or data migration | `MAJOR` |

The app does not publish a library API, so `MAJOR` represents an incompatible product or data change rather than a source-level API break. Major releases are product decisions and should be called out in the release notes.

Additional rules:

- A hotfix keeps `MAJOR.MINOR`, increments `PATCH`, and still increments the Android version code.
- A stable release drops the `-beta.N` suffix; its version code must be higher than every beta that preceded it.
- Never lower `versionName` to signal a rollback. Publish a higher version code and explain the rollback in the release notes.
- A build that was never uploaded may reuse its version name and code. Once an artifact is uploaded to a store, do not reuse either value.
- Do not copy the current version name into README or this document. Read the Gradle source listed above.

## Android version codes

Android orders updates by `versionCode`, not by `versionName`. For each `applicationId`:

- Use a positive integer and increase it strictly for every uploaded build.
- Derive the next value from the highest code ever uploaded for that application ID, not from the current source file alone.
- Never reuse a code after deleting, rolling back, or replacing a release.
- Use one code for the release build and for every store artifact produced from it.
- Make every pre-release code lower than the stable release that follows it.

The store consoles are the authority for uploaded code history. Git tags and `versionCode` values in the repository are supporting evidence, not proof that a code was never uploaded.

### Legacy shared-ID hazard

`app-android` still uses `app.jellystack.mobile`, which the legacy Jellystack TV releases also used. That TV line reached code `33` at `v0.17.2-tv` before the application split. Do not treat the current `app-android` value as the highest code used under the shared ID. Before the next `app.jellystack.mobile` upload, check the store console and choose a code above every code ever published under that ID.

The new `com.dongpiandi.videopro` application ID starts an independent sequence, but codes already uploaded under that ID are still permanent.

`verifyUniqueAndroidVersionCodes` is only a narrow guard: it compares the two current Gradle values when the application IDs match, returns early when they differ, and does not consult uploaded history. Passing it is necessary but not sufficient. The remaining gap is tracked in [Tech debt](tech-debt.md) item 1.1.

## Prepare a release

1. Confirm the publication gate in [Implementation handoff](../HANDOFF.md) section 3. Local builds and testing may continue, but do not make the repository public or publish store artifacts before the GPL-3.0 decision is settled.
2. Start from an up-to-date `main` and choose the release line and version name.
3. Update `versionName` and `versionCode` only in the owning Gradle module. Do not duplicate the values in documentation.
4. Add the newest section to [Release notes](../RELEASE_NOTES.md). Describe user-visible changes; do not paste a raw commit log. Label new headings with the product, for example `## 0.3.0 (Video Pro TV)`, because the file also contains historical Jellystack releases. Do not rewrite older release sections.
5. Add channel-specific text under `docs/releases/` using the existing `<versionName>-<channel>.md` naming pattern. Create files only for channels that are actually used.
6. Run the full gate in [Building](building.md). At minimum for a release-preparation change:

   ```powershell
   .\gradlew.bat spotlessCheck detekt verifyUniqueAndroidVersionCodes
   .\gradlew.bat :app-tv:testDebugUnitTest :app-tv:assembleRelease :app-tv:bundleRelease :app-tv:verifyTvReleaseManifestPermissions
   ```

   Release builds require the local signing configuration. CI does not sign or publish store artifacts. For a mobile release, run the corresponding `:app-android` tests and release build from [Building](building.md).

7. Confirm that the APK and AAB report the intended version name and code, use the expected signing certificate, and pass the artifact checks in [Android TV and Fire TV](android-tv.md).
8. Open a release-preparation pull request containing the version bump, release notes, and channel copy. Tag only after that change is merged and the artifact is accepted.

## Git workflow for every update

Every coherent, completed update belongs in Git and must be pushed to GitHub, even when it does not change a version or create a release. Use this workflow:

1. Start from an up-to-date `main` and create a short-lived branch. Branches created by Codex use `codex/<topic>`.
2. Stage only the files or hunks that belong to the update. Do not include unrelated user changes, generated files, secrets, APKs, or AABs just to make the working tree clean.
3. Commit with a focused message such as `docs: add versioning policy`, `fix(tv): handle playback failure`, or `test(shared-core): cover request rules`.
4. Push the branch to `origin` and open a pull request. Do not push directly to protected `main`.
5. Before handoff, confirm that the update's files are committed and the branch is on GitHub. `git status -sb` must not show the branch as ahead of its remote.

One coherent update normally produces one commit or a small, reviewable commit series. Do not batch unrelated in-progress work into a commit, and do not describe a push as a release. Version bumps, release tags, and release notes remain release-only steps in this document.

## Branches and tags

`main` is protected. All changes use pull requests, linear history, and squash merges; force pushes to `main` are disabled.

| Release line | New tag pattern | Example |
| --- | --- | --- |
| Jellystack mobile | `v<versionName>` | `v0.16.0` |
| Historical Jellystack TV | `v<versionName>-tv` | `v0.17.2-tv` |
| Video Pro TV | `v<versionName>-videopro` | `v0.3.0-videopro` |

Use annotated tags for new releases and point them at the merged commit that produced the accepted artifact. Do not tag a pull-request head, a debug build, or an artifact that has not completed acceptance. Never move or delete a published tag.

GitHub releases contain source and release notes only. Keep APK, AAB, and store bundles local; never attach them to a GitHub release or commit.

## Source-of-truth map

| Question | Authoritative place |
| --- | --- |
| Current version name and code | The app module's `build.gradle.kts` |
| Cross-version user-facing history | `RELEASE_NOTES.md` |
| Store and Discord copy for one release | `docs/releases/` |
| Uploaded Android version codes | Google Play Console and Amazon Developer Console |
| Build and verification commands | `docs/building.md` |
| TV packaging and store checklist | `docs/android-tv.md` |
| Versioning, tagging, and bump rules | This document |

## Known gaps

- There is no committed, machine-readable registry of every uploaded version code, so release preparation still requires a manual store-console check.
- `verifyUniqueAndroidVersionCodes` does not protect against historical reuse or a code collision with another product after an application ID changes.
- There is no automated release or tagging workflow. A green CI run is not a release; acceptance and store upload remain manual.
