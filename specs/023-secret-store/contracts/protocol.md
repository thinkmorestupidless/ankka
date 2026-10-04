# Contract: the sidecar protocol at 1.6

What a process and a module see. Decisions are in [research.md](../research.md) (R8).

## `client.proto`

```protobuf
service Client {
  // … the five calls of 1.3, unchanged …
  rpc GetSecret    (GetSecretRequest)    returns (GetSecretReply);
  rpc PutSecret    (PutSecretRequest)    returns (PutSecretReply);
  rpc DeleteSecret (DeleteSecretRequest) returns (DeleteSecretReply);
}

message GetSecretRequest { string name = 1; }
message GetSecretReply {
  oneof result {
    string value  = 1;   // the secret; never empty
    Empty  absent = 2;   // nothing is kept under the name
    Error  error  = 3;
  }
}

message PutSecretRequest { string name = 1; string value = 2; }
message PutSecretReply   { Error error = 1; }   // unset: kept

message DeleteSecretRequest { string name = 1; }
message DeleteSecretReply   { Error error = 1; }   // unset: removed, or there was nothing
```

A refusal or a fault is an `Error` in the reply, never a gRPC status, so that a module's import
can carry it. Codes:

| Case | `ErrorCode` |
|---|---|
| a name or a value that breaks its rule | `BAD_REQUEST` |
| no secret key; a key that is not the one the secret was kept with | `INTERNAL` |
| the database cannot be reached | `UNAVAILABLE` |

`GetSecretReply` with no case set is read as a fault by every SDK, never as absent.

## The `ankka1` imports

| Import | Request | Reply |
|---|---|---|
| `get_secret` | `GetSecretRequest` | `GetSecretReply` |
| `put_secret` | `PutSecretRequest` | `PutSecretReply` |
| `delete_secret` | `DeleteSecretRequest` | `DeleteSecretReply` |

`(ptr, len) -> i64`, as `invoke` and `query` are. Each blocks the calling instance. Before the
service is bound, each answers `Error(UNAVAILABLE)`, as `invoke` does.

`config("ANKKA_SECRET_KEY")` answers absent, as for every name the platform withholds.

## Version

- The protocol version is `1.6`. A 1.5 process or module runs unchanged.
- A process built for 1.6 calling a 1.5 runtime gets gRPC `UNIMPLEMENTED`; each SDK reports it as
  the runtime's protocol being too old for secrets, naming both versions.
- A module that calls the store imports the three functions and does not instantiate on a 1.3
  runtime; a module that does not call it imports none of them.

## The sidecar

`ClientLogic` gains `getSecret`, `putSecret` and `deleteSecret` over `AnkkaService.secrets`.
`ClientService` and `HostImports` call those and nothing else, so a process and a module get one
behaviour. The secret key is read by the runtime in the sidecar's container; it is never sent to
the process and no message carries it.

## Conformance cases

Each drives routes of the reference service's `ConformanceEndpoint` and reads the database
through the test kit.

| Case | Shows |
|---|---|
| `secret.put-then-get` | a value kept in one request is read in another |
| `secret.absent` | a name never kept answers absent, with no error |
| `secret.overwrite` | keeping again replaces; one row for the name |
| `secret.delete` | a removed secret answers absent; the row is gone |
| `secret.stored-encrypted` | the row's bytes are not the value, and the value is in no other table |
| `secret.refuses-bad-name` | a name with a space is `BAD_REQUEST`, naming the rule |
| `secret.refuses-empty-value` | an empty value is `BAD_REQUEST` |
| `secret.name-with-slash` | `provider/acme` is kept and read |

The reference service gains three routes in each language, on its `ConformanceEndpoint` and
declared in the conformance contract's route table: `POST /conformance/secrets?name=…` with the
value as a text body (`204`), `GET /conformance/secrets?name=…` (`200` with the value, `404` when
absent) and `DELETE /conformance/secrets?name=…` (`204`). The name is a query parameter because it
may hold a slash; keeping is a `POST` because the endpoint DSL takes a body on `POST` only.

`discovery.lists-every-component` is unchanged: no component is added.
