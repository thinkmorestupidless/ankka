# Polyglot runtimes: a design treatment

The specification is `specs/009-polyglot-runtimes/spec.md`. This document is the technical
shape behind it: what the sidecar model asks of ankka, what ankka already has, and the order
the work should go in. It is a treatment, not a plan; the plan will decide what it keeps.

## The model

[Cloudstate](https://github.com/cloudstateio/cloudstate) runs the developer's code in one
container and the cluster node in a sidecar beside it. The sidecar owns sharding, the journal,
snapshots, projections and timers. The developer's process owns one thing: given this command
and this state, what should happen. The two speak a protobuf protocol over the pod's loopback.

ankka is built on the idea that a handler returns a description of what should happen and the
runtime interprets it. That is the same division of labour with the process boundary removed,
and it is why the sidecar model is a second hosting mode for ankka rather than a rewrite.

## What already lines up

Three seams in the current code are, almost exactly, the three halves of Cloudstate's protocol.

**The effect is the message.** `EventSourcedEffect.Materialised` in `modules/core` is events,
new state, retention and a reply or a refusal. Cloudstate's `EventSourcedReply` is events, a
snapshot and a client action. A protobuf message for ankka's effect is a transcription. The
same holds for `KeyValueEffect` and for a workflow step's outcome.

**Handlers already take bytes.** `EntityProtocol.Invoke` carries an encoded payload and
metadata, and each host calls `binding.decodeAndInvoke(entity, bytes)` to obtain an effect.
Today the binding is a Scala closure. In the sidecar mode it is a request on a conversation to
the developer's process, and the effect comes back as data. Everything downstream of the
binding is unchanged: the span, the refusal-versus-failure accounting, the reply.

**The component client sits over a transport.** `ComponentClient` lives in `sdk` over
`CallTransport`, with `runtime` supplying the sharding-backed implementation. A polyglot SDK's
client is a `CallTransport` whose implementation is "ask the sidecar". That is how Cloudstate
SDKs called other entities, and the seam exists for that reason.

Registration stays explicit. Cloudstate's discovery handshake, where the developer's process
serves its component list, is ankka's "no classpath scanning" rule expressed as a protocol.

## What does not line up

**The fold.** The host stores state and sequence numbers, but `applyEvent` is the developer's
code, and so is the snapshot. Cloudstate's answer, which this design adopts, is a long-lived
bidirectional stream per loaded entity instance. The sidecar opens it with the recovered
snapshot and the events after it; the developer's process folds them and holds the state in
memory; commands then flow on that stream, and each reply's events are folded on both sides.
So the protocol is a conversation per instance, both sides are stateful while an instance is
loaded, and passivation must be signalled so the process releases its copy.

The `Stored` wrapper, deletion markers and expiry stay on the host side, where they already
live. The developer's process never sees them; that is the same boundary the in-process host
keeps between the runtime's lifecycle facts and the domain model.

**Local development and the testkits.** `sbt run` and `AnkkaTestKit` both assume one JVM. A
second-language developer needs the sidecar as a Docker image they run beside their code, and
an integration testkit that starts it under their own test runner. The unit-level testkit
ports naturally: effects are values in every language, and a handler can be run against an
in-memory state with no sidecar at all.

**A second compatibility surface.** `Compatibility` in `controlplane-api` checks a declared
runtime version against the platform. The protocol adds a promise between every SDK version
and every sidecar version that outlives both. The protocol is versioned independently and
carried in discovery; the sidecar refuses a version it cannot speak, and the control plane
applies the same "cannot project" refusal it applies to an unsupported runtime.

**A hop per handler.** One invocation measures 640µs end to end today. A loopback gRPC call
adds something in the low hundreds of microseconds, and workflow steps and agent tool loops
multiply it. That cost is real and should be visible in the console as time waiting on the
developer's process, never hidden.

## A caution from the lineage

Cloudstate became Akka Serverless, then Kalix, all sidecar-based with several SDKs. The next
generation moved the runtime back into the developer's JVM and shipped one SDK. The reasons
were the ones above: local development, debugging, latency, and SDKs that never earned their
upkeep. That is not an argument against the model. It is an argument for one additional
language built well, a conformance suite that defines "compatible", and leaving the in-process
path exactly as it is.

## The shape

1. **The protocol, envelope only.** Protobuf for discovery, the per-instance conversations,
   the component client call, and the agent's tool invocation. Payloads (commands, replies,
   events, state) stay opaque bytes with a content type. Existing journals keep their shape,
   and a journal is portable between a Scala service and its port.
2. **Remote descriptors in `runtime`.** A second family of `ComponentDescriptor` whose handler
   binding and fold go over the conversation, reduced through the same `materialise`. The
   hosts gain a remote variant of the two functions they call into user code; nothing else in
   them changes.
3. **The sidecar is `ankka-runtime` with a different `main`.** It boots with no components,
   runs discovery, builds its registry from the answer and starts the existing runtime. It is
   published as an image beside the operator and control plane. Cluster formation is the
   existing overlay per mode.
4. **The sidecar serves the HTTP surface.** The developer's process does not implement the
   platform's HTTP; the sidecar invokes components over HTTP as an in-process service's
   endpoints do. A developer who wants their own HTTP layer serves it on their own port and
   declares it, and the platform routes there. HTTP routing stays out of the protocol.
5. **The agent loop moves into the sidecar.** `ModelProvider`, session memory, compaction,
   guardrails and token accounting are platform code already; in the sidecar they are hosted
   once for every language, and the developer's process is asked only to run a tool. This is
   the strongest product argument for the whole feature.
6. **The descriptor and the operator.** `AnkkaServiceSpec` gains a hosting mode. The operator
   renders two containers for a polyglot service, injects the sidecar image and environment
   itself, delivers the database credential to the sidecar only, and gates readiness on the
   sidecar's discovery having completed. The descriptor cannot name the sidecar image or set
   its variables, by the same rule that refuses the cluster's variables today.
7. **One SDK and a conformance suite.** TypeScript is the working assumption, for reach among
   developers writing agents; Python is the alternative. The conformance suite drives a
   reference service through every conversation from the sidecar's side, and runs against the
   Scala SDK in-process too, so a divergence between hosting modes is a failing test.

## Order of work

The runtime side is additive and each step is testable alone:

1. Protocol definition and a Scala implementation of both ends, tested in one JVM with two
   processes' worth of state (the "developer's process" is a Scala test double speaking the
   protocol). This proves the conversation shape before any second language exists.
2. Remote entity hosts and the sidecar `main`, tested with `AnkkaTestKit` against the double.
3. Operator rendering and the k3s suite: a `pause`-style double image, then the real port.
4. The remaining component kinds, one conversation each, in the order views, consumers,
   timed actions, workflows.
5. The agent conversation.
6. The TypeScript SDK, its testkits, the shopping cart port, and the conformance suite run
   against both SDKs.

The SDK is where the cost lives and does not stop. Everything before it is a few features of
the size this repository has been shipping.
