# Tasks: Expose Services Outside the Cluster

**Input**: Design documents from `/specs/005-expose-services/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: required — FR-026 and FR-027 make the automated cluster proof and the kind walkthrough
part of the feature, and every prior feature found its documentation wrong on a real cluster.
Tests are written before the code they exercise, per story.

**Organization**: one phase per user story in priority order. Phase 2 is the real-cluster
verification of the five research items marked "not verified" — no Scala until those hold.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: US1 expose/call, US2 hostname, US3 control plane, US4 kind, US5 tenancy

---

## Phase 1: Setup

- [X] T001 Read `kustomization/components/cnpg/kustomization.yaml` for the vendored-by-URL pattern; create `kustomization/components/certmanager/kustomization.yaml` (Component, resource `https://github.com/cert-manager/cert-manager/releases/download/v1.21.2/cert-manager.yaml`) and `kustomization/components/envoy-gateway/kustomization.yaml` (resource `https://github.com/envoyproxy/gateway/releases/download/v1.9.1/install.yaml`), each with the same "pinned, not a branch" comment.
- [X] T002 [P] Create `kustomization/kind.yaml`: `kind: Cluster`, one control-plane node, `extraPortMappings` containerPort 30080→hostPort 80 and 30443→443, protocol TCP. Comment: port publishing is decided at creation and cannot be added later (FR-022).
- [X] T003 [P] Create `kustomization/components/gateway/` with `kustomization.yaml`, `namespace.yaml` (`nakka-gateway`, labelled `app.kubernetes.io/managed-by: nakka`), `gatewayclass.yaml` (`nakka`, controller `gateway.envoyproxy.io/gatewayclass-controller`), `envoyproxy.yaml` (`EnvoyProxy nakka`: `provider.kubernetes.envoyService.type: NodePort` with a patch pinning nodePorts 30080/30443 — exact shape from T006), `gateway.yaml` per [contracts/route-object.md](./contracts/route-object.md) with hostname `*.BASE_DOMAIN` placeholder, `redirect-route.yaml` (`HTTPRoute https-redirect` on listener `http`, `RequestRedirect scheme https statusCode 301`).
- [X] T004 [P] Create `kustomization/overlays/local/platform-configmap.yaml` (`ConfigMap nakka-platform` in `nakka-gateway`, `baseDomain: 127.0.0.1.sslip.io`) and `kustomization/overlays/local/local-ca.yaml` (`Issuer selfsigned`, `Certificate nakka-root-ca` isCA, `ClusterIssuer nakka-ca` from secret `nakka-root-ca`, `Certificate nakka-wildcard` dnsNames `*.BASE_DOMAIN` → secret `nakka-wildcard-tls`), all in `nakka-gateway`.
- [X] T005 Edit `kustomization/overlays/local/kustomization.yaml`: add the three components (certmanager, envoy-gateway, gateway) and the two overlay files; add `replacements` from `nakka-platform.data.baseDomain` into: `Certificate nakka-wildcard` `spec.dnsNames.0` (as `*.` prefix — use a second key `wildcardDomain` in the ConfigMap if kustomize cannot prefix), `Gateway nakka` `spec.listeners.[name=https].hostname`, `Deployment nakka-controlplane` and `Deployment nakka-operator` env `NAKKA_BASE_DOMAIN`, `HTTPRoute nakka-controlplane` `spec.hostnames.0` (as `api.`). Verify with `kubectl kustomize kustomization/overlays/local | grep -c sslip` (expect ≥ 5).
  - `kubectl kustomize kustomization/overlays/local`: six occurrences, prefixes intact (`*.`, `api.`), no `BASE_DOMAIN` left. The control plane route, both `NAKKA_BASE_DOMAIN` envs and the `httproutes` RBAC rule (the manifest halves of T032/T037) were written here because the replacements need their targets to exist.

---

## Phase 2: Foundational — verify on a real cluster before any Scala

**Purpose**: the five items research left unverified. Each records its answer in
[research.md](./research.md) and, where it differs, corrects the plan.

