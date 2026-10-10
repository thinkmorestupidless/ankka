# Tasks: Custom Hostnames for an Exposed Service

**Input**: Design documents from `/specs/045-custom-hostnames/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: included, and first. This repository's rule is that each acceptance scenario ends as a
test that fails without the feature, and the plan's "verify first" items turn each fact read from
documentation into a spike before the code that relies on it. The scenarios are in
`features/exposure/custom-hostnames.feature`, `features/exposure/tenancy.feature`,
`features/web-hosting/requests.feature` and `features/web-hosting/mounts.feature`; where a task
says "case", it means a `test(...)` in the named suite, named for the scenario or the rule it holds.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel — different files, no dependency on an incomplete task
- **[Story]**: US1 (a product answers at its own domain), US2 (a hostname is one service's and the
  platform's names are nobody's), US3 (a web-hosted interface knows which address it was reached
  at), US4 (removing a hostname releases it, and so does deleting the service)

Paths are repository-relative. Abbreviations, each followed by
`…/com/thinkmorestupidless/ankka/<module>` where it is a Scala tree: `CRD`/`CRDT` =
`crd/src/{main,test}/scala/…/crd`; `API`/`APIT` = `controlplane-api/src/{main,test}/scala/…/controlplane/api`;
`CP`/`CPT` = `controlplane/src/{main,test}/scala/…/controlplane`; `OP`/`OPT` =
`operator/src/{main,test}/scala/…/operator`; `PXC`/`PXCT` = `proxy-core/src/{main,test}/scala/…/proxy/core`;
`PX`/`PXT` = `proxy/src/{main,test}/scala/…/proxy`; `CLI`/`CLIT` = `cli/src/{main,test}/scala/…/cli`;
`KUST` = `kustomization`; `CON` = `console/package`; `DOCS` = `docs`; `FEAT` =
`features/exposure/custom-hostnames.feature`. "R*n*" is a section of `research.md`; "S*n*" one of
its spikes; a contract is named by its file under `contracts/`.

The branch `045-custom-hostnames` is checked out in the worktree `ankka-045`. Every `sbt` command
below takes `-Dankka.cluster.tests=off` unless the task names a k3s suite or a spike; a k3s run
belongs under `caffeinate -i`. sbt's project ids are `crd`, `controlPlaneApi`, `controlPlane`,
`operator`, `proxyCore`, `proxy`, `cli`.

---

## Phase 1: Setup — what the design rests on, shown true before anything is built on it

**Purpose**: R1, R3, R5, R6, R9 and R15 each rest on a fact read from a pinned manifest or its
documentation. A spike is a throwaway suite that runs only under `-Dankka.spikes=on`, as
`GatewayGrpcSpike` does; each ends by writing what it found under a new heading, *Verified during
implementation*, at the end of `research.md`. If one is false, stop and change the decision it
holds before going on.

- [X] T001 Spike S1, part one, in `OPT/ListenerSetSpike.scala` on k3s with `GatewayStack` (after patching the Gateway it applies with `allowedListeners: {namespaces: {from: Selector, selector: {matchLabels: {app.kubernetes.io/managed-by: ankka}}}}` and the `http` listener's `allowedRoutes` to the same selector): in a managed namespace create a self-signed TLS Secret for `app.example.com` (openssl, RSA), a `ListenerSet` exactly as `data-model.md` gives it with `parentRef` the Gateway, and an `HTTPRoute` with `hostnames: [<derived>, app.example.com]` and `parentRefs` the Gateway (`sectionName: https`) and the set, to a `pause`-backed Service. Assert with `kubectl -o jsonpath` on the node: the set's listener `Accepted`, `Programmed`, `ResolvedRefs` all `True`; `status.parents` on the route has two entries, both `Accepted`; from the host, `curl --resolve app.example.com:<mapped>:127.0.0.1 -k` reaches the backend and the derived hostname still does with `--cacert`. Then add to the set a listener named `*.<base>` and one named `api.<base>`; assert each is `Conflicted: True` and the derived hostname still answers with the wildcard certificate. Record the exact condition reasons and whether `envoy-gateway-config` needed any change.
- [X] T002 Spike S1, part two, in the same file: install `AcmeStack` by hand as `contracts/installation.md` describes (namespace `acme-test`, `ghcr.io/letsencrypt/pebble-challtestsrv:v2.10.1` with `-dnsserver :8053 -management :8055`, `ghcr.io/letsencrypt/pebble:v2.10.1` with a ConfigMap `pebble-config.json` setting `httpPort: 80` and the command `-config /etc/pebble/pebble-config.json -dnsserver <challtestsrv cluster ip>:8053`, `PEBBLE_VA_NOSLEEP=1`); patch cert-manager's controller args with `--enable-gateway-api --acme-http01-solver-nameservers=<challtestsrv ip>:8053` and restart it; apply a `ClusterIssuer pebble` (`server: https://pebble.acme-test.svc:14000/dir`, `skipTLSVerify: true`, `solvers: [{http01: {gatewayHTTPRoute: {parentRefs: [{name: ankka, namespace: ankka-gateway, kind: Gateway}]}}}]`). `POST /add-a` for `app.example.com` with the Envoy proxy Service's cluster IP (the Service in `envoy-gateway-system` labelled `gateway.envoyproxy.io/owning-gateway-name=ankka`). Apply a `Certificate app.example.com` from `pebble` in the managed namespace and assert it is `Ready` within 120s, that `cm-acme-http-solver-*` existed in that namespace meanwhile with a Gateway `parentRef`, and that from the host `curl --resolve … --cacert <Pebble root from GET https://pebble:15000/roots/0 through a pod>` verifies with subject `app.example.com`. Then `POST /clear-a`, set the default IPv4 to `""`, apply a second Certificate for `other.example.com`, and record the `Challenge`'s `status.reason` text after 60s and the Certificate's `Ready`/`Issuing` conditions. Record whether Pebble's image honoured the command args, and the self-check reason text verbatim.
- [X] T003 [P] Spike S2 in `CPT/ProofLookupSpike.scala`, offline: start `ghcr.io/letsencrypt/pebble-challtestsrv:v2.10.1` with testcontainers (ports 8053/udp+tcp and 8055), `POST /set-txt {"host":"_ankka.app.example.com.","value":"ankka-project=checkout"}`, then with `javax.naming.directory.InitialDirContext` (`java.naming.factory.initial=com.sun.jndi.dns.DnsContextFactory`, `java.naming.provider.url=dns://127.0.0.1:<mapped 8053>`, `com.sun.jndi.dns.timeout.initial=2000`, `retries=2`) read `TXT` of `_ankka.app.example.com` and assert the string; read a name with no record and assert `NameNotFoundException`; point the URL at a closed port and record the exception type and how long it took. Confirm `jdk.naming.dns` needs no `--add-modules`.

