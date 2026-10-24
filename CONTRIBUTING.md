# Contributing

Thanks for taking a look at Pocket Pi. This public repository is a **reviewed, sanitized snapshot** of a privately developed project. Issues and pull requests are welcome here; accepted changes are ported to the private upstream and appear in a later public snapshot.

## Before you open an issue

- Search existing issues first.
- Include the public snapshot version (for example `0.4.4 · code 23`), Android version, and whether the bridge runs in Termux on the same phone or on a remote host.
- For UI problems, include a screenshot made with **synthetic** data.
- Never include real tokens, SSH keys, private hostnames/IPs, customer data, session transcripts or personal vault content.
- Security issues: follow [SECURITY.md](SECURITY.md) instead of opening a public issue.

## Pull requests

- Keep changes focused. One concern per PR.
- Explain the before/after behavior and why the change is needed.
- Include the checks you ran and any device/emulator details.
- By submitting a PR you agree to license your contribution under the repository's MIT license.
- A maintainer will review the change and, if accepted, port it to the private upstream. The public PR itself is not merged into private history; the next snapshot carries the change.

## Local checks

Bridge, demo and mock tooling:

```sh
node --check termux/pi-pocket-bridge.mjs
node --check tools/mock-bridge.mjs
node --check demo/assets/landing.js
node --test tools/mock-profile.test.mjs
```

Android app (JDK 17+ and Android SDK API 36):

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
```

UI changes should be checked on an emulator against `tools/mock-bridge.mjs`; do not use another person's phone for testing. Screenshots committed to `demo/screenshots/` must use synthetic names, paths and IPs.

## Documentation and screenshots

- Public docs are English; the README and the Pages site are bilingual (English/Chinese).
- The demo screenshots are captured from the Android debug build at 100% font scale, using the mock bridge. Do not add screenshots that contain real hosts, tokens, personal projects or conversations.
- Keep the site free of third-party subresources and analytics.

## Code of conduct

Be direct, technical and respectful. Harassment, doxxing and publishing someone else's private data are not acceptable.
