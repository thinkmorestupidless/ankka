# Implementation Plan: Deploy from GitHub Actions

**Branch**: `013-deploy-from-github` | **Date**: 2026-09-25 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/013-deploy-from-github/spec.md` and the clarification
session of 2026-09-25 recorded in it (90-day default expiry; member role only; day-granular last use;
`services deploy <service> <image>` after Akka's verb; the caller provides Java).

## Summary

Give a machine a credential the control plane issues itself — a **deploy token** — and build the
GitHub Action and template workflows on top of it, so a project made by `ankka init` tests itself on
every push and deploys itself on a tag with three secrets and nothing else.

The token is the substance. It is an event sourced entity of its own whose subject, `token:<id>`, is
recorded as an ordinary `member` of the organization that created it, so every membership check,
attribution and "what you cannot see does not exist" 404 the control plane already has applies to it
with no second code path. The secret is shown once and stored only as a SHA-256 digest. Verifying one
performs no I/O on the request's thread: each control-plane node keeps its own in-memory index of live
tokens, fed by a local projection over the token entity's journal — not an ankka `Consumer`, which
runs on one node — and the node is not ready until that index has caught up. Revocation reaches every
node through the same stream, within the read journal's propagation window.

Around it: `ankka services deploy <service> <image> [-f service.json]`, Akka's verb with Akka's
positional image and everything else from the descriptor; a composite action in `action/`, pushed to
`thinkmorestupidless/ankka-action` by the release the way the template, marketplace and formula are;
two workflows in the template, written with Giter8's `$` escaping and checked by `TemplateSuite`;
and, last, per-project registry credentials — a `dockerconfigjson` Secret the control plane writes into
the project's namespace and the operator names in `imagePullSecrets`, with no secret value ever in the
control plane's journal.

## Technical Context

**Language/Version**: Scala 3.9.0, JDK 21, sbt 1.12.15 throughout (unchanged). The action is a
composite GitHub Action: `action.yml` plus POSIX shell, no language runtime of its own (R9). The
template's workflows are YAML under Giter8 (R10).

**Primary Dependencies**: no new library in any module. Hashing is `java.security.MessageDigest`
(SHA-256) and `java.security.SecureRandom` from the JDK (R4). The per-node index is a Pekko Projection
over `EventSourcedProvider.eventsBySlices` with an in-memory offset, which `runtime` already depends on
(R3). The pull Secret is written with the fabric8 client the control plane already holds (R7). The
action downloads with `curl`, checks with `sha256sum`, unzips with `unzip` — all on a GitHub-hosted
Linux runner (R9).

**Storage**: one new event sourced entity (`deploy-token`) and one new view table (`deploy-token-rows`)
in the control plane's own database — additive, so an existing installation upgrades without a DDL
change (the view table is created by `ProjectionRuntime` as every view table is). The Project entity
gains one event. No password, token secret or registry credential is ever written to the journal: the
token is stored as a digest, and the registry password goes straight to a Kubernetes Secret (R4, R7).

**Testing**: munit. Entity suites for the token and the project's registry event (`TenancyEntitySuite`
style, no runtime). A unit suite for the index's fold and its readiness. `ControlPlaneHttpSuite`
extended with the whole token lifecycle over real HTTP with `TestIdentity` as the owner. The routes and
CLI reference suites, which fail until the docs say what the new routes and commands do.
`VerificationOverheadBenchmark` gains a deploy-token run (SC-003). `AnkkaServiceCodecSuite` and
`RenderingSuite` for `imagePullSecret`. `TemplateSuite` expands the template and asserts every
`${{ … }}` in the workflows survived. A new `ActionSuite` in `cli` pins the action's placeholders the
way `HomebrewFormulaSuite` pins the formula's. One k3s case in `EndToEndClusterSuite` deploys from a
private in-cluster registry (SC-006). The one thing this repository cannot automate — a real GitHub
repository running the workflows — is a manual tier in `quickstart.md`.

**Target Platform**: unchanged for the platform. The action targets GitHub-hosted `ubuntu-latest`
(Java from `actions/setup-java`, per the clarification), and is expected but not proven to work on
self-hosted Linux and macOS runners.

**Project Type**: additive changes to `controlplane`, `controlplane-api`, `cli`, `crd`, `operator`, the
kustomize components (RBAC), `ankka.g8`, the release workflow and documentation; one new top-level
directory `action/` outside sbt.

**Performance Goals**: SC-003 — admitting a request with a deploy token costs no I/O and is measured
by `VerificationOverheadBenchmark` against the same denominator as OIDC verification (one control plane
request: HTTP, ACL, entity command, durable write, reply): a hash and a map lookup, expected below the
OIDC path's own cost. Last use costs at most one entity command per token per day, issued from a
background task, never from the request (FR-008).

**Constraints**: the ACL is synchronous on the server's dispatcher and may not block (FR-004, R3);
the control plane still holds no credential able to create a workload — it gains `create` and `patch`
on Secrets in project namespaces and nothing else, and that adds no power it did not already have over
which image runs (R7); a token is never an owner (FR-001, FR-010); no secret is printed after creation
in either output format (FR-011, FR-014); the descriptor's shape does not change at all — registry
credentials are a property of the project, and `services deploy` replaces one field of a descriptor
in the CLI (R7, R8); a resource written before this feature decodes with `imagePullSecret` absent and
renders exactly as before (FR-027).

**Scale/Scope**: roughly 45 files touched or added. The largest pieces are the token entity, index and
ACL composition (US1), the HTTP suite cases, the action and its release job, the two workflows with
their escaping, and the k3s registry case. The riskiest is the per-node index's readiness and
revocation propagation (R3); the most expensive to prove is the private-registry k3s case (R7).

## Constitution Check

`.specify/memory/constitution.md` is the unfilled template; `CLAUDE.md` governs.

| Principle | How this plan keeps it |
|---|---|
| Effects are inert data; the runtime interprets them | the token and project changes are ordinary event sourced entities returning `effects.persist(...)`; the index folds their events with a pure function that its unit suite applies directly (R3, R5) |
| Cross-entity checks live in the endpoint, never a handler | creating a token is two entity commands issued by the endpoint — create the token, then add its subject to the organization — ordered so a failure between them leaves a token that authorizes nothing (R5) |
| Module dependency direction | `controlplane-api` gains wire types and the `services deploy` field replacement, both Pekko-free; `crd` gains one optional field and depends on nothing; the operator reads it and never learns what a project is; the CLI depends on nothing new (R7, R8) |
| The RuntimeExtension seam | the per-node index is a `RuntimeExtension` with a `readiness` that says no until it has caught up, exactly as `HttpServer` says no until it has bound; the ACL reads the index and nothing else (R3) |
| `Acl.Authenticate` decides; `ankka-http` never knows what a token is | `ControlPlaneAcl.composite` dispatches on the credential's prefix to the token path or the OIDC path; `modules/http` is untouched (R2) |
| A fieldless enum encodes as `{"type":…}` under the shared codec | no new enum crosses the wire; expiry is `Option[Instant]`, last use a `LocalDate` with an explicit string codec in its companion (R5) |
| Anything reading `~/.ankka` or `$HOME` must be overridable | `services deploy` reads the descriptor path it is given, `service.json` by default, relative to the working directory — no home directory involved (R8) |
| A CLI's `main` is a one-line wrapper; commands return exit codes | unchanged; the new commands go through `Main.run` (R8) |
| Never touch `ActorContext` from a callback | the index is a plain stream into a `ConcurrentHashMap`; the last-use task is a scheduled `Runnable` that sends a command through the component client (R3) |
| `pekko.persistence.r2dbc.behind-current-time` is not to be tuned down | the index's propagation delay *is* that setting plus stream latency; the plan measures it and the test allows for it rather than shortening it (R3) |
| Server-side apply rejects `managedFields`; always build a fresh object | the pull Secret is built fresh on every write, like the namespace and the resource (R7) |
| A test must never name an image by literal tag | the k3s registry case pushes the sample image under this build's version, as `EndToEndClusterSuite` already does (R7) |
| Nothing in the build may write to a tracked file during publish | the action's version is `0.0.0` in the tree and written by the release job before `git subtree split`, the same rule as the template, plugin and formula (R9) |
| Giter8 reads `$` as template syntax | every `${{ … }}` in the template's workflows is written `\${{ … }}`; `TemplateSuite` asserts the expansion (R10) |
| Only a tag publishes anything | the action is pushed by a job under `if: startsWith(github.ref, 'refs/tags/v')`, like its siblings (R9) |

**One tension, named**: `CLAUDE.md` and the control plane's RBAC say it "never reads a secret" and
holds "no credential able to create a workload". Writing a pull Secret is a new mutating verb on a new
resource for the control plane. It is granted as `create` and `patch` only — no `get`, `list` or
`delete` — so the control plane can put a credential in a project's namespace and can never read one
back; and it adds no power over *what runs*, because the control plane already decides every
workload's image. The alternative, routing the credentials through the operator, would mean either a
secret value inside the `AnkkaService` resource (readable by everything that can read the resource)
or a second resource kind and a second reconciler for one field. Recorded in the RBAC file's own
comment (R7).

No violations.

## Project Structure

### Documentation (this feature)

```text
specs/013-deploy-from-github/
├── plan.md
├── research.md          # R1–R13, and the "verify at implementation" list
├── data-model.md        # the token entity and index, the project's registry, the resource field, the action's inputs
├── quickstart.md        # tiers 1–6 and the manual GitHub tier
├── contracts/
│   ├── deploy-tokens.md       # routes, wire types, CLI commands, the credential's format and the ACL's decisions
│   ├── services-deploy.md     # `ankka services deploy`, field by field
│   ├── action.md              # action.yml: inputs, what it sets, how it fails, how it is released
│   ├── template-workflows.md  # ci.yml and deploy.yml as generated, the secrets, the build changes
│   └── registry.md            # project registry credentials: routes, CLI, the Secret, the resource field, RBAC
└── tasks.md             # /speckit-tasks output — not created here
```

### Source Code (repository root)

```text
controlplane-api/src/main/scala/com/thinkmorestupidless/ankka/controlplane/api/
├── descriptors.scala            # wire types: CreateDeployToken, DeployTokenCreated, DeployTokenSummary,
│                                #   SetRegistry, RegistrySummary; ProjectDetail/ProjectSummary gain `registry`
└── Deploy.scala                 # NEW: ServiceDescriptor.withImage — the one field replacement `services deploy` makes

