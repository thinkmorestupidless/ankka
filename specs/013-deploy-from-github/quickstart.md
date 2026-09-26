# Quickstart: proving the feature works

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Contracts**: [contracts/](./contracts/)

Six tiers and one manual step, cheapest first. Each is a gate for the next. Tiers 1–3 need Docker
for Postgres; tier 4 needs `sbt` and Docker for the template; tier 5 needs the k3s image; tier 6 is a
person with a GitHub account.

## Tier 1 — no runtime (seconds)

```bash
sbt 'controlPlane/testOnly *TenancyEntitySuite *DeployTokenIndexSuite *DeployTokensSuite'
sbt 'controlPlaneApi/testOnly *DescriptorSuite *DeploySuite'
sbt 'crd/testOnly *AnkkaServiceCodecSuite'
sbt 'operator/testOnly *RenderingSuite *ServiceRenderingSuite *ProcessHostingRenderingSuite'
sbt 'cli/testOnly *ActionSuite *HomebrewFormulaSuite'
```

Expected: the token entity creates, records a later date once, refuses an earlier one, revokes, and
refuses to recreate a revoked id; the index folds the three events, `touch` marks today, the
once-a-day rule issues one command, and `readiness` is false until `caughtUp`; a minted credential
matches `ankka_[0-9a-f]{16}_[0-9a-f]{64}`, its digest verifies, and one wrong character does not;
`withImage` changes `service.image` and nothing else; `imagePullSecret` round-trips and defaults to
absent; both pod shapes name the pull secret when present and omit it when absent; `action.yml`'s
placeholders and download URL are what the release job rewrites.

## Tier 2 — the control plane over HTTP (about two minutes, Docker for Postgres)

```bash
sbt 'controlPlane/testOnly *ControlPlaneHttpSuite *AuthorizationMatrixSuite *ProjectorSuite'
```

Expected, from the spec's stories:

- S1.1: `POST …/tokens` as an owner answers `201` with the secret; `GET …/tokens` lists label, creator,
  date and no secret; no JSON body but the create's contains `ankka_`.
- S1.2: a rename of a project with the token succeeds and `services history` names `label
  (token:<id>)`.
- S1.3: `POST …/tokens`, `POST …/members`, `PUT …/name`, `DELETE /organizations/{id}` with the token
  are all `403`.
- S1.4: after `DELETE …/tokens/{id}`, the token is `401` within five seconds and stays `401` (V2).
- S1.5: the token against another organization's project is `404`.
- S1.7: with the suite's clock 91 days on, a default token is `401` naming its expiry date; a
  `--never-expires` token still works and lists `never`.
- Registry: `PUT /projects/{id}/registry` as a member calls the fake client once with the password;
  `GET /projects/{id}` shows server and username only; the project's service projects with
  `imagePullSecret: ankka-registry`; `DELETE` clears it; a fake that refuses the write yields `503`
  and no record.
- The matrix: a `token` column beside `owner`, `member`, `outsider`, `admin`.

## Tier 3 — cost (a few minutes, opt in)

```bash
sbt -Dankka.benchmarks=on 'controlPlane/testOnly *VerificationOverheadBenchmark'
```

Expected: a third line, `one request, deploy token`, at or below the OIDC line, and the assertion that
the token path is within the same budget (SC-003).

## Tier 4 — the CLI and the template (about ten minutes; needs `sbt` on PATH and Docker)

```bash
sbt 'controlPlane/testOnly *CliEndToEndSuite *CliReferenceSuite *ControlPlaneRoutesReferenceSuite'
sbt 'cli/testOnly *TemplateSuite'
```

Expected: `organizations tokens create` prints the secret once with the expiry; `services deploy cart
ghcr.io/x/cart:1` applies the descriptor with that image and leaves `service.json` untouched;
`services deploy orders …` against a `cart` descriptor exits `1` before any request; `projects registry
set --password-stdin` reads the secret from stdin; the reference pages are current (or fail naming the
route or command that has no section). The template expands with `.github/workflows/ci.yml` and
`deploy.yml` containing `${{` and no `\${{`, the expansion's own tests pass, and its image builds
tagged with its name — and, with `DOCKER_REPOSITORY=registry.example.test/acme SERVICE_VERSION=1.2.3`,
tagged `registry.example.test/acme/probe:1.2.3`.

