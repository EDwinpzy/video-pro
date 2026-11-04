# Versioning and releases

This document is the source of truth for Android version names, version codes,
release tags, and release-note placement. Build commands live in
[Building and verification](building.md); the product and directory boundary
lives in [Repository layout and release authority](repository-layout.md).

## Current release line

| Product | `applicationId` | Version source | Status |
| --- | --- | --- | --- |
| Video Pro TV | `com.dongpiandi.videopro` | `frontend-tv/app/build.gradle` | Official release line; currently `0.3.1` (`versionCode 7`) |
| Experimental KMP TV | `com.dongpiandi.videopro` | `app-tv/build.gradle.kts` | Reference only; currently `0.3.0` (`versionCode 6`), never publish |
| Jellystack mobile | `app.jellystack.mobile` | `app-android/build.gradle.kts` | Inherited mobile line |
| iOS | Not distributed | `app-ios` build configuration | Experimental; no supported App Store release |

The version values in the table describe the repository state on 2026-11-05.
The owning Gradle file remains authoritative if this table drifts.

`0.3.0` (`versionCode 6`) was published by the experimental KMP TV shell. The
restored legacy frontend therefore starts at `0.3.1` (`versionCode 7`) so it can
replace that installation without reusing a code or presenting a downgrade.

The official and experimental TV trees share an application ID. Only
`frontend-tv` may own that ID going forward. Do not bump `app-tv`, build it for
distribution, or upload an artifact from it. See
[Repository layout and release authority](repository-layout.md).

Historical Jellystack tags such as `v0.16.0`, `v0.16.0-tv-beta.*`, and
`v0.17.*-tv` are immutable upstream history. Do not move, delete, or reuse them
for Video Pro.

## Version names

Use `MAJOR.MINOR.PATCH` for released TV builds and append `-beta.N` only for a
pre-release. Product and channel belong to the application ID and release tag,
not to the version name.

| Change | Version-name bump |
| --- | --- |
| Bug fix, performance, security, copy, or build-only correction | `PATCH` |
| Backwards-compatible feature or visible workflow | `MINOR` |
| Removal, incompatible behavior, required user action, or data migration | `MAJOR` |

Additional rules:

- A hotfix keeps `MAJOR.MINOR`, increments `PATCH`, and still increments the
  Android version code.
- A stable release drops the `-beta.N` suffix; its version code must be higher
  than every beta that preceded it.
- Never lower the version name to signal a rollback. Publish a higher version
  code and explain the rollback in the release notes.
- A build that was never uploaded may reuse its version name and code. Once an
  artifact is uploaded to a store, do not reuse either value.
- `-PapkVersionCode` and `-PapkVersionName` are local verification overrides.
  They do not change the authoritative version or authorize a release.
- Update the version only in `frontend-tv/app/build.gradle`; do not copy the
  values into other build files or change the experimental module to match.

## Android version codes

Android orders updates by `versionCode`, not by version name. For
`com.dongpiandi.videopro`:

- Use a positive integer and increase it strictly for every uploaded build.
- Derive the next value from the highest code ever uploaded for that
  application ID, not from the current source file alone.
- Never reuse a code after deleting, rolling back, or replacing a release.
- Use one code for the release build and for every store artifact produced
  from it.
- Make every pre-release code lower than the stable release that follows it.

The store consoles are the authority for uploaded code history. Git tags and
the current repository values are supporting evidence, not proof that a code
was never uploaded.

## Prepare a release

1. Confirm the GPL-3.0 distribution decision and the release boundary in
   [Repository layout and release authority](repository-layout.md).
2. Start from an up-to-date `main` and create a short-lived branch.
3. Update `versionName` and `versionCode` only in
   `frontend-tv/app/build.gradle`.
4. Add the newest section to [Release notes](../RELEASE_NOTES.md). Label it
   with the product, for example `## 0.3.1 (Video Pro TV)`. Do not rewrite
   historical sections.
5. Add channel-specific text under `docs/releases/` only for channels that are
   actually used.
6. Run the official TV gate from inside `frontend-tv`:

   ```powershell
   Set-Location .\frontend-tv
   .\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
   .\gradlew.bat :app:assembleDebugAndroidTest
   ```

   Release signing and the accepted API origin are local configuration. Never
   put passwords, keystores, or private URLs in Git. Do not substitute a root
   `:app-tv` build for this gate.
7. Confirm that the produced APK reports the intended version name, version
   code, package ID, and signing certificate. Keep the APK/AAB local.
8. Open a release-preparation pull request. Tag only after the change is merged
   and the artifact is accepted.

## Git workflow for every update

Every coherent, completed update belongs in Git and must be pushed to GitHub,
even when it does not change a version or create a release.

1. Start from an up-to-date `main` and create a short-lived branch. Branches
   created by Codex use `codex/<topic>`.
2. Stage only the files or hunks that belong to the update. Do not include
   unrelated user changes, generated files, secrets, APKs, AABs, or local
   `frontend-tv/dist/` output.
3. Commit with a focused message such as `docs: establish TV release authority`
   or `fix(tv): recover account DNS`.
4. Push the branch and open a pull request. Do not push directly to protected
   `main`.
5. Before handoff, confirm that the update's files are committed and the branch
   is on GitHub. `git status -sb` must not show the branch as ahead of its
   remote.

Do not describe a push as a release. Version bumps, tags, and release notes
remain release-only steps.

## Branches and tags

`main` is protected. Changes use pull requests, linear history, and squash
merges; force pushes to `main` are disabled.

| Release line | Tag pattern | Example |
| --- | --- | --- |
| Jellystack mobile | `v<versionName>` | `v0.16.0` |
| Historical Jellystack TV | `v<versionName>-tv` | `v0.17.2-tv` |
| Video Pro TV | `v<versionName>-videopro` | `v0.3.1-videopro` |

Use annotated tags for new releases and point them at the merged commit that
produced the accepted artifact. Do not tag a pull-request head, a debug build,
or an artifact that has not completed acceptance. Never move or delete a
published tag.

GitHub releases contain source and release notes only. Keep APK, AAB, and store
bundles local; never attach them to a GitHub release or commit.

## Source-of-truth map

| Question | Authoritative place |
| --- | --- |
| Current TV version name and code | `frontend-tv/app/build.gradle` |
| Official product and directory boundary | `docs/repository-layout.md` |
| Cross-version user-facing history | `RELEASE_NOTES.md` |
| Store and Discord copy for one release | `docs/releases/` |
| Uploaded Android version codes | Google Play Console and Amazon Developer Console |
| Official TV build and verification commands | `frontend-tv/README.md` and `docs/building.md` |
| Versioning, tagging, and bump rules | This document |

## Known gaps

- There is no committed, machine-readable registry of every uploaded version
  code, so release preparation still requires a manual store-console check.
- The official and experimental TV trees share an application ID. Repository
  policy forbids publishing `app-tv`, but no build-time guard prevents it yet.
- There is no automated release or tagging workflow. A green CI run is not a
  release; acceptance and store upload remain manual.
