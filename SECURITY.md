# Security Policy

## Supported versions

Security fixes target the current Video Pro TV release line and the current
`main` branch. The official TV source is
[`frontend-tv`](frontend-tv); the root KMP TV migration is experimental.

## Reporting a vulnerability

Do not open a public issue and do not paste credentials, tokens, cookies,
private service URLs, account identifiers, or exploit details into Discussions.

Use GitHub's **Report a vulnerability** form in the Security tab of the
[`EDwinpzy/video-pro`](https://github.com/EDwinpzy/video-pro) repository.
Include:

- affected version and platform;
- minimal reproduction steps using sanitized data;
- impact and suggested mitigation;
- whether the issue may expose stored credentials or service access.

Valid reports are coordinated privately until a fix and disclosure plan are
ready. Do not promise or imply a response time that the maintainers cannot
meet.

## Scope

In scope are Video Pro source code, official Android TV releases, credential
handling, authentication and DNS behavior, local storage, networking, and
repository automation. The inherited KMP mobile application remains covered by
its upstream security policy.

Vulnerabilities in Jellyfin, Seerr, Android, Dongpiandi services, or other
third-party servers should be reported to those projects. Never send live
credentials. Rotate any credential that may have been exposed before reporting
it.