**Checkpoint**: three findings are written into `research.md`. Nothing else has changed.

---

## Phase 2: Foundational — the rules, the resource, the entity, the wire, the settings, the installation

**Purpose**: what every story reads. Nothing here renders a listener yet.

**⚠️ CRITICAL**: a field on `AnkkaServiceSpec` or inside the status block that `ankkaservice.yaml`
does not declare passes every offline suite and is refused by a real API server on every
projection. T007 extends the one suite that sees it; show it failing before trusting it.

### The rules and the names

- [X] T004 [P] Write `APIT/CustomHostnamesSuite.scala`, red, per `contracts/control-plane.md` refusals 1 to 5: `normalise("App.Example.COM.") == "app.example.com"`; each row of the table gives exactly its message (`https://app.example.com`, `app.example.com/shop`, `app.example.com:8443`, `*.example.com`, a 64-character label naming the label and "63", 254 characters naming "253", `a_b.example.com`, `-a.example.com`, `a..example.com`, `localhost`, `shop.example.test` and `example.test` against base `example.test`); `app.example.com` and `example.com` give none; `MaxPerService == 5`; `isApex("example.com")` true and `isApex("app.example.com")` false.
- [X] T005 Create `API/CustomHostnames.scala` per R2 (pure, standard library only) and add `isApex(hostname)` to `CRD/Hostnames.scala` with a case in `CRDT/HostnamesSuite.scala`. T004 green. In `API/descriptors.scala` make `ProjectSecrets.problems` refuse a name containing a dot with `a project secret's name has no dot: names with a dot are the platform's certificates` and `ServiceSpec.isPlatformSecret` true for a dotted name; add both to `CPT/ReservedSecretNamesSuite.scala` and extend its "every Secret name Rendering asks cert-manager to write is refused" case to a hostname certificate's Secret (the name `app.example.com`), red until T026.

### The resource

- [X] T006 [P] In `CRDT/AnkkaServiceCodecSuite.scala` add, red: a resource with no `customHostnames` decodes to `Nil` and omits it; two hostnames round-trip in order; a `HostnameStatus` with and without `reason` round-trips; a status with no `hostnames` decodes to `Nil`; `sameReport` is false when only a hostname's state differs. In `OPT/CrdSchemaSuite.scala` add a case comparing `status.properties.hostnames.items.properties` with `HostnameStatus`'s fields in both directions, and `spec.properties.customHostnames` declared as an array of strings.
- [X] T007 Add `customHostnames: List[String] = Nil` to `AnkkaServiceSpec` and `HostnameStatus` plus `hostnames: List[HostnameStatus] = Nil` to `AnkkaServiceStatus` in `CRD/AnkkaService.scala` per `data-model.md`, `sameReport` comparing the list; declare both in `KUST/components/crd/ankkaservice.yaml`. T006 green. Show `CrdSchemaSuite` failing twice and restore each: remove `hostnames` from the yaml's status; remove `reason` from inside it.

### The entity and the journal

- [X] T008 [P] In `CPT/EventCompatibilitySuite.scala` add, red: `CustomHostnameAdded` and `CustomHostnameRemoved` decode with `None`/`None` and `byAdministrator = false` from JSON carrying only `hostname`; a `ServiceObserved` without `hostnames` decodes to empty; the pre-feature journal fixtures still decode. In `CPT/ServiceEntitySuite.scala` add, red, the handler cases: `add-hostname` on an unexposed service answers `service 'cart' is not exposed`; on an exposed one persists `CustomHostnameAdded` and `get` lists it; a second add of the same answers the same state and persists nothing; a sixth answers `service 'cart' holds 5 custom hostnames, the most a service can hold` (refusal 8); `remove-hostname` of one not held persists nothing; `take-hostname-away` persists `CustomHostnameRemoved(byAdministrator = true)`; `history` shows `hostname added`, `hostname removed`, `hostname taken away` with actors; `ServiceDeleted` clears the list and `ServiceUnexposed` keeps it; `desiredState` carries the list; `ServiceObserved` with `hostnames` replaces `hostnameReports`.
- [X] T009 Add the two events to `CP/domain/events.scala` per `data-model.md`, the fields and folds (`onHostnameAdded`, `onHostnameRemoved`, `remember` kinds, `onDeleted`, `onObserved`) to `CP/domain/model.scala`, the three handlers with wire names `add-hostname`, `remove-hostname`, `take-hostname-away` to `CP/application/ServiceEntity.scala` (the cap and "not exposed" checked there), and `HostnameReport`. T008 green.
- [X] T010 In the listing cases of `CPT/ControlPlaneHttpSuite.scala` (there is no rows suite today) add, red: the row carries `customHostnames` after an add, drops one after a remove, and carries each state after an observation. Then fold the two events and `ServiceObserved.hostnames` in `CP/application/ServiceRows.scala`.

### The wire and the records

