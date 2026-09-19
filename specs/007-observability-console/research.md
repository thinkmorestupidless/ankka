# Research: Seeing What a Service Is Doing

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

Each finding says whether it was verified against this repository or is an assumption a named task
settles. The pattern this feature breaks from 001–006: those added things the platform *does*, and
could be proven by making the platform do them. This one adds a way to *watch*, and the danger is
different — an observer that changes what it observes, or that reports confidently about a request
it actually lost track of. Several findings below exist to keep those two failures visible.

## R1 — Local mode has no management endpoint, and that is deliberate

**Verified.** `ClusterFormation.scala` starts Pekko Management in the `bootstrap` path only, and
says why:

> Management is started here and only here because it binds a fixed port, which two local services
> on one laptop would fight over.

`ankka-cluster-kubernetes.conf` registers `http.routes.ankka-version`; `ankka-cluster-local.conf`
registers nothing and starts no management server. So `VersionRoute` — the obvious model for an
observability endpoint — **does not exist locally**, which is precisely where the console needs it.

**Decision**: observability is exposed twice, by environment, exactly as cluster formation already
is:

| mode | exposure | why |
|---|---|---|
| `local` | a small HTTP endpoint on an **ephemeral** port, address published for discovery (R3) | management is absent by design, and a fixed port would reproduce the clash the comment describes |
| `kubernetes` | a `ManagementRouteProvider` on the management port, registered in the overlay like `VersionRoute` | management is already running, already named `management` in the pod spec, and is where a scraper looks |

One recorder, two exposures. This is the same organising idea as "cluster formation is an overlay
chosen by where the process runs", applied to a second concern, and a new means of execution is a
new overlay entry rather than new code.

**Alternatives considered**: starting management in local mode too — rejected for the reason the
code already gives, and it would change local behaviour for every existing user; reusing the
service's own HTTP port — rejected because `runtime` must not depend on `http`, and because a
service may legitimately serve no HTTP at all (`"http": false`).

## R2 — The recorder lives in `runtime`, and carries no new dependency

**Verified by constraint.** `ankka-runtime` is a published artifact. Every dependency added to it
lands in the build of every application that uses the platform, and feature 006 already produced
one hard lesson about that (`dependencyOverrides` never reaching a POM, so the Pekko HTTP family
had to be declared directly). An observability library in `runtime` would be inherited by every
user whether they want it or not.

**Decision**: the recorder is plain Scala over JDK primitives — a bounded ring buffer of fixed-size
records. No metrics library, no tracing library, no exporter SDK. The exposition format for P3 is
Prometheus' text format, hand-written, because it is line-oriented and the metric set is small and
fixed.

**Rationale**: the runtime is the only place that sees every invocation, because effects are inert
data it interprets — so this is the correct home. The cost of putting it there is that it must be
frugal, which SC-003 enforces.

**Alternatives considered**: OpenTelemetry — the right answer for a platform with a customer asking
for it, and the wrong first step, since it is a large dependency graph imposed on every application
to serve a local console; Micrometer — same objection, smaller; a separate `observability` module —
does not help, since `runtime` would still have to depend on it to record anything.

## R3 — Discovery: a registry file per running service

**Decision**: a service in `local` mode writes a small file naming itself, its HTTP address and its
observability address; it removes the file on graceful shutdown. The console lists the directory.

**Rationale**: it is the simplest thing that gives Akka's dashboard-of-several-services, it needs no
port scanning and no broadcast, and it mirrors `~/.ankka/config.json`, which the CLI already owns.

**The trap it must respect, already recorded in `CLAUDE.md`**:

> Anything reading `~/.ankka/config.json` or `$HOME` must be overridable by a system property.
> Environment variables cannot be set in-process, so `ANKKA_CONFIG` alone makes `config set`
> untestable without writing to the developer's own home directory.

So the registry directory takes the same treatment — a system property checked first — or the
suites cannot exercise discovery without writing into the developer's home.

**Staleness is the real problem, not discovery.** A crashed process leaves its file behind. A file
whose observability address does not answer is stale and must be ignored and removed, not shown as
a dead entry — FR-009 requires services to disappear, and a `kill -9` is the common case in
development, not the rare one.

**Alternatives considered**: scanning a port range — fragile and slow, and indistinguishable from
someone else's server; having the console hold a socket services connect to — inverts the
lifecycle, so a service started before the console would never appear.

## R4 — Trace propagation rides on `Metadata`, which already crosses the sharding boundary

**Verified (T002), end to end through the code path.** The chain is complete and already
serialized:

