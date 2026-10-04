# Quickstart: validating the polyglot service client

The runs that show the feature works, cheapest first. Shapes are in
[contracts/](contracts/); which scenario each run holds is in [research.md](research.md) (R14).
Docker is needed from the second run on.

## 1. The client's rules, with no service

```bash
sbt 'http/testOnly *ServiceClientSuite'
sbt 'proxy/testOnly *OutboundHeadersSuite'
```

Expected: the existing five cases, and new ones for a refused connection and a late answer
raising `ServiceUnanswered`, a listener that accepts and closes seeing exactly one connection,
and the platform's headers not arriving. The second suite holds the client's list of headers to
the proxy's.

## 2. Every component, in a running Scala service

```bash
sbt 'testkit/testOnly *ServiceCallsSuite'
```

Expected: one test per scenario of `features/service-calls/components.feature`, named for it,
against a `ScriptedService` on loopback. The span test reads the step's span id and asserts the
call's parent is that id.

To see it fail: remove `services` from `SimpleWorkflowContextImpl`'s wiring in `WorkflowHost` and
the suite does not compile; restore the two counting calls in `HttpServiceClients.counted` and
the span test alone fails.

## 3. The sidecar's rules

```bash
sbt 'sidecar/testOnly *ClientRequestSuite'
```

Expected: an entity's handler of each kind refused, naming the kind; a workflow's command
refused; a request with no caller made and counted from the unknown caller; a process that
declared 1.6 refused, naming 1.6 and 1.7; two requests at once, one to a slow service, with the
fast one answered first; a body of 4,000,001 bytes refused; each of the runtime's three errors
arriving as its `failure`.

## 4. The three languages agree

```bash
sbt 'sidecar/testOnly *ConformanceSuite -- *service.*'
cd sdks/python && uv sync && ANKKA_CONFORMANCE_ONLY='*service.*' uv run conformance
cd sdks/typescript && npm ci && npm run proto && ANKKA_CONFORMANCE_ONLY='*service.*' npm run conformance
```

Expected: eight cases pass in each run, and the first line of each run names its target. The
wildcard before `service` matters: without it the filter matches nothing and the run is green
having run nothing. A run with `-Dankka.conformance.target=wasm:…` reports the eight as skipped,
by name.

## 5. Each SDK on its own

```bash
cd sdks/python && uv run pytest -q && uv run mypy
cd sdks/typescript && npm run typecheck && npm test && npm run test:slow
cd sdks/rust && ./scripts/proto.sh && git diff --exit-code ankka/protocol && cargo test --workspace
```

Expected: the unit tests of `Services` against a fake sidecar (each reply case, the too-old
runtime, metadata forwarded, an entity with no `services`), one integration test through a real
sidecar container that calls a stand-in on the host, and Rust unchanged but for its copy.

## 6. A real cluster

```bash
caffeinate -i sbt 'sidecar/testOnly *SidecarClusterSuite'
```

Expected: the Python service `orders` calls the Scala `carts` on a route that admits only
`orders` and is answered `200` with "the orders service in project …"; the same route asked from
another service's pod answers `403`; the Python container holds no certificate and no key.

```bash
caffeinate -i sbt 'controlPlane/testOnly *ZeroTrustClusterSuite'
```

Expected: the Scala `orders` is admitted by the same route through the service client, `orders`
of another project is refused, and a request for the route through the gateway answers `403`
while `/callers/whoami` through the same gateway answers `200`. Read a failure whose duration is
absurd as the machine's, and run it again awake.

## 7. Documentation

```bash
just docs-sync && just docs
sbt 'controlPlaneApi/testOnly *ServiceCallsDocumentationSuite'
just features
```

Expected: the new page builds with every sample included from tested code; the suite finds the
call in three languages on one page and no sentence saying a Python or TypeScript service cannot
call another as itself; the checker reports nothing for this spec.

## 8. Everything

```bash
caffeinate -i sbt buildAll
```

## By hand, on a laptop

Run the Scala shopping cart twice on two ports, the second told where the first is with
`ankka.local-services.<name>`, and ask the second's `/callers/call/<name>`: the answer is "this
machine", because locally every caller is the local caller. Then the same call from the Python
example beside its sidecar, with the address given to the sidecar as
`JAVA_OPTS=-Dankka.local-services.<name>=http://host.docker.internal:<port>`. This is the run
that shows what a developer with two services on one machine has to write, and it is what the
new page's local section must say.