- [X] T011 [P] In `APIT/ControlPlaneFixturesSuite.scala` add a `ServiceStatus` fixture with two `customHostnames` (one serving with a `CNAME` record, one pending with a reason and the apex note) and a `proofRecord`, red; add `CustomHostname`, `DnsRecord`, `customHostnames` and `proofRecord` to `ServiceStatus` in `API/descriptors.scala` per `data-model.md` with codecs in `Wire`, defaults omitted; regenerate `CON/fixtures/control-plane/ServiceStatus.json` and confirm `git diff` shows the new fields only.
- [X] T012 [P] In `CPT/DeployConfigSuite.scala` (create beside `DeployConfig` if absent) add, red: `from(config)` reads `ankka.controlplane.hostnames.{issuer,resolver,gateway-address}` as `Option[String]`, empty strings as `None`; `proofRecord("checkout")` is `DnsRecord("_ankka.<hostname>", "TXT", "ankka-project=checkout")`; `recordFor("checkout", "cart", "app.example.com")` is `CNAME` to `cart-checkout.<base>`; `recordFor(…, "example.com")` is `A <address>` with the address set and `Left(note)` without, the note carrying the port when `httpsPort != 443`. Add the three config keys to `controlplane/src/main/resources/reference.conf` (`${?ANKKA_HOSTNAME_ISSUER}`, `${?ANKKA_DNS_RESOLVER}`, `${?ANKKA_GATEWAY_ADDRESS}`) and the fields and functions to `CP/deploy/DeployConfig.scala`.
- [X] T013 [P] In `CPT/StatusIngestSuite.scala` add, red: a `Reported` status with two `hostnames` entries becomes an observation carrying both as `HostnameReport`s in order and `detail` unchanged by them; then fold them in `CP/deploy/StatusIngest.scala`. In `CPT/ServiceProjectionSuite.scala` add, red: `customHostnames` is projected whole whether or not exposed; then in `CP/deploy/ServiceProjection.scala`.

### The operator's settings and the status rules

- [X] T014 [P] In `OPT/SettingsSuite.scala` add, red: `ANKKA_HOSTNAME_ISSUER` read as `hostnameIssuer: Option[String]`, empty as `None`, with its system property; then `OP/Settings.scala`.
- [X] T015 [P] Write `OPT/HostnameRulesSuite.scala`, red, one case per row of `data-model.md`'s status rules table, in order, with the views as inputs, plus: a hostname with no Certificate and no set yet is `pending: the certificate is being issued`; the challenge reason from T002 (verbatim) is carried as `waiting for the certificate: <reason>`; a `Ready` certificate whose `Issuing` is `False` with a message is `serving` with `renewal refused: <message>`; entries come out in the spec's order. Then create `OP/HostnameRules.scala` with `status` and the view types in `OP/ClusterSnapshot.scala` (`CertificateView`, `ChallengeView`, `ListenerView`, `RouteParentView`).

### The installation

- [X] T016 [P] In `KUST/components/gateway/gateway.yaml` add `spec.allowedListeners` by the managed-by selector and change the `http` listener's `allowedRoutes` to the same selector, rewriting both comments (the HTTP listener carries cert-manager's solver routes from project namespaces; the operator renders nothing against it). Rewrite the comment in `KUST/components/gateway/kustomization.yaml` per R14.
- [X] T017 [P] Add `KUST/components/certmanager/controller-args.yaml`, a strategic merge patch on Deployment `cert-manager`'s `cert-manager-controller` container adding `--enable-gateway-api`, listed under `patches:` in its `kustomization.yaml`, with a comment that the gateway HTTP-01 solver is what obtains a custom hostname's certificate.
- [X] T018 [P] In `KUST/overlays/local/local-ca.yaml` move `selfsigned` and `ankka-root-ca` to namespace `cert-manager`, make `ankka-ca` a `ClusterIssuer`, point `ankka-wildcard` (still in `ankka-gateway`) at it with `kind: ClusterIssuer`, and rewrite the comment for both directions of the trap (R5). In `KUST/deploy-local.sh` read `ankka-root-ca` from `cert-manager` for the export and the wait. In `KUST/overlays/cloud/acme-issuer.yaml` add `ClusterIssuer letsencrypt-hostnames` per `contracts/installation.md` with a comment on HTTP-01, the gateway, port 80 and Let's Encrypt's per-domain limits.
- [X] T019 [P] Add `hostnameIssuer` (`ankka-ca` / `letsencrypt-hostnames`) and `gatewayAddress` (`""` / `# SET`) to both `KUST/overlays/{local,cloud}/platform-configmap.yaml`; add `ANKKA_HOSTNAME_ISSUER` to the operator's and the control plane's Deployments (`KUST/components/operator/operator.yaml`, `KUST/components/controlplane/deployment.yaml`) and `ANKKA_GATEWAY_ADDRESS` to the control plane's, each as an env entry the overlays' `replacements` fill, with the replacements in both `kustomization.yaml`s.
- [X] T020 In `CPT/RemoteOverlaySuite.scala` add, red before T016–T019 and green after, per `contracts/installation.md` "What RemoteOverlaySuite asserts": both overlays render `ANKKA_HOSTNAME_ISSUER` on both Deployments with the overlay's value; the Gateway's `allowedListeners` by selector and the `http` listener's `allowedRoutes` by selector; the local `ankka-ca` is a `ClusterIssuer` and `ankka-root-ca` is in `cert-manager`; the cloud's `letsencrypt-hostnames` has exactly one solver, HTTP-01, naming Gateway `ankka`/`ankka-gateway`; the cert-manager controller's args contain `--enable-gateway-api` (assert the shape: the container named `cert-manager-controller`, one such arg).
- [X] T021 In `OPT/GatewayStack.scala` apply T017's patch after cert-manager installs (then restart its controller and wait), read `ankka-root-ca` from `cert-manager` in `exportCa`, and apply `gateway.yaml` as changed by T016. Run `caffeinate -i sbt 'controlPlane/testOnly *ExposureClusterSuite'` and confirm it is green unchanged.
- [X] T022 Run `sbt 'controlPlane/testOnly *PlatformDeclarationSuite'` and confirm it stays green with no change: `ANKKA_HOSTNAME_ISSUER`, `ANKKA_DNS_RESOLVER` and `ANKKA_GATEWAY_ADDRESS` are each one `val` beside the setting they feed and no list of `ANKKA_` literals was added to any module the suite scans.

**Checkpoint**: `sbt -Dankka.cluster.tests=off crd/test controlPlaneApi/test 'controlPlane/testOnly *ServiceEntitySuite *EventCompatibilitySuite *StatusIngestSuite *ServiceProjectionSuite *RemoteOverlaySuite *ReservedSecretNamesSuite' 'operator/testOnly *HostnameRulesSuite *SettingsSuite *CrdSchemaSuite'` green, except T005's reserved-name case for the certificate's Secret, which waits for T026.