- [X] T006 In a scratch k3s container (`K3sContainer` with `withExposedPorts(30443)`) apply cert-manager, Envoy Gateway and the `gateway` component with base domain `test.local`; confirm `Gateway nakka` reports `Programmed: True`, the Envoy Service is `NodePort` with nodePort 30443, and `curl --cacert <ca from nakka-root-ca> --resolve api.test.local:<mapped>:127.0.0.1 https://api.test.local:<mapped>/` reaches the gateway (404 is success — no routes yet). Record: cert-manager ready time, Envoy ready time, the exact `EnvoyProxy` patch shape that pinned the ports (R5), whether `install.yaml` conflicts with any CRD k3s already has.
    **Correction found at T039**: on k3s v1.31.2 the Envoy Gateway manifest's experimental `xbackends` CRD is
    *rejected* (its CEL rule needs the `format` library, Kubernetes ≥ 1.32); the spike's `grep -c applied`
    hid it because `kubectl apply` applies the other 39 objects and exits 1. Every k3s suite now runs
    `rancher/k3s:v1.35.1-k3s1`. On kind (v1.35) it was never a problem.
  - Done by hand on `rancher/k3s:v1.31.2-k3s1` (docker, ports 30080/30443 published). cert-manager
    webhook ready **18s** after apply; envoy-gateway **11s**; 10 Gateway API CRDs from `install.yaml`,
    no conflict with anything k3s ships. Gateway `Programmed` 4s after the component landed; the
    `EnvoyProxy` patch shape as written pinned `http-80→30080`, `https-443→30443` exactly. **One
    correction**: a `ClusterIssuer` looks up `ca.secretName` in cert-manager's own namespace, not the
    Certificate's — `secrets "nakka-root-ca" not found` — so `local-ca.yaml` uses a namespaced
    `Issuer` in `nakka-gateway`; the wildcard was Ready 2s after that. From the host: `curl
    --cacert <root> --resolve api.test.local:30443:127.0.0.1` → `ssl_verify=0`, HTTP 404 (no route);
    without `--cacert` → curl 60, as it must; `http://…:30080/x/y` → 301 to `https://…:30080/x/y`
    (the Location keeps the *request's* port — on kind that port is 80 so it is omitted; the suite
    must assert scheme and path, not follow). Served cert: `DNS:*.test.local`, issuer `nakka local CA`.
