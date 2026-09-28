# Tasks: Zero Trust in the Service Clusters

**Input**: Design documents from `/specs/014-zero-trust-clusters/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: included. Every success criterion in the spec is a measurement against a real cluster or
a real TLS handshake, each contract ends with the suites that pin it, and this repository's rule is
that a guarantee nobody measured is a comment. Where a task says "case", it means a `test(...)` in
the named suite. The k3s suites are gated on `-Dankka.cluster.tests` as every existing one is.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel — different files, no dependency on an incomplete task
- **[Story]**: US1 (cluster traffic is private), US2 (a request carries the caller), US3 (a
  service calls another as itself), US4 (the database is mutual TLS with no password), US5 (the
  platform practices what it renders), US6 (the guarantee is written down)

Paths are repository-relative. Abbreviations: `RT` =
`modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime`, `RTT` its test twin;
`H` = `modules/http/src/main/scala/com/thinkmorestupidless/ankka/http`, `HT` its test twin;
`SDK` = `modules/sdk/src/main/scala/com/thinkmorestupidless/ankka/sdk`; `TK` =
`modules/testkit/src/main/scala/com/thinkmorestupidless/ankka/testkit`; `OP` =
`operator/src/main/scala/com/thinkmorestupidless/ankka/operator`, `OPT` its test twin; `CP` =
`controlplane/src/main/scala/com/thinkmorestupidless/ankka/controlplane`, `CPT` its test twin;
`API` = `controlplane-api/src/main/scala/com/thinkmorestupidless/ankka/controlplane/api`, `APIT`
its test twin; `SC` = `sidecar/src/main/scala/com/thinkmorestupidless/ankka/sidecar`, `SCT` its
test twin; `PROTO` = `protocol/src/main/protobuf/ankka/protocol/v1`; `PY` =
`sdks/python/src/ankka`; `TS` = `sdks/typescript/src`; `K` = `kustomization/components`; `RES` =
`modules/runtime/src/main/resources`; `SAMPLE` =
`samples/shopping-cart/src/main/scala/com/thinkmorestupidless/ankka/samples/shoppingcart`.

---

## Phase 1: Setup — spikes that retire the plan's risks (research R2, R4, R5, R7, R13)

**Purpose**: prove the four facts the design infers rather than reads, before any rendering is
written on top of them. Each spike's *answer*, recorded in [research.md](./research.md), is the
deliverable; its code is throwaway unless a task below names it as a seed.

- [X] T001 [P] Add `org.bouncycastle:bcpkix-jdk18on` as a `Test`-scope dependency in `project/Dependencies.scala` and `build.sbt` (`runtime`), and write `RTT/TestPki.scala`: `TestPki.root(name)` mints a self-signed RSA-2048 CA; `ca.issue(uris, dnsNames, cn, validFor)` mints a PKCS#8 leaf; `writeTo(dir)` writes `tls.key`, `tls.crt`, `ca.crt`. Make `http`, `operator`, `controlplane` and `sidecar` depend on `runtime % "test->test"` in `build.sbt` so every module can mint. Add a `TestPkiSuite` case that a minted leaf verifies against its root with the JDK's `CertPathValidator` and fails against another root.
- [X] T002 Spike R2 in `RTT/TlsBootstrapSpike.scala` (gated `-Dankka.spikes=on`): two `ActorSystem`s on loopback with `pekko.discovery.method = config` naming both management ports, management started with `withHttpsConnectionContext` (client auth `Need`) from a `TestPki` certificate and `Http().setDefaultClientHttpsContext` set before `ClusterBootstrap.start()`; assert the two form a cluster, that the probe URIs the coordinator logs are `https://…`, and that a third system holding a certificate from a *different* root never joins and logs a handshake failure. Record in research R2 whether the scheme was derived from the self contact point or needed an override, and the exact settings that worked. Keep as the seed of T021.
- [X] T003 [P] Spike R7 in `OPT/GatewayStack.scala` (a `-Dankka.spikes=on` case in `OperatorClusterSuite`): on k3s with the gateway stack, apply an `EnvoyProxy` with `backendTLS.clientCertificateRef` naming a Secret in `ankka-gateway`, a `BackendTLSPolicy` (v1alpha3) targeting a Service backed by an `nginx` requiring a client certificate from a `TestPki` root (CA in a ConfigMap `ankka-service-ca`), an `HTTPRoute`, and `curl --cacert --resolve` from the host: expect 200 with the gateway's client DN in nginx's access log, and a plain backend (no client cert) refused. Record in research R7 whether a `ReferenceGrant` was needed and which namespace the Secret must live in.
- [X] T004 [P] Spike R4/R5 in `OPT/OperatorClusterSuite.scala` (`-Dankka.spikes=on`): apply a CNPG `Cluster` with `certificates.clientCASecret` and `replicationTLSSecret` from a cert-manager `Issuer` over a self-signed CA, `postgresql.pg_hba: ["hostssl all +ankka_tls all cert clientcert=verify-full"]`, `managed.roles: [{name: ankka_tls, login: false}]`, a `DatabaseRole` with `disablePassword: true` and `inRoles: [ankka_tls]`, a client `Certificate` with `commonName` = that role; from a `postgres:16` pod run `psql "sslmode=verify-full sslcert=… sslkey=… sslrootcert=…"` and `SELECT ssl, client_dn FROM pg_stat_ssl JOIN pg_stat_activity USING (pid)`; also assert a second `DatabaseRole` *with* a `passwordSecret` and *without* the group still logs in by password. Record in research R4/R5 the exact `Cluster` fields accepted by CNPG's schema.
- [X] T005 [P] Create `K/trust-manager/` from the upstream chart rendered at a pinned version (`helm template trust-manager jetstack/trust-manager --version <pinned> -n cert-manager`), with a `README` line naming the command, and `K/pki/` with `ClusterIssuer ankka-selfsigned`, `Certificate ankka-cluster-ca` and `ankka-service-ca` (ns `cert-manager`, `isCA`, RSA 4096, `87600h`), `ClusterIssuer ankka-cluster`, `ClusterIssuer ankka-service`, and `Bundle ankka-service-ca` targeting namespaces labelled `app.kubernetes.io/managed-by: ankka`; include both in `kustomization/overlays/local/kustomization.yaml` and `overlays/cloud/kustomization.yaml`; add `RemoteOverlaySuite` cases in `CPT/RemoteOverlaySuite.scala` that both overlays render the two `ClusterIssuer`s and the `Bundle`, and that `overlays/cloud` documents replacing them (a `# SET` marker beside each issuer).

