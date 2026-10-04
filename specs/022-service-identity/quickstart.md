# Quickstart: validating Service-Level Identity

Each step proves one layer; run them in order. Every step is offline except the last optional
one. Docker is needed for the sidecar's conformance suite and the SDK suites.

## 1. The module

```bash
sbt authOidc/test
```

Expected: `OidcConfigSuite`, `OidcVerifierSuite` and `OidcAclSuite` pass. `OidcVerifierSuite`
includes every case the control plane's `TokenVerifierSuite` had, plus two issuers, the `typ`
switch, and an unlisted issuer with zero fetches.

## 2. `ankka-http` unchanged, with the principal's new field

```bash
sbt http/test
```

Expected: every suite passes with no change; `AclSuite` constructs `Principal` as before.

## 3. The sidecar

```bash
sbt 'sidecar/testOnly *DiscoveryAuthSuite *RemoteEndpointSuite *ConformanceSuite'
```

Expected: the refusal names the route and `ANKKA_AUTH_ISSUERS` beside a second problem; the
`http.auth-*` cases pass against the Scala reference; `http.acl-deny-never-reaches-process` is
401, not 503.

## 4. Every language

```bash
cd sdks/python && uv sync && uv run pytest -q && uv run mypy && uv run conformance
cd sdks/typescript && npm ci && npm run proto && npm run typecheck && npm test && npm run conformance
cd sdks/rust && cargo test --workspace && ./conformance.sh
```

Expected: each target passes the `http.auth-*` cases; each SDK declares protocol `1.5`; the
regenerated stubs carry `claims` and `issuer`.

## 5. The operator routes the prefix

```bash
sbt 'operator/testOnly *ProcessHostingRenderingSuite *WasmHostingRenderingSuite'
sbt 'controlPlaneApi/test'
```

Expected: `ANKKA_AUTH_ISSUERS` is on the sidecar container and absent from the app container; the
descriptor suite pins the prefix list.

## 6. The control plane is unchanged

```bash
sbt 'controlPlane/testOnly *ControlPlaneHttpSuite *AuthorizationMatrixSuite *ConsoleAclSuite *DeployTokensSuite *DeployTokenIndexSuite'
grep -rn nimbus build.sbt    # authOidc only
```

Expected: every case passes with the same statuses and headers; nimbus is a dependency of
`authOidc` and of no other project. `KeycloakRealmSuite` and `CliEndToEndSuite` run under
`sbt controlPlane/test` with Docker.

## 7. Docs

```bash
just docs-sync && just docs
sbt scalafmtCheckAll compile     # -Wunused clean
```

Expected: the included sample is fresh, every page in nav and a skill, no stale generated block;
`docs/reference/limitations.md` no longer says the platform authenticates only its operators.

## 8. Optional: on a cluster

```bash
sbt 'sidecar/testOnly *SidecarClusterSuite'
```

A case deploying the Python sample with the named set in its descriptor and asserting a 401 with
the challenge from outside the cluster, which proves the sidecar container received the variables
on a real pod. Minutes, not seconds; needs Docker and the k3s image.
