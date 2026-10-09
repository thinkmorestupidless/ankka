# Contract: what a service declares and reads

Held by `features/cross-project/route-grants.feature`, `topic-grants.feature` and the conformance
suite's new cases.

## Granted callers

| SDK | Declaration |
|---|---|
| Scala | `Acl.allowCallers(Callers.granted)`; `Acl.allowCallers(Callers.internet, Callers.granted)` |
| Python | `Acl.allow_callers(Callers.granted)` |
| TypeScript | `Acl.allowCallers(Callers.granted)` |
| Rust | `Acl::Callers(vec![CallerMatcher::Granted])` |

- `Callers.granted` admits a caller that holds an accepted grant naming *this* route (method and
  template) or *this* gRPC method (`Service/Method`) of this service. It admits `Caller.Local`.
- It reads the grants file and nothing else. A route whose ACL does not name it never consults a
  grant.
- The gRPC binding evaluates it exactly as the HTTP server does; a refusal is `PERMISSION_DENIED`.
- A refusal on HTTP is a 403 with the usual body (`not permitted by this endpoint's acl`) and is
  recorded as a refused invocation in the service's topology, never as a failure.
- A grant revoked while a socket or an SSE stream it admitted is open closes it: the socket with
  code 1008 (`CloseReason.Revoked`), the stream by completion; a reconnect is refused.

## The grants file

`ANKKA_PROJECT_GRANTS=/var/run/ankka/project/grants.json` (platform-only; a module's `config`
answers it absent). Re-read when its modification time changes, checked at most every
`ankka.grants.reload-interval` (10 s). An unreadable file keeps the last good grants. Without the
variable: no grants, so `Callers.granted` admits only `Local` — which is every caller on a laptop.

In a test, `AnkkaTestKit.start(…, grants = Grants.of(GrantEntry(Caller.Service("payments",
"merchant"), GrantTarget.Route("POST", "/v1/wallets/{player}/{currency}/deposits"))))` and
`asCaller(Caller.Machine("eitheror", "affiliate-network"))` present a caller and a grant set with
no cluster.

## Cross-project topics

| SDK | Consume | Produce |
|---|---|---|
| Scala | `ChangeSource.fromTopic("spinvibe", "casino.players", decoder, StartFrom.Earliest)` or `TopicOptions(project = Some("spinvibe"))` | `produceTo("spinvibe", "payments.deposits")` or `Publication(…, project = Some(…))` |
| Python | `topic("casino.players", project="spinvibe", start_from=…)` | `produces_to("payments.deposits", project="spinvibe")` |
| TypeScript | `topic("casino.players", { project: "spinvibe", startFrom })` | `producesTo("payments.deposits", { project })` |
| Rust | `Source::topic("casino.players").project("spinvibe")` | `Publication::new("payments.deposits").project("spinvibe")` |

- The broker name is `<project>.<name>`; the consumer group stays the service's own
  (`ankka.<ownProject>.<service>.<kind>.<id>`).
- The project's declarations are not consulted for another project's topic; the start-time check
  passes; the broker refuses the subscription or the publish until a grant is in effect, and the
  subscription retries with the projection's backoff. The topology shows the edge as
  `topic:<project>/<name>`; `services get` lists it under `cross-project topics` as `granted` or
  `not granted (no grant | pending | ended)`.
- A declared broker (`TopicOptions.broker`) and a project cannot both be named: refused at start.

## Protocol 1.15

`CallerMatcher.granted`, `Caller.machine`, `Source.Topic.project`, `Publication.project`. An SDK
refuses discovery from a runtime below 1.15 when the spec uses any of them; the sidecar refuses
such a spec under an earlier minor. Conformance cases: `http.caller-machine`,
`http.granted-admits-a-grant`, `http.granted-refuses-without`, `http.granted-in-grpc`,
`topics.cross-project-source`, `topics.cross-project-publication`; the reference endpoint gains
`GET /callers/granted`.
