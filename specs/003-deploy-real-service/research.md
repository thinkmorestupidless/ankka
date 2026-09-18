# Research: Deploy a Real nakka Service

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

Everything below marked **Verified** was established by running it, not by reading documentation.
Feature 002 set that standard after CloudNativePG's documentation proved wrong or silent on three
points that decided the design; two findings here (R2, R3) would each have cost a debugging session
and one of them blocks the feature outright.

---

## R1 — Getting a locally built image into the k3s test container

**Decision**: `docker save` the image to a tar, copy it into the k3s container, and import it with
`ctr` into containerd's **`k8s.io` namespace**:

```
docker save <image> -o img.tar
<copy into container>
ctr -a /run/k3s/containerd/containerd.sock -n k8s.io images import img.tar
```

**Verified**, end to end, against `rancher/k3s:v1.31.2-k3s1` — the exact image both cluster suites
already use. A pod created from the imported image reached `Running`.

Three details, each of which silently breaks the import if wrong:

- **`ctr`, not `k3s ctr`.** `k3s ctr images import` answers `No help topic for 'ctr'` on this image.
  `ctr` exists at `/bin/ctr` as a symlink to the `k3s` multicall binary and works when invoked
  directly.
- **`-a /run/k3s/containerd/containerd.sock`.** k3s runs its own containerd on a non-default socket
  path; without the address flag `ctr` talks to a socket that is not there.
- **`-n k8s.io`.** containerd namespaces are hard isolation. An image imported into the default
  namespace imports successfully, lists successfully under `ctr images ls`, and is still invisible to
  kubelet — the failure looks exactly like the image was never imported.

**On `docker save --platform`**: not needed, but worth knowing why. Saving a *multi-architecture
remote* image (`registry.k8s.io/pause:3.9`) produced a tar `ctr` rejected with
`content digest sha256:… not found`; `docker save --platform linux/arm64` fixed it. A **locally
built** image — which is what this feature deals with, since sbt-native-packager builds for the host
architecture only — saves and imports with no platform flag at all. **Verified** both ways. Do not
add a `--platform` flag: hardcoding one would break the other architecture, and deriving the host's
would be solving a problem this feature does not have.

**Alternatives considered**: running a `registry:2` container on the same Docker network and
configuring k3s to pull from it (more moving parts, and k3s registry configuration has to be written
before the container starts); testcontainers' own image-transfer helpers (`K3sContainer` has none).

---

## R2 — `imagePullPolicy` is not optional, and its absence blocks this feature

**Decision**: the operator MUST render `imagePullPolicy: IfNotPresent` on the workload container.

**Rationale**: Kubernetes defaults `imagePullPolicy` to **`Always` when the image tag is `latest`**
(and `IfNotPresent` otherwise). sbt-native-packager publishes `:latest` alongside the version tag
(`dockerUpdateLatest := true`), so the natural descriptor for a locally built sample says
`sample-shopping-cart:latest` — and the pod then tries to pull from Docker Hub and fails, *even
though the image is sitting in the node's containerd*.

**Verified**. The same imported image, same cluster, two pods:

| Pod | `imagePullPolicy` | Result |
|---|---|---|
| explicit `IfNotPresent` | `IfNotPresent` | `1/1 Running` |
| left to the default | `Always` (implied by `:latest`) | `ErrImagePull` — "pull access denied, repository does not exist" |

This is invisible today only because every deployment ever tested used
`registry.k8s.io/pause:3.9` — a genuinely pullable image on a non-`latest` tag, which takes both
halves of the trap off the table at once.

The platform's own manifests already hardcode `imagePullPolicy: IfNotPresent` for the operator and
the control plane, for exactly this reason. Rendering it for workloads makes the platform treat
workload images the way it already treats its own.

