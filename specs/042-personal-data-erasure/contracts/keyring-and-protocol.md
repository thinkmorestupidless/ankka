# Contract: the keyring, its protocol, and the sidecar

## The keyring's gRPC service (`keyring/src/main/protobuf/ankka/keyring/v1/keyring.proto`)

Mutual TLS; the caller is read from its certificate (`ankka://<project>/<service>`), from a relayed
installation identity (R17), or from a machine bearer token through the gateway (decrypt only).

```proto
service Keyring {
  rpc GetSubjectKey(SubjectKeyRequest) returns (SubjectKeyReply);      // a service, or a granted consumer
  rpc GetLookupKey(LookupKeyRequest) returns (LookupKeyReply);         // a service of the project only
  rpc Notices(stream NoticeAck) returns (stream Notice);               // held open by every instance
  rpc Destroy(DestroyRequest) returns (DestroyReply);                  // control plane only
  rpc Resend(ResendRequest) returns (ResendReply);                     // control plane only (re-runs)
  rpc ErasureStatus(StatusRequest) returns (StatusReply);              // control plane only; no key
  rpc Decrypt(DecryptRequest) returns (DecryptReply);                  // a machine with a decrypt grant
}

message SubjectKeyRequest { string project = 1; string subject = 2; bool create = 3; }
message SubjectKeyReply {
  oneof answer { bytes key = 1; Destroyed destroyed = 2; Refused refused = 3; }
}
message Notice { int64 erasure_seq = 1; string project = 2; string subject = 3; bool rerun = 4; }
message NoticeAck { int64 erasure_seq = 1; string service = 2; string instance = 3; Completion completion = 4; }
message DecryptRequest { string grant = 1; string project = 2; repeated Envelope fields = 3; string manifest = 4; }
```

- `create = true` on a write: makes the key if absent (FR-005); refused for a tombstoned subject.
- A fetch from another project's consumer is admitted only when `grants.json` holds a consume grant
  with `decrypt` on a topic of `project` for the caller (FR-018); the keyring reads the rendered
  grants of every project from the `ankka-project` ConfigMaps it may list.
- The control plane's identity may call `Destroy`, `Resend` and `ErasureStatus` only; a
  `GetSubjectKey` from it is refused and recorded.
- `Notices`: the keyring sends every destroyed notice for the instance's project (and for projects
  it holds a decrypt grant on) as it happens, and the backlog since the instance's last ack on
  reconnect. An instance acks when its duties are done (R15).
- `Decrypt` answers values field by field; refused once the grant is revoked or the subject is
  erased; each call recorded (FR-031).

## Startup order

1. Read the root key (secret store, or KMS through the `wrapping-key` output).
2. Read `applied_log`'s last sequence; read the erasure log's two copies from the control plane's
   `GET /internal/erasure-log?after=<seq>` and the bucket; apply the union in order; report a copy
   that was behind on `/status` and in its log (FR-021).
3. Only then serve. `/ready` holds until step 2 finishes.

## The runtime's keyring client (`RT/keyring/`)

- `KeyringClient`: gRPC client over the service's mutual-TLS identity; the cache (R5, FR-020);
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
