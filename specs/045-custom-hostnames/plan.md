# Implementation Plan: Custom Hostnames for an Exposed Service

**Branch**: `045-custom-hostnames` | **Date**: 2026-10-09 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/045-custom-hostnames/spec.md`

## Summary

A member adds a hostname under a domain its owner brings to an exposed service, beside the one the
platform derives. The control plane proves the project controls the name with one DNS read of a
proof record, refuses a name another service holds, under the base domain, malformed or beyond a
cap of five, and records the hostname on the service entity. The operator renders it as a listener
in a `ListenerSet` of the service's own, in the project's namespace, with a certificate cert-manager
obtains from the issuer the installation names, and carries it on the service's route beside the
derived hostname. A member reads where each hostname stands, in the authority's words. Removing a
hostname, unexposing and deleting the service each stop it; a platform administrator can take one
away. Nothing about the caller, readiness, rollouts or the derived hostname changes.

Technically: the gateway opts in to `ListenerSet`s from managed namespaces (Gateway API v1.6.1,
bundled by the pinned Envoy Gateway; R1), so the operator attaches exactly what serves a hostname
and still cannot touch the Gateway (R10). The certificate is a `Certificate` per hostname from a
`ClusterIssuer` named by `ANKKA_HOSTNAME_ISSUER`, ACME HTTP-01 through the gateway on a cloud
installation and the local root, moved to a `ClusterIssuer`, locally (R5). The proof is
`_ankka.<hostname> TXT ankka-project=<project id>`, read once over the JDK's JNDI DNS provider with
no dependency added (R3). The status is read from the Certificate, the Challenge and the listener
and folded into the resource as `pending`, `serving` or `rejected` with a reason (R6). The proxy
states the address the gateway routed, which by the gateway's construction is one the service holds,
so no pod rolls when a hostname changes (R12). The k3s suite issues from Pebble with a resolver the
test populates (R15).

Planning found eight things the spec did not have, and the spec is amended for each:

- **The proof record cannot sit at the hostname.** A `CNAME` has no sibling records, so the proof
  is `_ankka.<hostname>`, and its value is the project id, nothing derived (R3).
- **The proxy cannot be given a list without rolling pods.** It takes the address from the
  gateway's routing, which already is the list (R12).
- **A namespaced `Issuer` cannot serve a project's namespace.** The issuer for custom hostnames is a
  `ClusterIssuer`; the local root moves to cert-manager's namespace (R5).
- **The k3s suite issues from Pebble throughout.** The local authority's path is one case in
  `OperatorClusterSuite` (R15).
- **A local platform still needs the proof in real DNS** (R16).
- **The gateway's HTTP listener admits routes from project namespaces**, for cert-manager's solver
  route; the operator still renders nothing against it (R5).
- **A project secret may not be named with a dot**: the hostname's certificate and Secret are
  named by the hostname (R8).
- **The apex record needs an address the installation publishes**, `ANKKA_GATEWAY_ADDRESS` (R17).

## Technical Context

**Language/Version**: Scala 3 on JDK 21 (`crd`, `controlplane-api`, `controlplane`, `operator`,
`cli`, `proxy-core`, `proxy`); TypeScript on Node ≥ 22 (`console/package`); YAML (kustomize);
Gherkin

**Primary Dependencies**: none added to any main classpath. The DNS read is the JDK's
`jdk.naming.dns` (R3); the `ListenerSet` type is in fabric8 7.9.0 already on the operator's
classpath (R1). Two third-party images for tests only: `ghcr.io/letsencrypt/pebble:v2.10.1` and
`ghcr.io/letsencrypt/pebble-challtestsrv:v2.10.1` (R15). Platform dependencies at their pinned
versions: Envoy Gateway v1.9.1, cert-manager v1.21.2 with `--enable-gateway-api`.

**Storage**: the control plane's journal gains two events and three defaulted fields; the
`ServiceRows` view gains a column (the row is the wire type); `AnkkaServiceSpec` gains one list
and `AnkkaServiceStatus` one block, with the schema. Kubernetes: one `ListenerSet` per service with
hostnames, one `Certificate` and its Secret per hostname. No DDL.

**Testing**: munit in `controlplane-api`, `controlplane`, `operator`, `crd`, `cli`, `proxy-core`,
`proxy`; a `GherkinSuite` on k3s for `features/exposure/custom-hostnames.feature`
(`CustomHostnamesClusterFeatures`, with `AcmeStack`); `WebHostingClusterFeatures` extended for the
web-hosting scenarios; `OperatorClusterSuite` extended (RBAC both ways, the local authority);
`ExposureClusterSuite` unchanged and green; the console's unit, fixtures and Playwright tests; the
docs build; the features check; two spikes first (S1, S2).

**Target Platform**: an installation's cluster — kind locally, any Kubernetes ≥ 1.32 otherwise.
A service on a developer's own machine has no hostname at all.

**Project Type**: a platform's control plane, operator, CLI, console and proxy; two installation
components and both overlays; documentation

**Performance Goals**: a request at a custom hostname takes the same path as one at the derived
hostname (one listener, the same route, the same backend) — SC-002 is structural. The one DNS read
is at add time and never on a read (R3). A reconcile of a service without custom hostnames reads
one more object type (the set, absent) and lists Certificates by label once.

**Constraints**: the operator's grant gains `listenersets` and read-only `challenges` and nothing
on gateways, secrets or clusterissuers; no pod template of any hosting changes (R12, R13), so no
service rolls on upgrade and none on a hostname change; a stored journal decodes unchanged; no
secret in the journal (the proof is public); `kubectl apply -k` remains the whole deploy; the
derived hostname is still derived, never read from the resource; warning-free; tests serialised;
no fixed port; no literal image tag for an image this build makes

**Scale/Scope**: about 8 new Scala source files and 24 changed across seven modules; about 10 new
suites and 18 changed; 2 components and 2 overlays changed, the deploy script, `GatewayStack`; 4
console source files and their tests; 1 new docs page and about 8 changed; 1 new feature file (25
scenarios) and 3 changed (3 scenarios)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is the unfilled template, so the gate is the principles
`CLAUDE.md` and the rule files state as the codebase's constitution:

| Principle | Status | How the design honours it |
|---|---|---|
| Effects are inert data; one thing performs them | pass | `EnsureListenerSet`, `RemoveListenerSet`, `RemoveCertificate` and `PruneHostnameCertificates` are descriptions; `Fabric8Executor` performs them; `HostnameRules.status` is pure (R6) |
| Where two parties must agree on a derivation, there is one | pass | `CustomHostnames` in `controlplane-api` is the rules' one home; `Hostnames` still derives the derived hostname for both ends; the operator never reads it from the resource (R2, R9) |
| Module dependency direction | pass | `crd` depends on nothing; `controlplane-api` on `core`; the operator on `crd`; `proxy-core` on the JDK; no module gains a dependency |
| The operator cannot change how the gateway is reached | pass | nothing on `gateways`; a `ListenerSet` in the project's namespace, admitted by the Gateway's selector, loses every conflict with the Gateway's own listeners; `OperatorClusterSuite` proves `patch` on the Gateway refused (R1, R10) |
| The operator has no `get` on Secrets and never needs one | pass | the certificate's Secret is read by the gateway; the operator reads the Certificate, the Challenge and the set (R5, R6) |
| A field on the resource needs the schema | pass | one spec list and one status block in `ankkaservice.yaml`, `CrdSchemaSuite` extended into the block |
| Never render what must not roll | pass | no pod template changes; `RenderingUnchangedSuite` repinned for two action lines and no object (R12, R13) |
| No secret value in the control plane's journal | pass | the proof is the project id in public DNS; no token exists (R3) |
| Cross-entity checks live in the endpoint, never a handler | pass | the holder and the proof in `ServiceEndpoint`; the cap and "not exposed" in the handler, as the entity's own state (R2, R4, R7) |
| Stored forms stay readable both ways | pass | every new journaled field defaults; `EventCompatibilitySuite` pins the two events and the three fields (R7) |
| Wire names are declared separately and never reused | pass | `add-hostname`, `remove-hostname`, `take-hostname-away` (R7) |
| `Acl.Authenticate` does no I/O; a per-request check never backs on a consumer | pass | the DNS read is in the endpoint on a virtual thread, after authentication (R3) |
| An overlay that only works from the script is not an overlay | pass | every setting is in the ConfigMap and carried by `replacements`; the script only reads the root from its new namespace (R14) |
| A `ClusterIssuer` reads its secret in cert-manager's namespace | pass | the local root moves there, so one `ClusterIssuer` serves the wildcard and every project (R5) |
| A wildcard is one label deep | pass | untouched; a custom hostname is a listener of its own, exactly one name (R1) |
| A route can be `Accepted` and still not serve | pass | `serving` needs the listener `Programmed` and `ResolvedRefs`, and the suite asserts an answer with the right certificate, not a condition (R6, R19) |
| Every new test switch is forwarded to the forked JVM | pass | none added; `ankka.cluster.tests` and `ankka.spikes` are forwarded already |
| Could this check pass while the thing it checks is false? | pass | R19 names seven and how each is sharpened |
| Each acceptance scenario ends as a test that fails without the feature | pass | 25 + 3 scenarios: 22 by `CustomHostnamesClusterFeatures`, 3 by `WebHostingClusterFeatures`, the console's by Playwright, the tenancy pair by `OperatorClusterSuite` |
| Docs: pages stand alone, generated reference, new page in nav and a skill | pass | R18 |
| Every tracked file claimed by a CI path filter | pass | every touched directory is claimed already |
| Every port a workload has is mutual TLS | pass | untouched; the gateway terminates the hostname's TLS and speaks mTLS to the backend as before |

**Violations to justify**: none. The departures from the spec's wording are in Complexity
Tracking.

**Post-design re-check**: unchanged. The contracts add no dependency, no verb beyond the two named,
no field beyond the resource's list and block, and no change to any pod template.

## Project Structure

### Documentation (this feature)

```text
specs/045-custom-hostnames/
├── plan.md              # this file
├── research.md          # R1–R19: decisions with file-level evidence; S1, S2 spikes first
├── data-model.md        # the hostname on the entity, the resource, the objects, the status, the wire
├── quickstart.md        # the validation runs: pure → offline → console → k3s → docs → by hand
├── contracts/
│   ├── control-plane.md   # routes, refusals, what a member reads, CLI, history
│   ├── operator.md        # settings, actions, what is rendered, the status rules, the grant
│   ├── installation.md    # the gateway, the issuers, the settings, the overlays
│   └── proxy.md           # the address a request was sent to
└── tasks.md             # /speckit-tasks, not created here
```

The acceptance scenarios are in `features/exposure/custom-hostnames.feature` (new),
`features/exposure/tenancy.feature`, `features/web-hosting/requests.feature` and
`features/web-hosting/mounts.feature`, in the words of `GLOSSARY.md`.

### Source Code (repository root)

```text
crd/…/crd/AnkkaService.scala                                   # customHostnames; HostnameStatus block
crd/…/crd/Hostnames.scala                                      # isApex; platform names (unchanged derivation)
kustomization/components/crd/ankkaservice.yaml                 # the same, declared

