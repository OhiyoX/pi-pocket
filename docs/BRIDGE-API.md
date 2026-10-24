# Bridge API

The Termux bridge listens on `127.0.0.1:8789` by default and returns a uniform envelope:

```json
{ "ok": true, "data": { } }
```

Errors return `{ "ok": false, "error": "message" }` with a 4xx/5xx status.

## Authentication

- Loopback bind: no token by default.
- Non-loopback bind: `PI_POCKET_TOKEN` is mandatory. Send it as:

```http
Authorization: Bearer <token>
```

The launcher refuses to start a non-loopback bind without a token. HTTP is plaintext; use a trusted LAN or Tailscale.

## Endpoints used by the app

| Method | Path | Body / query | Result |
| --- | --- | --- | --- |
| GET | `/v1/health` | — | `{ok, host, platform, pi, cwd, sessionsRoot, bind, worker, auth}` |
| GET | `/v1/info` | — | Current session state, stats, model, thinking levels |
| GET | `/v1/sessions` | `?limit=40` | `{sessions:[{file,id,cwd,name,updatedAt,bytes,createdAt,running}]}` |
| POST | `/v1/sessions/open` | `{file}` | Switch the active session |
| POST | `/v1/sessions/new` | `{cwd?}` | Create a fresh worker/session |
| GET | `/v1/messages` | `?full=1&cols=80` | Transcript; images are placeholders unless `full=1` |
| POST | `/v1/prompt` | `{text, images?:[{mimeType,data}], steer?, streamingBehavior?}` | Send a prompt |
| POST | `/v1/abort` | — | Abort the running turn |
| GET | `/v1/events` | — | SSE stream: message deltas, tools, thinking, status, bridge events |
| GET | `/v1/slash` | — | Built-in slash commands: `{name,args,argsHint,rpc,description,available}` |
| GET | `/v1/commands` | — | Extension/skill/template commands |
| POST | `/v1/bash` | `{command, excludeFromContext?, id?}` | Run a shell command; output streams as `bash_execution_update` |
| POST | `/v1/bash/abort` | — | Abort the running shell command |
| POST | `/v1/model` | `{provider,model}` or `{cycle:true}` | Switch model |
| POST | `/v1/thinking` | `{level}` | Set thinking level |
| POST | `/v1/compact` | `{instructions?}` | Compact context |
| POST | `/v1/rename` | `{name}` | Rename the session |
| POST | `/v1/ui` | `{id,response,file?}` | Answer an extension UI request |
| GET | `/v1/file` | `?path=` | Read a text/image/HTML artifact |
| GET | `/v1/tree` | — | Session tree projection |
| POST | `/v1/fork` | — | Fork the session and rebind the worker |
| POST | `/v1/export` | — | Export HTML and return the artifact path |
| POST | `/v1/restart` | — | Restart through `start-bridge.sh` (`sv restart` when supervised) |

Additional endpoints (providers, app profile, device, SSH hosts, file operations, reload) are documented in `termux/README.md`. `/v1/restart` is also there; it delegates to `start-bridge.sh` so the service and watchdog keep a single restart path.

## SSE events

`/v1/events` is a `text/event-stream`. JSON events carry a `kind` and a `sessionFile` so the app can ignore events from other sessions. Typical events:

- `message_update` with `assistantMessageEvent.text_delta`
- thinking updates
- tool call / tool result updates
- `agent_end` / `agent_settled`
- `bash_execution_update`
- bridge status (worker started, idle reclaimed, reconnect)

A keep-alive `ping` event is sent periodically.

## Example

```sh
curl -s localhost:8789/v1/health
curl -s "localhost:8789/v1/sessions?limit=5"
curl -s -X POST localhost:8789/v1/prompt \
  -H 'content-type: application/json' \
  -d '{"text":"Reply with exactly: POCKET-OK"}'
```

For a remote host:

```sh
PI_POCKET_HOST=0.0.0.0 PI_POCKET_TOKEN=<token> \
  nohup sh pi-pocket-start >> ~/.pi/agent/pi-pocket-start.log 2>&1 &
```

Then configure the URL in the app. The app pins the SSH host key on first connect and stores the token in app preferences.

## Known gaps

See [PI-PROTOCOL-GAPS.md](PI-PROTOCOL-GAPS.md) for the pi RPC coverage table and known limitations. The bridge intentionally does not invent session storage: the JSONL transcripts remain the source of truth.
