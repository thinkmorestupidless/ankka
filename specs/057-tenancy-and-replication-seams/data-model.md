# Data Model: Tenancy and Replication Seams

## Control plane

### DeployToken (state of the `deploy-token` entity), extended

| Field | Type | New | Meaning |
|---|---|---|---|
| `project` | `Option[String]` | yes | the one project the token is a member of; `None` is organization-wide, as every token before this release |

`DeployTokenCreated` gains `project: Option[String] = None`, absent on old events. `DeployTokenRows`'s
row, `DeployTokenIndex.Live` and the wire `DeployTokenSummary` carry it; the admitted `Principal`
carries it as the claim `"project"`. `DeployTokenSummary` also gains `administrative: Boolean`
(the creator acted as the platform, from `DeployTokenCreated.actor.administrative`).

Validation (`DeployTokenRules`): a named project must exist and belong to the organization,
else 404 `no such project`, which is what a stranger is told. Lifecycle: the scope never changes;
the token ends with revocation, and a deleted project leaves it a member of nothing it can reach.

### Authorization, the scope

`scopedTo(principal) = principal.claims.get("project")`. On `project(principal, projectId, _)` and
`projectOwner`: when `scopedTo` is `Some(p)` and `p != projectId`, `noSuchProject(projectId)`. On
the organization's project listing: only `p`. On every owner-only route: refused as a member is.
New: `projectGrants(principal, projectId)`: `projectOwner`, or `scopedTo == Some(projectId)` with
the actor the token's subject.

### Grant views

`GrantAct(by, at)` gains `administrative: Boolean = false`; `GrantChangeRecord(change, by, at)`
likewise. Filled from the `Actor.administrative` the project's and the organization's grant events
already record.

### Installation (wire), extended

| Field | Type | Meaning |
|---|---|---|
| `broker` | `Option[InstallationBroker]` | absent from an older control plane |
| `retention` | `Option[InstallationRetention]` | absent from an older control plane |
| `erasure` | `Option[InstallationErasure]` | absent until spec 042: "not stated" |

`InstallationBroker(nodes: Option[Int], defaultCopies: Int, minInSync: Int)`: `nodes` absent when
`ANKKA_BROKER_NODES` is unset ("not stated"); the other two from `TopicPolicy.defaults`.
`InstallationRetention(longest: Option[String], unbounded: Boolean)`: `longest` the bound as a
duration string when there is one; `unbounded` true when the bound is `everything`.
`InstallationErasure(enabled: Boolean, keyringInstances: Int)`: reserved; never produced in this
release.

## Runtime

### Metadata keys

| Key | Set by | Meaning |
|---|---|---|
| `ankka-attribution` | the outgoing stamp of a component call, when the calling thread's trace carries an attribution | the subject of the principal who made the original call |
| `ankka.forwarded-from` | `HostReplication.forward` (exists) | the installation the write was forwarded from |

The attribution is entered by `HttpServer` from `RequestContext.principal.subject` for the life of
a handler, in the `Trace.Working` thread-local beside the call origin, and stripped nowhere: a call
chain keeps the first principal.

### CommandContext and EventContext (SDK)

`CommandContext.attribution: Option[String] = None`, `CommandContext.forwardedFrom: Option[String] =
None`; `EventContext.attribution`, `EventContext.forwardedFrom` likewise, read from the record.

### JournalRecord, extended

| Field | Type | JSON | Meaning |
|---|---|---|---|
| `attribution` | `Option[String]` | `NON_ABSENT` | the attribution of the command that persisted this record |
| `forwardedFrom` | `Option[String]` | `NON_ABSENT` | the installation the command was forwarded from, when it was |

Kinds unchanged. Old records read with both absent. The compatibility fixtures are not regenerated.

### Replication settings

`installations: Map[String, InstallationSettings]` as today; an entry from `ANKKA_INSTALLATIONS`
has no database. `enabled` unchanged. Admission at the home: `installations.keySet` plus the home
itself.

### ReplicationTransportProvider

`transport(system: ActorSystem[?], config: Config, settings: ReplicationSettings, replicated:
Set[ComponentId]): Option[ReplicationTransport]`.

### ReplicationPeer

`reported(system: ActorSystem[?], componentId: ComponentId, entityId: EntityId, from: String,
position: Long): Future[Done]`.

### Rejections

`CommandError("installation '<from>' is not admitted at '<home>'", ErrorCode.Forbidden)`, returned
to the forwarder as `ForwardedReply.Rejected`, which it answers unchanged.

## Protocol 1.22

`EventSourcedIn.Command`: `string attribution = 8`, `string forwarded_from = 9` (empty when none).
`EventSourcedIn.Event`: `string attribution = 4`, `string forwarded_from = 5`.
