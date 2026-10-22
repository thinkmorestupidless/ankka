# Contract: the replication seam, widened

What a transport beside the runtime's own may rely on from this release. Additive to spec 051's
`contracts/`, except the provider's signature, which changes.

## The provider is given the replicated components

```scala
trait ReplicationTransportProvider:
  def transport(
      system: ActorSystem[?],
      config: Config,
      settings: ReplicationSettings,
      replicated: Set[ComponentId]
  ): Option[ReplicationTransport]
```

`replicated` is every component of the service declared `replicated`, the same set the test kit's
`ReplicationTransportFactory` receives; empty when none is. A transport serves and tails those and
no others. A provider written against 051 does not compile against this release; the release note
says so.

## A handover's report through a public operation

```scala
object ReplicationPeer:
  def reported(
      system: ActorSystem[?],
      componentId: ComponentId,
      entityId: EntityId,
      from: String,
      position: Long
  ): Future[Done]
```

Delivers the report of a handover target (`from`, the installation that holds everything up to
`position`) to the local copy of the entity, as `ReplicationTransport.report` at the home must. The
future completes when the local copy has it; it fails when the component is not replicated here.
`EntityProtocol.Reported` stays private.

## The test kit's group over a supplied transport

```scala
val group = AnkkaTestKit.installations("leipzig", "bratislava").using(factory)
group.start("leipzig", descriptors)
```

`using` returns a group whose every `start` and `restart` runs that installation over
`factory(system, settings, replicated)`; the group's databases, names, `jdbcUrl`, `isStarted` and
`stopAll` are as today. `group.transport.pause`, `block` and `loopbackOnly` throw on a `using`
group, naming it: those switches belong to the runtime's own transport.
`ServiceBuilder.withReplicationTransport` stays `private[ankka]`.

## Naming an installation without a database

`ANKKA_INSTALLATIONS=leipzig,bratislava` adds `ankka.replication.installations."leipzig" {}` and
`."bratislava" {}`; names are lower-cased and must be DNS labels. A name that also has
`ANKKA_INSTALLATION_<NAME>_DB_*` entries is one installation, with its database. With names and no
transport on the classpath the service refuses to start as today, and the refusal's hint names
both forms. `PlatformVariables.Installations` declares the variable; it is the platform's to set.

## A forwarded write at the home

- The command arrives with its metadata unchanged plus `ankka.forwarded-from = <installation>`.
- The home admits it only when `<installation>` is in its own `ankka.replication.installations`;
  otherwise it answers `Rejected(CommandError("installation '<from>' is not admitted at '<home>'",
  Forbidden))`, which the forwarder returns to its caller as it is. Distinct from `Timeout` and
  from the "home cannot be reached" refusal.
- The handler at the home reads `CommandContext.forwardedFrom` and `CommandContext.attribution`.
- Each record the command persists carries `attribution` and `forwardedFrom`; `EventContext`
  exposes both; a view or consumer reading the journal sees them.

## Attribution

`ankka-attribution` in a component call's metadata: the subject of the principal who made the
original call, stamped by the runtime when the calling thread entered an endpoint handler that
authenticated one. Nothing else about the principal travels; a bearer never does. A call chain
keeps the first attribution. A transport carries metadata unchanged, so it carries this.
