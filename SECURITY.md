# Security policy

## Supported versions

Security fixes are applied to the latest public snapshot on `main`. The app is currently **debug-signed** and is distributed as a development artifact, not through an app store.

## Reporting a vulnerability

Please use GitHub's **private vulnerability reporting** for this repository:

- GitHub → Security → Report a vulnerability

Do **not** open a public issue containing tokens, private hostnames, session transcripts, screenshots with personal data, or reproduction steps that require access to someone else's machine.

If private reporting is unavailable, open a minimal issue asking for a private channel; do not include the sensitive details.

## Scope and known boundaries

In scope:

- The Android app (`app/`) and the Termux bridge (`termux/`).
- Authentication and network binding behavior of the bridge.
- SSH host-key pinning and credential handling in the app.
- Unintended file writes, command execution, or cross-session event mixing.

Known boundaries of the current build (not vulnerabilities by themselves):

- The bridge defaults to loopback and **without a token**. Other apps on the same Android device can reach loopback.
- A non-loopback bind requires `PI_POCKET_TOKEN`, but HTTP is plaintext. Use a trusted LAN or Tailscale.
- Tokens and SSH credentials are stored in app preferences **unencrypted**.
- The APK is debug-signed; there is no Play Store release.
- The in-app self-update panel talks to a self-hosted Gitea instance. The public snapshot uses a placeholder host and requires configuration.

## Response

Reports are reviewed on a best-effort basis. Please allow time for a fix and coordinated disclosure before publishing details.
