# Documentation

Start with the root [`README.md`](../README.md) for the product overview and
the official build entry point. The current TV release is
[`frontend-tv`](../frontend-tv), version `0.3.1` (`versionCode 7`). The root
KMP `app-tv` and `design-tv` implementation is experimental and is not a
release target.

## Start here

- [Repository layout and release authority](repository-layout.md) - authoritative
  source, experimental boundaries, generated files, and release certificate
- [Building and verification](building.md) - official TV checks, inherited KMP
  checks, and device-test commands
- [Versioning and releases](versioning.md) - current version source, Android
  version codes, tags, and release preparation
- [Android TV and Fire TV](android-tv.md) - installation, device testing, and
  store-submission boundaries
- [`frontend-tv/README.md`](../frontend-tv/README.md) - TV product behavior,
  login recovery, build output, and focus ownership

## Architecture and project history

- [Architecture](architecture.md) - production boundary, KMP modules, and
  dependency rules
- [Video Pro plan](../PROJECT_PLAN.md) - migration goals and acceptance gates;
  historical where superseded by the current release status
- [Implementation handoff](../HANDOFF.md) - service evidence, device findings,
  risks, and unfinished work; historical context for the active TV source
- [Server compatibility](server-compatibility.md) - inherited Jellyfin
  compatibility expectations
- [Tech debt and cleanup backlog](tech-debt.md) - inherited KMP issues and
  repository-hygiene findings

## Build, version, and release

- [Release notes](../RELEASE_NOTES.md) - user-facing history across releases
- [Store and Discord release copy](releases/) - channel-specific release text
- [Low-tier TV optimization](../frontend-tv/docs/LOW_TIER_TV_OPTIMIZATION.md) -
  performance profile, measurements, and device constraints

## Privacy and platform

- [Privacy policy](privacy.md)
- [German privacy policy](privacy-de.md)
- [Android permissions](permissions.md)
- [Google Play Data Safety review](play-console-data-safety.md)

## Contributing and agent guidance

- [Contributing](../CONTRIBUTING.md)
- [Security policy](../SECURITY.md)
- [Code of Conduct](../CODE_OF_CONDUCT.md)
- [Agent and maintainer conventions](../AGENTS.md)

## Historical planning

The files under [superpowers/plans](superpowers/plans/) and
[superpowers/specs](superpowers/specs/) record completed or superseded KMP
design and implementation planning. Use the current release, layout, build,
and versioning documents above for active guidance.