controlplane-api/…/api/CustomHostnames.scala                   # new: normalise, problems, MaxPerService
controlplane-api/…/api/descriptors.scala                       # ServiceStatus: customHostnames, proofRecord; ProjectSecrets: no dot
controlplane/…/controlplane/api/ServiceEndpoint.scala          # the two routes; the holder; the proof; the records
controlplane/…/controlplane/api/ProofLookup.scala              # new: the one TXT read over JNDI
controlplane/…/controlplane/deploy/DeployConfig.scala          # issuer, resolver, gateway address; records
controlplane/…/controlplane/deploy/ServiceProjection.scala     # customHostnames onto the spec
controlplane/…/controlplane/deploy/StatusIngest.scala          # hostnames into the observation
controlplane/…/controlplane/domain/events.scala, model.scala   # the two events; the fields; history kinds
controlplane/…/controlplane/application/ServiceEntity.scala    # add-hostname, remove-hostname, take-hostname-away
controlplane/…/controlplane/application/ServiceRows.scala      # the column
controlplane/src/main/resources/reference.conf                 # ankka.controlplane.hostnames.*

operator/…/operator/HostnameRules.scala                        # new: status from the three reads; the set and certificate shapes
operator/…/operator/Action.scala, Executor.scala               # EnsureListenerSet, RemoveListenerSet, RemoveCertificate, PruneHostnameCertificates; observeHostnames
operator/…/operator/Rendering.scala                            # hostnames on the route; the second parent; the set; the certificates
operator/…/operator/Settings.scala                             # hostnameIssuer
operator/…/operator/ServiceReconciler.scala                    # the status block
operator/…/operator/ClusterSnapshot.scala                      # ListenerView, CertificateView, ChallengeView
operator/src/test/…/AcmeStack.scala                            # new: Pebble and its resolver on k3s
operator/src/test/…/GatewayStack.scala                         # cert-manager's flag; the moved root
operator/src/test/resources/golden/custom-hostnames.txt        # new
operator/src/test/resources/unchanged/*.json.txt               # two action lines each

kustomization/components/gateway/gateway.yaml                  # allowedListeners; the http listener's selector
kustomization/components/certmanager/                          # the controller patch
kustomization/components/operator/operator.yaml                # listensersets; challenges
kustomization/components/controlplane/deployment.yaml          # ANKKA_HOSTNAME_ISSUER, ANKKA_DNS_RESOLVER, ANKKA_GATEWAY_ADDRESS
kustomization/overlays/local/{local-ca.yaml,platform-configmap.yaml,kustomization.yaml}
kustomization/overlays/cloud/{acme-issuer.yaml,platform-configmap.yaml,kustomization.yaml}
kustomization/deploy-local.sh                                  # the root's namespace

proxy-core/…/proxy/core/Headers.scala                          # the gateway's Host is the address
proxy/…/proxy/TlsTransport.scala                               # Sender.Internet carries the routed authority

cli/…/cli/Main.scala, ControlPlaneClient.scala, Output.scala   # services hostnames add|remove; the block; the column
cli/…/cli/mcp/AnkkaTools.scala                                 # add_hostname, remove_hostname

console/package/src/client/schemas.ts, control-plane.ts        # the fields; the two calls
console/package/src/routes/service.tsx                         # the hostnames section
console/package/src/testing/fake-control-plane.ts, scenarios.ts
console/package/fixtures/control-plane/                        # written again
console/e2e/tests/services.spec.ts

controlplane/src/test/…/CustomHostnamesClusterFeatures.scala   # new: the steps and the suite
controlplane/src/test/…/WebHostingClusterFeatures.scala        # three scenarios
controlplane/src/test/…/{ControlPlaneHttpSuite,CliEndToEndSuite,ServiceEntitySuite,EventCompatibilitySuite,
                          ServiceProjectionSuite,StatusIngestSuite,RemoteOverlaySuite,ReservedSecretNamesSuite}.scala
operator/src/test/…/{RenderingSuite,RenderingGoldenSuite,RenderingUnchangedSuite,CrdSchemaSuite,
                     HostnameRulesSuite,OperatorClusterSuite,SettingsSuite}.scala
proxy-core/src/test/…/HeadersSuite.scala; proxy/src/test/…/{TlsTransportSuite,ProxySteps}.scala

docs/deploy/custom-hostnames.md                                # new
docs/deploy/expose.md; docs/reference/{limitations,control-plane-api,cli,web-hosting}.md
docs/platform/install-cloud.md; docs/operate/status-and-history.md
mkdocs.yml, tools/docs/skill/{ankka-deploy,ankka-platform}/SKILL.md, the rendered skills
```

**Structure Decision**: no module, image this build makes or published artifact is added. Each
piece goes where its kind already lives: the rules in `controlplane-api` beside the descriptor's,
the lookup beside the endpoint that is its only caller, the status rules beside `LifecycleRules`,
the set and the certificates beside the route in `Rendering`, the ACME stack beside `GatewayStack`,
the page beside `expose.md`. The one new kind of thing is a DNS read in the control plane, and it
is one file behind a function the tests replace.

## Order of work

Cut by user story, tests before the code they hold. Slices 1 to 4 need no cluster.

0. **Spikes** — S1 and S2 of the research, each throwaway under `-Dankka.spikes=on`: the
   `ListenerSet` with a Secret in its namespace, the two-parent route, the Pebble round trip and
   the Challenge's reason texts; the JNDI `TXT` read. R1, R5, R6, R9, R15 and R3 rest on them.
1. **Names, rules and the resource** (FR-001, FR-002, FR-002a's order, FR-003a). `CustomHostnames`,
   the reserved dot, `Hostnames.isApex`, the spec's list and the status block with the schema, the
   projection.
2. **The entity and what a member reads** (FR-001, FR-003, FR-004, FR-013). The events, the fields,
   the three handlers, the history kinds, `ServiceRows`, `StatusIngest`, the wire fields, the records.
3. **The endpoint, the lookup and the CLI** (FR-002a, FR-004). The two routes, the holder scan, the
   proof over JNDI with a fake for the suites, the refusals in order, the CLI commands and output,
   the MCP tools, the console's fixtures.
4. **The operator's rendering and status** (FR-005 to FR-008, FR-012). The set, the certificates,
   the route's hostnames and second parent, the removal actions, the status rules from the three
   reads, the golden file, the repin, the settings, the grant.
5. **The installation** (FR-014). The gateway's two changes, cert-manager's flag, the moved root,
   the cloud issuer, the ConfigMap keys and replacements, the script, `GatewayStack`,
   `RemoteOverlaySuite`.
6. **User Stories 1, 2 and 4 on k3s.** `AcmeStack`, the steps, `CustomHostnamesClusterFeatures`;
   `OperatorClusterSuite`'s RBAC and local-authority cases; the tenancy scenarios.
7. **User Story 3** (FR-010, FR-011). `Headers.inbound` and `TlsTransport`, their suites, the
   three web-hosting scenarios on k3s.
8. **The console** (FR-008). The section, the fake, Playwright, parity.
9. **Documentation** (FR-015), then the whole build.

Slice 7 depends on 4 and 5; slice 8 on 3; slices 6 and 8 do not depend on each other.

## Complexity Tracking

No principle is departed from. The rows are places the plan departs from the spec's wording,
each with the simpler thing that was rejected.

| Departure | Why needed | Simpler alternative rejected because |
|---|---|---|
| The proof record is `_ankka.<hostname>`, not at the name (FR-002a, FR-004, amended) | a name with a `CNAME` can carry no `TXT` | the apex alone could, and two rules for one record is worse than one prefix (R3) |
| The proof's value is the project id, with no token (FR-004, amended) | only the zone's owner can write the record, which is the proof | a random token is an event and a listing for a value that proves nothing more (R3) |
| The proxy is given no list of hostnames (FR-010, amended) | an environment variable rolls the pod on every change and a mount rolls every web service on upgrade | the gateway routes a hostname only to its holder, so the routed `Host` is the list (R12) |
| The issuer is a `ClusterIssuer` and the local root moves to cert-manager's namespace (FR-014, amended) | a Certificate in a project's namespace cannot name a namespaced `Issuer` elsewhere | a `ClusterIssuer` reading a secret in `ankka-gateway` fails `not found`, the trap `kubernetes.md` records (R5) |
| The k3s suite issues every scenario from Pebble (SC-005, amended) | the challenge, the solver route and the reason a member reads are what the local authority cannot exercise | two issuers in one suite restarts the operator mid-run; the local path is one case in `OperatorClusterSuite` (R15) |
| A local platform needs the proof record in real DNS (FR-014, amended) | one rule everywhere | a switch that waives the proof is a switch an installation could set (R16) |
| The gateway's HTTP listener admits routes from managed namespaces (FR-007's tenancy wording, amended) | cert-manager writes the solver route in the Certificate's namespace | a per-project issuer the operator renders needs the ACME account in every namespace (R5) |
| A project secret may not contain a dot (added in planning) | the hostname's certificate Secret is named by the hostname | a hashed name is unreadable and still a reserved form (R8) |
| `ANKKA_GATEWAY_ADDRESS` on the control plane (FR-004's "where the installation knows it", made concrete) | an apex takes an address record and the operator cannot learn a load balancer's address reliably | reading the Gateway's status from the control plane needs a grant it does not have (R17) |
| `RemoveListenerSet` and `PruneHostnameCertificates` rendered for every exposed service | a hostname removed in the same apply that unexposed the service would leave its listener and certificate | rendering removal only for services that still name hostnames leaves exactly that behind; the repin is two lines and no object (R13) |

**Operational consequences** to announce in the release:

- The Gateway gains `allowedListeners` and its HTTP listener admits routes from project
  namespaces; cert-manager's controller gains `--enable-gateway-api`. Both are component changes an
  installation picks up with `kubectl apply -k`.
- The local overlay's root CA moves to the `cert-manager` namespace and `ankka-ca` becomes a
  `ClusterIssuer`; an existing kind cluster re-deployed keeps its exported root only if the Secret
  is moved, otherwise `~/.ankka/local-ca.crt` is rewritten and the CLI's `config set ca` runs again.
- A cloud installation that wants custom hostnames sets `hostnameIssuer` and `gatewayAddress` in
  its ConfigMap and adds the HTTP-01 `ClusterIssuer`; without them, adding a hostname is refused
  naming the setting.
- A project secret named with a dot can no longer be set.
- The operator's ClusterRole gains `listenersets` and read-only `challenges`.
