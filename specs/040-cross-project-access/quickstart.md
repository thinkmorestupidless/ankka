# Quickstart: proving Cross-Project Access

Prerequisites: Docker (Postgres, Kafka and k3s containers), `uv`, Node 24, cargo; a kind cluster
made from the current `kind.yaml` (it maps 30094) for the walk-through by hand.

## Offline, in minutes

```bash
sbt 'http/testOnly *MachineTokensSuite *GrantedCallerSuite *GrantsFileSuite *RefusedSpanSuite *RevokedStreamsSuite'
sbt 'grpc/testOnly *AdmissionSuite'                                                    # granted on a method
sbt 'controlPlaneApi/testOnly *GrantRulesSuite *ControlPlaneFixturesSuite *DocumentationDescriptorsSuite'
sbt 'controlPlane/testOnly *GrantEntitySuite *GrantMirrorSuite *MachineEntitySuite *MachineTokenSuite *EventCompatibilitySuite *ReservedProjectIdsSuite *PlatformDeclarationSuite'
sbt 'controlPlane/testOnly *CrossProjectAcceptanceFeatures *CrossProjectListingFeatures'   # features/cross-project/{acceptance,listing}.feature
sbt 'operator/testOnly *BrokerGrantsRenderingSuite *MachineRenderingSuite *ProjectRenderingSuite *RenderingGoldenSuite *RenderingUnchangedSuite *CrdSchemaSuite'
sbt 'testkit/testOnly *CrossProjectTopicSuite'                                         # a view over another project's topic, in-memory broker
sbt 'sidecar/testOnly *ConformanceSuite'                                               # the new cases against the Scala reference
cd sdks/python && uv run pytest -q && uv run mypy && uv run conformance                # likewise typescript, rust
sbt 'controlPlane/testOnly *RemoteOverlaySuite'                                        # needs kubectl: the component's shape in both overlays
just features && just docs-reference && just docs
```

Expected: every suite green; `GherkinSuite` reports each scenario of the two offline features by
name; the checker reads 46 specs; `docs check` finds the new page in the nav and a skill.

## On k3s (`-Dankka.cluster.tests` on; also `gh workflow run cluster --ref <branch> -f suite=CrossProject*`)

```bash
caffeinate -i sbt 'set controlPlane / Test / logBuffered := false' \
  'controlPlane/testOnly *CrossProjectRouteGrantsFeatures *CrossProjectTopicGrantsFeatures *CrossProjectMachinesFeatures *CrossProjectMachineTopicsFeatures'
sbt 'operator/testOnly *OperatorClusterSuite'      # the RBAC case covers ankkamachines both ways
```

Expected: `merchant` refused, served within 120 s of a grant, refused within 120 s of a revocation,
no pod restarted; the broker refuses `attribution` until the grant, serves it under
`ankka.affiliates-hub.attribution.view.players`, refuses it again after; a machine's `curl` through
the gateway is served with the caller it expects and refused without a grant; a Kafka client on
the host reads 100 messages from `broker.127.0.0.1.sslip.io:9094` and is refused a second topic, a
publish and a connection without a token.

## By hand, on kind

```bash
just deploy
ankka organizations machines register eitheror affiliate-network            # keep the secret
ankka projects grants make machine:eitheror/affiliate-network route affiliates GET /v1/affiliates/attribution -p spinvibe
ankka projects grants list -p spinvibe                                       # in effect, once the route is seen
TOKEN=$(curl -s -u 'machine:eitheror/affiliate-network:<secret>' -d grant_type=client_credentials \
        --cacert ~/.ankka/local-ca.crt https://api.127.0.0.1.sslip.io:8443/oauth/token | jq -r .access_token)
curl --cacert ~/.ankka/local-ca.crt -H "Authorization: Bearer $TOKEN" https://affiliates-spinvibe.127.0.0.1.sslip.io:8443/v1/affiliates/attribution
ankka projects grants revoke <id> -p spinvibe                                # refused within two minutes, same token
```

For the broker: add `components/broker-external` to the local overlay, `just deploy` again, grant a
machine `consume` on a declared topic, and run a Kafka console consumer with
`contracts/broker-external.md`'s properties against `broker.127.0.0.1.sslip.io:9094`.

## Done when

- SC-001 to SC-013 hold on the suites above; `EventCompatibilitySuite` asserts no `secret` or
  `token` field on any event.
- `RenderingGoldenSuite` and `RenderingUnchangedSuite` pass with one new env entry per fixture
  (`ANKKA_PROJECT_GRANTS`) and no new object: no service rolls on upgrade for a grant it has not
  been given.
- `docs/reference/limitations.md` no longer says a project cannot grant a topic; the topics guide
  shows a stock client outside the installation; `control-plane-api.md` and `cli.md` are
  regenerated with a hand-written section per route and command.