**Checkpoint**: R2, R4, R5 and R7 have recorded answers. If T002 needed an override, T021 uses
it; if T003 needed a `ReferenceGrant`, T041 renders it; if T004 found a field CNPG's schema
refused, T054 uses the accepted shape.

---

## Phase 2: Foundational — shared pieces every story depends on

**Purpose**: the one TLS provider, the labels, the config keys, the protocol fields, the operator's
new kinds and verbs, and the compatibility floor. Nothing here is visible to a service author yet.

- [X] T006 Write `RT/RotatingTls.scala` (research R12): `RotatingTls(directory, reloadInterval)` reads `tls.key` (PKCS#1 or PKCS#8 via `org.apache.pekko.pki.pem.PEMDecoder` + `DERPrivateKeyLoader`), `tls.crt`, `ca.crt`; exposes `serverEngine(): SSLEngine` (`setUseClientMode(false)`, `setNeedClientAuth(true)`), `clientEngine(host, port)`, `sslContext: SSLContext`, `identity: Option[(String, String)]` parsed from the leaf's `ankka://` URI SAN, and `current: X509Certificate`; re-reads when any file's mtime changed, checked at most every `reloadInterval` on demand, never on a timer thread. Add `RTT/RotatingTlsSuite.scala`: reload after `TestPki` rewrites the files; a stale context still validates the old leaf; a server engine refuses a client with no certificate (drive a `SSLEngine` pair in-process); `identity` parses `ankka://p/s` and is `None` for a certificate without the SAN.
- [X] T007 [P] Add `Labels.TransportKey = "ankka.thinkmorestupidless.com/transport"` and `Labels.TransportTls = "tls"` in `OP/Labels.scala` with a comment that, like `FormationKey`, it is on the pod template and the contact-point selector and never on the Deployment's immutable selector.
- [X] T008 [P] Add the configuration keys from `contracts/configuration.md` to `RES/reference.conf` (`ankka.http.tls.{enabled=off,directory=""}`, `ankka.tls.{cluster-directory,service-directory}=""`, `ankka.tls.reload-interval=1m`, `ankka.probe.{enabled=off,port=7627}`, `connection-factory.ssl.{mode,root-cert,cert,key}` from `${?ANKKA_DB_SSL_*}`), and the Kubernetes overlay lines to `RES/ankka-cluster-kubernetes.conf` (transport `tls-tcp`, `RotatingKeysSSLEngineProvider`, `secret-mount-point`, `tls-session-info-header = on`, the four directories, probe on, `ca-path = ""` with its comment). Add a `RTT/ClusterConfigSuite` case that the local overlay leaves every new key at its `reference.conf` default and the Kubernetes overlay sets each of them.
- [X] T009 [P] Extend the protocol: in `PROTO/endpoint.proto` add `Caller caller = 9` to `HttpRequest` with `Caller { oneof kind { Empty gateway = 1; ServiceCaller service = 2; Empty local = 3; } }` and `ServiceCaller { string project = 1; string name = 2; }`; in `PROTO/discovery.proto` add `CALLERS = 3` to `Endpoint.Acl`, `repeated CallerMatcher allow_callers` to `Endpoint` and `RouteSpec`, `CallerMatcher { oneof kind { Empty internet = 1; NamedService service = 2; Empty any_in_project = 3; Empty self = 4; } }`, `NamedService { optional string project = 1; string name = 2; }`; increment the protocol minor where `ProtocolVersion` is declared (find it with `grep -rn "ProtocolVersion(" --include=*.scala protocol sidecar modules`); regenerate stubs for Python (`uv run` proto step) and TypeScript (`npm run proto`); add a `SCT/ProtocolSuite` case that an SDK declaring the previous minor is still admitted.
- [X] T010 [P] Add `Compatibility.MinimumRuntime = Version(0, 8, 0)` to `API/Compatibility.scala`, make `supports` require `declared >= MinimumRuntime`, and give `describe` the detail `runtime <declared> predates mutual TLS; this platform requires 0.8.0 or later`; add `APIT/CompatibilitySuite` cases for `0.7.1` (refused, detail text), `0.8.0` (accepted), and the unchanged same-major/one-minor-below rule above the floor.
- [X] T011 Add the operator's new resource models and actions: `OP/certmanager/Certificate.scala` and `Issuer.scala` (fabric8 `GenericKubernetesResource` builders over `cert-manager.io/v1`, only the fields `data-model.md` §1–2 set), `OP/gateway/BackendTlsPolicy.scala` (`gateway.networking.k8s.io/v1alpha3`), `NetworkPolicy` through fabric8's built-in model; in `OP/Action.scala` add `EnsureCertificate`, `EnsureIssuer`, `EnsureNetworkPolicy`, `RemoveNetworkPolicy(namespace, name, ownerUid)`, `EnsureBackendTlsPolicy`, `RemoveBackendTlsPolicy(namespace, name, ownerUid)`, `Transition(namespace, name)` (the last is executed in T027); in `OP/Executor.scala` implement each as server-side apply with the operator's field manager or an owner-checked delete, treating a 404 on the `BackendTLSPolicy` *type* as "absent" for reads and removes, exactly as `HTTPRoute` is handled. Add `OPT/ExecutorSuite` cases (fake client) for the owner check on both removes.
- [X] T012 Extend the operator's ClusterRole in `K/operator/operator.yaml` (the symlinked canonical file) with `cert-manager.io certificates, issuers` (get list watch create patch delete / get create patch), `networking.k8s.io networkpolicies` (get list watch create patch delete), `gateway.networking.k8s.io backendtlspolicies` (same), `"" events` (get list), each with a comment saying which task and why; add `OPT/OperatorClusterSuite` cases with the operator's *own* minted token: it can create a `Certificate`, `NetworkPolicy` and `BackendTLSPolicy`; it cannot `list` secrets; it cannot `get` a `Certificate`'s Secret it was not told the name of; it cannot `delete` an `Issuer`.

**Checkpoint**: `sbt compile` warning-free; `sbt 'runtime/testOnly *RotatingTlsSuite'` green;
`sbt operator/test` offline green; the operator's token proves the new verbs and nothing more.

---

## Phase 3: User Story 1 — Cluster traffic is private to the service (Priority: P1)

**Goal**: remoting and management over mutual TLS with a rotated per-service certificate, refused
at the network from any other pod and at the handshake from any other identity; readiness intact;
the one non-rolling transition performed by the operator.

**Independent Test**: `ZeroTrustClusterSuite` steps 1, 2, 5 and 7 and `MultiNodeClusterSuite`
over TLS (`quickstart.md` §4).

- [X] T013 [P] [US1] Write `RT/ProbeEndpoint.scala` (research R2): a `RuntimeExtension` that, when `ankka.probe.enabled`, binds a plain pekko-http listener on `0.0.0.0:${ankka.probe.port}` serving only `GET /ready` → `HealthChecks(system).ready()` (200 / 503 with the failing check's name), nothing else 404; started by `Ankka.builder` after the other extensions so its 200 means what management's did. Add `RTT/ProbeEndpointSuite` cases: 503 until an extension with `readiness = Some(() => false)` flips; `/anything-else` is 404; not bound at all when disabled.
- [X] T014 [US1] In `RT/ClusterFormation.scala`, under `formation = bootstrap`: build a `RotatingTls` from `ankka.tls.cluster-directory`; start Pekko Management with `_.withHttpsConnectionContext(ConnectionContext.httpsServer(() => tls.serverEngine()))`; call `Http()(system).setDefaultClientHttpsContext(ConnectionContext.httpsClient((host, port) => tls.clientEngine(host, port)))` **before** `ClusterBootstrap(system).start()`, applying whatever T002 recorded; fail startup with a message naming the directory if any of the three files is missing. Convert T002's spike into `RTT/TlsClusterFormationSuite`: two systems form over HTTPS mutual TLS; a third with a foreign root does not join; a `/cluster/members` request with no client certificate is refused at the handshake; `/ready` on the probe port answers without one.
- [X] T015 [US1] In `OP/Rendering.scala`: render `Certificate <service>-cluster` per `data-model.md` §1 (issuer `ClusterIssuer ankka-cluster`, URI `ankka://<project>/<service>`, DNS `<service>.<ns>.svc`, RSA 2048 PKCS8 `rotationPolicy: Always`, `24h`/`16h`, both usages, owner reference), the `ankka-cluster-tls` volume mounted at `/var/run/secrets/ankka/cluster` (whole Secret, no `subPath`), the container port `probe` 7627, the readiness probe `httpGet /ready` on port name `probe`, the transport label on the pod template, and the transport label appended in `contactPointSelector`; keep `selectorLabels` untouched. Add `OPT/RenderingSuite` cases for each, `OPT/ServiceRenderingSuite`'s selector-equality case unchanged and still green, and a case that `spec.selector` is byte-identical to a rendering from before this task (pin the map).
- [X] T016 [P] [US1] In `OP/Rendering.scala`: render `NetworkPolicy <service>-cluster` (owned; `podSelector` = identity labels; ingress `{ports: [17355, 7626] from: [{podSelector: identity labels}]}` and `{ports: [7627]}` with no `from`), emitted on every render. `OPT/RenderingSuite` case pinning the exact rule set and that the policy selects the same labels the Deployment selects on.
- [X] T017 [P] [US1] Write `OP/Transition.scala` (research R9): `Transition.needed(existing: Option[Deployment]): Boolean` — true when the Deployment is ankka-managed (`Labels.ownedByAnkka`) and its pod template lacks `TransportKey=tls`; `Transition.detail = "moving to mutual TLS: instances restart together, once"`. Add `OPT/TransitionSuite`: a pre-feature template needs it; a post-feature one does not; a foreign Deployment does not (the foreign-object path refuses before this is reached anyway).
- [X] T018 [US1] In `OP/ServiceReconciler.scala` and `OP/Executor.scala`: when `Transition.needed` on the existing Deployment, execute `Action.Transition` — `DeleteDeployment`, then poll until no pod with the identity labels remains, bounded at 90 seconds; on the bound expiring, report `Deploying` with `Transition.detail` and return so the next reconcile retries rather than applying beside a live old pod; on success continue with the normal action list and report `Deploying` + the detail for that pass. Add `OPT/TransitionSuite` cases with a fake executor: the action order; the retry path; a normal reconcile emits no `Transition`.
- [X] T019 [P] [US1] In `OP/Executor.scala` `podProblems` and `OP/LifecycleRules.scala` (research R10): for a not-ready pod, read its Events (`involvedObject.uid`), pick the newest with `reason` in {`Unhealthy`, `ProbeWarning`} and fold its message into `PodProblem`, so a `Failed` detail reads `readiness probe: <kubelet's message>`; `OPT/LifecycleRulesSuite` case with a snapshot carrying such a problem produces that detail, and one without it is unchanged.
- [X] T020 [P] [US1] In `kustomization/deploy-local.sh` (research R8, FR-028): after the CRD-bearing controllers and before any ankka manifest, create namespace `ankka-netpol-probe`, two `busybox` pods (`A` running `httpd -f -p 8080`, `B` a client), a deny-all `NetworkPolicy` selecting `A`; `kubectl exec B -- wget -T 3 -qO- http://<A ip>:8080` must **fail** — on success print the message in `contracts/rendering.md` and `exit 1`; always delete the namespace. Refuse a kind whose `kind version` is below 0.24 with the same message before creating anything. Add `CPT/DeployLocalScriptSuite` (bash, like `ActionSuite`) that the probe block exits non-zero when the exec succeeds and zero when it fails, by stubbing `kubectl` on `PATH`.
- [X] T021 [US1] Extend `CPT/MultiNodeClusterSuite.scala`: every existing case (crash, partition, handoff) runs against the new rendering; add assertions that each pod's remoting connections are TLS (from the node: `ss -tnp` shows 17355 and a Java `SSLHandshake` line in the pod log at join) and that `/ready` on 7627 answered without a certificate throughout.
- [X] T022 [US1] Create `CPT/ZeroTrustClusterSuite.scala` (k3s, `GatewayStack`, the real control plane, the shopping cart image) with steps 1, 2 and 7 of `quickstart.md` §4: (1) three instances form; from a pod in `default`, `nc -z` to 17355, 7626 and 5432 fails and to 7627 succeeds; (2) a pod carrying the identity labels but a `TestPki` leaf from a foreign root (mounted from a Secret the suite writes) fails the handshake and membership is unchanged; (7) the transition — deploy through the previous release's operator image (`europe-west2-docker.pkg.dev/ankka-ops/ghcr/thinkmorestupidless/ankka-operator:0.7.1`), then roll the operator to the build's image, and assert exactly one `Deploying` observation carrying `Transition.detail` in `ankka services history`, then `Ready 3/3`, one cluster (`/cluster/members` from inside a pod with its own certificate), and a cart written before the transition readable after.
- [X] T023 [US1] Add the renewal case (step 5) to `ZeroTrustClusterSuite`: under a 20 req/s load from the host through the gateway, patch the `Certificate`'s status `Issuing=True` (`kubectl patch … --subresource=status`), wait for the Secret's `tls.crt` to change and the pod's mounted file to follow (≤ 2 min), and assert zero failed requests, zero container restarts, zero membership change events in the control plane's history; repeat for the service and database certificates once T041 and T056 exist.

**Checkpoint**: US1 is demonstrable alone — `sbt 'controlPlane/testOnly *ZeroTrustClusterSuite -- *cluster*'` and `*MultiNodeClusterSuite` green; the HTTP port is still plain until US2.

---

## Phase 4: User Story 2 — A request carries the caller's identity (Priority: P1)

**Goal**: every in-cluster HTTP connection is mutual TLS; a request reads as a named service or the
gateway; caller-naming ACLs in all three SDKs; local mode admits everything as the local machine
and the testkit can impersonate.

**Independent Test**: `ZeroTrustClusterSuite` step 4; `HT/TlsServerSuite`, `HT/CallerAclSuite`; the
five `ep.caller-*` conformance cases on three targets.

- [X] T024 [P] [US2] Write `H/Caller.scala` per `contracts/acl-and-caller.md`: `Caller`, `CallerMatcher`, `Callers`, `Caller.fromCertificate(cert): Either[String, Caller]` (URI SAN type 6; `ankka://gateway` → `Gateway`; `ankka://<p>/<s>` → `Service`; anything else `Left`), `CallerMatcher.admits(caller, self: (String, String)): Boolean` with rule 2 of the contract, and the header encoding/decoding for the local token (`gateway`, `service:<p>/<n>`, `local`). Add `HT/CallerSuite`: parsing, every matcher × every caller, `Local` admitted by all.
- [X] T025 [US2] In `H/HttpEndpoint.scala` add `Acl.AllowCallers(matchers: Vector[CallerMatcher])` and `Acl.allowCallers(first, rest*)`, and `protected def caller: Caller = request.caller`; in `H/RequestContext.scala` add `def caller: Caller` to the trait and `caller: Caller = Caller.Local` to `SimpleRequestContext`; in `H/HttpServer.scala` `Router.admit` handle `AllowCallers` (403 with the existing refusal text, recorded as a refusal); in `Router.contextFor` set `caller` from the `Tls-Session-Info` header when present (peer certificate → `Caller.fromCertificate`, a `Left` → 403 `unrecognised caller certificate` before routing), else from the local token header when it matches, else `Local`; the server's own identity comes from `RotatingTls.identity` in TLS mode and `("local", serviceName)` otherwise; log the FR-019 line once at start when any effective ACL is `AllowCallers` and TLS is off. Add `HT/CallerAclSuite` (plain, using the local token): scenarios 1–3 and 5 of the contract's conformance list, a route-level `withAcl(allowCallers(...))`, and that the refusal body names no matcher.
- [X] T026 [US2] In `H/HttpServer.scala`: when `ankka.http.tls.enabled`, bind with `Http().newServerAt(host, port).enableHttps(ConnectionContext.httpsServer(() => tls.serverEngine()))` from a `RotatingTls` over `ankka.http.tls.directory`, log `listening on https://…`, and set `boundAddress` accordingly; generate the per-process local token at start (only when TLS is off) and expose it `private[ankka]` as `localCallerToken`. Add `HT/TlsServerSuite.scala` with `TestPki`: a client presenting `ankka://p/x` reaches a handler that returns `caller` as JSON; no client certificate → handshake failure and no span in the recorder; a leaf without the SAN → 403 recorded as refusal; a leaf from a foreign root → handshake failure; after rewriting the server's files a new connection sees the new certificate (compare the peer certificate's serial) and an open keep-alive connection keeps working.
- [X] T027 [P] [US2] In `TK/AnkkaTestKit.scala` add `httpAs(caller: Caller)` returning the same HTTP helper the testkit already offers with `X-Ankka-Local-Caller: <token> <encoded caller>` added; the token is read from the started `HttpServer`. Add a `modules/testkit` suite case that `httpAs(Caller.Gateway)` is admitted by `allowCallers(internet)` and refused by `allowCallers(service("x"))`, and that a wrong token reads as `Local`.
- [X] T028 [US2] In `SC/RemoteEndpoint.scala` and `SC/GrpcConversation.scala`: map `ctx.caller` into `HttpRequest.caller` on every forward, and map discovery's `acl == CALLERS` + `allow_callers` (endpoint and route) into `Acl.AllowCallers` with the matchers; in `SC/Main.scala` read `ANKKA_LOCAL_CALLER_TOKEN` and, when set and TLS is off, hand it to `HttpServer` as the local token instead of a random one. Add `SCT/RemoteEndpointSuite` cases against `ProcessDouble`: the caller arrives on the wire for each of the three kinds; a `CALLERS` endpoint with `service("x")` refuses `Gateway`.
- [X] T029 [P] [US2] Python SDK: in `PY/endpoint.py` replace the `Acl` enum with a class per the contract (`ALLOW_ALL`, `DENY_ALL`, `AUTHENTICATED` as identity-comparable instances; `Acl.allow_callers(*matchers)`; `Callers.internet`, `Callers.service(name, *, project=None)`, `Callers.any_in_project`, `Callers.self_`), emit `CALLERS` + `allow_callers` into discovery for endpoints and routes; in `PY/context.py` add `Gateway`, `ServiceCaller`, `LocalCaller`, `Caller` and `request.caller` decoded from `HttpRequest.caller`; in `sdks/python/src/ankka/testkit` accept a `caller=` on the integration testkit's HTTP helper and pass `ANKKA_LOCAL_CALLER_TOKEN` to the sidecar container it starts. Add `sdks/python/tests/test_caller.py` (unit: discovery bytes; integration: the five cases against the sidecar) and keep `uv run mypy` clean.
- [X] T030 [P] [US2] TypeScript SDK: in `TS/routes.ts` and `TS/endpoint.ts` add `Acl.allowCallers(...)`, `Callers`, the `CallerMatcher` type and discovery emission; in `TS/context.ts` the `Caller` union and `ctx.request.caller`; in the integration testkit a `caller` option and `ANKKA_LOCAL_CALLER_TOKEN` for the sidecar container. Add `sdks/typescript/test/caller.test.ts` (fast: discovery shape; slow: the five cases) under both Node lines.
- [X] T031 [US2] Add the five `ep.caller-*` cases to `SCT/conformance/ConformanceSuite.scala` and the reference endpoint to `SCT/conformance/ConformanceReference.scala`; make the Python (`uv run conformance`) and TypeScript (`npm run conformance`) reference services implement the same endpoint; all three targets green.
- [X] T032 [US2] In `OP/Rendering.scala`: render `Certificate <service>-service` (issuer `ankka-service`, URI SAN, the three DNS names, both usages) and its volume at `/var/run/secrets/ankka/service` for every service with a `port`; render `NetworkPolicy <service>-http` (ingress `{ports: [<port>], from: [{namespaceSelector: managed-by ankka, podSelector: managed-by ankka}, {namespaceSelector: kubernetes.io/metadata.name = ankka-gateway}]}`) and `RemoveNetworkPolicy` for a service that dropped its port; a `"http": false` service gets neither. `OPT/RenderingSuite` and `OPT/ProcessHostingRenderingSuite` cases (the sidecar container gets the mount; the process container does not).
- [X] T033 [US2] In `OP/Rendering.scala`: beside `EnsureHttpRoute`/`RemoveHttpRoute`, render `BackendTLSPolicy <service>` (targetRef Service `<service>` sectionName `http`; `caCertificateRefs` ConfigMap `ankka-service-ca`; hostname `<service>.<ns>.svc.cluster.local`) and remove it with the route. `OPT/RenderingSuite` case for the exposed/unexposed pair and that the policy's hostname equals a DNS SAN of the service certificate rendered in T032 (assert against the rendered objects, not a string).
- [X] T034 [P] [US2] Gateway component: add `Certificate ankka-gateway-client` (ns `ankka-gateway`, issuer `ClusterIssuer ankka-service`, `uris: [ankka://gateway]`, client auth, 24h/16h) to `K/gateway/`, and `spec.backendTLS.clientCertificateRef` (plus the `ReferenceGrant` if T003 needed one) to `K/gateway/envoyproxy.yaml`; label the `ankka-gateway`, `ankka-auth` and `ankka-controlplane` namespaces `app.kubernetes.io/managed-by: ankka` so the `Bundle` reaches them. `CPT/RemoteOverlaySuite` cases: the certificate and the ref render in both overlays and the sidecar-patch lesson holds (assert the field is set once, not merely present).
- [X] T035 [US2] Add caller-facing routes to the shopping cart sample in `SAMPLE/CallerEndpoint.scala` (prefix `/callers`): `GET /whoami` under `AllowAll` returning the caller as `{kind, project?, name?}`; `GET /only-orders` under `allowCallers(Callers.internet, Callers.service("orders"))`; `GET /only-self` under `allowCallers(Callers.self)`; each wrapped in `// docs:start`/`// docs:end` regions for US6; register in the sample's `Main`; `samples/shopping-cart` testkit suite cases with `httpAs`.
- [X] T036 [US2] Add step 4 to `CPT/ZeroTrustClusterSuite.scala`: deploy the sample image three times — `shopping-cart` and `orders` in project `checkout`, `orders` in project `other` — and assert: from an `orders`/`checkout` pod, `curl` with its own service certificate to `https://shopping-cart.ankka-checkout.svc.cluster.local:9000/callers/only-orders` → 200 with `{"kind":"service","project":"checkout","name":"orders"}`; the same from `orders`/`other` → 403; through the gateway (`curl --cacert --resolve`) → 200 with `{"kind":"gateway"}`; `curl -k` to the pod IP with no client certificate → TLS failure and no request in `/ankka/metrics`; `GET /callers/only-self` from the gateway → 403.

**Checkpoint**: US1 + US2 together are the platform's zero-trust MVP: every port is mutual TLS,
every request has a caller, every SDK can name one.

---

## Phase 5: User Story 3 — A service calls another service as itself (Priority: P2)

**Goal**: `ServiceClient` by name, presenting the service's certificate in a cluster and reaching
the console's registered address locally.

**Independent Test**: `HT/ServiceClientSuite` (two loopback servers with minted certificates) and
`ZeroTrustClusterSuite` step 4's client leg.

- [X] T037 [P] [US3] Write `SDK/ServiceClient.scala` per `contracts/service-client.md`: `ServiceClient` (blocking `get`/`post`/`put`/`delete` over `core` codecs plus raw `request`), `ServiceResponse`, `ServiceCallFailed`, `ServiceUnresolvable`, `ServiceIdentityMismatch`, `ServiceClients`; document that primitives cross as `text/plain`.
- [X] T038 [US3] Write `H/PekkoServiceClient.scala`: in Kubernetes mode resolve `<name>.<ANKKA_NAMESPACE_PREFIX>-<project>.svc.cluster.local` and the port from SRV `_http._tcp.<that>` via `pekko-discovery`'s `pekko-dns` method (cache by TTL), connect with `ConnectionContext.httpsClient((h, p) => tls.clientEngine(h, p))` over `ankka.tls.service-directory`, verify hostname, and after the handshake check the peer's `ankka://` SAN equals `<project>/<name>` else `ServiceIdentityMismatch` before sending the body; locally (`ankka.tls.service-directory` empty) read `ServiceRegistration`'s directory for the entry named `<name>`, ask its observability address for the HTTP bound address (the console's own call), and use plain HTTP; own project comes from `RotatingTls.identity` or `"local"`. Wire `EndpointClients.services` and `AnkkaService.services` (`RT/Ankka.scala`). Add `HT/ServiceClientSuite`: two `HttpServer.at("127.0.0.1", 0)` instances with `TestPki` certificates and an in-suite `ServiceDiscovery` stub — the callee sees `Caller.Service(p, caller)`; a callee presenting the wrong `ankka://` name → `ServiceIdentityMismatch` with no request logged; a 404 → `ServiceCallFailed(404)`; the local path against a `ServiceRegistration` entry written into a temp `-Dankka.running.dir`.
- [X] T039 [P] [US3] Operator and control plane: set `ANKKA_NAMESPACE_PREFIX` on every workload container in `OP/Rendering.scala` (`RenderingSuite` case) and refuse it in a descriptor in `API/…/ServiceSpec.problems` beside the cluster variables (`APIT` case naming the variable in the problem text).
- [X] T040 [US3] Add `GET /call/{service}/whoami` to `SAMPLE/CallerEndpoint.scala`: `services(service).get[CallerView]("/callers/whoami")`, in a `docs:start` region; extend `ZeroTrustClusterSuite` step 4: `orders`/`checkout` → `GET /call/shopping-cart/whoami` returns `{"kind":"service","project":"checkout","name":"orders"}`; a name that resolves to nothing → 503 with `ServiceUnresolvable`'s text; and a local case in the sample's own suite running two `AnkkaTestKit`s with `-Dankka.running.dir` shared.