controlplane/src/main/scala/com/thinkmorestupidless/ankka/controlplane/
├── domain/
│   ├── model.scala              # DeployToken state; Project gains `registry: Option[RegistryRef]`
│   └── events.scala             # DeployTokenEvent; ProjectEvent.RegistryConfigured / RegistryCleared
├── application/
│   ├── DeployTokenEntity.scala  # NEW: create / recordUse / revoke / get
│   ├── DeployTokenRows.scala    # NEW: the listing view, one row per live token
│   └── ProjectEntity.scala      # configureRegistry / clearRegistry; `get` returns the registry summary
├── auth/
│   ├── DeployTokens.scala       # NEW: secret format, generation, digest, constant-time comparison
│   ├── DeployTokenIndex.scala   # NEW: the per-node RuntimeExtension — local projection, map, readiness,
│   │                            #   last-use task
│   └── TokenVerifier.scala      # unchanged
├── api/
│   ├── ControlPlaneAcl.scala    # `composite(index, oidc)`: prefix dispatch
│   ├── DeployTokenEndpoint.scala# NEW: /organizations/{id}/tokens — create, list, revoke (owner only)
│   ├── ProjectEndpoint.scala    # PUT /projects/{id}/registry, DELETE /projects/{id}/registry
│   └── OrganizationEndpoint.scala # members listing shows token members as it shows anyone
├── deploy/
│   ├── AnkkaServiceClient.scala # + ensurePullSecret(namespace, server, username, password)
│   ├── Fabric8AnkkaServiceClient.scala # the Secret, server-side applied, create+patch only
│   ├── ServiceProjection.scala  # takes the project's registry; sets imagePullSecret
│   └── ServiceProjector.scala   # reads ProjectEntity.get for the registry before projecting
└── ControlPlane.scala           # registers the entity, the view, the index extension; aclFor composes

