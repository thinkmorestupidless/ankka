# Contract: the keeper

The program beside Ollama: the door, the loop, the status, the probe, the metrics. Decisions are in
[research.md](../research.md) (R2, R8).

## Environment

| Variable | Default | Meaning |
|---|---|---|
| `ANKKA_KEEPER_OLLAMA` | `http://127.0.0.1:11434` | where Ollama listens |
| `ANKKA_KEEPER_DECLARATION` | `/etc/ankka/models/models.json` | the declaration file, re-read on change |
| `ANKKA_KEEPER_ADDRESS` | required | the address the status document reports as `server` |
| `ANKKA_KEEPER_PORT` | `8443` | the door |
| `ANKKA_KEEPER_STATUS_PORT` | `8080` | the plain status port |
| `ANKKA_KEEPER_PROBE_PORT` | `7627` | the probe |
| `ANKKA_KEEPER_PASS_INTERVAL` | `30s` | how often the loop runs when nothing changed |
| `ANKKA_KEEPER_PARALLEL` | `1` | what the server runs at once, the same value as `OLLAMA_NUM_PARALLEL`; calls in flight beyond it are counted as waiting |
| `ANKKA_OTLP_ENDPOINT`, `ANKKA_OTLP_HEADERS` | none | where metrics and spans go; none means nothing is exported |
| `ankka.keeper.service-directory` (system property) | `/var/run/secrets/ankka/service` | the certificate's files |

The keeper refuses to start, naming every problem, when `ANKKA_KEEPER_ADDRESS` is missing, the
certificate directory has no `tls.crt`, or the certificate's URI is not `ankka://platform/models`.

## The door — port 8443, mutual TLS

A connection must present a certificate the `ankka-service` authority issued; otherwise the
handshake fails and no HTTP exchange happens. An admitted connection's sender is read as the proxy
reads one (`Caller.fromCertificate`):

| Sender | Answer |
|---|---|
| `Caller.Service(project, service)` | admitted |
| `Caller.Gateway`, a mount certificate | 403 `{"error": "the model server admits services of the installation only"}` |
| anything else the authority issued | 403, the same |

Admitted paths, forwarded to Ollama with the request's headers except `Authorization`, the body
read whole before it is forwarded (a chat request is small), the answer streamed out (server-sent events
included), the status code passed through:

| Method and path | Why a service needs it |
|---|---|
| `POST /v1/chat/completions` | the call |
| `GET /v1/models` | a client that lists |
| `POST /api/show` | the capability check |
| `GET /api/tags` | what is held |
| `GET /api/ps` | what is loaded |
| `GET /ankka/models` | the status document, answered by the keeper itself |

Every other path is 404 `{"error": "not served to services"}`: no pull, delete, create, copy, push,
generate or embed through the door. Forwarded requests carry `X-Ankka-Caller: <project>/<service>`
for Ollama's log and nothing else of the keeper's.

Each forwarded call is counted (`calls.answered` or `calls.failed`, by Ollama's status class) and
is a span `models.call` with attributes `ankka.model`, `ankka.caller.project`,
`ankka.caller.service` and `http.response.status_code`, a child of the request's `traceparent`
when one is sent.

## The status port — port 8080, plain

`GET /ankka/models` only; the same document as the door's. Admitted by the policy from the
operator's pods alone. Anything else is 404.

## The probe — port 7627, plain

`GET /ready`: 200 once Ollama answers `GET /api/version` and the loop has completed one pass, else
503 with the reason. `GET /live`: 200 while the process runs.

## The loop

Every `ANKKA_KEEPER_PASS_INTERVAL`, and at once when the declaration file's modification time
changes:

1. Read the declaration; a file that does not parse keeps the last good declaration and sets
   `ready` false with the parse error as the document's `detail`.
2. Ask Ollama `GET /api/tags` (held, with digests) and `GET /api/ps` (loaded).
3. Plan, purely (`Reconcile.plan`): for each declared tag in order, `Pull` if not held and not in
   backoff, `Load` if held and not loaded, `Record` if loaded and no digest or capabilities
   recorded (`POST /api/show`); for each held tag not declared, `Delete`.
4. Perform one `Pull` at most per pass (a pull is minutes), every `Load`, `Record` and `Delete`.
   A pull streams `POST /api/pull`; its last status line is the `failed` detail when it does not
   end in `success`. A load is `POST /api/generate` with the model, no prompt and `keep_alive: -1`.
5. Publish the document.

A `failed` tag is retried after one minute, doubling to an hour; a declaration change resets every
backoff. A served model is never fetched again by the keeper.

## Metrics

| Name | Kind | Attributes |
|---|---|---|
| `ankka.models.calls` | counter | `ankka.model`, `outcome` (`answered`, `failed`, `refused`) |
| `ankka.models.in_flight` | up-down counter | `ankka.model` |
| `ankka.models.waiting` | gauge | — (`max(0, in flight − ANKKA_KEEPER_PARALLEL)`) |
| `ankka.models.declared` | gauge | — |
| `ankka.models.served` | gauge | — |
| `ankka.models.loaded` | gauge | — |
| `ankka.models.fetch_failures` | counter | `ankka.model` |
| `ankka.models.pass.duration` | histogram | — |

Resource attributes: `service.name` `ankka-model-keeper`, `ankka.installation` when
`ANKKA_INSTALLATION` is set.

## Logging

One line per pass that changed something (`served qwen3:0.6b 7d4f…`, `failed no-such-model:1b:
<reason>`, `removed llama3.2:1b`), one per refused connection with the sender's identity or
"no certificate", and nothing per forwarded call: the span is the record.
