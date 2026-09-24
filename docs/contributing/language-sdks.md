---
title: Adding a language SDK
description: What an SDK for a new language must do to host services on ankka, and how the encoding fixtures and the conformance suite prove it compatible without the platform knowing the language exists.
kind: contributing
related: [reference/sidecar-protocol.md, reference/python-sdk.md, concepts/polyglot.md]
---

# Adding a language SDK

ankka hosts services in a language other than Scala through the sidecar: the ankka runtime runs beside the
service's process and speaks a gRPC protocol to it. The platform never learns which language is on the other
end. A new language therefore needs no change to the platform, only an SDK, and an SDK is compatible when it
passes two things: the encoding fixtures and the conformance suite. The Python SDK in
[`sdks/python`](https://github.com/thinkmorestupidless/ankka/blob/main/sdks/python) is the working example.

## What an SDK does

1. **Serves the protocol.** It implements every service in the protocol except `Client`, on loopback at
   `ANKKA_PROCESS_PORT` (9010 by default), and calls the sidecar's `Client` service at
   `ANKKA_SIDECAR_ADDRESS` (`127.0.0.1:9011` by default). The definitions and the rules they do not state are
   in [Sidecar protocol](../reference/sidecar-protocol.md).
2. **Answers discovery.** It describes every registered component and endpoint in a `Spec`: kinds, component
   ids, handler wire names and whether each is read-only or streaming, each kind's details, and the protocol
   version it speaks. It logs whatever the sidecar reports through `ReportError`.
3. **Gives developers the component model in their language's idiom.** Components declared with a stable
   component id and handlers declared with wire names separate from method names; handlers that return
   effects as values rather than performing them; queries that can only return a read-only effect, refused at
   registration otherwise.
4. **Encodes values exactly as the Scala SDK does.** Its default codec produces and accepts the encoding in
   [`protocol/ENCODING.md`](https://github.com/thinkmorestupidless/ankka/blob/main/protocol/ENCODING.md), so a
   journal written in one language is readable in the other.
5. **Ships testkits.** Unit testkits that run one component with no sidecar and still round-trip every value
   through its codec, and an integration testkit that starts Postgres and the real sidecar image.

## The protocol artifact

[`protocol/`](https://github.com/thinkmorestupidless/ankka/blob/main/protocol) is what an SDK consumes: the
`.proto` files under `src/main/protobuf`, `ENCODING.md`, and `fixtures/`. Copy the whole directory into the
SDK so it builds on its own, and generate stubs from the copy; the Python SDK's `scripts/proto.py` does both.
Continuous integration checks that the copy is identical to the original, so a protocol change reaches every
SDK deliberately.

## The encoding fixtures

Each file in [`protocol/fixtures`](https://github.com/thinkmorestupidless/ankka/blob/main/protocol/fixtures)
is one encoded value: a manifest, a content type, the bytes in base64, and the value in a language-neutral
JSON form. The fixtures are generated from the Scala codecs, and a build check fails if regenerating them
would change a committed file, so they always describe what the Scala SDK actually writes.

The SDK's own test runner reads every fixture, decodes the bytes with the codec the manifest and content type
select, compares the result with the value, re-encodes it and compares the bytes. A fixture with no matching
codec is a failure, never a skip. The Python SDK's `tests/test_encoding_fixtures.py` is the model.

## The conformance suite

`ConformanceSuite`, in
[`sidecar/src/test`](https://github.com/thinkmorestupidless/ankka/blob/main/sidecar/src/test/scala/com/thinkmorestupidless/ankka/sidecar/conformance),
is the platform's definition of a compatible SDK. It drives a reference service through that service's own HTTP
routes and reads the journal directly, so it works against any language. Each behaviour is one test case named
for what it checks, such as `es.refusal-persists-nothing`, so a failure names the behaviour.

It runs in two modes:

```bash
sbt 'sidecar/testOnly *ConformanceSuite'                                              # the Scala reference, in-process
sbt 'sidecar/testOnly *ConformanceSuite' -Dankka.conformance.target=127.0.0.1:9010    # a process listening there
```

With a target, the suite starts the sidecar against a throwaway Postgres and points it at the process
listening at that address. Without one, it runs the Scala reference service in-process, so both hosting modes
are held to one definition. Docker is needed for Postgres.

## The reference service

An SDK passes the suite with a reference service of its own: the same components, wire names and routes as
the Scala reference, `ConformanceReference`. They include a shopping cart entity, an entity whose handlers
exercise every effect, a key value entity, a workflow with a pause, a failure and a compensation, a view, a
consumer, a timed action, an agent with a tool and a guardrail, and three endpoints including one that
requires authentication. The Scala reference, `ConformanceReference`, beside the suite is the full list of
components, wire names and routes, and the suite itself names every behaviour. The behaviours are also
written down, one per case, in the
[conformance contract](https://github.com/thinkmorestupidless/ankka/tree/main/specs/009-polyglot-runtimes/contracts).

The Python reference service is the SDK's shopping cart example plus a conformance entity and endpoint, in
[`examples/shopping_cart`](https://github.com/thinkmorestupidless/ankka/blob/main/sdks/python/examples/shopping_cart).
`uv run conformance` in `sdks/python` serves it and runs the suite against it, and exits with the suite's
status. `ANKKA_CONFORMANCE_ONLY` narrows the run to matching behaviours.

## Declaring a service

A service built with the new SDK is deployed like any other, with a descriptor that declares process hosting
and the protocol version its SDK speaks:

```json title="service.json"
{ "name": "cart", "service": { "image": "registry.example.com/acme/cart-rb:1.0.0", "hosting": "process", "protocol": "1.0" } }
```

The image holds only the process. The platform supplies the sidecar.