---

## Phase 3: User Story 1 — a product answers at its own domain (Priority: P1) 🎯 MVP

**Goal**: a member adds `app.example.com` to an exposed service, is told the proof record, proves,
is told the record to create, and within minutes the service answers there with a certificate for
that name while still answering at the derived hostname; `services get`, the CLI and the console
show both and where the custom one stands.

**Independent Test**: `caffeinate -i sbt 'controlPlane/testOnly *CustomHostnamesClusterFeatures'`
runs the US1 scenarios of `FEAT` against Pebble; `sbt 'controlPlane/testOnly *ControlPlaneHttpSuite
*CliEndToEndSuite'` runs the add, the refusals for proof, and the records offline with a fake
lookup; `just test-console` shows the hostnames on the page.

### Tests for User Story 1 (first, and red)

- [X] T023 [P] [US1] In `CPT/ControlPlaneHttpSuite.scala`, with `ProofLookup` replaced by a fake map of name → `Either[LookupFailure, Vector[String]]`, add cases: `PUT /services/checkout/cart/hostnames/app.example.com` with the record present is 204 and `GET` shows `customHostnames: [app.example.com, pending]`, `proofRecord` and the `CNAME` record; `GET` of an unexposed service carries `proofRecord` too; with the record absent, 409 with refusal 10's whole message; with the lookup `Unreachable`, 409 with refusal 11 and no event; on an unexposed service refusal 7; with no issuer configured refusal 6; `PUT` of a name in uppercase with a trailing dot is recorded normalised; a second `PUT` of the same is 204 and the history has one entry; a `ServiceObserved` injected through `FakeAnkkaServiceClient.setStatus` with `hostnames: [{app.example.com, serving}]` shows `serving` on `GET` and in the listing; an apex with `gatewayAddress` set gets an `A` record and without it the note.
- [X] T024 [P] [US1] In `CPT/CliEndToEndSuite.scala` add: `services hostnames add cart app.example.com -p checkout` prints the record to create on success and the proof-record refusal with exit 1 when absent; `services get` prints the `proof record` line and the `custom hostnames` block per `contracts/control-plane.md`; `services list` shows `cart-checkout.example.test, app.example.com` in `HOSTNAME`; `--format json` carries the wire. In `CLIT/OutputSuite.scala` add the block's and the column's exact text, including a `!` after a hostname not serving.
- [X] T025 [P] [US1] Write `OPT/CustomHostnamesRenderingSuite.scala`, red: for an exposed service with `customHostnames: [app.example.com, example.com]` and an issuer, `render` yields two `EnsureCertificate`s exactly as `contracts/operator.md` gives them (name, `secretName`, `dnsNames`, `issuerRef` kind `ClusterIssuer`, the label, the owner, `rotationPolicy: Always`), one `EnsureListenerSet` as `data-model.md` gives it with the listeners in the spec's order, `PruneHostnameCertificates(ns, "cart", keep = both)`, and the `HTTPRoute` with `hostnames: [cart-checkout.<base>, app.example.com, example.com]` and two `parentRefs` in that order; for the same service with no issuer, no certificate, no set, `RemoveListenerSet` and `PruneHostnameCertificates(keep = [])`; for one with no custom hostnames, the route exactly as before plus `RemoveListenerSet` and `PruneHostnameCertificates(keep = [])`; for one not exposed, as today plus those two; every `describe` line names the hostname and no object carries a key; the pod template of every hosting is byte-identical with and without hostnames (`WebHostingRenderingSuite`'s public-authority case still exact). Add the golden file `operator/src/test/resources/golden/custom-hostnames.txt` to `OPT/RenderingGoldenSuite.scala`.
- [X] T026 [US1] Add to `OPT/RenderingUnchangedSuite.scala`'s expectation the two new action lines per fixture and no object; run it red, then (after T029) repin with `-Dankka.rendering.pin=true` and assert `git diff --stat operator/src/test/resources/unchanged` shows only added lines, one `# RemoveListenerSet` and one `# PruneHostnameCertificates` per fixture. T005's reserved-name case goes green here, holding `Rendering`'s certificate Secret names to the dotted form.

### Implementation for User Story 1