**Checkpoint**: a two-service project calls itself by name in both places.

---

## Phase 6: User Story 4 — The database connection is mutual TLS, and there is no password (Priority: P2)

**Goal**: `verify-full` against the project database's CA, certificate authentication, no
password anywhere, a network policy on the database port, migration of pre-feature roles.

**Independent Test**: `RTT/DatabaseTlsSuite` against a TLS-configured Postgres container;
`ZeroTrustClusterSuite` step 3.

- [X] T041 [US4] Write `RT/DatabaseTls.scala` (research R4): when `connection-factory.ssl.mode` is set, build the r2dbc `ConnectionFactoryOptions` with `SSL_MODE`, `SSL_ROOT_CERT`, `SSL_CERT`, `SSL_KEY`, no `PASSWORD` when the key is set, and an `SSL_CONTEXT_BUILDER_CUSTOMIZER`; wrap in a delegating `ConnectionFactory` whose `create()` rebuilds the Postgres factory when the cert/key mtime changed (≤ once per `ankka.tls.reload-interval`); register it through pekko-persistence-r2dbc's `connection-factory-options-customizer` hook so `Database.apply` and the plugin share one pool. Add `RTT/DatabaseTlsSuite`: a `postgres:16` testcontainer started with `ssl=on`, a `TestPki` server certificate and a `pg_hba` of `hostssl all all all cert clientcert=verify-full` — a service certificate with CN = the role connects and `pg_stat_ssl` shows `ssl = true`; a wrong root fails naming verification; after rewriting the client files a new connection presents the new serial.
- [X] T042 [P] [US4] Extend the CNPG models: `OP/cnpg/PostgresCluster.scala` gains `certificates: Option[CertificatesSpec]` (`clientCASecret`, `replicationTLSSecret`), `postgresql: Option[PostgresqlSpec]` (`pg_hba: Vector[String]`), `managed: Option[ManagedSpec]` (`roles: Vector[ManagedRole(name, login)]`); `OP/cnpg/PostgresDatabaseRole.scala` gains `disablePassword: Option[Boolean]`, `inRoles: Vector[String]`, and `passwordSecret` becomes `Option[PasswordSecretRef]`; use exactly the shapes T004 recorded. `OPT/cnpg` codec cases.
- [X] T043 [US4] In `OP/CnpgRendering.scala`: render per project `Certificate <cluster>-client-ca` (isCA, via `ankka-selfsigned`), `Issuer ankka-database`, `Certificate <cluster>-replication` (CN `streaming_replica`), the `Cluster` fields (`clientCASecret`, `replicationTLSSecret`, `pg_hba: ["hostssl all +ankka_tls all cert clientcert=verify-full"]`, `managed.roles: [{ankka_tls, login: false}]`), and `NetworkPolicy <cluster>-database` per `data-model.md` §5; per service `Certificate <service>-database` (issuer `ankka-database`, `commonName: <service>`, client auth), `DatabaseRole` with `disablePassword: true`, no `passwordSecret`, `inRoles: [ankka_tls]`; the credential Secret with `ANKKA_DB_SSL_MODE=verify-full`, `ANKKA_DB_SSL_ROOT_CERT=/var/run/secrets/ankka/database-ca/ca.crt`, `ANKKA_DB_SSL_CERT`/`KEY` under `/var/run/secrets/ankka/database/`, **no** `ANKKA_DB_PASSWORD` and no basic-auth keys; in `OP/Rendering.scala` the two volumes (`<service>-database-tls`, and `<cluster>-ca` with `items: [{key: ca.crt, path: ca.crt}]`). Delete `OP/Passwords.scala` and the generation in `EnsureCredentials`; `EnsureCredentials` becomes create-or-patch of the non-secret keys. `OPT/CnpgRenderingSuite` and `OPT/ProvisioningSuite` cases for every object, the `items` restriction, and that `password` appears nowhere in the rendered Secret (assert on the serialized object).
- [X] T044 [P] [US4] In `RES/reference.conf` and `docs/reference/configuration.md` prose (US6 fills the tables): make `password = ${?ANKKA_DB_PASSWORD}` absent-tolerant so a Secret without it yields no password; add a `RTT/ClusterConfigSuite` case that with `ANKKA_DB_SSL_KEY` set and no password the factory options carry no `PASSWORD`.
- [X] T045 [US4] Extend `OPT/OperatorClusterSuite`: apply the rendered project objects to the real CNPG and assert the `Cluster` accepts them, the `Database` reaches `applied`, and a `DatabaseRole` from the previous rendering (with `passwordSecret`, no group) still logs in by password while a new one logs in by certificate — the migration invariant of research R5.
- [X] T046 [US4] Add step 3 to `CPT/ZeroTrustClusterSuite.scala`: every `pg_stat_ssl`/`pg_stat_activity` row for the service's role has `ssl = true` and `client_dn` = `CN=<service>`; the credential Secret has no `password` and no `ANKKA_DB_PASSWORD`; from a pod in project `other`, `nc -z` to the `checkout` cluster's `-rw` Service on 5432 fails; a pod in `checkout` presenting the `orders` certificate is refused when logging in as `shopping-cart` (`psql` exit non-zero with `certificate authentication failed`).
- [X] T047 [US4] Add the pre-feature migration to step 7 of `ZeroTrustClusterSuite`: the service deployed under the 0.7.1 operator has a password; after the operator swap and its transition, its role has `rolpassword IS NULL`, it is a member of `ankka_tls`, the old credential Secret still exists (nothing deleted), and the service is `Ready` with its cart intact.

