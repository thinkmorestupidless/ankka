# Research: Tenancy and Replication Seams

Decisions taken against the code as it is on `main` at e3db4bf8 (2026-10-22). Paths are from the
repository root; the control plane is `controlplane/`, its wire types `controlplane-api/`, the CLI
`cli/`, the runtime `modules/runtime`, the protocol `protocol/`, the sidecar `sidecar/`.

## R1. A project-scoped deploy token is a scope on the token, not a new kind of member

**Decision**: `CreateDeployToken` gains `project: Option[String]`. The `DeployToken` state, its
`DeployTokenCreated` event, the `DeployTokenRows` row, `DeployTokenIndex.Live` and the admitted
`Principal`'s claims (`"project" -> id`) all carry it. The token stays a member of its organization
(`OrganizationEntity.addMember`, role `Member`, as today); the scope is applied where membership is
read: `Authorization.project` and `projectOwner` throw `noSuchProject(projectId)` when the principal
holds a `project` claim that is not `projectId`, and `requireMember`/`requireOwner` on an
organization-level route refuse a scoped token the organization-level writes it never had anyway
and narrow the reads that list projects (`GET /organizations/{id}/projects`) to its one project.

**Rationale**: the organization entity's member map is the one place membership lives, and every
suite (`AuthorizationMatrixSuite` test 10, `TenancyEntitySuite`) proves a token "is a member and
obeys every rule". A second membership kind would duplicate that. The claim is already how the
ACL tells a token's organization (`ControlPlaneAcl.scala:82`); the project rides beside it, and the
index replays it from the journal like the rest of `Live`.

**Alternatives**: a per-project member map on the organization (a new state shape, a migration of
every organization); a separate `project-token` entity (two token kinds to list, revoke and index).

## R2. A project token makes and ends its project's grants through one new check

**Decision**: a new `Authorization.projectGrants(principal, projectId): Authorized` admits an
owner (as `projectOwner` does) or a deploy token whose `project` claim is `projectId`. `POST
/projects/{id}/grants` and `DELETE /projects/{id}/grants/{grantId}` use it instead of
`projectOwner`. The grantee-side answers (`accept`, `decline`, `relinquish`) keep `requireOwner`,
so a project token is refused there as any member is (acceptance.feature's outline already pins
it). `GrantMade`'s actor is the token's subject, as every change by a token is attributed today.

**Rationale**: FR-002 widens exactly two routes for exactly one credential kind; the handlers say
"the endpoint checked who", so the widening lives where the check lives. The clarification of
2026-10-22 says every project token has the right, so there is no flag to carry.

**Alternatives**: giving organization-wide tokens the right too (declined by the spec);
a grant-specific permission on the token (a flag the clarification declined).

## R3. A platform administrator already passes both routes; the work is proof and visibility

**Decision**: `Authorization.require` already lets a `platform-admin` through `requireOwner` and
marks the actor `administrative = true` when the role alone allowed it, so `POST
/organizations/{id}/tokens` and `POST .../grants/{grantId}/accept` need no authorization change.
The work is: scenario tests that prove it (none exist); `administrative` exposed where the
organization's owners read what happened: `DeployTokenSummary.administrative: Boolean` (the
token's creator acted as the platform) and `GrantAct.administrative: Boolean` on every grant
change, including the received listing's `GrantChangeRecord`; and the docs (the "Roles" and
accept sections of `control-plane-api.md` say "owners only", which is wrong for an administrator).

**Rationale**: the entity events already carry `Actor(subject, display, administrative)`; only the
wire views drop it. Showing it is what "recorded under the role that allowed it" means, since the
"organization's history" is its token list and its grants listing (`CrossProjectAcceptanceFeatures`
documents that reading).

**Alternatives**: a new organization history route (a bigger surface than the spec asks); a
distinct admin-only route for each act (two routes doing what the existing ones already do).

## R4. The installation's facts come from the control plane's own configuration, read once

**Decision**: `Installation` gains `broker: Option[InstallationBroker]` (`nodes: Option[Int]`,
`defaultCopies: Int`, `minInSync: Int`), `retention: Option[InstallationRetention]`
(`longest: Option[String]`: a duration, or absent for unbounded, with `unbounded: Boolean` beside it
so the reader never has to infer) and `erasure: Option[InstallationErasure]` (`enabled: Boolean`,
`keyringInstances: Int`), all absent from an older control plane. `defaultCopies`, `minInSync`
and the longest retention are what `TopicPolicy` already holds (`ANKKA_TOPIC_DEFAULT_COPIES`,
`ANKKA_TOPIC_DEFAULT_MIN_IN_SYNC`, `ANKKA_TOPIC_LONGEST_RETENTION`), passed to
`InstallationEndpoint` as it is passed to the project routes. The node count is a new variable
`ANKKA_BROKER_NODES`, set to `1` on the control plane's own Deployment in the kustomize
`controlplane` component and to `3` by `broker-three-nodes` beside the topic defaults it already
sets; unset, `nodes` is absent ("not stated"). *Amended in implementation (accepted 2026-10-22)*:
the plan put the `1` in the `broker` component, but that component is listed before
`controlplane` and cannot patch a Deployment that does not exist yet, so the value lives with the
Deployment, as the topic defaults' laptop values already do. Erasure
is always absent in this release: spec 042 is a draft and nothing in the tree knows a keyring.

