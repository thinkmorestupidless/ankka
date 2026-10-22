# Quickstart: proving spec 057

Each block is a check that fails without the feature. Run from the repository root; Docker is
required for the control plane and replication suites.

## The living features and the glossary

```bash
just features          # 0 findings; the 14 scenarios 057 added are referenced from spec.md
```

## A deploy token for one project; the platform administrator

```bash
sbt 'controlPlane/testOnly *AuthorizationMatrixSuite *ControlPlaneHttpSuite *DeployTokenIndexSuite'
```

Expected: the tests named after the four `deploy-tokens.feature` scenarios and the three
`administrators.feature` scenarios pass; test 10 ("a deploy token is a member and obeys every rule")
passes unchanged. Proof it can fail: hand `projectGrants` to the `accept` route and the outline row
"a member who is not an owner cannot accept, nor can a deploy token" in
`CrossProjectAcceptanceFeatures` goes red.

```bash
sbt 'controlPlane/testOnly *CliEndToEndSuite'   # `ankka tokens create --project`, the PROJECT column
```

## The installation's facts

```bash
sbt 'controlPlane/testOnly *InstallationFactsFeatures *InstallationRouteSuite'
```

Expected: with the topic policy at 3 copies, 2 in sync, retention `everything` and
`-Dankka.broker.nodes=3`, the read reports `broker {3, 3, 2}` and `retention {unbounded: true}`;
with the node count unset, `broker.nodes` is absent; `erasure` is absent always.

```bash
ankka installation      # against compose's control plane: prints the broker and retention blocks
```

## The replication seam

```bash
sbt 'testkit/testOnly *TransportFeatures *TestkitFeatures *ForwardingFeatures'
sbt 'runtime/testOnly *ReplicationSettingsSuite *PlatformDeclarationSuite *JournalRecordCompatibilitySuite'
```

Expected: `TransportFeatures` lists 6 scenario headers (9 tests), `TestkitFeatures` 3,
`ForwardingFeatures` 8, all passing; `JournalRecordCompatibilitySuite` passes with its fixtures
untouched (`git diff --exit-code modules/runtime/src/test/resources/`). Proof the attribution test
can fail: comment out the stamp in `Trace`'s outgoing metadata and "a forwarded write reaches the
home as a call by the forwarding installation, with the caller who acted as its attribution" goes
red.

The environment form, by hand:

```bash
ANKKA_INSTALLATION=leipzig ANKKA_INSTALLATIONS=leipzig,bratislava sbt shoppingCart/run
# refuses to start naming bratislava and both forms of naming its database or a transport
```

## Protocol 1.22

```bash
sbt 'sidecar/testOnly *ProtocolSuite *ConformanceSuite'
cd sdks/python && uv run pytest -q && uv run conformance
cd sdks/typescript && npm test && npm run conformance
cd sdks/rust && cargo test --workspace && ./conformance.sh
```

Expected: each conformance run lists the new case ("a command carrying attribution and where it was
forwarded from persists a record carrying both") as passed, and `ProtocolSuite` reports 1.22.

## Docs

```bash
just docs-reference && just docs-sync && just docs && git diff --exit-code docs/ marketplace/ ankka.g8/
```
