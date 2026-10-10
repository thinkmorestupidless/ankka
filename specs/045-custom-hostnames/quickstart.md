# Quickstart: validating custom hostnames

The runs that show the feature works, cheapest first. Docker is required from step 2. Contracts:
[control-plane](contracts/control-plane.md), [operator](contracts/operator.md),
[installation](contracts/installation.md), [proxy](contracts/proxy.md). Data:
[data-model.md](data-model.md).

Every command switches the k3s suites off unless it is one; a k3s run is minutes, and belongs
under `caffeinate -i` on a laptop.

## 0. The spikes, once

```bash
caffeinate -i sbt -Dankka.spikes=on 'operator/testOnly *ListenerSetSpike'      # S1: the set, Pebble, the two-parent route
sbt -Dankka.cluster.tests=off -Dankka.spikes=on 'controlPlane/testOnly *ProofLookupSpike'   # S2: JNDI TXT against challtestsrv
```

Expect, from S1: a `ListenerSet` listener `Programmed` with a Secret in its namespace; a route
`Accepted` on both parents; a certificate from Pebble for the custom name; the Challenge's
`status.reason` texts printed for a name that resolves and one that does not; a set naming
`*.<base>` `Conflicted` with the base domain still answering. From S2: the `TXT` strings read
through `dns://127.0.0.1:<port>`.

## 1. Pure: the rules, the entity, the rendering, the status

```bash
sbt -Dankka.cluster.tests=off \
    'controlPlaneApi/testOnly *CustomHostnamesSuite *ControlPlaneFixturesSuite *DocumentationDescriptorsSuite' \
    'crd/testOnly *HostnamesSuite *AnkkaServiceCodecSuite' \
    'operator/testOnly *HostnameRulesSuite *CustomHostnamesRenderingSuite *RenderingGoldenSuite *RenderingUnchangedSuite *CrdSchemaSuite *SettingsSuite' \
    'controlPlane/testOnly *ServiceEntitySuite *EventCompatibilitySuite *ServiceProjectionSuite *StatusIngestSuite *ReservedSecretNamesSuite *PlatformDeclarationSuite' \
    'proxyCore/testOnly *HeadersSuite' 'proxy/testOnly *TlsTransportSuite'
```

Expect: every refusal of the contract's table 1 to 5 in its words; the sixth hostname refused by
the entity; a pre-feature journal decoding with no hostnames; the resource carrying the list and the
status block; each row of the status rules; the set, the certificate and the two-parent route
rendered for a service with two hostnames and nothing for one with none; `RenderingUnchangedSuite`
green against fixtures whose only change is two action lines each (`git diff --stat
operator/src/test/resources/unchanged`); a `Host` from the gateway stated as the address and a
forged `X-Forwarded-Host` not.

To see a check fail once: remove `hostnames` from `ankkaservice.yaml`'s status and run
`CrdSchemaSuite`; then remove `reason` from inside it.

## 2. The control plane, offline, and the CLI

```bash
sbt -Dankka.cluster.tests=off \
    'controlPlane/testOnly *ControlPlaneHttpSuite *CliEndToEndSuite *RemoteOverlaySuite' \
    'cli/testOnly *OutputSuite *CliReferenceSuite'
```

The HTTP suite's lookup is a fake (`ProofLookup` replaced): expect the refusals 6 to 11 in order,
the holder named across projects, the take-away by an administrator in the history, the records in
`get`, the apex note; both overlays rendering the issuer and the Gateway's `allowedListeners`
(needs `kubectl` on the PATH, or the suite skips — check it ran).

## 3. The console

```bash
just test-console
```

Expect: the fixtures test decoding a `ServiceStatus` with hostnames; the service page listing the
derived hostname, the proof record and each custom hostname with its state; add and remove through
the page; parity green with the two routes exercised.

## 4. On k3s

```bash
caffeinate -i sbt 'controlPlane/testOnly *CustomHostnamesClusterFeatures'
caffeinate -i sbt 'controlPlane/testOnly *WebHostingClusterFeatures'
caffeinate -i sbt 'operator/testOnly *OperatorClusterSuite'
caffeinate -i sbt 'controlPlane/testOnly *ExposureClusterSuite'      # unchanged, still green
```

Expect one case per scenario of `features/exposure/custom-hostnames.feature`, issued by Pebble:
the certificate's subject asserted as the custom hostname from Pebble's root; the unproved name
refused with the whole record; the unpointed name `pending` with a reason naming it; removal
within 30 seconds with the same pod UIDs; the sixth refused. In `WebHostingClusterFeatures`: the
process told the custom hostname, the derived one, and the custom one under a forged header; a
mount under it. In `OperatorClusterSuite`: `patch` on the Gateway refused and a `ClusterIssuer`
refused for the operator's token; a `ListenerSet` granted; a certificate for a custom hostname
from the local `ankka-ca` served.

sbt holds a suite's report until it ends; `sbt 'set controlPlane / Test / logBuffered := false' …`
reports each scenario as it finishes.

## 5. Documentation and the features

```bash
just docs-reference && just docs-sync && just docs && just features
```

Expect: the two routes and the commands in the generated blocks with hand-written sections; the new
page in the nav and two skills; the limitations page without "No custom hostnames"; no finding.

## 6. By hand, on kind

```bash
kind create cluster --name ankka --config kustomization/kind.yaml   # if there is none
./kustomization/deploy-local.sh
ankka login
```

With a domain you control, create `_ankka.app.<your domain> TXT "ankka-project=shop"` at your
provider and add `127.0.0.1 app.<your domain>` to `/etc/hosts`. Then:

```bash
ankka services expose cart -p shop
ankka services hostnames add cart app.<your domain> -p shop
ankka services get cart -p shop
```

Expect `app.<your domain>   serving` within a minute (the local authority runs no challenge), and:

```bash
curl -sv --cacert ~/.ankka/local-ca.crt https://app.<your domain>:8443/carts/c1 2>&1 | grep -E 'subject:|HTTP/'
```

Expect `subject: CN=app.<your domain>` and a `200`. Before the TXT record exists, `hostnames add`
answers the refusal with the record to create. Then:

```bash
ankka services hostnames remove cart app.<your domain> -p shop
curl -s -o /dev/null -w '%{http_code}\n' --cacert ~/.ankka/local-ca.crt https://app.<your domain>:8443/carts/c1
```

Expect a handshake failure or `404` within 30 seconds, and `kubectl -n ankka-shop get pods` showing
the same pods as before.