**Alternatives considered**: putting the versioned tag (`:0.1.0-SNAPSHOT`) in the sample descriptor so
the default becomes `IfNotPresent` — works, changes no platform code, and was rejected because it
makes correctness depend on a user never writing `:latest`, which is the first thing anyone writes;
adding an `imagePullPolicy` field to the descriptor — a knob for a decision that has exactly one
right answer while the platform has no registry (README's "No image registry" gap). **Revisit when a
registry lands**: `IfNotPresent` will then stop being right for a re-pushed mutable tag, and that is
the moment for the descriptor field, not now.

---

## R3 — The operator has no RBAC to create a Service

**Decision**: add `services` to the operator's `ClusterRole` with
`get, list, watch, create, patch, delete`.

**On `delete` — a correction.** The first draft withheld it "for consistency with feature 002's
rule". That misapplied the rule. Feature 002 withholds `delete` on databases and credentials because
they hold *data*; a Service holds none, and the operator **already has** `delete` on `deployments`
and a `DeleteDeployment` action. Withholding it here bought nothing and forced the plan to leave a
stale Service behind when a service stops serving HTTP — contradicting the spec's own edge case
("the address is created or removed to match"). See R7.

**Rationale**: **Verified by inspection** — `kustomization/components/operator/operator.yaml` grants
`nakkaservices`, `nakkaservices/status`, `namespaces`, `deployments`, `pods`, the three CNPG kinds,
`secrets` and `configmaps`. There is no rule for `services` at all. The operator would render a
Service it is forbidden to create.

**This is the same bug feature 002 shipped and had to debug in-cluster**, where the operator's
missing CNPG and `secrets` rules presented as "nothing happens, no error visible". It recurs because
**the k3s suites hand fabric8 the testcontainer's admin kubeconfig and never exercise the shipped
ClusterRole** — a gap both feature 001 and 002 recorded. So the new suite in this feature *will not
catch this either*.

Mitigation, and the reason this is worth more than a one-line RBAC addition: feature 002's
`OperatorClusterSuite` test 20 already mints a real token for the operator's ServiceAccount to prove
a withheld verb is refused. Extend that same client with the **positive** direction — assert the
operator's own ServiceAccount *can* create a Service — so the grant is proven, not assumed. One
client, already built, now checked in both directions.

---

## R4 — Declaring the port: representation

**Decision**: two descriptor fields — `http: Boolean = true` and `port: Int = 9000` — resolved once
into a single `Option[Int]` that is the only thing anything downstream ever sees.

| Layer | Representation | Meaning |
|---|---|---|
| Descriptor (`ServiceSpec`) | both absent | serves HTTP on the default port |
| Descriptor | `"port": 8080` | serves HTTP on 8080 |
| Descriptor | `"http": false` | serves no HTTP (`port` is ignored) |
| Resolved (`ServiceSpec.resolvedPort`) | `Option.when(http)(port)` | the one value |
| Custom resource (`NakkaServiceSpec`) | `port: 9000` | serves HTTP on 9000 |
| Custom resource | field absent | serves no HTTP |

**Rationale — and a correction.** The first draft of this plan used `port: Option[Int] = Some(9000)`
with an explicit `"port": null` meaning "no HTTP". **Verified: that is unrepresentable.** Under
nakka's shared codec config, jsoniter treats `null` as *absent* and applies the field's default:

| Input | Parsed |
|---|---|
| `{"image":"a"}` | `Some(9000)` |
| `{"image":"a","port":null}` | **`Some(9000)`** — not `None` |
| written from `None` | `{"image":"a","port":null}` → re-read as **`Some(9000)`** |

So a service declared as serving no HTTP would have silently become a port-9000 service with a
readiness probe that can never pass — and the descriptor crosses that codec twice (CLI → control
plane, and into the journal), so the corruption was guaranteed, not a corner case. "Serves no HTTP"
has to be a *positive* statement, because absent and `null` cannot be told apart.

`"port": 0` was considered as that positive statement and rejected: in this codebase port `0` already
means "pick a free port" (`HttpServer.at`), and giving it a second, opposite meaning in the
descriptor is a trap. A boolean says what it means.

The "one value, five consequences" property survives intact: the two fields exist only at the
descriptor surface, `resolvedPort` collapses them immediately, and the custom resource and operator
see exactly the `Option[Int]` they did before. On the CRD side `Option[Int] = None` is safe — that
codec is Jackson, where absent decodes as `None` (the existing `database: Option[DatabaseStatus]`
field already relies on this, with a test).

It also gives a free migration at the *resource* layer: a `NakkaService` written before this feature
has no `port` and keeps behaving as it does today. The *descriptor* layer is the opposite — see R12.

**Alternatives considered**: `Option[Int]` with `null` (unrepresentable, above); `port: 0` (collides
with an existing meaning); a custom codec that distinguishes `null` from absent (fights the shared
codec config every other type in the platform relies on).

---

## R5 — The port is the single source of truth (FR-005, FR-006)

**Decision**: the operator derives all three of the container port, the injected `NAKKA_HTTP_PORT`
and the Service's `targetPort` from the one resolved value. A descriptor that sets `NAKKA_HTTP_PORT`
in its own `env` is **refused at apply time**, always — the `port` field is the only way to set it.

**Rationale**: the runtime reads `nakka.http.port`, overridable by `NAKKA_HTTP_PORT`
(`modules/http/src/main/resources/reference.conf`). Two independent ways to state one fact is
precisely the shape of bug this codebase keeps writing down — "a name computed slightly differently
in two places". Injecting the env var from the same field that renders the port makes the runtime and
Kubernetes structurally unable to disagree.

Validating the conflict in `ServiceSpec.problems` (in `controlplane-api`) puts it where every other
descriptor problem already lives, so it arrives with the rest of them in one response, and the CLI
and control plane apply the identical check — the reason that module holds validation at all.

There is precedent for a name-based env rule: feature 002's escape hatch keys off any `NAKKA_DB_*`
variable being present in `env`. This is the same shape, and deliberately simpler — one exact name.

**Note on the runtime's bind interface**: no change needed. `nakka.http.interface` already defaults
to `0.0.0.0`, so a pod accepts traffic from outside itself. Had it been `127.0.0.1` the Service would
have routed to a socket refusing every connection — checked precisely because that failure is
invisible from the manifests.

---

## R6 — Readiness: what to probe, and what not to add

**Decision**: a `tcpSocket` readiness probe on the resolved port, `initialDelaySeconds: 10`,
`periodSeconds: 5`. **No liveness probe.**

**Rationale**:

- **`tcpSocket`, not `httpGet`.** The operator does not know the workload's routes, and it cannot
  know its ACL — an endpoint may require a bearer token, and a probe is not a place to hold one. The
  control plane's own Deployment settled this identically, with the same reasoning recorded in its
  manifest. The port opening is a late enough signal to be a fair proxy for "ready": `HttpServer`
  binds after the runtime has started.
- **No liveness probe.** A liveness probe that fires during a long GC pause or a slow start restarts a
  healthy pod; on a single-replica service whose entities must rehydrate from the journal, that turns
  a hiccup into an outage. Readiness alone is what `lifecycle: Ready` needs, and nothing here asks
  for a restart policy.
- **Readiness makes `Ready` mean something.** `LifecycleRules` derives `Ready` from the Deployment's
  `readyReplicas`, which counts only pods passing their readiness probe. So the probe is what
  upgrades `Ready` from "the JVM launched" to "the port is open" — with no change to `LifecycleRules`
  itself, which is the neatest part of this: the honest-status machinery from feature 001 gets more
  honest without being touched.

**Interaction to respect**: the schema-init container (feature 002) runs to completion *before* the
workload container starts, and CNPG's transient RBAC window can add 20-40s to a project's first
service. The readiness probe's clock starts only once the container starts, so it does not compound
that delay — but the Deployment's `progressDeadlineSeconds` covers both, which is why FR-013 reuses
that existing deadline rather than introducing a second clock.

---

## R7 — The Service object

**Decision**: a `ClusterIP` Service, named after the service, in the project's namespace, selecting
the workload's immutable identity labels, owned by the `NakkaService`.

| Field | Value | Why |
|---|---|---|
| `metadata.name` | the service name | "The Deployment, the container and the resource all share the service's name" — the Service joins them, so the address is predictable without a lookup (FR-008) |
| `metadata.namespace` | the project's namespace | one namespace per project; uniqueness within it follows |
| `spec.type` | `ClusterIP` | in-cluster reachability is the whole scope; anything else is ingress, explicitly out |
| `spec.selector` | `Labels.identity(projectId, serviceName)` | the *same* labels the Deployment's selector uses — computing a second selector is how a Service ends up with no endpoints |
| `spec.ports[0]` | `port` and `targetPort` both the resolved port, named `http` | one value, twice, from R5 |
| `ownerReferences` | the `NakkaService` | deletion cascades with no sweep (FR-009), exactly as the Deployment does |

**Note on owner references vs. feature 002**: CNPG objects and credential secrets deliberately carry
**no** owner reference, because they must outlive the resource. A Service is the opposite — it holds
no data, and an orphan would be a stale address pointing nowhere. Owning it is correct here for the
same reason not owning a database was correct there.

When a service stops serving HTTP the operator removes its Service, guarded on the owner reference so
it can never delete one it did not create — see [contracts/service-object.md](./contracts/service-object.md).

---

## R8 — Packaging the sample

**Decision**: enable `JavaAppPackaging, DockerPlugin` and the shared `dockerSettings` on the
`shoppingCart` project, with `Compile / mainClass := Some("runShoppingCart")`.

**Verified**: `@main def runShoppingCart()` compiles to a top-level class named exactly
`runShoppingCart` (confirmed in `samples/shopping-cart/target/scala-3.9.0/classes/`). Explicit rather
than discovered, for the reason already recorded on the operator's own setting: discovery is one new
`@main` away from an ambiguous-main build failure unrelated to whatever changed.

**Nothing in the sample's own code needs to change** — checked, and worth stating because it is the
strongest evidence the platform's contract is already right:

- its database arrives as `NAKKA_DB_*` through `envFrom` on the provisioned credential secret, which
  is what the runtime's `reference.conf` already reads;
- it binds `0.0.0.0` on `nakka.http.port`, which this feature now sets from the descriptor;
- a single pod forms its own cluster (`seed-nodes` empty, `join-self-if-no-seed-nodes` on) over
  loopback artery, which needs no pod networking;
- `logback` is a compile dependency of `runtime`, so a deployed sample logs.

`docker:publishLocal` at the root already aggregates to every project with `DockerPlugin` enabled, so
FR-015 costs nothing — but `kustomization/deploy-local.sh` names its two images explicitly in `kind
load docker-image` lines and needs a third (FR-016).

---

## R9 — Build ordering: how the suite gets its image

**Decision**: wire `controlPlane`'s `Test / test` to depend on `shoppingCart / Docker / publishLocal`
*only when cluster tests are enabled*, and have the suite fail with an actionable message if the
image is absent anyway.

```
lazy val sampleImageForClusterTests = taskKey[Unit]("the sample image, unless cluster tests are off")

sampleImageForClusterTests := Def.taskDyn {
  if (sys.props.get("nakka.cluster.tests").contains("off")) Def.task(())
  else Def.task { val _ = (shoppingCart / Docker / publishLocal).value }
}.value

Test / test := (Test / test).dependsOn(sampleImageForClusterTests).value
```

**A correction**: the first draft put the `taskDyn` on `Test / test` itself, referring to
`(Test / test).value` inside the dynamic branch. A reference inside `taskDyn` resolves against the
*final* settings at run time, not the previous definition — that is a cycle, not an override. The
conditional belongs on a helper task; `Test / test` then takes an ordinary static `dependsOn`.

**Rationale**: this is the one genuinely awkward part of the feature, flagged in the spec's checklist,
and every option has a real cost:

- `sbt test` must keep working with no preparatory step, which means *something* has to build the
  image first.
- FR-020 says the existing `-Dnakka.cluster.tests=off` switch must skip this, and a switch that skips
  the suite while still spending a minute building an image it will not use is not skipping it. sbt
  reads that property from its own JVM, so the build can branch on it at task-graph time.
- `testOnly` bypasses `Test / test` entirely, so the suite must also diagnose a missing image itself
  rather than failing as a timeout.

**On the module graph**: this is a *build-level task dependency*, not a classpath one — `controlPlane`
gains no dependency on `shoppingCart` in any compilation scope, and the documented module direction is
unchanged. It is worth one line in `CLAUDE.md` all the same, because "the control plane's tests build
a sample image" is surprising until it is written down.

**Alternatives considered**: putting the suite in `shoppingCart`'s own test scope (it would need
`controlPlane`, `cli` and `operator` as Test dependencies, inverting the sample→platform direction in
the module graph for the sake of a task ordering); making the suite shell out to sbt (a test that
invokes its own build); an unconditional dependency (violates FR-020 in spirit, and taxes every
`controlPlane/test` run with a Docker build).

