# Contract: the keyring, its protocol, and the sidecar

## The keyring's HTTP service

pekko-http over mutual TLS (found during implementation: `runtime` carries no gRPC, and every
service's runtime calls the keyring). The caller is read from its certificate
(`ankka://<project>/<service>`), from a relayed installation identity (R17), or from a machine bearer
token through the gateway (decrypt only). Bodies are JSON; keys are standard base64.

| Method and path | Who | Body → answer |
|---|---|---|
| `POST /v1/keys` | a service of the project; a consumer with a `decrypt` grant; a relayed installation | `{"project","subject","create"}` → `{"key"}`, or `{"destroyed": true}`, or 403 `{"refused": reason}` |
| `POST /v1/lookup-keys` | a service of the project | `{"project"}` → `{"key"}` |
| `GET /v1/notices` (WebSocket) | every instance, held open | server sends `Notice`, client sends `NoticeAck` (below), text frames of JSON |
| `POST /v1/erasures` | the control plane only | `{"seq","project","subject"}` → `{"destroyedAt"}` (destroy) |
| `POST /v1/erasures/{seq}/resend` | the control plane only | → 202 (re-runs) |
| `GET /v1/erasures/{seq}` | the control plane only | → `{"services": [{service, completedAt, completion}]}`; no key |
| `POST /v1/decrypt` | a machine with a decrypt grant | `{"grant","project","manifest","fields": [envelope…]}` → `{"values": [json or null]}` |

```json
{"type":"notice","seq":12,"project":"brand","subject":"player/8c1f","rerun":false}
{"type":"ack","seq":12,"service":"players","instance":"players-7f9c-0","completion":{"rowsRedacted":3,"tokensDropped":1,"sessionsEnded":0,"handler":{"objectsErased":2,"finality":"immediate"}}}
```

On connect, the instance's first frame is `{"type":"hello","project":"brand","service":"players","instance":"…","after":<last seq acked>}`;
the keyring answers the backlog after `after`, in order, then a heartbeat, then live notices. The
first heartbeat on a channel therefore says its backlog is done (decided 2026-11-04, T055); no
periodic heartbeat reaches a channel still sending its backlog.

- `create = true` on a write: makes the key if absent (FR-005); refused for a tombstoned subject.
- A fetch from another project's consumer is admitted only when `grants.json` holds a consume grant
  with `decrypt` on a topic of `project` for the caller (FR-018); the keyring reads the rendered
  grants of every project from the `ankka-project` ConfigMaps it may list.
- The control plane's identity may call only the three `/v1/erasures` routes; a key fetch from it is
  refused and recorded.
- An instance acks when its duties are done (R15). `handler` is present on the one instance that ran
  the service's erasure handler: `finality` is `immediate` or `after:` and an ISO-8601 duration
  (`after:P7D`); a run that threw is acked all the same with `{"objectsErased":0,"finality":"failed","error":"<why>"}`,
  and the next pass runs it again.
- `/v1/decrypt` answers values field by field; refused once the grant is revoked or the data subject
  is erased; each call recorded (FR-031).

## Startup order

1. Read the root key (secret store, or KMS through the `wrapping-key` output).
2. Read `applied_log`'s last sequence; read the erasure log's two copies from the control plane's
   `GET /internal/erasure-log?after=<seq>` and the bucket; apply the union in order; report a copy
   that was behind on `/status` and in its log (FR-021).
3. Only then serve. `/ready` holds until step 2 finishes.

## The runtime's keyring client (`RT/keyring/`)

- `KeyringClient`: the cache (R5, FR-020) over `RemoteKeyStoreClient`, the JDK HTTP client over the service's mutual-TLS identity, its notices on a WebSocket;
  `Notices` held open from start; on no confirmation within `notice-bound`, the cache is dropped
  whole.
- `KeyringHandle`: implements `core`'s `Keyring` over the client; installed as `Keyring.current`.
- `ErasureDuties`: on a notice, in order: drop the key; redact rows and tokens per view (R14); end
  agent sessions and stop instances of the subject (R23); run the handler on the singleton (R16);
  ack.
- `KeyringRelay` SPI (R17) for a replicated component's subject.

## The sidecar protocol (1.23)

`client.proto`:

```proto
rpc GetSubjectKey(SubjectKeyRequest) returns (SubjectKeyReply);
rpc SubjectNotices(SubjectNoticesRequest) returns (stream SubjectNotice);
rpc AckSubjectNotice(SubjectNoticeAck) returns (SubjectNoticeAckReply);
rpc AskErasure(AskErasureRequest) returns (AskErasureReply);
```

`discovery.proto`: `Spec.erasure_handler` (bool). New service the process serves:

```proto
service Erasure { rpc Invoke(ErasureCall) returns (ErasureReply); }
message ErasureCall { string subject = 1; int64 erasure_seq = 2; bool rerun = 3; Metadata metadata = 4; }
message ErasureReply { int64 objects_erased = 1; string finality = 2; Error error = 3; }
```

- `Discovery` refuses `erasure_handler` from a `Spec` below 1.23; every SDK refuses discovery from a
  runtime below 1.23 when the service uses `Personal` or a handler.
- The sidecar answers `GetSubjectKey` from the runtime's `KeyringClient` (the sidecar is the
  service's identity); `SubjectNotices` relays the runtime's notices through `ClientService.drain`.
- The process acks; the sidecar acks the keyring when every process instance it fronts has.

## The Wasm ABI (`ankka1`)

| Import | In | Out | Permitted in |
|---|---|---|---|
| `keyring_seal` | subject, ad, plaintext | envelope bytes, or an error code | every call site |
| `keyring_open` | envelope bytes, ad | plaintext, `ERASED`, or an error code | every call site |

Export `erase(subject_ptr, len) -> result_ptr` when the module has a handler. `storage` gains the op
`erase`. `ModuleLoader.Imports`/`Exports`, `WASM-ABI.md` and `WasmImportsSuite` updated.

## The shared fixtures (`protocol/fixtures/personal/`)

- `subjects.json`: valid and invalid subjects with the refusal message.
- `envelopes.json`: key, nonce, subject, project, manifest, plaintext JSON, and the exact envelope;
  plus a spliced envelope that must fail as corrupt.
- Copied to each SDK with the protocol (`scripts/proto.*`), checked by CI's `diff -r`.
