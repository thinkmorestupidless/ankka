---
paths:
  - "modules/runtime/**"
  - "modules/http/**"
  - "cli/src/main/resources/console/**"
  - "cli/src/main/scala/**/cli/console/**"
  - "controlplane/**"
---

# Observability, topology and the local console

## Calls are attributed by the runtime and counted from two ends

A service's topology (feature 019) has declared connections, read from the registry, and observed calls,
counted over a window (`CallCounts`, `ankka.observability.call-window`). The caller is a `CallOrigin`
(component and handler) held on the thread beside the trace (`Trace.within`, `Trace.currentOrigin`) and
written into a call's metadata as `ankka-caller` by `Trace.outbound`, which *strips* any caller already
there when nothing is current — a handler forwarding its own metadata must not put its call on whoever
called it. A name in metadata is believed only when `DeclaredNames` holds it, so nothing a call carries can
grow the names table. *Handled* (ok, refused, failed) is counted by the callee's host, *unanswered* (timed
out, undelivered) by the caller's transport, and the two are never added together; a view query and a
call to another service are counted where they are made, since nothing hosts the callee. Every host counts
through one function on `Observability`. A remote host stamps its own caller on what it sends the process,
and protocol 1.3 carries metadata on a step, a tool call, a guardrail check, a result check and a view query
so a call made from any of them is attributed. A deployed service's topology is read by the control plane
over port 7628 `observe` (`ObserveServer`, `InstanceTopologies`) and merged by `TopologyMerge`, which sums
pairs and recomputes percentiles from the instances' histograms.

## Traps

- **Local mode runs no management server, so observability needs its own local exposure.**
  `ClusterFormation` starts Pekko Management only in the `bootstrap` path, and says why: it binds a
  fixed port, which two services on one laptop would fight over. `VersionRoute` is therefore the
  obvious model for an observability endpoint and the wrong one — it does not exist where the local
  console needs it. Observability is exposed twice, chosen by where the process runs, exactly as
  formation is: a loopback endpoint on an ephemeral port locally (`ObservabilityEndpoint`, on the
  JDK's own HTTP server so `runtime` gains no dependency), and a `ManagementRouteProvider`
  (`ObservabilityRoute`) under Kubernetes. One recorder, two exposures.
- **A trace set on the caller's thread is invisible to the thread doing the work.** `dispatch`
  returns a `Future`; the handler runs later on its own virtual thread. Wrapping the *call to*
  `dispatch` in `Trace.within` compiles, runs, and produces an endpoint span and an entity span in
  two unrelated traces — a list, not a tree. The trace belongs where `RequestScope` already puts
  the request context: inside the `Future`, on the handler's thread. The general rule is the one
  `RequestContext` already states — work handed to another thread cannot see a thread-local — and
  tracing inherits it exactly. Where it genuinely cannot follow, the time shows as *unattributed*
  and an orphan span stays at the root marked unknown. **Never re-parent an orphan to the nearest
  plausible candidate**: a tree that reads correctly and describes something that did not happen is
  worse than a visible hole.
- **A refusal is not a failure, and the recorder has to be told which it was.** `effects.error(...)`
  returns a *value*, so a refused command reaches the caller looking exactly like a success — a
  `try`/`finally` around the handler records `Ok` for a working ACL. `interpret` returns the span
  outcome rather than leaving the caller to infer it from an exception that never comes. A console
  that paints a refusal red, or a fault green, teaches its reader to ignore the column.
- **Never intern anything unbounded into the recorder's name table.** Component and handler names
  are interned once and become `Int`s, which is what keeps a span allocation-free. That table is
  bounded *only* because registration is explicit and handler names are declared on companions.
  Entity ids, session ids and request paths with parameters filled in are not bounded, and interning
  one would grow the table for the life of the process.
- **A benchmark needs a denominator that is the thing the criterion names.** SC-003 asked for
  instrumentation within 5% of "a service's throughput". Measured against an empty loop recording
  cost 58%; against a jsoniter round-trip, 28%; against a testkit entity call, 50% *while measuring
  faster than the serializer alone*, which is the JIT folding a monomorphic loop. All three numbers
  were arithmetically true and answered a question nobody asked. Against a real service — HTTP in,
  entity, journal, reply — one invocation is 640µs and recording is 22ns, or 0.003%. A ratio that
  moves with the shape of the harness is measuring the harness.
- **An SVG with `role="img"` may not contain anything focusable.** axe reports `nested-interactive` for a
  topology picture whose nodes are buttons; the picture is a `group` with the same label.
- **A `var` that is assigned and never read is a lifecycle that never runs.** The local console
  endpoint was held in a `@volatile var` on the `Ankka` builder object; nothing read it, so
  `stop()` — and with it `ServiceRegistration.withdraw` — was unreachable, and every locally-run
  service left its entry in `~/.ankka/running` forever. Unit tests of `announce` and `withdraw`
  passed throughout: both were correct, and the defect was that one was never called. The console
  sweeps entries nothing answers for, so the only symptom was a directory quietly filling up. It
  belongs to `AnkkaService`, which is the thing that gets terminated — a singleton builder would
  keep only the most recently started endpoint anyway. `ServiceRegistrationSuite` drives the whole
  lifecycle for this reason; nothing narrower can catch a call that is never made.
