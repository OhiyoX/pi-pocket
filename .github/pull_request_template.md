## Summary

<!-- What changes, and why? -->

## Before / after

## Verification

- [ ] `node --check` on changed JavaScript
- [ ] `node --test script/*.test.mjs tools/mock-profile.test.mjs`
- [ ] Android checks, if app code changed: `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`
- [ ] UI checked on an emulator against `tools/mock-bridge.mjs`, if UI changed

## Privacy

- [ ] No tokens, SSH keys, private hostnames/IPs, customer data, real transcripts or personal vault content are included.
- [ ] Screenshots use synthetic data.

## Notes

<!-- Risks, follow-ups, screenshots, linked issues. -->
