---
paths:
  - "sdks/go/**"
  - "cli/src/main/templates/go/**"
---

# The Go SDK

A process SDK, like Python's and TypeScript's: a Go program serves the protocol over gRPC and the
sidecar image is the platform's program beside it (spec 059). Not WebAssembly: Rust is the module
language.

## The module

One module, `github.com/thinkmorestupidless/ankka-go`, rooted at `sdks/go`; its path is the mirror
repository's. The package `ankka` is the SDK, `testkit` the component and integration kits,
`internal/pb` the generated stubs, `internal/server` the conversations, `internal/kit` the seam the
kits use. `examples/shopping-cart` (the reference service, with `conformance/` and `cmd/conformance`)
and `examples/first-service` are modules of their own with a `replace` onto `../..`; `tools` is a
nested module holding buf and the generators, so they never enter a service's graph (buf needs Go
1.26.7, V3). `go.work` joins the four for development and a service never reads it.

**One module, test kit included, is a decision (R7), and V4 is why it holds**: a service importing
only `ankka` downloads no testcontainers code (`scripts/pack-smoke.sh` lists its dependencies); its
`go.sum` still records two testcontainers checksums, because the SDK's own tests import the kit.

```bash
cd sdks/go
go vet ./... ./examples/shopping-cart/... ./examples/first-service/...
go test ./... ./examples/shopping-cart/... ./examples/first-service/...      # fast, offline
ANKKA_SLOW=1 go test ./...                     # Docker: the integration kit and the slow cases
scripts/proto.sh                               # regenerate internal/pb from the module's protocol copy
scripts/proto.sh --check                       # CI: the committed stubs are what the copy generates
scripts/pack-smoke.sh                          # the module as the proxy serves it, built alone
cd examples/shopping-cart && go run ./cmd/conformance   # the suite against the Go reference (sbt, Docker)
scripts/conformance-broken.sh                  # the suite against a broken refusal: exactly 3 cases fail
sbt -Dankka.template.tests=go 'cli/testOnly *GoTemplateSuite'
```

**The generated code is committed**, unlike Python's and TypeScript's: `go get` builds what the
repository holds and runs no generator. `protocol/` under the module is a copy of the platform's
(CI diffs it, the fixtures and `ENCODING.md` against the originals), so the mirror builds alone.

## The version

`Version()` reads the SDK's version from the build's record of its dependencies
(`runtime/debug.ReadBuildInfo`); there is no placeholder in the tree. A build through `replace`
records `(devel)` or nothing, reported as `0.0.0`, which the integration kit maps to
`ankka-sidecar:latest`; a released version names `ghcr.io/thinkmorestupidless/ankka-sidecar:<v>`.

## Publishing

The release workflow's `sdk-go` job subtree-splits `sdks/go`, force-pushes the split to the mirror's
`main`, and pushes `v<version>` without force; it asks the proxy for the version and runs a program
that prints it. **A Go tag is never moved and never deleted**: the checksum database keeps what the
proxy first served, so a moved tag breaks every later download. A broken version gets a `retract`
in a later `go.mod`. The mirror, `thinkmorestupidless/ankka-go`, must exist with `TEMPLATE_REPO_TOKEN`
able to push to it before the first tag that carries the SDK.

## Traps

- **testcontainers-go's session id comes from the parent process** (2026-10-23), so the packages
  `go test ./...` runs at once share it, and a test counting the session's containers counted another
  package's kit. Every container a kit starts carries `RunLabel` with the kit's own run; a test
  finds what its start made by that label.
- **A development version carries `+`, which Go refuses as a module version** (2026-10-23). The Go
  template's pin is `v0.0.0` with a `replace` onto the checkout in GoTemplateSuite, and the suite
  rewrites the pin; a released CLI writes its own version.
- **A build through `replace` records no version** (2026-10-23). A test that needs a deployed
  process to state a real version builds against the SDK published into a file module proxy
  (`sidecar/src/test/resources/go-released`, `GOPROXY=file://`), as SidecarClusterSuite does.
- **The protocol's discovery message `McpServer` and the service `Mcp`'s server interface share a
  name in one Go package** (2026-10-23). `scripts/proto.sh` renames the service's identifiers to
  `McpServiceServer`; protoc-gen-go-grpc has no option for it. The protos carry no `go_package`, so
  `buf.gen.yaml` sets it in managed mode.
- **grpc-go refuses a received message over 4 MiB by default** (2026-10-23), which refused an object a
  process may keep. The server lifts it to `math.MaxInt32`, as Python lifts its bound.
- **A route template must start with `/`, the endpoint's own path included** (2026-10-23). The
  sidecar refused a client key route's `""` at discovery; the SDK refuses it first, naming it.
- **A sidecar of another major is answered with the spec, not refused by the process** (2026-10-23):
  the sidecar refuses and says why, where a process that refused discovery was retried for ever.