**Rationale**: the broker's defaults are configuration the control plane reads once and never
infers, so the node count sits best beside them, set by the same component that sizes the broker;
the per-project `brokerNodes` the operator writes to `AnkkaProject` status is the same number but
exists only once a project has topics, which the installation read must not depend on. Reporting
erasure as not stated is the honest answer FR-014 asks for; a requirement naming it is unmet until
042, which is the vendor's declared behaviour.

**Alternatives**: reading `KafkaNodePool` from the control plane (a Kubernetes client in a process
that deliberately has none beyond the operator seam); reporting erasure `enabled: false` (a guess
dressed as a fact).

## R5. The provider is given the replicated components, by changing its one method

**Decision**: `ReplicationTransportProvider.transport(system, config, settings, replicated:
Set[ComponentId])`. `ReplicationRuntime.resolve` passes the set it already computes for the
factory. No default method: a provider is a release-pinned consumer (ankka-replication asked for
this exact change), and a silent default would let an old provider serve every sharded type
again, which is the problem.

**Rationale**: the test kit's `ReplicationTransportFactory` already receives the set; the provider
is the only path that does not. One signature, one call site.

## R6. The handover report is delivered through a public `ReplicationPeer`

**Decision**: a new public object `runtime/replication/ReplicationPeer` with
`reported(system, componentId, entityId, from, position): Future[Done]`, which does what
`DatabaseReplicationTransport.report` does in the JVM (`EntityProtocol.Reported` told through the
sharding region for `componentId`) and completes when the region has it. `Reported` stays
`private[ankka]`; the runtime's own transport and `LoopbackRoutes` call `ReplicationPeer.reported`
too, so there is one path. The doc on `ReplicationTransport.report` names it.

**Rationale**: the request offered either; a public operation keeps the message hierarchy closed
and gives a transport one verb for the one private thing it needed. `Invoke`, `Succeeded` and
`Rejected` are public already, so forwarding needs nothing more.

## R7. The test kit's installations group takes a transport the test supplies

**Decision**: `AnkkaTestKit.Installations.using(factory: ReplicationTransportFactory):
Installations` returns a group whose every `start` wires `builder.withReplicationTransport(factory)`
instead of the test transport's; `transport` (the pause and block switches) is then a controller
over nothing and `pause`/`block` throw, naming `using`. `ServiceBuilder.withReplicationTransport`
stays `private[ankka]`: the group is the public way.

**Rationale**: the sibling reimplemented the group's pause and block over `AnkkaTestKit.start` per
installation; one method on the group gives it the databases, names, `restart` and `stopAll` it
rebuilt. Keeping `withReplicationTransport` private keeps "a transport is found by its provider" the
one way in production.

## R8. `ANKKA_INSTALLATIONS=a,b,c` names installations without a database

**Decision**: `ReplicationSettings.withEnvironment` also reads `ANKKA_INSTALLATIONS`, a
comma-separated list, each name lower-cased and checked by `nameProblem`, into
`ankka.replication.installations."<name>" {}`; a name that also has `ANKKA_INSTALLATION_<NAME>_DB_*`
entries is one installation with a database (the overlay merges). `PlatformVariables` gains
`Installations = "ANKKA_INSTALLATIONS"` in `PlatformOnly` beside `Installation`;
`PlatformDeclarationSuite` then sees it declared. The refusal message at
`ReplicationRuntime.scala:149-160` gains the new form to its hint.

*Amended in implementation (accepted 2026-10-22)*: the variable reaches the settings through a
configuration key of its own, `ankka.replication.named = ${?ANKKA_INSTALLATIONS}` in
`reference.conf`, which `ReplicationSettings.withEnvironment` reads beside the environment and
expands into empty `installations` entries. A separate key rather than writing the empty entries
directly: HOCON substitutes a variable's value, a string, and cannot turn a comma-separated list
into object keys, so the list has to be read as a string and expanded in code; and a key lets a
configuration file and the test kit's settings override name installations exactly as the
environment does, through the same expansion and the same name check. `installations` remains the
one set of names, with or without a database, that the rest of the runtime reads. An entry set to
`null` in a configuration above is not named.