| Step | Evidence |
|---|---|
| the caller supplies metadata | `ComponentClient.Invocation` holds `metadata: Metadata` and passes it to `transport.ask(...)` |
| it enters the sharding envelope | `ShardingTransport.ask` builds `EntityProtocol.Invoke(method, payload, MetaEntry.from(metadata), replyTo)` |
| it crosses a node boundary | `final case class MetaEntry(key, value) extends AnkkaSerializable`, and `reference.conf` binds `"…runtime.AnkkaSerializable" = jackson-cbor` |
| the callee reads it | `EventSourcedEntityHost` calls `MetaEntry.toMetadata(invoke.metadata)` into the handler's context |

So a trace identifier travels from an endpoint, through sharding, to an entity on another node
**with no change to any protocol type** — `EntityProtocol.Command` is untouched, and no new field
is added anywhere. Trace and span ids are simply two more metadata entries, and they are serialized
for free because `MetaEntry` already is.

**Decision**: the trace and parent-span identifiers are metadata entries under a reserved key
prefix. The HTTP entry point mints a trace; `ComponentClient` propagates it; each host records a
span against it.

**The honest gap.** `RequestContext` is a `ThreadLocal`, sound because one request owns one virtual
thread — and `CLAUDE.md` already records the consequence: *work handed to another thread cannot see
it*. A component that hands work to another executor therefore produces invocations the platform
cannot attribute. FR-002 and the spec's edge case require this to appear as an unattributed gap.
**It must never be resolved by guessing** — attaching an orphan span to the most recent trace would
produce a trace that reads correctly and is wrong, which is worse than an obvious hole.

## R5 — The console invokes endpoints over the service's real HTTP port

**Decision**: the "invoke" panel makes an ordinary HTTP request to the service's own HTTP address,
the one recorded in the registry file — not through a back door in the observability endpoint.

**Rationale**: this makes FR-012 structural rather than a check someone has to remember. The
console cannot bypass an ACL, because it is not privileged: it is another HTTP client, subject to
the same route matching, the same `acl`, and the same request context. There is no code path in
which the console reaches a handler an external caller could not, so there is nothing to get wrong
later.

**Consequence to accept**: a service with `"http": false` has nothing to invoke, and the console
must say so rather than offering a panel that cannot work.

## R6 — Logs: the control plane reads them, and that does not weaken what matters

**Verified.** `kustomization/components/controlplane/controlplane-rbac.yaml` grants the control
plane `get`/`list`/`watch` on `pods` **in its own namespace only** (a `Role`, for its own bootstrap
discovery since feature 004). It has no rights over a service's namespace at all.

**Decision**: the control plane gains `get` on `pods` and `pods/log` across service namespaces, and
serves logs to the CLI over its existing authenticated API.

**Why this does not erode the separation FR-023 protects.** The invariant is that the control plane
holds *no credential able to create or alter a workload* — that is what buys "a control plane that
holds no credential able to create a workload" and what makes the operator's withheld `delete` on
CNPG resources structural. `pods/log` is a read verb; it creates nothing, alters nothing, and
deletes nothing. The split survives intact.

**What it does change, stated plainly**: the control plane becomes able to read the application
output of every service in the installation. That is a real widening of what a compromised control
plane discloses, and it is the price of `ankka services logs` working with no cluster credentials
(SC-006). It is a disclosure change, not a capability change.

**Alternatives considered**: the operator proxies logs — it already has the pod access, but it has
no API surface at all and is deliberately "a process whose entire job is to keep working while
other things are broken", so giving it an HTTP server and an auth story is a large, badly-motivated
change; the CLI talks to the API server directly with the developer's own kubeconfig — rejected
because SC-006 requires logs on a machine with no cluster credentials, and because it would make
the CLI's rights differ from its rights for every other command.

## R7 — The console is served by the CLI, over the JDK's own HTTP server

**Decision**: `ankka local console` starts an HTTP server from `jdk.httpserver`
(`com.sun.net.httpserver`), serves hand-written HTML and JavaScript from the CLI's resources, and
exposes a small JSON API that aggregates the services it discovered.

**Rationale**: the CLI's defining property is that it "carries no actor system, no database driver
and no Kubernetes client", and `cli` depends on `controlPlaneApi` alone. The JDK's server keeps
that true — it is not a dependency, it is the platform. The same reasoning covers the UI: this
repository has **no frontend toolchain**, and introducing npm, a bundler and a lockfile to render
five panels would be the largest thing in the feature by far.

