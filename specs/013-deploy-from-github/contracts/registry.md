# Contract: project registry credentials

**Feature**: [../spec.md](../spec.md) | **Plan**: [../plan.md](../plan.md) | **Research**: R7

Credentials for a registry the cluster must pull from, held per **project**, written as a Kubernetes
Secret the control plane never reads, and named on every service in the project.

## Routes

Under `ProjectEndpoint`, requiring membership of the project's organization with `write = true`
(`authz.project`), so a deploy token may set them — a pipeline that pushes to a registry is the
natural thing to register it.

| Method | Path | Body | Reply |
|---|---|---|---|
| `PUT` | `/projects/{projectId}/registry` | `SetRegistry` | `204`; `503` if the cluster could not be written, and nothing is recorded |
| `DELETE` | `/projects/{projectId}/registry` | | `204`; `404` if none is set |

`GET /projects/{projectId}` and `GET /projects` gain `registry` on each project (below); there is no
route that returns a password.

### Wire types

```scala
/** `PUT /projects/{id}/registry`. The password is on the wire once, over TLS, and in no reply. */
final case class SetRegistry(server: String, username: String, password: String)

final case class RegistrySummary(
    server: String,
    username: String,
    setAt: Option[Instant] = None,
    setBy: Option[String] = None      // display, never a key
)

// ProjectDetail and ProjectSummary gain:
//   registry: Option[RegistrySummary] = None
```

### Validation (endpoint and CLI, same rules)

| rule | message |
|---|---|
| `server` non-empty and without a scheme | `registry server must be a host, such as ghcr.io, not a URL` |
| `username` non-empty | `registry username must not be empty` |
| `password` non-empty | `registry password must not be empty` |

### The set sequence

1. `authz.project(principal, projectId, write = true)`.
2. Validate.
3. `client.ensurePullSecret(namespace, server, username, password)` — creates the project's namespace
   if it does not exist yet (the same `ensureNamespace` the projector uses), then server-side applies
   the Secret. A failure here is a `503` with the reason, and step 4 does not run: the journal never
   claims a credential the cluster does not hold.
4. `ProjectEntity.configureRegistry(server, username)` → `RegistryConfigured(server, username, actor,
   at)`. No password.
5. The projection trigger is not needed: the next projection of any service in the project — the
   sweep, or the next apply — reads the project and sets `imagePullSecret`. `services restart` rolls
   the pods for a service that is already running and could not pull.

### The clear sequence

1. `authz.project(..., write = true)`.
2. `ProjectEntity.clearRegistry` → `RegistryCleared`. The Secret is **not** deleted: the control
   plane has no `delete` on secrets, by the same rule that keeps the operator from deleting a database
   credential, and a Secret nothing references is inert. The next projection drops `imagePullSecret`.

## The Secret

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: ankka-registry
  namespace: ankka-<projectId>
  labels: { app.kubernetes.io/managed-by: ankka }
type: kubernetes.io/dockerconfigjson
data:
  .dockerconfigjson: <base64 of {"auths":{"<server>":{"username":…,"password":…,"auth":base64(user:pass)}}}>
```

Written by `Fabric8AnkkaServiceClient` with field manager `ankka-controlplane`, `forceConflicts`,
always from a freshly built object. `AnkkaServiceClient` (the trait) gains
`ensurePullSecret(namespace, server, username, password): Unit`; `FakeAnkkaServiceClient` records the
call so the HTTP suite can assert on it without a cluster.

## RBAC

`kustomization/components/controlplane/controlplane-rbac.yaml` gains:

```yaml
  # Registry credentials for a project (feature 013): a dockerconfigjson Secret in the project's
  # namespace that the operator names on every workload. create and patch only — the control plane
  # can put a credential where the kubelet will read it and can never read one back (no get, no
  # list), and it cannot remove one (no delete), the rule that protects database credentials too.
  # This adds no power over what runs: the control plane already decides every workload's image.
  - apiGroups: [""]
    resources: ["secrets"]
    verbs: ["create", "patch"]
```

The header comment's "never reads a secret" stays true and is reworded to say so explicitly.

## The resource and the operator

`AnkkaServiceSpec.imagePullSecret: Option[String] = None`. `ServiceProjection.project(service,
registry: Option[RegistryRef], config)` sets `Some("ankka-registry")` when the project has one.
`Rendering` adds `.withImagePullSecrets(new LocalObjectReference(name))` to both `PodSpecBuilder`
sites when present; nothing when absent (FR-027). The `AnkkaServiceCodecSuite` cases "a spec missing
fields decodes to their defaults" and "a fully populated spec round-trips" cover the field;
`RenderingSuite` gains "a pull secret is named on the pod, and absent by default".

## CLI

```text
ankka projects registry set <project> --server <host> --username <name> --password <secret>
ankka projects registry set <project> --server <host> --username <name> --password-stdin
ankka projects registry clear <project>
```

`--password-stdin` reads one line from `Console.in`, for a pipeline that must not put the secret in
a process argument. `projects get` shows `registry: ghcr.io as octocat, set 2026-09-25 by sam@…` or
`registry: none`.

## Tests that pin this

- `TenancyEntitySuite`: `configureRegistry` / `clearRegistry` folds; earlier journals decode.
- `ControlPlaneHttpSuite`: set as a member → the fake client saw one `ensurePullSecret` with the
  password → `projects get` shows server and username and not the password, in table and JSON → a
  service in the project projects with `imagePullSecret` → clear → the next projection has none; the
  fake refusing the write → `503` and `projects get` still says `none`.
- `ProjectorSuite`: `imagePullSecret` follows the project's registry.
- `EndToEndClusterSuite` (k3s): a `registry:2` with `htpasswd` in the cluster; the sample image tagged
  and pushed to it from the node; `projects registry set` through the CLI; the service `Ready`; then
  `registry clear` + `services restart` → `detail` reports the pull failure (V5) — the honest
  negative.
- `OperatorClusterSuite` or the end-to-end suite: the control plane's own ServiceAccount can `create`
  the Secret and is refused `get` on it (V8), proving the grant is what the file says.
