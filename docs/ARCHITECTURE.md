# Architecture

Pocket Pi is a client for **pi** on Android. It does not embed a model or reimplement the agent; it gives the pi process a native mobile surface.

## Components

| Component | Responsibility |
| --- | --- |
| `app/` | Kotlin / Jetpack Compose UI. Session list, chat rendering, composer, shell, file viewer, machine picker, SSH launcher, settings. |
| `termux/pi-pocket-bridge.mjs` | Node HTTP + SSE bridge. Spawns one `pi --mode rpc` worker per session, owns process lifecycle, maps RPC events, serves session files. |
| `termux/pi-pocket-start` | Launcher used by Termux `RUN_COMMAND` and by SSH. Persists bind/token, checks the port, starts the bridge under a wake lock. |
| `termux/start-bridge.sh` | Idempotent entry used by the app, SSH, the service and the watchdog. Uses `sv` when a runsv service is ready; otherwise starts the launcher directly. |
| `termux/service/pi-pocket/run` | `termux-services` / runsv service definition installed to `$PREFIX/var/service/pi-pocket/run`. |
| `termux/pi-pocket-watchdog.sh` | 15-minute job-scheduler fallback for the case where `runsvdir` itself is gone. |
| `termux/boot/start-pocket` | Termux:Boot hook: starts `runsvdir`, then `sv up pi-pocket`. |
| `termux/rpc-channel.mjs` | JSONL channel to a pi worker; request/response correlation. |
| `termux/event-stream.mjs` | SSE fan-out and event attribution. |
| `tools/mock-bridge.mjs` | Mock bridge for UI work and the public demo screenshots. Never touches a real pi session. |
| `demo/` | Static bilingual Pages site (landing + screenshot tour). |

## Data flow

```
Compose UI ── HTTP/SSE ──> bridge ── JSONL/stdio ──> pi --mode rpc
     ▲                        │
     └──── session events ────┘
                              └── ~/.pi/agent/sessions/*.jsonl
```

1. The app probes loopback, the current subnet and saved hosts.
2. It opens `/v1/events` for SSE and calls `/v1/sessions` for the list.
3. Opening a session calls `/v1/sessions/open`; the bridge switches the active session or starts its worker.
4. A prompt goes to `/v1/prompt`. pi streams deltas, thinking, tool calls and status over SSE.
5. The bridge attributes every event to a `sessionFile`; the app only renders events for the active session.
6. Tool output and shell commands use the same worker/channel, so ordering matches the terminal.

## Session lifecycle

- One pi worker per session; switching sessions does not kill other running workers.
- An idle worker is reclaimed after a timeout (default 15 minutes), but never while a turn is running.
- The session list is a disk scan of `~/.pi/agent/sessions`; pi has no "list sessions" RPC.
- The JSONL transcript remains the source of truth. The bridge does not rewrite it.

## Bridge supervision

On Termux, `termux/install.sh` installs a `runsv` service, a boot hook, an idempotent `start-bridge.sh` and a watchdog:

- `runsv` restarts the bridge in seconds while the Termux service directory is alive.
- The watchdog covers `runsvdir` loss (Termux killed by Android, reboot before Termux opens).
- `start-bridge.sh` is the single entry for the app, SSH, boot and the watchdog. It calls `sv` when the service is ready and falls back to a direct launcher start otherwise.
- `sv down` is a temporary stop; the watchdog may bring it back. `sv-disable` writes a `down` marker that the watchdog and `start-bridge.sh` respect. `FORCE=1 start-bridge.sh` clears the marker and restarts, which is what installs and updates use.

## Failure handling

- The app reconnects SSE with backoff and rehydrates the current session after a reconnect.
- If the local bridge is down, the launcher can be invoked through Termux `RUN_COMMAND`; for a remote host, the app can SSH in and run the launcher.
- The launcher performs a bind pre-check before persisting a new host/port, so a typo cannot stop a healthy bridge.
- A crashed bridge is restarted by `runsv`; if the service directory is gone, the watchdog or the next app/SSH start calls `start-bridge.sh`.
- Host keys are pinned on first SSH connect; a changed key is rejected.

## Security boundaries

- The bridge defaults to `127.0.0.1` and no token. Android loopback is shared with other apps on the device, so a token is recommended if that matters.
- A non-loopback bind requires `PI_POCKET_TOKEN`; HTTP is plaintext, so use a trusted LAN or Tailscale.
- Tokens and SSH credentials live in app preferences unencrypted. Treat the device lock as part of the security boundary.
- The app is debug-signed in the public snapshot.
- The bridge only transports and attributes events; it does not add a cloud relay.

## Testing strategy

- Pure Kotlin logic is covered by JVM unit tests under `app/src/test/`.
- UI changes are checked on an emulator against `tools/mock-bridge.mjs`; the real phone is only used for installation.
- Bridge and launcher behavior have Node tests under `script/` and `tools/`.
- The public demo uses a mock bridge so no model, session or personal data is involved.

See [BRIDGE-API.md](BRIDGE-API.md) for endpoints and [TESTING.md](TESTING.md) for the current test commands.