- **An HTTP `Authenticate` must name its issuers** (`Authenticate(ankka.Issuer(...))` or
  `ankka.Issuers(...)` on the endpoint): Go checks the code names them, the sidecar that
  `ANKKA_AUTH_ISSUERS` lists them, and the refusal of a service with neither names both.
- **A port held and not served leaves the sidecar's discovery waiting on one call, saying nothing**
  (2026-10-23). A test of a process that is not yet running leaves its port closed, so each dial is
  refused and the sidecar logs `discovery attempt … failed; retrying`.
- **The conformance runner reports each case as it ends** (`logBuffered := false`), or a three-minute
  run said nothing until the end, failures included.
- **The encoding is jsoniter-scala's, not `encoding/json`'s.** A double's text follows the JVM's
  `Double.toString`, held by `testdata/doubles.txt` (321 values), and DEL (U+007F) is escaped as
  `\u007f` with the control characters. The TypeScript and Python SDKs differ from Scala on both in
  places; the Go codec follows Scala.
- **Breaking `remove-item` in the reference left the conformance suite green** (2026-10-23): no case
  removes an absent product. The standing red proof breaks `refuse` (`-tags conformance_broken`).
- **The runtime's snapshot adapter in `EventSourcedEntityHost` ignores `snapshotSeq`**, for every
  process SDK: the Scala sample reading a journal a process snapshotted skips the event after the
  snapshot. The shared-journal case in `examples/shopping-cart/cart_slow_test.go` skips, naming the
  gap, rather than assert it.
- **`Declaration` has an unexported method**, so a kind the platform cannot host cannot be written in
  Go; `testdata/compilefail` holds that it does not compile, and `TestWhatMustNotCompileDoesNot` runs
  the compiler over each file there.

## What backs each Go row of the living features

The table FR-031 asks for (T041): every Go `Examples` row the spec added, and what holds it.

Branch `059-go-sdk` against `main`, `git diff main -- features` (42 files). 138 rows: 133 Examples
rows naming `Go`/`go` and 5 added or changed scenarios (2 documentation scenarios, the 2 scenarios
of `go-sdk/installing.feature`, 1 in `polyglot/releasing.feature`).

Conventions: `go test sdks/go -run X` is the root module; `sdks/go/examples/shopping-cart` is a
module of its own (run `go test . -run X` inside it); `(slow)` means the test calls `testkit.Slow`
or `testkit.StartT` and skips unless `ANKKA_SLOW=1`. `conformance <id>` is a `test("...")` id of
`sidecar/.../conformance/ConformanceSuite.scala`, run against the Go reference with
`cd sdks/go/examples/shopping-cart && go run ./cmd/conformance` (179 passed, 5 skipped, recorded in
`specs/059-go-sdk/quickstart.md`, T040). PARTIAL means the named test exists and exercises the
scenario, but at least one Then is not asserted for Go; every PARTIAL and MISSING row is in
Missing, below.