crd/src/main/scala/com/thinkmorestupidless/ankka/crd/AnkkaService.scala   # imagePullSecret: Option[String] = None
operator/src/main/scala/com/thinkmorestupidless/ankka/operator/Rendering.scala # withImagePullSecrets on both pod shapes
kustomization/components/controlplane/controlplane-rbac.yaml               # secrets: create, patch — with the reasoning

cli/src/main/scala/com/thinkmorestupidless/ankka/cli/
├── Main.scala                   # organizations tokens {create,list,revoke}; services deploy;
│                                #   projects registry {set,clear}
├── ControlPlaneClient.scala     # the corresponding calls
└── Output.scala                 # tokens listing; the one-time secret line

action/                          # NEW, outside sbt; subtree-pushed to thinkmorestupidless/ankka-action
├── action.yml                   # composite: check Java, fetch + verify the CLI, configure, whoami
├── README.md
└── LICENSE

ankka.g8/src/main/g8/
├── .github/workflows/ci.yml     # NEW: sbt test on push and pull request
├── .github/workflows/deploy.yml # NEW: tag or dispatch → build, push, services deploy; skips without secrets
├── build.sbt                    # version from SERVICE_VERSION; dockerRepository from DOCKER_REPOSITORY
└── README.md                    # the three secrets, and where each comes from

.github/workflows/release.yml    # cli job also attaches ankka-cli-<v>.zip.sha256; new `action` job

docs/
├── deploy/ci.md                 # rewritten around the token and the action; Keycloak route moved
├── deploy/images.md             # "Private registries"
├── platform/identity.md         # "Machine accounts" → deploy tokens first, Keycloak client as the alternative
├── concepts/tenancy-and-access.md # deploy tokens as members
├── reference/cli.md, reference/control-plane-api.md   # generated tables + hand-written sections
├── reference/limitations.md     # the registry line
└── reference/akka-divergences.md # `services deploy`; no `--push`

Tests (beside the code they test): TenancyEntitySuite, DeployTokenIndexSuite (new), ControlPlaneHttpSuite,
AuthorizationMatrixSuite, ControlPlaneRoutesReferenceSuite, CliReferenceSuite, VerificationOverheadBenchmark,
AnkkaServiceCodecSuite, RenderingSuite, ProjectorSuite, EndToEndClusterSuite (k3s), TemplateSuite,
ActionSuite (new), DocumentationDescriptorsSuite.
```

**Structure Decision**: the existing module layout absorbs everything; the only new top-level
directory is `action/`, which must be a subtree root so the release can split it, exactly like
`ankka.g8/`, `marketplace/` and `homebrew/`. The token's verification code lives in
`controlplane/auth` beside `TokenVerifier` because it is the second answer to the same question, and
the index is a `RuntimeExtension` in the same package because it is the mechanism that keeps that
answer offline.

## Complexity Tracking

No constitution violations to justify. The one tension — the control plane writing a Secret — is
argued above and in R7, and is the smaller of the available shapes.
