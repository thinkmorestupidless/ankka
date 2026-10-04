# Contract: the protocol change and the SDKs

## `endpoint.proto`

```protobuf
message Principal {
  string subject = 1;
  optional string name = 2;
  optional string email = 3;
  bool email_verified = 4;
  repeated string roles = 5;
  map<string, string> claims = 6;   // every other claim of the verified token, as text
  optional string issuer = 7;       // the configured name of the issuer that verified it
}
```

Version `1.4` → `1.5`. A process declaring `1.4` is accepted by a `1.5` sidecar and receives a
principal whose two new fields it cannot read. The sidecar fills both on every `AUTHENTICATED`
route.

## Discovery

No message changes. A sidecar with no issuer configured refuses an `AUTHENTICATED` endpoint or
route in the discovery report, with the text in `data-model.md` §5.

## SDK types

| SDK | Principal | Declaration |
|---|---|---|
| Python | `Principal(subject, name, email, email_verified, roles, claims: Mapping[str, str], issuer: str \| None)` | `acl = Acl.AUTHENTICATED` on an endpoint or `@get(..., acl=Acl.AUTHENTICATED)` |
| TypeScript | `Principal { subject, name, email, emailVerified, roles, claims: Readonly<Record<string, string>>, issuer: string \| null }` | `static readonly acl = Acl.authenticated` or `{ acl: Acl.authenticated }` on a route |
| Rust | `Principal { subject, name, email, email_verified, roles, claims: BTreeMap<String, String>, issuer: Option<String> }` | `Acl::Authenticated` |

Each SDK's `PROTOCOL_VERSION` becomes `"1.5"`.

## Conformance cases

Each reference (Scala in-process, Python, TypeScript, Rust) keeps `PrivateEndpoint` at
`/private` with `AUTHENTICATED` and gains `GET /private/me` answering JSON:

```json
{ "subject": "ada", "roles": ["buyer"], "tier": "gold", "issuer": "test" }
```

`ConformanceTarget` runs one `TestIssuer` per suite run and passes its configuration to the
sidecar; the issuer's name is `test`, its audience `conformance`.

| Case | Request | Expected |
|---|---|---|
| `http.auth-admits-verified-token` | `GET /private/me` with a token for `ada`, roles `buyer` | 200, the JSON above |
| `http.auth-claims` | the same token carrying `tier: gold` | `tier` is `gold` |
| `http.auth-challenges-missing` | no token | 401, `WWW-Authenticate: Bearer realm="…"`, the handler not reached |
| `http.auth-challenges-expired` | an expired token | 401 with `error="invalid_token"` |
| `http.auth-challenges-unlisted-issuer` | a token from a second issuer not configured | 401, and the second issuer's keys were never fetched |

`http.acl-deny-never-reaches-process` loses its `|| 503` and asserts 401.