---

## R10 — What the new suite proves that existing ones cannot

**Decision**: a new `SampleDeploymentClusterSuite` in `controlplane`'s test scope, beside
`EndToEndClusterSuite`, rather than more cases inside it.

**Rationale**: `EndToEndClusterSuite` is already 11 cases and ~2 minutes, and its subject is the
control plane and operator agreeing about the custom resource — a subject it covers with `pause`
precisely because the workload is irrelevant to it. The new suite's subject is the opposite: the
workload is the whole point, and it is the only test in the repository where a nakka runtime, a
platform-provisioned database, the platform-applied schema and HTTP serving are exercised at once.
Mixing them would make one slow suite with two unrelated reasons to fail.

It also carries a cost the other suites do not — importing a ~700MB image into the k3s container —
which is a good reason to keep it separately skippable and separately diagnosable.

---

## R11 — How the suite reaches the service (and why not port-forward)

**Decision**: read the Service's `clusterIP` through fabric8, then issue requests **from the k3s node
itself** — `k3s.execInContainer("wget", …)` against `http://<clusterIP>:<port>`.

**Verified** against `rancher/k3s:v1.31.2-k3s1`:

| Check | Result |
|---|---|
| node → a Service's `clusterIP` | works — response served through kube-proxy |
| node → `<svc>.<ns>.svc.cluster.local` | **fails**, `bad address` — the node does not resolve through CoreDNS |
| `wget` present, with `--post-data` and `--header` | yes (busybox; no TLS, which plain HTTP does not need) |