**Checkpoint**: no password exists for any provisioned database; SC-005 measured.

---

## Phase 7: User Story 5 — The platform practices what it renders (Priority: P3)

**Goal**: the control plane and the identity provider under the same guarantees.

**Independent Test**: `EndToEndClusterSuite` additions; `RemoteOverlaySuite`.

- [X] T048 [P] [US5] Control plane component `K/controlplane/`: `Certificate ankka-controlplane-cluster` and `-service` (from the two `ClusterIssuer`s, URI `ankka://platform/controlplane`), the three volumes and mounts on `deployment.yaml`, the `probe` port and probe, the transport label, `NetworkPolicy ankka-controlplane-cluster` and `-http`, `BackendTLSPolicy` for `httproute.yaml`, `ANKKA_AUTH_JWKS_URL` on `https://ankka-keycloak-service.ankka-auth.svc:8443/…` and `ANKKA_AUTH_JWKS_CA=/var/run/secrets/ankka/service/ca.crt`. `CPT/RemoteOverlaySuite` cases.
- [X] T049 [P] [US5] Keycloak component `K/keycloak/`: `Certificate ankka-keycloak-tls` (issuer `ankka-service`, DNS `ankka-keycloak-service.ankka-auth.svc` and `.svc.cluster.local`), `Keycloak.spec.http.tlsSecret` + `httpEnabled: false`, `BackendTLSPolicy` for the identity provider's `HTTPRoute` (hostname the service DNS name); update `KeycloakStack` in `OPT/KeycloakStack.scala` and the compose file only if it references the in-cluster HTTP port. `RemoteOverlaySuite` case.
- [X] T050 [US5] In `CP/auth/AuthConfig.scala` and `CP/auth/TokenVerifier.scala`: read `ANKKA_AUTH_JWKS_CA`, build the JWKS fetch's `SSLContext` trusting only that CA when set (system trust otherwise, for the compose Keycloak); `CPT/TokenVerifierSuite` case with a `TestPki`-backed HTTPS stub JWKS: verified with the CA, refused without.
- [X] T051 [US5] Extend `CPT/EndToEndClusterSuite.scala`: the control plane's 17355/7626 refused from `default`; `ankka organizations list` succeeds through the gateway; the control plane's log shows the JWKS fetch over `https://…:8443`; the advertised issuer assertion unchanged.