**Alternatives considered**: pekko-http in the CLI — contradicts the module's stated purpose and
drags an actor system behind it; a separate `console` module with its own binary — a twelfth module
and a second thing to install, for one command, and it loses the `ankka local console` UX the
feature exists to imitate; a single-page app with a build step — rejected above.

**Verified (T001)**: `HttpServer.create` binds an ephemeral loopback port and serves; the bound
port is **refused from a real interface** (tested against `192.168.0.212`, `ConnectException`), so
loopback-only is a real property and not an intention. Resources placed in
`cli/src/main/resources/console/` are packaged *inside* the CLI jar and read back from the staged
classpath (`sbt cli/stage`) with an ordinary `getResourceAsStream`. The UI therefore ships as
resources with no special packaging.

## R8 — Always-on has a budget, and the budget is the design constraint

The user chose always-on over sampling. SC-003 puts a number on it: throughput and median latency
within 5% of uninstrumented.

**Decision**: record fixed-width values only on the hot path — identifiers, two timestamps, an
outcome code — into a pre-allocated ring. Resolve names, build trees and format anything only when
a reader asks. No string concatenation, no map lookups by string, no allocation per span beyond the
record itself.

**Measured (T003/T007): recording one span costs 22ns**, stable across runs (22.1, 22.5, 22.2).
It therefore fits inside 5% of any invocation costing more than ~445ns. A deployed request — HTTP
parse, actor mailbox, sharding hop, journal write — is orders of magnitude above that.

**Three micro-denominators were tried and all three were wrong**, which is worth recording because
the next person will reach for them too: an empty loop said recording cost 58%; a jsoniter
round-trip said 28%; a testkit entity call said 50% *while measuring faster than the serializer
alone*, which is the JIT folding a monomorphic loop rather than work happening. A ratio that moves
that much with the shape of the harness is measuring the harness. SC-003 says "a **service's**
throughput", and the only honest denominator is a service — which means the gate cannot run until
recording is wired into the hosts. The plan sequenced T014 after T011–T013 for exactly that reason.

**Settled (T014), against a real service.** `ServiceRecordingCostSuite` drives a registered entity
through `ComponentClient` → sharding → entity → durable write → reply on a real Postgres:

| | |
|---|---|
| one real invocation | **640,529 ns** (0.64 ms) |
| recording one span | **22 ns** |
| cost of always-on | **0.0034%** of an invocation, against a 5% budget |

A margin of roughly 1,470×. Always-on stands, and needs no sampling, no switch and no revisit.
The suite also asserts that spans were genuinely produced by the path it timed — a benchmark whose
subject had been optimised away would otherwise measure nothing and pass.

**Verification is a task, not an assumption.** A benchmark comparing a representative workload with
recording on and off must run before the design is locked. If 5% cannot be met, the correct
response is to take the always-on decision back to the user — *not* to introduce sampling quietly,
which would leave the spec's assumption reading as though it still held. The spec's checklist says
this explicitly so that the decision cannot be lost.

**The second cost, less obvious than CPU**: memory. A bounded ring is the whole answer, and SC-004
tests it by driving 100,000 requests and 10,000 requests and requiring the same footprint.

## R9 — Agent cost is already computed, and the module direction allows recording it

**Verified.** `AgentLoop`, `AgentRuntime` and `AnthropicProvider` already carry token usage. The
module graph is `core → sdk → runtime → {http, agent}`, so `agent` may write to a recorder that
lives in `runtime`; the reverse would be a cycle and is not needed.

**Decision**: the agent records usage per model call against the current trace, including calls
that failed part-way, and the console totals it per session and per service.

**Cost needs a price the platform does not own.** Token counts come from the provider; money does
not. A price table the platform is told about converts one to the other, and an unknown model must
show tokens with *unknown cost* rather than a confident zero — a zero here is indistinguishable
from "free" and would be read as such.

## Carried to tasks as "verify first"

1. The 5% budget (R8) — benchmark before the recorder's shape is fixed.
2. `jdk.httpserver` under the JDK 21 baseline, from the staged CLI and from a jar (R7).
3. Stale registry entries after `kill -9`, and that the console drops them (R3).
4. A trace that crosses sharding to another node carries its identity through `Metadata` (R4).
5. That the control plane's new `pods/log` rule is actually sufficient, proven against a real
   cluster with a token scoped to the shipped RBAC — not with kind's admin credentials, which
   `CLAUDE.md` records as the reason an RBAC gap once reached a real deploy (R6).