- [X] T007 Same container: hand-write an `HTTPRoute` for `nakka-controlplane` (deploy the control plane manifests from feature 004's suite scaffold) and confirm `services list` works through `https://api.test.local:<mapped>` with the CLI **once T027 exists** — until then with `curl --cacert`. Record the `Accepted` condition's exact shape in `status.parents` (R8) for `ClusterSnapshot`.
  - Route status shape, per parent: `status.parents[i].parentRef{namespace,name,sectionName}`,
    `controllerName: gateway.envoyproxy.io/gatewayclass-controller`, `conditions[]` with `type`
    `Accepted`/`ResolvedRefs`, `status`, `reason`, `message`. The valid route (echo backend standing in
    for the control plane): `Accepted=True/Accepted`, `ResolvedRefs=True/ResolvedRefs`, and
    `https://cart-checkout.test.local` reached the pod through the gateway from the host. The CLI
    half waits for T027.
- [X] T008 Same container: hand-write an `HTTPRoute` in a *non-labelled* namespace and confirm the Gateway does **not** admit it (`Accepted: False`, reason `NotAllowedByListeners`); and one with a `backendRef` into another namespace, confirm `ResolvedRefs: False` `RefNotPermitted`. These are FR-025's guarantees and must be seen, not assumed.
  - Seen: unlabelled namespace → `Accepted=False` reason `NotAllowedByListeners` ("No listeners
    included by this parent ref allowed this attachment") and the hostname 404s; cross-namespace
    backend → `Accepted=True` but `ResolvedRefs=False` reason `RefNotPermitted`, and the hostname
    returns **500** from Envoy. So `ClusterSnapshot.route` must copy *both* conditions: a route can be
    accepted and still not serve.
- [X] T009 From a developer machine (not this sandbox): `dig +short cart-checkout.127.0.0.1.sslip.io` and `api.127.0.0.1.sslip.io` → `127.0.0.1` (R4). Record the result and the resolver used. If it fails, the hosts-file fallback becomes the documented primary and R4 is rewritten.
  - Settled from this machine, not by `dig` (the sandbox blocks UDP DNS entirely) but by the thing
    that matters: `curl https://api.127.0.0.1.sslip.io:8443/` with no `--resolve` reached the kind
    gateway through the system resolver (8.8.8.8) with the certificate verified. sslip.io resolves
    `<anything>.127.0.0.1.sslip.io` → 127.0.0.1 as documented. The hosts-file fallback stays in the
    README for resolvers that block it.
- [X] T010 On `kind-nakka`: recreate from `kustomization/kind.yaml`, apply the same three components and `gateway` with the sslip base domain, and confirm `curl --cacert ~/.nakka/local-ca.crt https://api.127.0.0.1.sslip.io/` reaches the gateway on port 443 with the certificate verified (R5 on kind). Record `kind` version and whether `extraPortMappings` needed `listenAddress`.
  - kind v0.31.0; cluster recreated from `kustomization/kind.yaml`. **Ports changed to 8080/8443**:
    this machine already had something on 80 and 443 (a 308 to https://127.0.0.1/ — not nakka's,
    not touched) and `kind create` failed to bind. Agreed with the user: 8080/8443 by default, since
    a developer's machine routinely has 80/443 taken and the port is part of the URL only, never of
    the certificate or the hostname rule. No `listenAddress` needed. Stack applied by hand with the
    sslip base domain: https listener Programmed, root exported to `~/.nakka/local-ca.crt` (570
    bytes), `curl --cacert` → `ssl_verify=0`. **Second correction**: the http→https redirect kept the
    request's port (`https://…:8080`), so `requestRedirect.port` is now explicit — 443 in the
    component, replaced from `nakka-platform.httpsPort` (8443) in the local overlay; verified
    `301 → https://api.127.0.0.1.sslip.io:8443/carts/c1`.
- [X] T011 Update `research.md` R3/R4/R5/R8 from T006–T010 ("Verified" or corrected), and `plan.md`'s design notes if any shape changed.
  - research.md R3, R4, R5 updated below; data-model settings table gains `httpsPort`; plan design note 5 amended (8080/8443).

**Checkpoint**: the routing stack is proven on both k3s and kind by hand. Everything after this is
making nakka render what was hand-written.

---

## Phase 3: User Story 2 — the hostname (P1, first because everything renders it)

**Goal**: one derivation, shared, with the two refusals; the wire type carries it.

**Independent test**: `HostnamesSuite` and `DescriptorSuite`.

- [X] T012 [P] [US2] Test `crd/src/test/scala/nakka/crd/HostnamesSuite.scala`: `of("cart","checkout","example.test") == "cart-checkout.example.test"`; `controlPlane("example.test") == "api.example.test"`; a 40-char name with a 40-char project → `problems` names 81 and the 63 limit; `label` for the longest pair that fits (31+1+31) has no problem; `api` is not derivable — for every (name, project) the label contains `-` so `label != "api"` (property over a small generator); `of` never contains `..` or a leading/trailing `-`.
- [X] T013 [US2] Create `crd/src/main/scala/nakka/crd/Hostnames.scala` per [data-model.md](./data-model.md): `label`, `of`, `controlPlane`, `problems`, `MaxLabel = 63`. No dependencies. Doc comment states the one-label rule and why (research R2).
- [X] T014 [P] [US2] Test in `controlplane-api/src/test/scala/nakka/controlplane/api/DescriptorSuite.scala`: `ServiceStatus` round-trips `hostname: Some("https://…")` and omits the field when `None` (jsoniter: absent, not null).
- [X] T015 [US2] Edit `controlplane-api/src/main/scala/nakka/controlplane/api/descriptors.scala`: `ServiceStatus.hostname: Option[String] = None` after `database`, with the doc comment from data-model.

---

## Phase 4: User Story 1 — expose and unexpose (P1)

**Goal**: the control plane records exposure, the CLI drives it, the operator renders the route.

**Independent test**: `ServiceEntitySuite`, `CliEndToEndSuite`, `RenderingSuite`,
`OperatorClusterSuite` cases, then `ExposureClusterSuite` from the host.

### Control plane

- [X] T016 [P] [US1] Tests in `controlplane/src/test/scala/nakka/controlplane/ServiceEntitySuite.scala`: `expose` on an existing service persists `ServiceExposed` and `exposed == true`; `expose` again persists nothing and replies the state; `unexpose` persists `ServiceUnexposed`; `unexpose` on an unexposed service persists nothing; `expose`/`unexpose` on a non-existent service is refused; `generation` is unchanged by both; `apply` after `expose` leaves `exposed` true; replay reproduces `exposed`.
- [X] T017 [US1] Edit `controlplane/src/main/scala/nakka/controlplane/domain/events.scala` (`ServiceExposed`, `ServiceUnexposed`) and `domain/model.scala` (`Service.exposed`, `onExposed`, `onUnexposed`); edit `application/ServiceEntity.scala` (commands `expose`, `unexpose`, wire names `"expose"`, `"unexpose"`, idempotent per data-model).
- [X] T018 [P] [US1] Tests in `controlplane/src/test/scala/nakka/controlplane/ServiceProjectionSuite.scala`: `spec.exposed` follows `Service.exposed`; nothing else in the spec changes on expose (byte-compare the rest).
- [X] T019 [US1] Edit `crd/src/main/scala/nakka/crd/NakkaService.scala` (`NakkaServiceSpec.exposed: Boolean = false`; `NakkaServiceStatus.route: Option[String]` — the string form `accepted` / `pending` / `rejected: <reason>`), `kustomization/components/crd/nakkaservice.yaml` (schema: `spec.exposed` boolean, `status.route` string), `crd/src/test/scala/nakka/crd/NakkaServiceCodecSuite.scala` (round trip; absent `exposed` decodes false). Edit `controlplane/src/main/scala/nakka/controlplane/deploy/ServiceProjection.scala` to project it.
- [X] T020 [US1] Edit `controlplane/src/main/scala/nakka/controlplane/deploy/DeployConfig.scala` (`baseDomain: Option[String]`), `controlplane/src/main/resources/application.conf` (`nakka.controlplane.deploy.base-domain = ${?NAKKA_BASE_DOMAIN}`), and wherever `DeployConfig` is built from config.
- [X] T021 [US1] Edit `controlplane/src/main/scala/nakka/controlplane/application/ServiceRows.scala`: columns `exposed: Boolean`, `hostname: Option[String]` (derived with `Hostnames.of` and the configured base domain at projection time; `None` when unexposed or no base domain). The row projector needs the base domain — thread it the way the namespace prefix reaches the projector. Test in `ProjectorSuite` or a new `ServiceRowsSuite`: a row for an exposed service carries the hostname; unexposed carries `None`.
  - The row is a `ServiceStatus`, so it carries `exposed` and no hostname column: the view has no configuration, and the endpoint adds the URL on the way out for `get`, `list` and every command reply (`withHostname`). The collision check lists rows with `exposed = true` and compares derived labels.
- [X] T022 [P] [US1] Tests in `controlplane/src/test/scala/nakka/controlplane/ControlPlaneHttpSuite.scala`: `POST /services/checkout/cart/expose` → 200 with `hostname`; again → 200 same; `unexpose` → 200 without `hostname`; unknown service → 404; the four refusals from [contracts/expose-api.md](./contracts/expose-api.md) → 409 with the exact message (`http: false`; label over 63 — use a 40/40 name pair; hostname held — expose `a-b` in project `c` then `a` in project `b-c`; no base domain configured).
  - Plus `ExposureRulesSuite`: the four refusals as values, including "no base domain", which the HTTP suite (configured with `example.test`) cannot reach. Two suite corrections: the view's `onChange` needed the two new cases (a `MatchError` there stops the projection — every later test cascaded), and jsoniter omits `exposed: false` as a default.
- [X] T023 [US1] Edit `controlplane/src/main/scala/nakka/controlplane/api/ServiceEndpoint.scala`: the two routes; the refusals as cross-entity checks (descriptor's `http` from the entity's desired state; `Hostnames.problems`; the view lookup for a held hostname; `DeployConfig.baseDomain`); `ServiceStatus.hostname = Some(s"https://${Hostnames.of(…)}")` when exposed, on every route that returns a status; `detail` from `status.route` when not `accepted` ("route pending" / "route rejected: R").
  - `ExposureRules.refusal` beside the endpoint; `ControlPlane.endpoints(acl, deploy)` threads `DeployConfig`; `ServiceStatus` gained `exposed` alongside `hostname` because the entity's reply cannot carry a URL it does not know and "exposed, no base domain" is a real state.
- [X] T024 [US1] Edit `controlplane/src/main/scala/nakka/controlplane/deploy/StatusIngest.scala` and `ClusterView` if needed so the resource's `status.route` reaches the entity's observation and out to `ServiceStatus.detail`; test in `StatusIngestSuite`.

### CLI

- [X] T025 [P] [US1] Tests in `cli/src/test/scala/nakka/cli/OutputSuite.scala`: `services get` prints a `hostname` row (`-` when absent); `services list` has a `HOSTNAME` column.
- [X] T026 [US1] Edit `cli/src/main/scala/nakka/cli/Main.scala` (`services expose <name>`, `services unexpose <name>`; expose prints the hostname on stdout) and `Output.scala`.
- [X] T027 [US1] Tests in `controlplane/src/test/scala/nakka/controlplane/CliEndToEndSuite.scala` (through the real `Main.run` against a real control plane, base domain `example.test`): `services expose cart` prints `https://cart-checkout.example.test`; `get` shows it; `list` shows it; `apply -f` again leaves it; `unexpose` clears it; `expose` of an `"http": false` descriptor exits 1 with the message; the collision pair exits 1 naming the holder.

### Operator

- [X] T028 [P] [US1] Tests in `operator/src/test/scala/nakka/operator/RenderingSuite.scala`: `Rendering.httpRoute(resource, baseDomain)` matches [contracts/route-object.md](./contracts/route-object.md) field for field (name, namespace, owner reference to the resource, labels, parentRef to `nakka`/`nakka-gateway`/`https`, hostname from `Hostnames.of`, one backendRef to the Service on the resolved port, **no** namespace on the backendRef); `None` when `exposed = false`; `None` when `http = false` even if exposed; the port follows a re-applied `port`; an unexposed service's Deployment/Service/identity objects are byte-identical to before this feature (SC-009).
- [X] T029 [US1] Edit `operator/src/main/scala/nakka/operator/Rendering.scala` (`httpRoute`, constants `GatewayName = "nakka"`, `GatewayNamespace = "nakka-gateway"`, `GatewaySection = "https"`), `Names.scala` (`httpRoute(name) = name`), `Action.scala` (`EnsureHttpRoute(route)`, `DeleteHttpRoute(namespace, name)`), `Settings.scala` (`baseDomain: Option[String]` from `NAKKA_BASE_DOMAIN`).
- [X] T030 [US1] Edit `operator/src/main/scala/nakka/operator/ClusterSnapshot.scala` (`route: Option[RouteView]` — `accepted: Boolean`, `reason: Option[String]`, from the parent status matching the nakka Gateway, shape per T007) and `ServiceReconciler.scala`: plan `EnsureHttpRoute` when `exposed && port.isDefined && baseDomain.isDefined`; `DeleteHttpRoute` when a route exists and any of those is false; a resource with `exposed = true` and no base domain reports `status.route = rejected: operator has no base domain (NAKKA_BASE_DOMAIN)`. Test in `LifecycleRulesSuite`/a reconciler suite: the plan for each of the four combinations; `lifecycle` never changes because of a route.
- [X] T031 [US1] Edit `operator/src/main/scala/nakka/operator/Executor.scala`: the two cases (server-side apply with the operator's field manager; delete ignoring not-found); read the route for the snapshot via `client.resources(classOf[HTTPRoute])`. Edit `LifecycleRules.scala` or the status writer so `status.route` is copied every reconcile.
  - Both route reads (`RemoveHttpRoute`'s owner check and `observeRoute`) treat a 404 on the *type* as "no route": they run on every pass for every service, so a cluster without the Gateway API CRDs must not fail every reconcile — only an exposed service's `EnsureHttpRoute` may, loudly.
- [X] T032 [US1] Edit `kustomization/components/operator/operator.yaml`: ClusterRole rule for `httproutes.gateway.networking.k8s.io` get/list/watch/create/patch/delete; env `NAKKA_BASE_DOMAIN` (value replaced by the overlay). Nothing else.
- [X] T033 [US1] Tests in `operator/src/test/scala/nakka/operator/OperatorClusterSuite.scala` (k3s, Gateway API CRDs applied in `beforeAll` from the Envoy Gateway manifest **without** the controller — CRDs are enough to store routes): 31. an exposed resource gets an HTTPRoute matching T028's shape with the resource as owner; 32. unexpose deletes it and leaves the Deployment's pod template untouched (compare `template` hashes); 33. `http: false` exposed → no route; 34. deleting the resource cascades the route; 35. under the operator's real token (feature 002's helper), `create gateways` and `get secrets` in a project namespace are 403; 36. under a deployed service's token (feature 004's helper), `list httproutes` in its own namespace is 403.
  - 36/36 first run (7.4m). The suite now installs the Gateway API v1.6.1 standard CRDs (the version Envoy Gateway v1.9.1 bundles) alongside the nakka CRD and CNPG; no controller, so a rendered route reports `pending`, which case 31 asserts.

**Checkpoint**: expose/unexpose works end to end *up to* the Gateway; the route object exists and
is right. Nothing has been proven to answer HTTP yet — that is Phase 5's suite, which needs US3's
control plane route to run the CLI, so it comes after.

---

## Phase 5: User Story 3 — the control plane at `api.<base>` (P2)

**Goal**: the CLI works over HTTPS with `config set ca`; the control plane's own route ships.

- [X] T034 [P] [US3] Tests in `cli/src/test/scala/nakka/cli/SettingsSuite.scala`: `config set ca <path>` saves; `config unset ca` clears; `config get` shows it; a relative path is saved as given (not resolved — the user's shell did that).
- [X] T035 [US3] Edit `cli/src/main/scala/nakka/cli/Settings.scala` (`ca: Option[String]`), `Main.scala` (`config set ca`, `config unset ca`).
- [X] T036 [US3] Edit `cli/src/main/scala/nakka/cli/ControlPlaneClient.scala`: when `ca` is set, build an `SSLContext` whose `TrustManagerFactory` is initialised from a `KeyStore` holding the JDK's default roots *plus* every certificate in the PEM (`CertificateFactory.getInstance("X.509").generateCertificates`); pass it to `HttpClient.newBuilder().sslContext`. On `SSLHandshakeException`, append the one-line hint from [contracts/expose-api.md](./contracts/expose-api.md). Unit test with a self-signed cert generated in-test (`keytool` is not available in-process — generate with `sun.security`-free code, or check in a test PEM pair under `cli/src/test/resources/` with a 100-year expiry and a comment saying it is a test fixture).
  - `Trust.scala`: the JDK's accepted issuers plus the PEM's certificates in one key store; no other mode exists. Fixtures are RSA — LibreSSL's default EC encoding writes explicit parameters the JDK refuses (`Only named ECParameters supported`).
- [X] T037 [P] [US3] Create `kustomization/components/controlplane/httproute.yaml` (`HTTPRoute nakka-controlplane` in `nakka-controlplane`, parentRef `nakka`/`nakka-gateway`/`https`, hostname `api.BASE_DOMAIN` placeholder, backend `nakka-controlplane:9000`); add it to the component's `kustomization.yaml`; label `namespace.yaml` with `app.kubernetes.io/managed-by: nakka` so the route may attach; add env `NAKKA_BASE_DOMAIN` to `deployment.yaml`.
- [X] T038 [US3] Edit `kustomization/deploy-local.sh`: (a) guard — `docker port "${CLUSTER_NAME}-control-plane" 30443/tcp` must print a mapping, else refuse naming `kustomization/kind.yaml` and `kind delete cluster`; (b) install cert-manager and Envoy Gateway with `--server-side` and wait for `cert-manager-webhook` and `envoy-gateway` rollouts before the overlay (their CRDs must exist first — same reason as CNPG); (c) after the overlay, wait for `Gateway nakka` `Programmed` and `Certificate nakka-wildcard` `Ready`; (d) export `kubectl -n nakka-gateway get secret nakka-root-ca -o jsonpath='{.data.ca\.crt}' | base64 -d > ~/.nakka/local-ca.crt`; (e) `dig +short api.$BASE` check with a warning naming the hosts-file fallback if it does not resolve; (f) the epilogue per [quickstart.md](./quickstart.md) Tier 6 — `config set url https://api.…`, `config set ca …`, `services expose`, `curl --cacert`; no `port-forward` anywhere in it.
  - Plus a `curl /health` smoke test through the gateway at the end with the hosts-file fallback printed if it fails; the base domain and HTTPS host port substitutions are anchored to the exact lines kustomize renders from the ConfigMap (verified against the full manifest: eight lines change, cert-manager's own 9443 untouched).
- [X] T039 [US3] Tests in `controlplane/src/test/scala/nakka/controlplane/ControlPlaneClusterSuite.scala` (add cert-manager, Envoy Gateway and the `gateway` component to `beforeAll`, `withExposedPorts(30443)`, base domain `test.local`): 6. `Gateway nakka` Programmed and the control plane's route `Accepted`; 7. the real CLI (`Main.run`) with `config set url https://api.test.local:<mapped>` and `config set ca <exported root>` — `services list` succeeds; then `config unset ca` and the same command fails with the hint from the contract (SC-010: there is no bypass to try). The CLI has no `--resolve`, and SNI needs the real name, so name resolution in the forked test JVM comes from the JDK's own `-Djdk.net.hosts.file=<file>` (a hosts-format file `InetAddress` consults instead of the system resolver) mapping `api.test.local` to `127.0.0.1` — **verify in T007 that `java.net.http.HttpClient` honours it**, and record the answer; the fallback is a `--resolve`-style override in `ControlPlaneClient` that exists only under a test-visible system property, never a CLI flag.
  - 7/7 on k3s v1.35.1: the control plane's route `Accepted` + `ResolvedRefs`; the real CLI as a
    subprocess through `https://api.test.local:<mapped>` with `config set ca` → `services list`
    succeeds; `config unset ca` → exit 1 with the `nakka config set ca` hint. Three test-side
    corrections on the way, none of them the platform: the Gateway API CRDs need Kubernetes ≥ 1.32
    (suites moved to k3s v1.35.1); the two install manifests and the gateway component are applied
    with the node's `kubectl` (fabric8 mangles one CRD; a typed client drops `selfSigned: {}`); the
    readiness waits use `kubectl -o jsonpath` on the node, because the fabric8 generic-status parse
    threw inside a wait loop that swallowed it — two runs lost to a certificate that was Ready in 20s.
- [X] T040 [US3] Edit `build.sbt`: for `controlPlane`, `Test / javaOptions += "-Djdk.net.hosts.file=<target/test-hosts>"` with the file written by a small task before tests (`api.test.local`, `cart-checkout.test.local`, `cart-returns.test.local` → `127.0.0.1`) — if T039's approach holds; otherwise the alternative T039 found, recorded here.
  - **No `build.sbt` change.** `-Djdk.net.hosts.file` does steer `java.net.http` (probed against the kind gateway: `api.test.local` → 127.0.0.1, handshake reached Envoy) — but it replaces resolution for the whole JVM, so every other name in the forked test JVM would stop resolving. The suite therefore runs the real CLI as a *subprocess* (`java -Djdk.net.hosts.file=… -cp <this JVM's classpath> nakka.cli.Main`) — the actual binary path, with `Main.main`'s `sys.exit` where it belongs.

---

## Phase 6: User Story 1 + 2 + 5 proven from outside — `ExposureClusterSuite` (P1)

**Goal**: the full chain, from the host, with the certificate verified.

- [X] T041 [US1] Create `controlplane/src/test/scala/nakka/controlplane/ExposureClusterSuite.scala`: `beforeAll` like `ControlPlaneClusterSuite`'s (feature 004) plus the three components with base domain `test.local`, the in-process operator **with `baseDomain = Some("test.local")`**, the sample image, `withExposedPorts(30443)`; helpers `ca: Path` (root from the secret to a temp file), `curl(host, path, method, body): (Int, String)` = host `curl -sS --cacert $ca --resolve $host:$port:127.0.0.1 -m 10 -w '\n%{http_code}' https://$host:$port$path`, skipping the suite with a message if `curl` is absent. Gated like the other k3s suites (`munitIgnore` + image check).
  - 9/9 on k3s v1.35.1 in 5.9 minutes, `curl --cacert --resolve` from the host against the mapped NodePorts (30443 and 30080). Cases 1–9 cover T042–T047; the tenancy negatives (unlabelled namespace, cross-namespace backend) were seen by hand in T008 and stay covered by the Gateway's own `allowedRoutes` selector and the operator never rendering a `ReferenceGrant`. Case 13's CLI-over-TLS lives in `ControlPlaneClusterSuite` (T039).
- [X] T042 [US1] Cases: 1. scaffold: org, project `checkout`, apply `cart` (real image, `minInstances: 3`), `Ready`; 2. private by default: `curl cart-checkout.test.local /carts/c1` → 404 from the gateway (no route), body not from nakka; 3. `nakka services expose cart` prints `https://cart-checkout.test.local`; within 60s `POST /carts/c1/items` then `GET /carts/c1` by hostname returns the Widget, **certificate verified** (no `-k` anywhere in the suite — assert the curl args do not contain it); 4. plain `http://cart-checkout.test.local:<mapped 30080>` → 301 with `Location: https://…` (expose 30080 too); 5. `services get` shows the hostname and `detail` empty; `kubectl`-equivalent read of the route's `Accepted` condition is True.
- [X] T043 [US2] Cases: 6. project `returns`, apply `cart` there too, expose; both hostnames answer with their own state (write `r1` in returns, read `c1` in checkout — different carts); 7. `restart cart` → same hostname, route UID unchanged; `apply` with `minInstances: 4` → same; `pause` → the hostname returns 503 from the gateway (no endpoints), `resume` → answers again with no re-expose.
- [X] T044 [US1] Case 8. rolling restart under load by hostname: a background loop of `curl` GETs every 200ms through the gateway; `services restart cart`; **0 failed** and no response without the Widget (SC-003 — the `preStop` sleep from 004 is what makes this hold).
- [X] T045 [US1] Case 9. unexpose: within 30s the hostname 404s; `services get` still `Ready` 3/3; pod UIDs unchanged; the in-cluster Service still answers from the node (feature 003's `nodeHttp`).
- [X] T046 [US5] Cases: 10. delete project `returns` → no HTTPRoute anywhere in the cluster names it (list across namespaces); 11. a route hand-created in an unlabelled namespace is `Accepted: False`; 12. a hand-created route in `nakka-checkout` with a backendRef into `nakka-returns` is `ResolvedRefs: False` — the API's own guarantees, seen (T008 made these by hand; here they are permanent).
- [X] T047 [US3] Case 13. the control plane's own route: `Main.run` with `config set url https://api.test.local:<mapped>` and `config set ca`, `services list` succeeds (the T039/T040 resolution approach).

**Checkpoint**: SC-001 to SC-008 hold on k3s from the host.

---

## Phase 7: User Story 4 — kind, end to end (P2)

- [X] T048 [US4] Perform [quickstart.md](./quickstart.md) Tier 6 on `kind-nakka` in full — recreate the cluster from `kustomization/kind.yaml`, `deploy-local.sh`, the CLI with `config set ca`, expose, `curl --cacert`, the 301, unexpose — and record every output in this task. Then the negative: run `deploy-local.sh` against a cluster created with the old one-liner and confirm the refusal message.
  - Done on `kind-nakka` (kind v0.31.0, Kubernetes v1.35.0) from `kustomization/kind.yaml`, ports 8080/8443:
    - `deploy-local.sh` installs the stack, exports `~/.nakka/local-ca.crt`, smoke-tests `/health` through the gateway, prints the epilogue. A re-run rolls the images as before.
    - CLI at `https://api.127.0.0.1.sslip.io:8443` with `config set ca`: `organizations list` over real DNS and verified TLS, no port-forward; `config unset ca` → the PKIX error plus the `nakka config set ca` hint, exit 1.
    - cart applied, `Ready 1/1`; hostname 404 before expose; `services expose cart` → `https://cart-checkout.127.0.0.1.sslip.io:8443` (the port is in the URL since the redeploy — the first run printed it without, which is what `NAKKA_HTTPS_PORT` now fixes); POST 204 and GET by hostname with `--cacert`; `http://…:8080` → `301 https://…:8443/carts/c1`; `get`/`list` show the URL.
    - `services restart` under host `curl` load by hostname: **423 requests, 0 failed**, at one instance.
    - `unexpose` → 404 within 5s, no HTTPRoute in the namespace, service still `Ready`, `list` shows `-`.
    - The negative: a bare `kind create cluster --name guardtest`, then the script → refused, naming `kustomization/kind.yaml` and the two commands. Found the guard exiting *silently* under `set -e` the first time (`docker port` non-zero inside a substitution); fixed with `|| true`.
- [X] T049 [US4] `README.md`: rewrite the "Getting started"/deploy section around the new cluster creation and the epilogue; a new "Exposing a service" section (private by default, `expose`/`unexpose`, the hostname rule and its two refusals, the certificate and `config set ca`, `curl --cacert`); the ACL sentence from the spec ("exposure changes who can reach an endpoint, not who is allowed to"); the hosts-file fallback; production notes (a real base domain, a wildcard certificate via DNS-01 or supplied, `NAKKA_BASE_DOMAIN`); update *Not implemented* (remove "no ingress, no LoadBalancer, no TLS"; add: no custom hostnames, no auth at the route, no HTTP/2 or gRPC through the gateway, one gateway per cluster).
- [X] T050 [P] [US4] `CLAUDE.md`: "Deploying locally" for the new steps; traps from this feature — wildcards are one label deep (certificates and Gateway listeners alike), `install.yaml` CRD ownership, whatever T006–T010 found; the module note that `Hostnames` lives in `crd` and why.

---

## Phase 8: Polish

- [X] T051 `sbt scalafmtAll scalafmtSbt`; `sbt clean compile Test/compile` warning-free.
  - Clean build warning-free after two fixes: a replay fold in `ServiceEntitySuite` missing the two new cases, and a pre-existing scaladoc `[[DatabaseObservation]]` link in `Provisioning.scala` that `docker:publishLocal` (which runs `doc`) had been warning about since feature 002.
- [X] T052 Seams: `crd` still depends on nothing (`Hostnames` is pure); `cli` still depends on `controlPlaneApi` alone (the trust-store code is JDK only); no cert-manager or Gateway API *client* dependency was added anywhere — the operator uses the model classes fabric8 already bundles.
  - `crd` still has no `dependsOn`; `cli` → `controlPlaneApi` only (`Trust` is JDK `javax.net.ssl`); no cert-manager or Gateway API client dependency anywhere — the operator uses `kubernetes-model-gatewayapi`, which fabric8 already bundles.
- [X] T053 `grep -rn -- "-k \|--insecure\|TrustAll\|noVerify\|InsecureTrustManager" cli/ controlplane/ operator/ kustomization/ README.md` finds nothing (SC-010).
  - Nothing in main code, manifests or README. The grep's only hits are `kubectl apply -k` (kustomize) and the pre-existing `withTrustCerts(true)` in `OperatorClusterSuite`, which is the test client's trust of the *k3s API server's* self-signed certificate (feature 002), not the platform's TLS.
- [X] T054 Full `sbt test`; then the reviewer's checklist in [quickstart.md](./quickstart.md) Tier 5.
  - Full `sbt test` 2026-09-18 16:53: 63 suites, 62 green in 44m22s; the one failure was
    `MultiNodeClusterSuite` case 3 at 98.6% (3 of 218 requests, bar 99%) — rerun alone: 8/8 twice
    more on k3s v1.35.1 (61m with one `kill -9` exec returning 137 in case 7; then 18m clean, no retry
    taken). Both are starved-node artefacts of a 45-minute run with six k3s suites, not the
    platform: the same rolling update through the gateway (`ExposureClusterSuite` case 7) lost 0
    of its requests in the same full run. `sigkill` now re-reads the pod and retries once, keeping
    both outputs, and the load loop records every failure, so the next such run says what it was.
  - Reviewer's checklist (quickstart Tier 5): re-apply after expose leaves the hostname (CLI e2e
    suite) and restart leaves the route UID (exposure case 6); the bypass grep finds nothing in
    main code, manifests or README; the operator's ClusterRole names `httproutes` and no other
    Gateway API or cert-manager resource; the base domain is written once and rendered six times;
    an unexposed service's rendered objects are identical with and without a base domain
    (`RenderingSuite`).

---

## Dependencies

- Phase 2 (T006–T011) before any Scala: its findings shape `EnvoyProxy`, `ClusterSnapshot` and the CLI test approach.
- US2 (T012–T015) before US1: everything renders or displays the hostname.
- US1 control plane (T016–T024) → CLI (T025–T027) → operator (T028–T033) can proceed in that order; the operator tasks only need the CRD change (T019).
- US3 (T034–T040) before Phase 6's case 13 and before T038's epilogue can be followed.
- Phase 6 needs US1 + US3; Phase 7 needs Phase 6 green.

## Parallel opportunities

- T002, T003, T004 together; T012/T014 together; T016/T018/T022/T025/T028 (all tests) together before their implementations; T034 with T037; T049 with T050.

## Implementation strategy

MVP is US2 + US1 through T033 plus Phase 6 cases 1–5: a service exposed and answering over
verified HTTPS from the host. US3 makes the CLI itself port-forward-free; US4 makes it a
walkthrough anyone can follow; US5 is the tenancy proof, mostly the API's own behaviour observed.