## Tier 5 — the cluster (tens of minutes; the k3s image and the sample image)

```bash
sbt 'controlPlane/testOnly *EndToEndClusterSuite'
sbt 'operator/testOnly *OperatorClusterSuite'
```

Expected: a `registry:2` with basic auth runs in the cluster, reached at `127.0.0.1:30500` — the one
address containerd treats as insecure, so no TLS and no per-node containerd configuration is needed.
The sample image is pushed to it under **two** tags and removed from the node, then: with no
credential the pull fails (so the registry is genuinely closed, and every later assertion means
something); `projects registry set` through the CLI, `services restart`, and the service reaches
`Ready` (SC-006); `registry clear` and a deploy of the *second* tag, and `services get` reports the
pull failure in `detail` (V5).

The two tags are the correction to this tier as first written. `registry clear` followed by
`services restart` proves nothing on its own: every workload renders `imagePullPolicy: IfNotPresent`,
so the image is already on the node and the restart succeeds whether or not clearing did anything.
The negative half needs a tag the node has never held.

The control plane's ServiceAccount can create the Secret and is refused `get`, `list` and `delete`
on it (V8, in `ControlPlaneClusterSuite` case 8 — the suite that deploys the control plane for real,
so the shipped RBAC is what answers). A deploy token created through the CLI deploys through the CLI
against the real control plane, and is refused after revocation.

## Tier 6 — GitHub (manual, once per release candidate)

Cannot run in this repository's CI: it needs a repository that does not exist yet and a control plane
reachable from GitHub's runners. Against a platform installed from `kustomization/overlays/arrakis`
(or any installation with a public address), a person:

1. `ankka init probe && cd probe && git init && gh repo create --private --push`.
2. Watch the `ci` workflow pass and the `deploy` workflow finish with the notice "No ANKKA_TOKEN
   secret is configured" and no failure (US3 scenario 1).
3. `ankka organizations tokens create acme --label github`; add `ANKKA_URL`, `ANKKA_TOKEN`,
   `ANKKA_PROJECT` as repository secrets; `ankka projects registry set …` with a `read:packages` PAT
   for the private package (or make the package public and skip it).
4. `git tag v0.1.0 && git push --tags`; watch `deploy` build, push `ghcr.io/<owner>/probe:0.1.0`,
   run the action, and `services get probe` print `Ready` (US3 scenario 2). The workflow stops there
   on purpose: `ankka services expose probe` is the owner's decision, taken once by hand, and every
   later deploy keeps it.
5. `gh workflow run deploy` from `main`; the image is tagged with the SHA and deployed (scenario 3).
6. Rename `service.json`'s `name` to `wrong`; push a tag; the deploy fails at `services deploy` with
   the mismatch and nothing is applied (scenario 4).
7. `ankka organizations tokens revoke acme <id>`; rerun the workflow; it fails in the action's
   `whoami` step with `the token was rejected` (S1.4 from outside).

Record the run's URLs in the PR that closes the feature.

## The reviewer's checklist

- No test or fixture names an image by a literal tag *that something outside the test may have
  built*. The k3s registry case pushes to literal tags (`cart:first`, `cart:second`), which is safe
  for the opposite reason: they exist only inside a registry this test created, and the image they
  are tagged from is resolved by asking containerd what it holds.
- No command prints a secret after creation: grep the CLI suites' captured output for `ankka_` outside
  the `create` case.
- `git grep -n '\${{' ankka.g8/` finds only escaped expressions; `git grep -n '[^\\]\${{' ankka.g8/`
  finds none.
- The release workflow's `action` job has `persist-credentials: false` and `if:
  startsWith(github.ref, 'refs/tags/v')`, like the three jobs beside it.
- `controlplane-rbac.yaml` grants `secrets: create, patch` and nothing else on secrets, and the file's
  header still says what a compromised control plane can and cannot do, accurately.
- `just docs` passes; `docs/deploy/ci.md` leads with the token; the Keycloak client is in
  `identity.md` as the alternative; every new route and command has its section.
