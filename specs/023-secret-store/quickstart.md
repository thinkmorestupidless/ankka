# Quickstart: validating the secret store

The runs that show the feature works, cheapest first. Docker is required from step 2. Contracts:
[scala-api](contracts/scala-api.md), [protocol](contracts/protocol.md),
[sdk-apis](contracts/sdk-apis.md), [control-plane](contracts/control-plane.md),
[operator](contracts/operator.md). Data: [data-model.md](data-model.md).

Every command switches the k3s suites off unless it is one; a k3s run is minutes, and belongs
under `caffeinate -i` on a laptop.

## 1. Pure: rules, cipher, declaration

```bash
sbt -Dankka.cluster.tests=off 'core/testOnly *PlatformVariablesSuite' \
    'sdk/testOnly *SecretRulesSuite' \
    'runtime/testOnly *SecretCipherSuite' \
    'controlPlaneApi/testOnly *ProjectSecretsSuite *DescriptorSuite *HostingSuite'
```

Expect: the rules fixture's every row; a round trip; a wrong key, a moved row and a changed byte
each refused; the descriptor suites unchanged.

## 2. The store on a real database

```bash
sbt -Dankka.cluster.tests=off 'testkit/testOnly *SecretStoreSuite'
```

Expect one case per scenario of `features/secrets/secret-store.feature`, including the dump of
every table, a restart, a second instance, no key and another key.

To see it fail: make `DatabaseSecretStore.put` store the value's bytes unencrypted; the dump case
and the stored-encrypted case go red.

## 3. A process and a module

```bash
sbt -Dankka.cluster.tests=off 'sidecar/testOnly *WasmHostSuite *ProtocolSuite'
sbt -Dankka.cluster.tests=off 'sidecar/testOnly *ConformanceSuite -- *secret.*'
cd sdks/python && uv sync && uv run pytest -q && uv run mypy && ANKKA_CONFORMANCE_ONLY='*secret.*' uv run conformance
cd sdks/typescript && npm ci && npm run proto && npm run typecheck && npm test && ANKKA_CONFORMANCE_ONLY='*secret.*' npm run conformance
cd sdks/rust && cargo test --workspace && ANKKA_CONFORMANCE_ONLY='*secret.*' ./conformance.sh
```

Expect eight `secret.*` cases per target. **Read what each run says it ran**: a filter without its
leading `*` matches nothing and reports green, and the Rust script must print both shapes.

## 4. The operator and the control plane, offline

```bash
sbt -Dankka.cluster.tests=off 'operator/testOnly *RenderingSuite *ProcessHostingRenderingSuite *WasmHostingRenderingSuite *CnpgRenderingSuite *SchemaResourceSuite'
sbt -Dankka.cluster.tests=off 'controlPlane/testOnly *ControlPlaneHttpSuite *TenancyEntitySuite *EventCompatibilitySuite *PlatformDeclarationSuite *ReservedSecretNamesSuite'
sbt -Dankka.cluster.tests=off 'cli/testOnly *ProjectSecretsCommandSuite *CliReferenceSuite'
```

Expect the key's placement per hosting; four schema files; set, merge, unset, list, the `404`s and
the `503`; no value in any event.

## 5. A real cluster's API server

```bash
caffeinate -i sbt 'operator/testOnly *OperatorClusterSuite'
caffeinate -i sbt 'controlPlane/testOnly *ControlPlaneClusterSuite *EndToEndClusterSuite'
```

Expect the key Secret made once and kept through a delete; the control plane's patch-then-create
with its shipped grant and `get` refused; and, through the real CLI, a project secret reaching a
pod's environment.

## 6. Documentation and the console

```bash
just docs-reference && just docs-sync && just docs
just test-console
```

Expect the CLI and route pages regenerated, the new routes described in prose, the new page in
`nav` and a skill, and the console's fixtures test green with the new schemas.

## 7. By hand, on the local cluster

Shows what no suite deploys: a real service keeping a secret, deleted, and deployed again.

With the local cluster up (`just up`), a project `<project>` and a service `<service>` deployed in
it from a descriptor file `<descriptor>`, as `docs/platform/install-local.md` describes:

```bash
ankka projects secrets set checkout STRIPE_KEY=sk_test_1 -p <project>
ankka projects secrets list -p <project>
kubectl -n ankka-<project> get secret checkout -o jsonpath='{.data}'     # the entry, read as an admin
kubectl -n ankka-<project> get secret <service>-secret-key -o jsonpath='{.metadata.uid}'
ankka services delete <service> -p <project>
ankka services apply -f <descriptor> -p <project>
kubectl -n ankka-<project> get secret <service>-secret-key -o jsonpath='{.metadata.uid}'
```

Expect the same `uid` before and after: the key outlived the service. With a service that keeps a
secret through its own endpoint, keep one before the delete and read it after the apply.

## Before a pull request

```bash
sbt scalafmtCheckAll scalafmtSbtCheck
caffeinate -i sbt test
```