**Rationale**: the request's first form; it is what the private deploy descriptors can write in one
line. `ANKKA_INSTALLATION_<NAME>=on` would collide with the prefix the sibling-database
credentials use and that `RuntimeOnlyPrefixes` hides from the process.

## R9. A forwarded write carries the acting principal as attribution; the home sees the forwarder

**Decision**: a new metadata key `ankka-attribution` (constant `Attribution.MetadataKey` in
`runtime/Attribution.scala`), holding a principal's subject. It is stamped where `ankka-caller` is
(`Trace.scala:~265`, the outgoing-metadata stamp of a component call) from a new field on the
`Trace.Working` thread-local, set by `HttpServer` around a handler when the `RequestContext` holds
a principal, in the same wrapper that enters the trace. A forward carries metadata unchanged
(`HostReplication.forward`), so the home receives `ankka-attribution` beside `ankka.forwarded-from`.
At the home, `CommandContext` gains `attribution: Option[String]` and `forwardedFrom:
Option[String]`, read from those keys; a persisted `JournalRecord` gains `attribution` and
`forwardedFrom`, both `@JsonInclude(NON_ABSENT)` (the compatibility fixtures stay as they are),
and `EventContext` exposes both. Nothing else about the principal travels; a bearer never did.
"The home sees the caller as the forwarding installation" is `forwardedFrom`, since entities carry
no `Caller`; the `ankka-caller` origin stays what it is (the follower's host and handler).

**Rationale**: the family decided the forwarding installation is the caller and the person is
attribution; entity calls today carry no principal, so the only question was where to stamp it,
and the one place every component call's metadata is stamped is the right one. The journal must
carry it for "the record says who acted", and `NON_ABSENT` keeps old records the release before's
form (`.claude/rules/runtime.md`, the compatibility trap).

**Alternatives**: a `Caller` in `CommandContext` (a type from `modules/http`, which `runtime` cannot
see; and a caller for a sharded call that was never one); stamping at the entity host from the
request context (a thread-local the host's thread never had).

## R10. Admission at the home is the home's own installation list

**Decision**: when a home receives a command whose `ankka.forwarded-from` names an installation
that is not in its `ReplicationSettings.installations`, `HostReplication` rejects it with
`CommandError(s"installation '<name>' is not admitted at '<home>'", Forbidden)` before the handler
runs; the forwarder maps the rejection to the caller as it is, so the caller reads the name, and
distinctly from `Timeout` and `notHome`. Admission across organizations by a grant to an
installation's identity is ankka-replication 002's (clarification 2) and is not here.

**Rationale**: the spec's FR-012 needs a meaning of "admit" within the platform; the one list the
home already holds is the right one, and it is what the runtime's own transport refuses to start
without.

## R11. The sidecar protocol carries attribution and the forwarding installation: 1.22

**Decision**: `EventSourcedIn.Command` gains `string attribution = 8` and `string forwarded_from =
9`; `Event` gains `string attribution = 4` and `string forwarded_from = 5`; `WireProtocol.Version`
becomes `"1.22"` with its line in `Discovery.scala`'s changelog (which still lacks 1.21's; add
both); the `.proto` copies, fixtures and version constants in the three SDKs move together, the
SDK contexts expose `attribution` and `forwarded_from` (`forwardedFrom`), and each language's
conformance covers a command carrying both. Key value entities and workflows are not replicated
(transport.feature's outline), so only the event sourced messages change.

**Rationale**: FR-011 and clarification 3; the 1.19 fields for `installation` and `at_home` are
the pattern, and the release bumps the version anyway. Attribution on a non-forwarded command is
carried too (the field is set whenever the metadata has it), so a handler reads it uniformly.

## R12. Where the scenarios run

**Decision**: `features/installation/facts.feature` gets `InstallationFactsFeatures`, a
`GherkinSuite` over `InstallationRouteSuite`'s harness with the control plane configured by
system properties for the variables (`-D` overrides of the `ANKKA_TOPIC_*` and `ANKKA_BROKER_NODES`
paths). The scenarios appended to `deploy-tokens.feature` and `administrators.feature`, which have
no suite, become munit tests named after the scenarios in `AuthorizationMatrixSuite` (the
matrix of who may do what) and `ControlPlaneHttpSuite` (the token routes), as that feature's
existing scenarios are. The replication scenarios get step definitions in `ReplicationSteps`;
the transport-beside-the-runtime's-own scenarios run over a `TestProvider`-style transport in the
testkit's test sources that records what it was given. `languages.feature` gains a row-less
mention: the protocol scenario's `ranElsewhere` covers 1.22 through `ProtocolSuite`.