**Rationale**: the test JVM runs outside the cluster and cannot reach a `ClusterIP`. The obvious
fix — fabric8's port-forward — goes API server → pod and **bypasses the Service entirely**. A Service
with the wrong selector or the wrong `targetPort` would pass every assertion, which is precisely the
regression SC-006 says the suite must catch. Going through the `clusterIP` from the node exercises
the real path: Service → endpoints → pod.

Use the IP, never the DNS name, from the node. (Pods resolve the name fine; the node does not.)

---

## R12 — Default-on probing breaks every existing `pause` deployment

**Decision**: every descriptor in the repository that deploys a non-listening image gains
`"http": false`, in the same change that introduces the probe.

**Rationale**: the default is "serves HTTP on 9000, and is probed". That is right for a nakka
service and wrong for `registry.k8s.io/pause`, which opens no port and would therefore **never
become `Ready`**. Affected:

- **`EndToEndClusterSuite` — all 11 cases.** They apply `pause` through descriptors naming only an
  image. Their subject is the control plane and operator agreeing about the resource; the workload is
  irrelevant to them, so `"http": false` is the honest descriptor, not a workaround.
- **Feature 002's quickstart**, whose walkthrough descriptor is `pause`.
- **The live `kind-nakka` cluster.** Journaled `ServiceApplied` events carry descriptors with no
  `http`/`port`, which now decode as the default; on the next projection pass those services gain a
  probe and a Service, and the `pause`-based ones go un-`Ready`. Re-apply them with `"http": false`.
  No real nakka service has ever been deployed, so nothing of value is affected — but it will look
  like a regression to anyone not expecting it, which is the reason to write it down.

`OperatorClusterSuite` is **unaffected**: it writes `NakkaServiceSpec` directly, where an absent
`port` means no HTTP.

---

## R13 — `publish / skip := true` on the sample (UNVERIFIED — check first)

`shoppingCart` sets `publish / skip := true`. `Docker / publishLocal / skip` delegates to it through
sbt's scope axes, and some sbt-native-packager versions honour `skip` in the Docker tasks — in which
case enabling `DockerPlugin` would appear to work and **silently build nothing**.

Not verified during planning (it needs the plugin enabled to observe). It is therefore the **first
implementation task of US2**: enable the plugin, run `sbt shoppingCart/Docker/publishLocal`, and
confirm with `docker images` that an image exists. If it does not, set
`Docker / publish / skip := false` on the project.