- [X] T027 [US1] Create `CP/api/ProofLookup.scala` per R3: `trait ProofLookup { def txt(name: String): Either[LookupFailure, Vector[String]] }`, `LookupFailure.NoRecord | Unreachable(detail)`, `ProofLookup.jndi(resolver: Option[String])` over `InitialDirContext` with the provider URL `dns://host:port` when set, the timeouts from T003, mapping `NameNotFoundException` to `NoRecord` and everything else to `Unreachable`; `hasProof(hostname, projectId)`. Wire it in `CP/ControlPlane.scala` from `DeployConfig.resolver`; the suites pass a fake.
- [X] T028 [US1] In `CP/api/ServiceEndpoint.scala` add `PUT /{projectId}/{name}/hostnames/{hostname}` per `contracts/control-plane.md`: normalise; refusals 1–5 (`CustomHostnames.problems`), 6 (`deploy.hostnameIssuer`), then the holder (extend `hostnameHolder` to custom hostnames per R4: scan rows with non-empty `customHostnames`, compare normalised, exclude the service itself), then the proof, then `ServiceEntity.addHostname`; 10 and 11 as 409s; `withHostname` extended to `withHostnames` adding `proofRecord`, each hostname's `record` or `note` and its state from `hostnameReports` (pending with no reason when unobserved; `the service is not exposed` when unexposed). `access(projectId, write = true)` before any lookup. T023 green.
- [X] T029 [US1] In `OP/Rendering.scala` render per `contracts/operator.md`: `hostnameCertificate(spec, hostname, issuer)` beside `ZeroTrust.certificate`'s shape, `listenerSet(spec)` as a typed `io.fabric8.kubernetes.api.model.gatewayapi.v1.ListenerSet`, the route's `hostnames` and second parent in `httpRoute`, `hostnameActions` appended after `backendTlsAction`; `EnsureListenerSet`, `RemoveListenerSet`, `RemoveCertificate`, `PruneHostnameCertificates` in `OP/Action.scala` with `describe` lines; in `OP/Executor.scala` server-side apply for the set, read-first owner-checked removal (404 on the type is absent, as `routeIfAny`), and the prune as a labelled list in the namespace filtered by owner, deleting each not kept. T025 green, then T026.
- [X] T030 [US1] In `OP/Executor.scala` add `observeHostnames(namespace, service, hostnames): Vector[HostnameViews]` reading the Certificate (`Ready`, `Issuing`), the newest `Challenge` by `spec.dnsName`, the set's listener conditions by name and the route's parent entry for the set (`observeRoute` extended to return both parents), each read tolerating a 404 on the type; in `OP/ServiceReconciler.scala` build `status.hostnames` with `HostnameRules.status` per hostname when exposed, `Nil` otherwise. Add cases to the executor's existing suite (the one with the mock API server) feeding a Certificate, a Challenge and a ListenerSet JSON as cert-manager 1.21 and Envoy Gateway 1.9 write them (from T001/T002's recordings) and asserting the views.
- [X] T031 [US1] Add `listensersets` (get list watch create patch delete) and `challenges.acme.cert-manager.io` (get list watch) to `KUST/components/operator/operator.yaml` with comments naming what each is for and what is still withheld.
- [X] T032 [US1] In `CLI/Main.scala` add `services hostnames add|remove <service> <hostname>` (`add` prints the record or the note; `remove` prints `removed`), `CLI/ControlPlaneClient.scala` `addHostname`/`removeHostname` over `PUT`/`DELETE`, `CLI/Output.scala` the `proof record` line, the `custom hostnames` block and the column, `CLI/mcp/AnkkaTools.scala` `add_hostname` and `remove_hostname`. T024 green. Run `just docs-reference` and confirm `DOCS/reference/cli.md` and `DOCS/reference/control-plane-api.md`'s generated blocks changed and nothing else.
- [X] T033 [US1] Write `OPT/AcmeStack.scala` from T002: `install(k3s, k8s)` applies the namespace, challtestsrv, Pebble, the `ClusterIssuer`, patches cert-manager's controller args and waits for both rollouts; `setTxt(name, value)`, `addA(name, ip)`, `clearA(name)`, `setDefaultIpv4(ip)` over the management port through the node; `gatewayAddress(k8s)` the Envoy Service's cluster IP; `root(k8s): Path` Pebble's root PEM fetched through a pod and written to a temp file; `resolver: String` the `ip:8053`.
- [X] T034 [US1] Write `CPT/CustomHostnamesClusterSteps.scala` and `CPT/CustomHostnamesClusterFeatures.scala` (a `GherkinSuite` over `FEAT`, modelled on `WebHostingClusterFeatures`), installing `GatewayStack` then `AcmeStack`, the operator with `hostnameIssuer = Some("pebble")`, the control plane with the same and `resolver = Some(AcmeStack.resolver)`, base domain `example.test`; the Background and the steps for US1's scenarios: `the name X carries the proof record of the project P` → `setTxt("_ankka.X.", "ankka-project=P")`; `carries no proof record` → `clear-txt`; `resolves to the installation` → `addA(X, gatewayAddress)`; `resolves to nothing` → `clearA` and default `""`; `a member adds the custom hostname` → the CLI or the HTTP API; `within N seconds a request … checking its certificate against the authority of the installation, is answered` → `curl --resolve X:<mapped>:127.0.0.1 --cacert <Pebble root>` until 200, asserting no `-k`; `the certificate is for X` → `curl -w '%{certs}'` (or `openssl s_client -servername`) parsed for the subject; `is shown that X is waiting for its certificate` and `the authority's reason, that it could not reach X` → `services get` polled for `pending` with a reason containing `X`; the derived-hostname, gRPC (`GrpcClusterSuite`'s `overrideAuthority` approach), redirect, record and `services get` scenarios; `the authority for custom hostnames refuses to issue a certificate for X` → `addA(X, <an address in the cluster's range where nothing listens on 80>)`, so the challenge fails with a connection-refused reason (cert-manager's self-check text, recorded verbatim in `research.md` under *Verified during implementation*), and `the member is shown the authority's reason` asserts the reason names X. Until Phases 4–6 land, run the suite with munit's glob on the US1 scenarios' words (`-- '*answers at a custom hostname*'` and so on); there is no tag mechanism to add. Run under `caffeinate -i`.
- [ ] T035 [US1] In `OPT/OperatorClusterSuite.scala` add a case: with `hostnameIssuer = Some("ankka-ca")` (the moved `ClusterIssuer`, installed by `PkiStack`/`GatewayStack`) and an `AnkkaService` carrying `customHostnames: [local.example.com]`, the Certificate is `Ready` from the local authority within 60s, the set's listener `Programmed`, and `curl --resolve --cacert <exported root>` answers with subject `local.example.com` — the local path SC-005 names.
- [X] T036 [US1] Console: add `customHostnames`, `proofRecord`, `DnsRecord` to `CON/src/client/schemas.ts`, `addHostname`/`removeHostname` to `CON/src/client/control-plane.ts`, the **Hostnames** section to `CON/src/routes/service.tsx` per `contracts/control-plane.md` (derived hostname, proof record, each custom hostname with state, reason and record, a remove `<form>`, an add `<form>` with one input, refusals verbatim), the column to `CON/src/routes/project.tsx`; in `CON/src/testing/fake-control-plane.ts` the two routes with refusals 1–5, 7, 8, 9 and 10 for a first label `unproved`, and a scenario in `scenarios.ts` with a serving and a pending hostname; in `console/e2e/tests/services.spec.ts` the scenario "the console shows a service's custom hostnames beside the one the platform derived" and an add-then-remove through the page (wait for the `PUT`'s response before navigating). `just test-console` green, parity included.

**Checkpoint**: US1's scenarios green on k3s against Pebble; the local path green in
`OperatorClusterSuite`; the page shows hostnames.

---

## Phase 4: User Story 2 — a hostname is one service's and the platform's names are nobody's (Priority: P1)

**Goal**: a name another service holds is refused naming the holder; a name under the base domain
is refused; a sixth is refused; a platform administrator takes a hostname away and another
service claims it; the operator attaches only what serves a held hostname and cannot change the
gateway.

**Independent Test**: the US2 scenarios of `FEAT` in `CustomHostnamesClusterFeatures`; the holder
and admin cases in `ControlPlaneHttpSuite`; `OperatorClusterSuite` test 20's new halves and the
tenancy scenarios of `features/exposure/tenancy.feature`.

### Tests for User Story 2 (first, and red)

- [X] T037 [P] [US2] In `CPT/ControlPlaneHttpSuite.scala` add: `portal` in project `billing` adding `app.example.com` held by `cart` is 409 with refusal 9 naming `cart` and `checkout` (across projects, through the view — wait with `eventually` on the listing first); the holder check happens before the proof (the fake lookup records no call); `shop.example.test`, `cart-checkout.example.test` and `example.test` each refused with refusal 5; the four malformed names of `FEAT`'s outline with refusals 1 and 2; a sixth hostname refused with refusal 8; `DELETE` by a principal with the `platform-admin` role is 204 and the history says `hostname taken away` by that actor with the administrative attribution, both when the administrator is a member of the organization and when not (two `TestIdentity` tokens), and `portal` can then add it; `DELETE` by a member without the role records `hostname removed`.
- [ ] T038 [P] [US2] In `OPT/OperatorClusterSuite.scala` test 20 add: the operator's minted token is refused a `patch` on Gateway `ankka` and a `create` of a `ClusterIssuer` (403 from the API server), and is granted a `ListenerSet` create in a managed namespace; and a new case for the tenancy scenarios of `features/exposure/tenancy.feature`: a `ListenerSet` the operator renders for a resource whose `customHostnames` is `[api.<base>]` (bypassing the control plane, as a hostile writer of the resource would) yields a listener `Conflicted: True` and the control plane's own hostname still answers with the wildcard certificate; a set the operator did not render for a hostname no resource carries is not something it can be made to render (assert `Rendering.render` of a resource with no hostnames yields `RemoveListenerSet`); and SC-003's rejected half: two `AnkkaService`s in two managed namespaces both carrying `customHostnames: [app.example.com]`, written directly as a hostile writer would, the first a minute before the second — assert the first's status `serving` and `curl --resolve` answered by it, the second's `rejected: HostnameConflict` (the exact reason from T001), and that removing the name from the first turns the second `serving` within 60s.

### Implementation for User Story 2

- [X] T039 [US2] In `CP/api/ServiceEndpoint.scala` add `DELETE /{projectId}/{name}/hostnames/{hostname}` calling `take-hostname-away` with `Authorization.administrator`'s attribution when `Principals.isPlatformAdmin(principal)` and `remove-hostname` with the member's attribution otherwise; the holder refusal (T028) now has its US2 cases. T037 green.
- [X] T040 [US2] In `CPT/CustomHostnamesClusterSteps.scala` add the US2 steps (`a deployed service "portal" of the project "billing" that is exposed`, `a platform administrator takes … away` through the CLI with an administrator's token from `KeycloakStack`, `the history of "cart" says who took it away, and when`, `the refusal says that …` for each outline row) and untag US2's scenarios. T038 green after T031. Run both k3s suites under `caffeinate -i`.

**Checkpoint**: every refusal of the contract is a passing case; the tenancy pair holds on a real
API server with the shipped RBAC.

---

## Phase 5: User Story 3 — a web-hosted interface knows which address it was reached at (Priority: P2)

**Goal**: a web-hosted service's process is told the custom hostname a request from the gateway
arrived at, the derived one at the derived hostname, and never a name a forwarded header states;
a mount is reached under the custom hostname.

**Independent Test**: `sbt 'proxyCore/testOnly *HeadersSuite' 'proxy/testOnly *TlsTransportSuite
*ProxySteps'` offline; the three web-hosting scenarios in `WebHostingClusterFeatures` on k3s.

### Tests for User Story 3 (first, and red)

- [X] T041 [P] [US3] In `PXCT/HeadersSuite.scala` change the case "a request cannot say that it was sent to another address" per `contracts/proxy.md`: `Sender.Internet(Some("app.example.com"))` with every forwarded header forged to `bank.example` is told `app.example.com`, `https`, `443`, `Host app.example.com`, `Forwarded` empty; `Sender.Internet(Some("app.example.com:8443"))` with public port 8443 is told host `app.example.com` and port `8443`; `Sender.Internet(None)` is told the public authority; a mounting proxy's stated authority still wins. In `PXT/TlsTransportSuite.scala`: a request over the gateway's certificate with `Host: app.example.com` yields `Sender.Internet(Some("app.example.com"))`; with no `Host`, `Sender.Internet(None)`; a mount certificate still reads `X-Forwarded-Host`. In `PXT/ProxySteps.scala` bind the outline "a request cannot say that it was sent to another address" for both rows and "the process is told the custom hostname a request was sent to" of `features/web-hosting/requests.feature`.
- [ ] T042 [P] [US3] In `CPT/WebHostingClusterFeatures.scala` add the steps for `"web" holds the custom hostname "app.example.com"` (add through the control plane with `AcmeStack`'s records, wait for `serving`), `sends a request to "app.example.com"` and `to <hostname> that says it was sent to "bank.example"` (`curl --resolve … -H 'X-Forwarded-Host: bank.example' -H 'Forwarded: host=bank.example'`), and the mounts scenario; the suite installs `AcmeStack` and sets the issuer and resolver as T034 does. Red until T043.

### Implementation for User Story 3

- [X] T043 [US3] In `PX/TlsTransport.scala` carry the request's authority for the gateway's certificate (`Host`, else `None`); in `PXC/Headers.scala` keep `stated.orElse(settings.publicAuthority)` and parse a stated authority's port, dropping it when it equals the public port. T041 green, then T042 on k3s under `caffeinate -i`. Confirm `WebHostingRenderingSuite` still pins `ANKKA_PROXY_PUBLIC_AUTHORITY` unchanged and no `ANKKA_PROXY_` variable was added (`CPT/ProxyEnvironmentSuite.scala` green).
- [X] T044 [US3] In `DOCS/reference/web-hosting.md` say the address a request was sent to is the hostname the gateway routed it at, the derived or a custom one, and that forwarded headers from the internet are never believed; `just docs` green.

**Checkpoint**: the process is told the right address at both hostnames, offline and on k3s.

---

## Phase 6: User Story 4 — removing a hostname releases it, and so does deleting the service (Priority: P2)

**Goal**: a removed hostname stops answering within 30 seconds with no instance restarted and is
free for another service; unexposing stops every hostname and keeps them; exposing again brings
them back; deleting the service frees them.

**Independent Test**: the US4 scenarios of `FEAT` in `CustomHostnamesClusterFeatures`; the
unexpose/expose and delete folds in `ServiceEntitySuite` (T008) and `ControlPlaneHttpSuite`.

### Tests for User Story 4 (first, and red)

- [X] T045 [P] [US4] In `CPT/ControlPlaneHttpSuite.scala` add: after `unexpose`, `GET` lists the hostname `pending` with `the service is not exposed` and the projected spec still carries it with `exposed = false`; after `expose` again, pending with no reason; after `DELETE` of the service, `portal` can add the name (the view row gone — `eventually`); after `DELETE` of the project `checkout`, likewise; `remove` then `GET` no longer lists it and the projected spec no longer carries it.
- [X] T046 [P] [US4] In `OPT/CustomHostnamesRenderingSuite.scala` add: an exposed service whose spec dropped one of two hostnames renders the set with one listener and `PruneHostnameCertificates(keep = [the one])`; an unexposed service with hostnames renders `RemoveListenerSet`, `PruneHostnameCertificates(keep = [])` and no certificates; in the executor suite, `PruneHostnameCertificates` deletes a labelled, owned Certificate not kept and leaves an unlabelled one and one owned by another resource.

### Implementation for User Story 4

- [X] T047 [US4] Confirm T029's rendering and executor satisfy T046 (they should by construction; fix what does not) and that `RemoveHttpRoute`'s read-first pattern is what `RemoveListenerSet` uses, so a set an older operator left is removed by owner. T045 green with T028/T039 in place.
- [ ] T048 [US4] In `CPT/CustomHostnamesClusterSteps.scala` add the US4 steps: `removes the custom hostname` through the CLI; `within "30" seconds nothing answers at X` → `curl --resolve` until a TLS handshake failure or a 404 (assert on the gateway's answer, never on the resource's status); `every instance of "cart" is ready` and `no instance of "cart" is restarted` → pod UIDs and `restartCount` before and after; `unexposes`, `exposes`, `deletes "cart"`, `a member can add the custom hostname … to "portal"`; `"cart" still holds X` → `services get`. Add three cases beside the scenarios, for the spec's edge cases and SC-002: a paused service answers nothing at the custom hostname and answers again on resume; a rolling restart under load (as `ExposureClusterSuite` does at the derived hostname) refuses no request at the custom hostname; the same 50 requests at each hostname have medians within a factor of two. Run the whole feature file under `caffeinate -i`, then `sbt 'controlPlane/testOnly *ExposureClusterSuite'` once more.

**Checkpoint**: the whole of `FEAT` green on k3s; the four stories independently proved.

---

## Phase 7: Polish & cross-cutting

- [X] T049 [P] Write `DOCS/deploy/custom-hostnames.md` per R18 (adding; the proof record and why its value is the project id; the record to create and the apex; where a hostname stands and what each reason means; the cap; removing; the administrator's take-away; what the platform does not do; a local platform's walk-through from `quickstart.md` §6), standing alone, with `ankka services hostnames` output included from `CliEndToEndSuite` through `docs:start`/`docs:end` and `just docs-sync`. Link it from `DOCS/deploy/expose.md`'s hostname section.
- [X] T050 [P] In `DOCS/reference/limitations.md` replace "No custom hostnames" with what remains: no DNS the platform manages, no certificate the owner supplies, no custom hostname for the platform's own services, no hostname shared by path, no hostname for a service on a developer's machine. In `DOCS/platform/install-cloud.md` add the issuer (`letsencrypt-hostnames`, HTTP-01, port 80 open on the load balancer), `hostnameIssuer` and `gatewayAddress` in the ConfigMap, the Gateway's `allowedListeners` and cert-manager's flag as things the components now carry. In `DOCS/operate/status-and-history.md` the three history kinds and the hostname states. In `DOCS/deploy/upgrading.md` the operational consequences from `plan.md`.
- [X] T051 [P] In `DOCS/reference/control-plane-api.md` write the hand sections for `PUT …/hostnames/{hostname}` and `DELETE …/hostnames/{hostname}` (the refusals table, who may call, the take-away), the `ServiceStatus` fields `customHostnames`, `proofRecord` and their shapes, and the history kinds; confirm `ControlPlaneRoutesReferenceSuite`'s coverage check passes without `-Dankka.docs.update`.
- [X] T052 Add the page to `mkdocs.yml`'s nav under Deploy and to `tools/docs/skill/ankka-deploy/SKILL.md`'s and `tools/docs/skill/ankka-platform/SKILL.md`'s `pages:`; `just docs-sync` to render the skills into `marketplace/` and the template (every `$` escaped there); `just docs` green.
- [X] T053 In `GLOSSARY.md` drop *Proposed.* from `proof record` now the scenarios use it as settled, and `just features` clean (39 specs, no finding).
- [ ] T054 Delete `OPT/ListenerSetSpike.scala` and `CPT/ProofLookupSpike.scala` once T030, T033 and T027's suites hold what they found; confirm `research.md`'s *Verified during implementation* section records each.
- [ ] T055 Run `quickstart.md` top to bottom: §1–§3 offline, §4 on k3s under `caffeinate -i` (all four suites), §5, then §6 by hand on kind against a domain you control; fix what differs from the expectations written there.
- [X] T056 `sbt scalafmtAll scalafmtSbt` then `sbt -Dankka.cluster.tests=off buildAll`; `gh workflow run cluster --ref 045-custom-hostnames` and confirm every suite green, `CustomHostnamesClusterFeatures` listed by `.github/cluster-suites.py` unasked.

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (Phase 1)**: T001 and T002 share one k3s cluster and run in order; T003 is independent.
  Everything in R1, R3, R5, R6 and R15 waits on their findings.
- **Foundational (Phase 2)**: after Phase 1. T004→T005, T006→T007, T008→T009→T010, T011–T015 are
  independent of each other; T016–T019 are independent and T020 reads all four; T021 after T016
  and T017; T022 last.
- **US1 (Phase 3)**: after Phase 2. T023–T025 first and red; T027→T028 (the holder scan is
  T028's; T037 adds its cross-project cases); T029→T026, T030; T031 before any
  k3s run of the operator; T033→T034; T035 after T029–T031; T036 after T028 and T032.
- **US2 (Phase 4)**: after US1's T028, T029, T031, T034. T037, T038 first; T039; T040.
- **US3 (Phase 5)**: after Phase 2 and T029 (the route carries the hostname) and T033 (the
  stack). T041, T042 first; T043; T044.
- **US4 (Phase 6)**: after US1 and T039. T045, T046 first; T047; T048.
- **Polish (Phase 7)**: after every story. T049–T051 are independent; T052 after T049; T053 after
  the feature file is final; T054 after T027, T030, T033; T055 then T056 last.

### Within a phase

Tests before the code they hold, red first; the pure rule before the thing that applies it; the
rendering before the executor; the executor before the k3s suite; the k3s suite under
`caffeinate -i` and never two at once.

### Parallel opportunities

- Phase 1: T003 beside T001/T002.
- Phase 2: T004, T006, T008, T011, T012, T013, T014, T015, T016, T017, T018, T019 all at once;
  then T005, T007, T009, T010, T020.
- US1: T023, T024, T025 at once; T027, T029, T031, T033 at once; T032 and T036 beside T030.
- US2: T037 and T038 at once.
- US3 can run beside US2 (different files throughout).
- US4: T045 and T046 at once.
- Polish: T049, T050, T051 at once.

### Parallel example: User Story 1 after the foundation is in

```bash
# Tests first, red, in three files:
Task: "ControlPlaneHttpSuite: add, proof refused, unreachable, records, observed state"   # T023
Task: "CliEndToEndSuite and OutputSuite: hostnames add/get/list text"                     # T024
Task: "CustomHostnamesRenderingSuite: certificates, the set, the two-parent route"       # T025

# Then four implementations in four modules:
Task: "ProofLookup over JNDI"                                                             # T027
Task: "Rendering, Action, Executor: the set, the certificates, the prune"                 # T029
Task: "operator.yaml: listensersets, challenges"                                          # T031
Task: "AcmeStack"                                                                         # T033
```

---

## Implementation Strategy

### MVP first (User Story 1 only)

1. Phase 1: the three spikes; stop if R1 or R15 is false.
2. Phase 2: the rules, the resource, the entity, the wire, the settings, the installation.
3. Phase 3: US1 — a service answers at `app.example.com` from Pebble on k3s and from the local
   authority in `OperatorClusterSuite`; the CLI and the console show it.
4. **STOP and VALIDATE**: `quickstart.md` §1–§4 (the first k3s suite only).

### Incremental delivery

1. US1 → a product moves to ankka at its own domain (the feature).
2. US2 → the refusals and the administrator; safe to turn on for every organization.
3. US3 → web-hosted interfaces write the right links.
4. US4 → the lifecycle; a hostname can move between services.
5. Polish → the page, the limitations, the release notes, the cluster workflow.

### Risks the order addresses

- **The `ListenerSet` is not served as read** (R1): found by T001 before a line of rendering.
- **Pebble's image ignores its args or the challenge cannot pass inside k3s** (R15): found by
  T002 before `AcmeStack` is written; the fallback is `PEBBLE_VA_ALWAYS_VALID=1` for every
  scenario but the one that needs a real failure, recorded in `research.md`.
- **The challenge reason text differs from what `HostnameRules` expects**: T015 takes the text
  T002 recorded, not a guess.
- **The repin changes an object**: T026 asserts the diff's shape; a changed object stops the
  work until the rendering is made unchanged.

---

## Notes

- `[P]` = different files, no dependency on an incomplete task.
- Every k3s suite builds the sample image; `-Dankka.cluster.tests=off` everywhere else.
- A `Host` from the gateway is the address; a forwarded header never is (R12). Do not add an
  `ANKKA_PROXY_` variable for hostnames: it rolls the pod.
- The derived hostname is still derived in `Rendering` and never read from the resource; the
  custom ones are read from the resource and the control plane is what refuses a wrong one, with
  the gateway's conflict rule as the backstop.
- Commit after each task or logical group; the hook refuses an unformatted commit.

## Status at the end of implementation (2026-10-09)

Done and green: every offline suite this feature touches, the console's unit and Playwright suites
(parity: all 52 routes), the docs build, the features check, and `CustomHostnamesClusterFeatures` on
k3s against Pebble — 24 scenarios run there, the other two by `services.spec.ts` (US3-9) and
`CliEndToEndSuite`. T030 became `GenericSuite` plus the k3s suite: the reads were wrong in a way only a
real API server showed (Scala collections, `.claude/rules/kubernetes.md`).

Open, each named so it is not mistaken for done:

- **T035** — the local authority's path is proved by `ListenerSetSpike` part 1 (gated, kept for that
  reason) and not by a case in `OperatorClusterSuite`, which installs no gateway.
- **T038** — the RBAC halves are in `OperatorClusterSuite` test 20, green in the cluster run; the case of two
  resources carrying one hostname (SC-003's rejected half) is not written. The operator's own refusal
  of names under the base domain is held offline by `CustomHostnamesRenderingSuite`.
- **T042** — the three web-hosting scenarios run offline in the proxy's feature suites; no k3s case
  shows Envoy handing the proxy the routed `Host` for a custom hostname.
- **T048** — the paused-service, rolling-restart and medians cases are not written.
- **T054** — `ListenerSetSpike` is kept (see T035); the other spike became `ProofLookupSuite`.
- **T055** — the by-hand run on kind against a real domain is not done.

The cluster workflow ran on this branch (run 37940554860): every suite green but
`ObjectStorageClusterFeatures`, whose nine failures are all "no step definition matches" in scenarios
specs 038–044 added on `main` (#96) ahead of their implementations.