---

## Phase 8: User Story 6 — The guarantee is written down (Priority: P3)

**Goal**: the fourteen pages of research R15, generated tables covered, samples from tested code.

**Independent Test**: `just docs` green; the four limitation statements gone.

- [X] T052 [P] [US6] Rewrite `docs/platform/networking.md`: the gateway's client identity, mutual TLS on every port, the new port table from `contracts/configuration.md`, caller identity, "What is isolated" (and what is not: cross-project HTTP by decision, egress), the measured overhead line (filled by T064), a "Requirements of the cluster" section (policy enforcement, how to check).
- [X] T053 [P] [US6] Update `docs/reference/limitations.md` (remove the four statements; add: no realm for a service's users; no identity-bearing client for Python/TypeScript; a supplied database's credential is its owner's; root CAs do not rotate; no egress policy) and `docs/reference/akka-divergences.md` (drop the caller-principal row; new section "Callers are named by certificate" listing which Akka principals exist now — `INTERNET`, named service, any service in the project, `SELF` — and that `BACKOFFICE` does not).
- [X] T054 [P] [US6] Update `docs/build/http-endpoints.md` (caller ACLs, `caller`, local behaviour and the startup line, `httpAs`, `ServiceClient` with the sample's `docs:start` regions from T035/T040), `docs/reference/scala-sdk.md`, `docs/reference/python-sdk.md`, `docs/reference/typescript-sdk.md` (the `Acl`/`Callers`/`request.caller` forms; the Python `Acl` no longer being an `Enum` is called out as a change).
- [X] T055 [P] [US6] Update `docs/concepts/clustering.md` (TLS remoting, the probe port, rotation), `docs/reference/runtime-endpoints.md` (management behind a client certificate; `/ready` on 7627), `docs/deploy/upgrading.md` (the one transition, what a deployer sees), `docs/operate/troubleshooting.md` (never-ready pre-feature image with the kubelet's message; `route rejected` when the `ankka-service-ca` ConfigMap is missing; a certificate not `Ready`), `docs/concepts/tenancy-and-access.md` (a project is an identity boundary, not a network one for HTTP).
- [X] T056 [P] [US6] Update `docs/platform/install-cloud.md` (network policy is a cluster requirement and how to check; trust-manager; the three issuers and replacing them; the gateway's client certificate), `docs/platform/install-local.md` (kind ≥ 0.24; the enforcement probe and its message), `docs/platform/databases.md` (certificate authentication, the credential Secret's keys, the `ankka_tls` group and migration, supplied databases and `ANKKA_DB_SSL_*`).
- [X] T057 [US6] Run `just docs-sync` and `just docs-reference`; write the prose beside every new row in `docs/reference/configuration.md` (the `ANKKA_DB_SSL_*`, `ANKKA_NAMESPACE_PREFIX`, `ANKKA_LOCAL_CALLER_TOKEN`, `ANKKA_AUTH_JWKS_CA` variables; the `ankka.http.tls`, `ankka.tls`, `ankka.probe` keys) and `docs/reference/sidecar-protocol.md` (the `caller` and `allow_callers` fields); regenerate the skills into `marketplace/plugins/ankka/skills/` and `ankka.g8/src/main/g8/.claude/skills/` (escaped `$`); `just docs` green; `DocumentationDescriptorsSuite` green.

---

## Phase 9: Polish & cross-cutting

- [X] T058 [P] Extend `CPT/VerificationOverheadBenchmark.scala` (SC-006): the same gateway → entity → journal → reply path with `ankka.http.tls` on and off in the k3s harness, 2,000 requests each after warm-up, report medians; fail only above 25% (a finding, not a gate, per the spec) and write the two numbers into `docs/platform/networking.md`'s overhead line.
- [X] T059 [P] Update `CLAUDE.md`: the component hosting table is unchanged, but add to *Architecture* a short "Zero trust is an overlay property" paragraph (one `RotatingTls`, the probe port, the transport label, the transition), and to *Traps* whatever T002–T004 and the k3s runs taught (at minimum: "TLS client auth is per listener, so readiness has its own port"; "the operator may never read a CA Secret, which is why trust-manager exists"; "a `cert` pg_hba rule for `all` breaks every not-yet-redeployed role — match the group").
- [X] T060 [P] Ensure `sbt -Dankka.cluster.tests=off test` still runs in about a minute: every new k3s case is in a gated suite; `TlsBootstrapSpike` and the gateway/CNPG spikes are gated on `-Dankka.spikes=on`; `Test / javaOptions` forwards `ankka.spikes`.
- [X] T061 [P] Template and samples: `ankka.g8`'s `service.json` and the Python/TypeScript templates need no change (no descriptor field) — verify with `TemplateSuite`, `PythonTemplateSuite`, `TypeScriptTemplateSuite`; the templates' compose files are unchanged because local mode is unchanged.
- [X] T062 Run the whole thing once, awake: `caffeinate -i sbt buildAll` (format check, compile, every suite including the k3s ones, every image), then `cd sdks/python && uv sync && uv run pytest -q && uv run mypy && uv run conformance` and `cd sdks/typescript && npm ci && npm run proto && npm run typecheck && npm test && npm run test:slow && npm run conformance`; then `kind create cluster --config kustomization/kind.yaml && ./kustomization/deploy-local.sh` and `quickstart.md` §5 by hand, recording the `kubectl get certificate,networkpolicy,backendtlspolicy` output in the PR description.
- [X] T063 Open the pull request from `014-zero-trust-clusters` with the spec's six stories as its sections and the SC-001–SC-010 evidence (suite names, the benchmark numbers, the by-hand kind output); note the release as 0.8.0 and the compatibility floor in the description.

---

## Dependencies & execution order

- **Phase 1 → Phase 2 → US1 → US2 → US3 → US4 → US5 → US6 → Polish** is the safe order; the
  checkpoints are the points at which the branch is demonstrable.
- **US1 depends on** T006 (`RotatingTls`), T007, T008, T011, T012 and T002's answer.
- **US2 depends on** T006, T009 (protocol), T011, T012, T001 (`TestPki`) and, for the gateway leg,
  T005 and T003's answer. It does not depend on US1 except that `ZeroTrustClusterSuite` is one
  file; its steps are independent cases.
- **US3 depends on** US2 (the caller the client presents is what the callee names) and T039.
- **US4 depends on** T001, T004's answer, T011, T012; on US1 only for the k3s suite file.
- **US5 depends on** US1 and US2's rendering shapes (it copies them into manifests).
- **US6 depends on** every story's `docs:start` regions and generated tables; T057 last.
- **Polish** depends on everything; T062 is the release gate.

## Parallel execution examples

- **Phase 1**: T001, T003, T004, T005 in parallel (four files, four harnesses); T002 after T001.
- **Phase 2**: T007, T008, T009, T010 in parallel; T006 alone (everything in US1/US2 imports it);
  T011 then T012.
- **US1**: T013, T016, T017, T019, T020 in parallel once T006 lands; T014 after T013 (probe) and
  T002; T015 after T007; T018 after T017 and T011; T021–T023 after all rendering tasks.
- **US2**: T024 first; then T025, T027, T029, T030, T034 in parallel; T026 after T025; T028 after
  T009 and T025; T031 after T028–T030; T032–T033 after T011; T035 after T025; T036 last.
- **US4**: T041 and T042 in parallel; T043 after T042; T044 with either; T045–T047 after T043.
- **US6**: T052–T056 in parallel; T057 after all of them.

## Implementation strategy

**MVP is US1 + US2** (Phases 1–4): every port mutual TLS, every request with a caller, the ACL
vocabulary in three SDKs, the transition performed once. That alone removes three of the four
limitation statements and is the whole of what a service author sees. US3 is a convenience over
US2's guarantee; US4 is the same discipline applied to the one credential the platform still
generated; US5 turns the platform's own pods green; US6 is what makes any of it true for the
reader who arrives with one page.

Ship in one pull request as one release (0.8.0), because the compatibility floor and the
transition make a half-applied state — some workloads TLS, the gateway not — worse than either
side: an intermediate release would be a platform that renders a `BackendTLSPolicy` to a plain
backend. Land the phases as commits in this order so the branch is demonstrable at each
checkpoint.

## Format validation

Every task line above is `- [ ] T### [P]? [US#]? description with a repository path`; setup,
foundational and polish tasks carry no story label; story tasks carry exactly one.