| Feature file | Scenario | Row | Backed by |
|---|---|---|---|
| agents/languages.feature | a tool that requires approval waits for a decision in every language | Go | conformance approval.awaits |
| agents/languages.feature | an approved tool call runs once after a restart in every language | Go | conformance approval.approved-runs-once (restarts the target between request and decision) |
| agents/languages.feature | an autonomous agent's wait for a decision uses none of the task's budget in every language | Go | conformance auto.approval.waits-without-budget |
| agents/languages.feature | a tool's call to another service is admitted in every language | Go | conformance svc.tool-calls-service |
| agents/languages.feature | an MCP server's tools are offered to the model in every language | Go | conformance mcp.tools-offered |
| agents/languages.feature | a result guardrail keeps an MCP server's result from the model in every language | Go | conformance mcp.result-guardrail-withholds |
| agents/model-providers.feature | an agent naming the model provider openai is answered by it in every language | Go | PARTIAL: conformance agent.names-a-model (an agent naming a second provider is answered by it and not the default); `TestAnAgentsPlanToolsAndGuardrails` (plan model "fast"); ProcessHostingRenderingSuite (the providers' keys are withheld from the process container). No case names `openai` itself |
| autonomous-agents/languages.feature | an endpoint of a process watches an agent instance's notifications | Go | conformance auto.notifications-in-order (through the Go reference's `SSE /autonomous/instances/{instance}/notifications`); conformance auto.notifications-no-replay |
| awaiting-workflows/languages.feature | a handler sends a command and is answered with the state in every language | Go | conformance wf.start-and-await; the feature's runner `LanguagesFeatures` (AwaitSteps) assumes the row away naming `sdks/go/examples` |
| awaiting-workflows/languages.feature | a handler that waits for a workflow that fails is answered with the failure in every language | Go | conformance wf.await-failed; `LanguagesFeatures` (AwaitSteps) assumes the row away naming `sdks/go/examples` |
| documentation/service-calls.feature | one page shows a call to another service in every language (changed to name "Go") | scenario | ServiceCallsDocumentationSuite "one page shows a call to another service in every language" (the Go endpoint's call and `sdks/go/services_step_test.go#step-calls-service`) |
| documentation/sockets.feature | the documentation describes a socket route in every language that has one (changed to name "Go") | scenario | SocketsDocumentationSuite "the documentation describes a socket route in every language that has one" (the Go socket sample in its tab) |
| endpoint-acls/route-acls.feature | a route that states no ACL answers to its endpoint's, in every language | Go | conformance http.caller-service (`/callers/whoami` states no ACL and is refused for `local/payments` by its endpoint's `AllowIf(Internet, ServiceNamed("orders"))`); `go test sdks/go -run TestTheEndpointDeclaresItsRoutes` (a route with no ACL declares none) |
| endpoint-acls/route-acls.feature | a route's own ACL applies to that route alone, in every language | Go | conformance http.route-acl-overrides-the-endpoint |
| endpoint-acls/stating-an-acl.feature | an endpoint that states no ACL is refused at registration | Go | `go test sdks/go -run TestWhatMustNotCompileDoesNot` (`endpoint_without_acl.go` does not compile); `go test sdks/go -run TestStartRefusesNamingAllFour` (a nil ACL: "endpoint /open: the endpoint has no ACL") |
| endpoint-acls/stating-an-acl.feature | an endpoint that states it allows all serves everyone | Go | conformance http.path-params-bind (any route of the `AllowAll` `/carts` and `/conformance` endpoints), e.g. also http.status-passthrough |
| erasure/languages.feature | an SDK writes the same personal envelope and reads it back into its own personal type | Go | MISSING in every language (spec 042's envelope is not implemented outside Scala; the limitations page says so) |
| go-sdk/installing.feature | the Go SDK fetched from the package registry builds a service with nothing else added | scenario | CI `sdk-go`: `scripts/pack-smoke.sh` (the module as the proxy serves it, built alone, and a stranger's program importing it); the release's proxy smoke (`go get` of the tag, then `Version()`) in `release.yml` `sdk-go` |
| go-sdk/installing.feature | a service's image holds its one executable and no language runtime | scenario | `go test sdks/go/examples/shopping-cart -run TestTheImageHoldsTheExecutableAndNoRuntime` (slow); SidecarClusterSuite (k3s, `cluster` workflow) "... ready only when both are (Go)" runs the developer's image beside the sidecar and serves requests |
| graph-deltas/store.feature | the same consumer written in another language fills the store the same way | go | conformance consumer.graph-deltas, consumer.graph-delete-and-recreate, consumer.graph-replay-is-equal (the same deltas the Scala reference publishes); `go test sdks/go -run TestTheBuilderWritesWhatTheSinkReads` (the `graph-deltas` fixtures the sink reads) |
| graph-deltas/testing.feature | a test reads back the elements a consumer published for a change | go | `go test sdks/go -run TestTheGraphConsumerKitShowsItsElements` (sequence 7; kind, key, version, labels, from, properties) |
| graph-deltas/testing.feature | a test reads back every message a consumer published and the key each named | go | `go test sdks/go -run TestTheConsumerKitShowsWhatWasPublishedAndItsKeys` |
| mcp-endpoints/languages.feature | a Python, TypeScript or Rust service declares an MCP endpoint and the platform answers the protocol for it | Go | conformance mcp-endpoint.lists-tools, mcp-endpoint.unknown-tool-is-protocol-error, mcp-endpoint.bad-argument-is-protocol-error; `go test sdks/go -run TestAnMcpEndpointIsDeclared` |
| mcp-endpoints/languages.feature | a service's own code is asked to run a tool with typed arguments and the principal, and never sees the credential | Go | conformance mcp-endpoint.call-reaches-handler, mcp-endpoint.client-key-admits-subject, mcp-endpoint.token-admits-subject; `go test sdks/go -run TestAnMcpEndpointsToolsRun` |
| mcp-endpoints/languages.feature | a service's own code issues, lists and revokes client keys through its client | Go, issues a client key for "acme" | conformance mcp-endpoint.client-key-admits-subject |
| mcp-endpoints/languages.feature | a service's own code issues, lists and revokes client keys through its client | Go, lists the client keys of "cart" | conformance mcp-endpoint.client-key-listed |
| mcp-endpoints/languages.feature | a service's own code issues, lists and revokes client keys through its client | Go, revokes a client key | conformance mcp-endpoint.client-key-revoked |
| polyglot/agents.feature | a tool runs in the developer's code with the model's arguments | Go | conformance agent.tool-invoked-in-process |
| polyglot/agents.feature | a guardrail is consulted in the developer's code | Go | conformance agent.guardrail-blocks |
| polyglot/agents.feature | an agent's reply reaches its caller as a stream while the model produces it | Go | conformance agent.stream |
| polyglot/agents.feature | a session survives a restart of the developer's code | Go, the process | RemoteAgentSuite (the session survives a restart of the process, for any process); conformance agent.session-survives-process-restart is skipped for every target |
| polyglot/agents.feature | a tool that fails is told to the model and the loop goes on | Go | conformance agent.tool-error-continues |
| polyglot/components.feature | a key value entity's state is kept and recovered after a restart | Go | conformance kv.recover-after-restart |
| polyglot/components.feature | a view's rows follow its source's events and are removed with its source | Go | conformance view.row-removed-with-source, view.row-updated |
| polyglot/components.feature | a consumer is given each event at least once and in the order its entity recorded them | Go | conformance consumer.at-least-once-in-order; `go test sdks/go -run TestAConsumerIsGivenEachEventInOrderAndNothingAgainAfterARestart` (slow) |
| polyglot/components.feature | a consumer publishes the messages its handler produces | Go | conformance consumer.single-produce-unchanged, consumer.produce-all-in-order |
| polyglot/components.feature | a timer set by a handler runs its timed action, and again after a failure | Go | conformance timer.fires; `go test sdks/go -run TestATimerRunsItsActionAgainAfterAFailure` (slow) |
| polyglot/components.feature | a timer due while the process is not running runs once it is | Go | RemoteProjectionSuite (a timer due while the process is not running runs once it is, for any process); conformance timer.fires-after-process-restart is skipped for every target |
| polyglot/components.feature | a workflow's steps run in the developer's code and each step is recorded | Go | conformance wf.runs-steps-in-order |
| polyglot/components.feature | a step that fails is retried and then failed over as the workflow declared | Go | conformance wf.step-failure-compensates; `go test sdks/go -run TestAWorkflowDeclaresItsStepsAndSettings` (retries and failover declared to the runtime) |
| polyglot/components.feature | a command to a workflow during a step is answered from the state before the step | Go | `go test sdks/go -run TestAWorkflowConversation` (a `status` query mid-step answered "started", the step then finishes) |
| polyglot/components.feature | a call through the SDK's client reaches its component wherever it runs | Go | conformance wf.step-calls-client; `go test sdks/go -run TestInvokeSendsTheWireAndDecodesTheReply`; SidecarClusterSuite (k3s, `cluster` workflow) "... restarted one instance at a time (Go)" (the Go endpoint's calls reach the cart on any of 3 instances) |
| polyglot/components.feature | a call made by a handler is nested under that handler in the trace | Go | conformance topology.call-attributed, client.trace-propagates; `go test sdks/go -run TestAConsumersHandlerIsGivenTheTraceItsChangeCarried`; SidecarClusterSuite (k3s, `cluster` workflow) "the trace ... (Go)" (the span's parent is in the same trace) |
| polyglot/conformance.feature | the conformance suite passes against the reference service in every language | Go | conformance, the whole suite: `cd sdks/go/examples/shopping-cart && go run ./cmd/conformance` (179 passed, 5 skipped); `go test sdks/go/examples/shopping-cart/conformance -run TestTheReferenceDeclaresEveryComponentTheSuiteExpects` |
| polyglot/conformance.feature | an SDK's MCP endpoint answers as the Scala reference does | Go | conformance mcp-endpoint.* (lists-tools, call-reaches-handler, handler-error-is-tool-error, unknown-tool-is-protocol-error, bad-argument-is-protocol-error, client-key-*, token-admits-subject, metadata-names-issuer, reference-tool) |
| polyglot/conformance.feature | a behaviour broken in an SDK is named and no other | Go | `sdks/go/scripts/conformance-broken.sh`, run by CI `sdk-go` (exactly the 3 cases reading the broken refusal fail, of 184) |
| polyglot/deploying.feature | a service hosted as a process runs beside the platform's own program and is ready only when both are | Go | SidecarClusterSuite (k3s, `cluster` workflow) "... ready only when both are (Go)" |
| polyglot/deploying.feature | a deployed service says which SDK its code was built with | Go | SidecarClusterSuite (k3s, `cluster` workflow) "... says which SDK (Go)" (the process's serving line: `sdk=ankka-go version=1.4.0`); `go test sdks/go -run TestTheSpecCarriesTheProtocolAndTheSDK`. No platform surface shows the SDK |
| polyglot/deploying.feature | a deployed service runs the platform's own program at the installation's version | Go | SidecarClusterSuite (k3s, `cluster` workflow) "... at the installation's version (Go)" |
| polyglot/deploying.feature | a service written in another language scales into one cluster without replacing an instance | Go | SidecarClusterSuite (k3s, `cluster` workflow) "... scales into one cluster (Go)" |
| polyglot/deploying.feature | a service written in another language is restarted one instance at a time and refuses no request | Go | SidecarClusterSuite (k3s, `cluster` workflow) "... restarted one instance at a time (Go)" |
| polyglot/deploying.feature | a service written in another language is exposed, paused and resumed like any other service | Go, not exposed, exposes "shop" | ProcessHostingRenderingSuite "an exposed process-hosted service renders the same route as an embedded one"; ExposureClusterSuite (the route answers by hostname). No suite exposes a process-hosted service in any language |
| polyglot/deploying.feature | a service written in another language is exposed, paused and resumed like any other service | Go, with 1 instance, pauses "shop" | SidecarClusterSuite (k3s, `cluster` workflow) "... exposed, paused and resumed (Go, pauses)" |
| polyglot/deploying.feature | a service written in another language is exposed, paused and resumed like any other service | Go, paused, resumes "shop" | SidecarClusterSuite (k3s, `cluster` workflow) "... exposed, paused and resumed (Go, resumes)" |
| polyglot/deploying.feature | the trace of a deployed service attributes each handler's time to the component that did the work | Go | SidecarClusterSuite (k3s, `cluster` workflow) "the trace ... (Go)" |
| polyglot/deploying.feature | a process that stops is started again while its instance stays in the cluster | Go | SidecarClusterSuite (k3s, `cluster` workflow) "a process that stops ... (Go)" |
| polyglot/documentation.feature | the first service in a language needs no JVM | Go, the Go toolchain and Docker | `docs check` (`docs/get-started/first-service-go.md`, its sample `sdks/go/examples/first-service`, tested by CI `sdk-go`) |
| polyglot/documentation.feature | the documentation of a language states every kind of component with a tested sample | Go, each kind of component | `docs check` (`docs/reference/go-sdk.md` and the Go tabs of the build pages, every sample included from tested code) |
| polyglot/documentation.feature | the documentation of a language states every kind of component with a tested sample | Go, calling a component | `docs check` (`docs/build/calling-services.md`'s Go tabs); ServiceCallsDocumentationSuite |
| polyglot/documentation.feature | the documentation of a language states every kind of component with a tested sample | Go, the component test kit and the test kit | `docs check` (`docs/build/testing.md`'s Go tab and the reference's Testing table) |
| polyglot/documentation.feature | the documentation of a language states every kind of component with a tested sample | Go, how a service runs | `docs check` (`docs/reference/go-sdk.md` "How a service runs") |
| polyglot/documentation.feature | a coding agent writing a component in a language is told how the language differs | Go | `docs check` and the rendered skills (`tools/docs/skill/ankka-go`; the Go differences in the other skills) |
| polyglot/encoding.feature | every fixture is read and written again exactly by an SDK | Go | `go test sdks/go -run TestEveryFixtureRoundTrips` |
| polyglot/entities.feature | a command runs its handler in the developer's code and records the events it names | Go | conformance es.persist-and-reply; `go test sdks/go -run TestAnEntityConversation` |
| polyglot/entities.feature | a refused command records nothing and its caller is told the refusal | Go | conformance es.refusal-persists-nothing |
| polyglot/entities.feature | a handler that fails records nothing and the service goes on serving | Go | conformance es.fault-is-failed-not-refused; `go test sdks/go -run TestAnEntityConversation` (a panic and a failure leave the state as it was) |
| polyglot/entities.feature | a restarted entity is recovered through the developer's code before its next command | Go | conformance es.recover-after-restart |
| polyglot/entities.feature | a snapshot is the developer's code's own, and recovery reads only the events after it | Go | conformance es.snapshot-on-request; `go test sdks/go -run TestASnapshotIsWhereRecoveryStarts` |
| polyglot/entities.feature | a service written in one language recovers what a service written in another recorded | written Scala, recovered Go | `go test sdks/go/examples/shopping-cart -run TestTheJournalIsTheScalaSamples` (slow; "Go reads both carts"); conformance es.journal-portable |
| polyglot/entities.feature | a service written in one language recovers what a service written in another recorded | written Go, recovered Scala | `go test sdks/go/examples/shopping-cart -run TestTheJournalIsTheScalaSamples` (slow; "Scala reads a Go cart that has no snapshot" and the Go cart read by a Scala instance) |
| polyglot/entities.feature | a query that would record an event is refused before the service is built | Go | `go test sdks/go -run TestWhatMustNotCompileDoesNot` (`query_persists.go` does not compile, naming `ankka.ReadOnlyEffect`) |
| polyglot/releasing.feature | a release publishes an SDK at its version after the platform's own | Go | `.github/workflows/release.yml` `sdk-go` (after `publish`; a tag on `thinkmorestupidless/ankka-go`, no stored registry credential) |
| polyglot/releasing.feature | a change to the protocol that an SDK's copy does not have fails the build | Go | CI `sdk-go`: `diff -r` of the protocol and the fixtures against the module's copy, and `scripts/proto.sh --check` |
| polyglot/releasing.feature | an SDK produced into a repository of its own is served by the package registry at the release's version | scenario | `release.yml` `sdk-go` (subtree push, tag never moved, the proxy asked for the version); `go test sdks/go -run TestTheVersionIsTheDependencysOrNone` |
| polyglot/starting.feature | a service is not ready until its process has declared its components | Go | `go test sdks/go/testkit -run TestAServiceIsNotReadyUntilItsProcessHasDeclaredItsComponents` (slow) |
| polyglot/starting.feature | a service whose code declares what the platform cannot host does not start | Go, two components named "cart" | `go test sdks/go -run TestStartRefusesWithEveryProblemAtOnce` (`share the id "cart"`) |
| polyglot/starting.feature | a service whose code declares what the platform cannot host does not start | Go, a kind the platform cannot host | `go test sdks/go -run TestWhatMustNotCompileDoesNot` (`testdata/compilefail/unknown_kind.go`: "does not implement ankka.Declaration") |
| polyglot/starting.feature | a service whose code declares what the platform cannot host does not start | Go, two handlers of "cart" named "add-item" | `go test sdks/go -run TestStartRefusesNamingAllFour` (`share the wire name "total"`); `go test sdks/go -run TestAnEntityDeclaresItselfAndItsProblems` |
| polyglot/starting.feature | a service whose code declares what the platform cannot host does not start | Go, an endpoint with no ACL | `go test sdks/go -run TestStartRefusesNamingAllFour` ("endpoint /open: the endpoint has no ACL"); `go test sdks/go -run TestWhatMustNotCompileDoesNot` |
| polyglot/starting.feature | a service whose code declares several problems is told every one at once | Go | `go test sdks/go -run TestStartRefusesNamingAllFour` (a shared id and an endpoint with no ACL in one refusal) |
| polyglot/starting.feature | a service whose code speaks a protocol version the platform does not is refused, naming both | Go | conformance discovery.refuses-wrong-major (a 99.0 runtime refused, naming 99.0, reported by the Go process); `go test sdks/go -run TestASidecarOfAnotherMajorIsLeftToRefuse`; `go test sdks/go -run TestDiscoveryOverTheWire` |
| polyglot/template.feature | a service started from the template passes its tests with none left out | Go | GoTemplateSuite "the project's own vet and tests, the slow one included, pass against this commit's SDK" (CI `sdk-go`) |
| polyglot/template.feature | a service started from the template runs beside the platform's own program of the template's version | Go | GoTemplateSuite (the slow test starts the sidecar the template names); PolyglotTemplateSuite's cases, inherited |
| polyglot/testing.feature | the component test kit shows a command's effect as values | Go | `go test sdks/go -run TestTheEntityKitShowsEffectsAsValues` |
| polyglot/testing.feature | a value the encoding cannot write fails in the component test kit | Go | `go test sdks/go -run TestAnUnrenderableFieldFailsInTheKitNamingIt` |
| polyglot/testing.feature | the component test kit follows a workflow to where it waits for a command | Go | `go test sdks/go -run TestTheWorkflowKitFollowsTransitionsToAPause` |
| polyglot/testing.feature | the test kit is ready before the test begins | Go | `go test sdks/go -run TestTheIntegrationKitIsReadyAndARestartKeepsState` (slow); `go test sdks/go -run TestAFailedStartLeavesNoContainer` (slow) |
| polyglot/testing.feature | the test kit restarts the platform's own program on the same database | Go | `go test sdks/go -run TestTheIntegrationKitIsReadyAndARestartKeepsState` (slow) |
| replication/languages.feature | an entity in every language is told where a change was written and whether it is at the home | Go | conformance es.replicated-context; the runner `LanguagesFeatures` (ReplicationSteps) assumes the row away, but its `commands` map has no Go entry, so the message reads "?" for Go |
| secrets/languages.feature | a service secret is kept and read in every language | Go | conformance secret.put-then-get |
| secrets/languages.feature | the database holds a service secret only encrypted in every language | Go | conformance secret.stored-encrypted |
| secrets/languages.feature | a service secret that was never kept is read as none in every language | Go | conformance secret.absent |
| secrets/languages.feature | keeping a service secret again replaces its value in every language | Go | conformance secret.overwrite |
| secrets/read-record.feature | a read through a process or a module is recorded without a component | Go | conformance secret.put-then-get (asserts the record's hosting and no component for a process target) |
| secrets/secret-key.feature | the process of a deployed service is not given the secret key | Go | SidecarClusterSuite (k3s, `cluster` workflow) "... not given the secret key (Go)" (the process's `/proc` environment) |
| secrets/secret-manager.feature | a service in every language passes every behaviour of the secret store on the Secret Manager backend | Go | conformance secret.* with `ANKKA_CONFORMANCE_SECRETS=secret-manager go run ./cmd/conformance` (wired in `cmd/conformance/main.go`; no run on that backend is recorded) |
| service-calls/calling.feature | a service in every language is admitted by name by a route that admits only it | Go | SidecarClusterSuite (k3s, `cluster` workflow) "... admitted by name (Go)"; conformance service.request-reaches-target |
| service-calls/calling.feature | a refusal by the service called reaches the calling handler as that refusal | Go | conformance service.refusal-is-the-answer |
| service-calls/calling.feature | a call to a service that cannot be found fails, naming the service, and is not sent | Go | conformance service.unresolvable; `go test sdks/go -run TestACallToAServiceWithNoAnswer` |
| service-calls/calling.feature | a call is not sent to a workload that is not the service asked for | Go | conformance service.identity-mismatch |
| service-calls/calling.feature | on a developer's machine a service in every language calls another service running there | Go | conformance service.request-reaches-target |
| service-calls/sdks.feature | the conformance suite's call to another service passes for every SDK | Go | conformance service.answer-reaches-handler |
| service-calls/sdks.feature | an SDK's type checks accept a call to another service | Go | `go test sdks/go -run TestACallToAServiceWithNoAnswer` (compiles and type-checks `CallService`; `go test` runs vet) |
| service-identity/languages.feature | an authenticated route admits a verified token in every language | Go | conformance http.auth-admits-verified-token |
| service-identity/languages.feature | an authenticated route challenges a token that does not verify in every language | Go | conformance http.auth-challenges-expired, http.auth-challenges-unlisted-issuer |
| service-identity/languages.feature | a claim reaches the handler by name in every language | Go | conformance http.auth-claims; `go test sdks/go -run TestThePrincipalsClaimsByName` |
| service-identity/languages.feature | a service with an authenticated route and no issuer listed does not start | Go | `go test sdks/go/testkit -run TestAnAuthenticatedRouteWithNoIssuerListedDoesNotStart` (slow; names `/orders` and `ANKKA_AUTH_ISSUERS`); `go test sdks/go -run TestStartRefusesNamingAllFour` |
| sockets/languages.feature | frames cross a socket both ways in every language | Go | conformance socket.frames-in-order; `go test sdks/go -run TestASocketRelaysFramesAndClosesWithItsHandler` |
| sockets/languages.feature | the handler of an authenticated socket route is told the principal in every language | Go | conformance socket.principal-and-caller |
| sockets/languages.feature | a request to open a socket with no token is challenged in every language | Go | conformance socket.challenged-without-token |
| sockets/languages.feature | a socket is closed when its handler finishes in every language | Go | conformance socket.closed-when-handler-returns |
| sockets/languages.feature | a service does not declare a socket route to a platform older than socket routes | Go | `go test sdks/go -run TestASocketRouteNeedsProtocol19` |
| storage-client/languages.feature | an object is kept and read through the storage client in every language | Go | conformance "storage.round-trip: an object kept is read back with its content type and length" |
| storage-client/languages.feature | the storage client lists, tells what an object is and deletes in every language | Go | conformance "storage.list: ...", "storage.stat: ...", "storage.delete-twice: ..." |
| storage-client/languages.feature | no object passes through the platform's own program | Go | conformance "storage.large: an object above the import's bound is kept by a process, refused to a module" |
| storage-client/languages.feature | every SDK refuses the same object names for the same reasons | Go | conformance "storage.rules: every SDK refuses the same object names and prefixes for the same reasons"; `go test sdks/go -run TestStorageRulesMatchTheFixture` |
| storage-client/languages.feature | the storage client in every language keeps its objects in a directory on a developer's machine | Go | `go test sdks/go -run TestTheDirectoryStoreKeepsTheRuntimesLayout`; `go test sdks/go -run TestTheChoiceOfStore`; `go test sdks/go/examples/shopping-cart -run TestTheStorageRoutesKeepAndReadObjects` (slow, directory store through the sidecar) |
| timers/languages.feature | a recurring timer fires once for each period in every language | Go | conformance timer.recurring.cadence |
| timers/languages.feature | a recurring timer set again keeps its next due time in every language | Go | conformance timer.recurring.set-again |
| timers/languages.feature | a cancelled recurring timer does not fire again in every language | Go | conformance timer.recurring.cancel |
| timers/languages.feature | a handler is told the due time of the timer that ran it in every language | Go | conformance timer.recurring.due-time |
| topics/groups.feature | a project made from a template states its service's name | go | PolyglotTemplateSuite "a project made from a template states its service's name", inherited by GoTemplateSuite |
| topics/start-position.feature | a consumer reading a topic must declare its start position | go | `go test sdks/go -run TestAConsumersProblemsAreNamed` ("no start": `reads the topic "in" and declares no start position`); `go test sdks/go -run TestAViewsProblemsAreNamed` |
| topics/start-position.feature | a start position is honoured in every language a service is written in | go | conformance topic.consumer-starts-latest, topic.view-starts-earliest |
| topology/languages.feature | a service declares the same connections in every language | Go | conformance "topology.declared-sources: a service declares the same connections in every language" |
| topology/languages.feature | a call is attributed to its caller in every language | Go | conformance "topology.call-attributed: a call is attributed to its caller in every language" |
| view-streams/languages.feature | every row is given as a stream in every language that can read one | Go | conformance view.stream-named (3 rows, not 5000) |
| view-streams/languages.feature | a watcher is given the rows now and then a row that comes to match in every language that can read one | Go | conformance view.watch-named; `go test sdks/go -run TestAWatchGivesRowsThenCaughtUpThenEnds` |
| views/languages.feature | a recursive query answers with the same rows in every language | Go | conformance "a recursive query answers with the same rows in every language" |
| views/languages.feature | a service whose view declares a statement that reads another table does not start in every language | Go | conformance "a service whose view declares a statement that reads another table does not start in every language" |
| views/languages.feature | a view of several sources reads every one of them in every language | Go | conformance "a view of several sources reads every one of them in every language"; `go test sdks/go -run TestAKeyedViewWritesRowsByKey` |
| views/languages.feature | a view finds the rows an event is about by asking a query of its own in every language | Go | conformance "a view finds the rows an event is about by asking a query of its own in every language" |
| views/languages.feature | the topology shows a view connected to each of its sources in every language | Go | conformance "the topology shows a view connected to each of its sources in every language" |
| workflow-sources/languages.feature | a view reads a workflow in every language | Go | conformance "view.workflow-completed: a view reads a workflow in every language" |
| workflow-sources/languages.feature | a consumer is handed a failed workflow's standing in every language | Go | conformance "consumer.workflow-end-once: a consumer is handed a failed workflow's standing", "view.workflow-failure-recorded: a failed workflow's standing in every language" |
| workflow-sources/languages.feature | the topology shows a view connected to the workflow it reads in every language | Go | conformance "topology.declared-sources: ..." (asserts `checkout -> checkout-rows` and `checkout -> checkout-ends` of kind `workflow`) |
| workflow-sources/languages.feature | the component test kit hands a view a workflow change in every language | Go | `go test sdks/go -run TestTheViewKitTakesAWorkflowStanding` |
| zero-trust/callers.feature | a service in any language is told the same calling workload | Go | conformance http.caller-service (as `local/orders`, on loopback through the local caller header) |

### Missing

Not backed for Go (1), and not for any language:

1. erasure/languages.feature, "an SDK writes the same personal envelope and reads it back into its own personal type": no SDK outside Scala has a personal type. A Go `Personal[T]` reading and writing the platform's envelope fixture, and a conformance case that journals it, would back it.

PARTIAL (2):

- agents/model-providers.feature, openai: the conformance case names a second scripted provider, not `openai`; the provider's own wire is proven once for every process by `OpenAiProviderSuite`.
- polyglot/deploying.feature, "exposes shop": backed by rendering, not by a cluster run; `SidecarClusterSuite` installs no gateway, and no suite exposes a process-hosted service in any language.

Backed by a language-independent proof rather than a Go run (2): the session that survives a restart of the process (RemoteAgentSuite) and the timer due while the process is not running (RemoteProjectionSuite). The conformance suite skips both for every target.

### Notes

- CI's `sdk-go` job runs every Go test, the slow ones with `ANKKA_SLOW=1`, the conformance run, the broken run and GoTemplateSuite. `SidecarClusterSuite` runs in the `cluster` workflow, nightly on `main` and on demand.
- `sdks/go/examples/shopping-cart` is its own Go module (`go.mod` with a `replace` to `../..`); its tests must be run from inside it. The integration tests there and `TestTheIntegrationKitIsReadyAndARestartKeepsState` skip unless `ANKKA_SLOW=1`.
- `ReplicationSteps.LanguagesFeatures.commands` has Python, TypeScript and Rust only, so the assume message for the Go row says `?` instead of the Go conformance command; `AwaitSteps.languageRow` names `sdks/go/examples` correctly.
- `StorageClientClusterFeatures.ranElsewhere` names the Python, TypeScript and Rust storage tests for the directory scenario and says "Python and TypeScript references" for `storage.large`; neither mentions Go.
- "a component of a kind the platform cannot host" cannot be written in Go: `Declaration` has an unexported method, so only the SDK's own constructors make one. The nearest refusal is a nil declaration ("declaration 5 is nil", `TestStartRefusesWithEveryProblemAtOnce`).
- "an endpoint that states no ACL" is a compile error in Go (`NewEndpoint` takes the ACL as its second argument); a nil ACL passed explicitly is refused at start. Both are tested.
- "a service with an authenticated route and no issuer listed": Go's own check is that the code names its issuers (`Authenticate(ankka.Issuer(...))`), not that `ANKKA_AUTH_ISSUERS` is set, so the Go row's "the reason names ... ANKKA_AUTH_ISSUERS" holds only through the sidecar.
- The erasure row has no backing in any non-Scala language, not only Go.
- The red proof for "a behaviour broken in an SDK" did not go as T040 planned: no conformance case removes an absent product, so breaking `remove-item` left the suite green; the `refuse` handler is broken instead, and `scripts/conformance-broken.sh` keeps that proof standing.
- The Secret Manager backend run for Go (`ANKKA_CONFORMANCE_SECRETS=secret-manager`) is wired but not recorded in quickstart.md.
- `view.stream-named` streams 3 rows, not the scenario's 5000; it is the case the other languages' rows rely on as well.
