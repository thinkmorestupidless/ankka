# Install on a cloud cluster

> Install ankka on a real Kubernetes cluster with a production kustomize overlay — a load balancer, a public wildcard certificate over DNS-01, a real base domain, images from a registry and an identity provider with no default credentials.

Source: https://docs.ankka.cloud/platform/install-cloud/
A cloud installation is the same set of components as the local platform, applied with a production
kustomize overlay that changes only what must differ from a laptop. The ankka repository carries an
example production overlay in `kustomization/overlays/arrakis`; copy it, set its values for your
installation, and apply it by hand after the three controllers whose resources it uses.

`deploy-local.sh` is not used here. It refuses any `kubectl` context that is not the local kind cluster,
on purpose: a script that deploys to whatever context happens to be current is a script that eventually
deploys to the wrong one.

## Prerequisites

- **Kubernetes 1.32 or later.** Envoy Gateway's custom resource definitions use a validation rule an
  older API server rejects.
- **A domain you control, with DNS at a provider cert-manager can write to.** The platform serves every
  exposed service under one wildcard certificate, `*.<base domain>`, and a wildcard can only be issued
  over the ACME DNS-01 challenge, which writes a TXT record into your zone. HTTP-01 cannot issue
  wildcards. cert-manager has built-in DNS-01 solvers for Cloudflare, Route53, Google Cloud DNS, Azure
  DNS, DigitalOcean, Akamai, ACME-DNS and RFC-2136 servers; other providers need a webhook solver.
- **A load balancer** your cluster can provision for a `Service` of type `LoadBalancer`, and a DNS
  record pointing `*.<base domain>` at its address.
- **A container registry** the cluster can pull from, holding the platform's four images: the operator,
  the control plane, the sidecar and, if you want the sample, the shopping cart.

## What a production overlay changes

The overlay lists the same components as the local one, and patches or replaces five things:

| | Local overlay | Production overlay |
|---|---|---|
| Gateway exposure | node ports 30080/30443, published by kind on 8080/8443 | a `LoadBalancer` service, optionally with a pinned address |
| Wildcard certificate | a certificate authority created in the cluster, as a namespaced `Issuer` | an ACME issuer, such as Let's Encrypt, over DNS-01 |
| Base domain and HTTPS port | `127.0.0.1.sslip.io`, port 8443 | your domain, port 443 |
| Identity provider admin | a development secret, `admin`/`admin` | the secret deleted; you create one out of band |
| Images | unqualified names loaded into the node | your registry, at a pinned release tag |

### The base domain

The base domain and HTTPS port are written once, in the overlay's `ankka-platform` ConfigMap, and
kustomize `replacements` copy them into every place that must agree: the wildcard certificate, the
gateway's listener, the control plane's and identity provider's routes, Keycloak's own hostname, and the
`ANKKA_BASE_DOMAIN` of the operator and the control plane.

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: ankka-platform
  namespace: ankka-gateway
data:
  baseDomain: example.com
  httpsPort: "443"
```

The control plane answers at `https://api.example.com`, the identity provider at
`https://auth.example.com`, and exposed services at `https://<service>-<project>.example.com`.

### The certificate

The gateway's HTTPS listener serves a Secret named `ankka-wildcard-tls` in the `ankka-gateway` namespace,
holding a certificate for `*.<base domain>`. That Secret is the whole contract. The example overlay
produces it with a cert-manager `Certificate` from an ACME `ClusterIssuer`; any issuer that produces it
will do, and so will a bought certificate placed in that Secret.

Two details decide whether issuance works. Use the ACME provider's staging endpoint until issuance
succeeds, because the production endpoint rate-limits failures. And mind where cert-manager looks for
secrets: a `ClusterIssuer` reads the secrets it references from cert-manager's own namespace, and a
namespaced `Issuer` from its own namespace. Put the DNS provider's credential where the issuer you choose
will look for it. A webhook solver may add its own rule: the example overlay's webhook reads its token
with its own permissions in cert-manager's namespace, which is why it uses a `ClusterIssuer`.

### The identity provider's administrator

The local platform's Keycloak administrator is `admin`/`admin`, in a Secret that is public in the ankka
repository. A production overlay deletes that Secret rather than overriding it, so the real password
never enters git, and Keycloak cannot start until you create the Secret yourself:

```bash
kubectl -n ankka-auth create secret generic ankka-keycloak-admin \
  --from-literal=username=admin --from-literal=password="\$(openssl rand -base64 32)"
```

An identity provider that refuses to start is a loud and immediate failure. One on the internet with a
published administrator password is neither. The realm contains no users at all; add them in the
console. See [Identity and machine accounts](identity.md).

### Images

Every platform manifest names an unqualified image, such as `ankka-controlplane:latest`, with
`imagePullPolicy: IfNotPresent`. That suits a node the image was loaded onto and is useless for a
cluster that pulls. Build the images tagged for your registry by setting `DOCKER_REPOSITORY` when you
build them:

```bash
DOCKER_REPOSITORY=registry.example.com/ankka sbt docker:publish
```

Then rename them in the overlay with an `images:` block, at the release you run:

```yaml
images:
  - name: ankka-controlplane
    newName: registry.example.com/ankka/ankka-controlplane
    newTag: "0.2.0"
  - name: ankka-operator
    newName: registry.example.com/ankka/ankka-operator
    newTag: "0.2.0"
```

The sidecar needs one more step. It appears in no manifest, because the operator injects it beside every
Python service, so the `images:` block never sees it. The operator learns its name from the
`ANKKA_SIDECAR_IMAGE` variable on its own Deployment; patch that to the same registry and tag. Unset, a
Python service fails with `operator has no sidecar image` rather than run a sidecar of the wrong version.

Pin a release tag rather than `latest`. A cluster should run a version that was built, tested and
published as one.

## Apply it, in order

```bash
kubectl config current-context       # confirm it is the cluster you mean
kubectl apply -k kustomization/components/cnpg --server-side --force-conflicts
kubectl apply -k kustomization/components/certmanager --server-side --force-conflicts
kubectl apply -k kustomization/components/envoy-gateway --server-side --force-conflicts
# wait for the three controllers to be ready; install a DNS-01 webhook solver here if yours needs one
kubectl apply -k kustomization/components/keycloak-operator --server-side --force-conflicts
kubectl create namespace ankka-controlplane
kubectl create namespace ankka-auth
kubectl -n ankka-auth create secret generic ankka-keycloak-admin ...   # see above
kubectl apply -k kustomization/overlays/<your-overlay> --server-side --force-conflicts
```

The controllers go first because each brings custom resource definitions that the overlay's resources
are instances of, and a single `kubectl apply -k` gives no ordering guarantee between a definition and a
resource of that kind. Apply with `--server-side`: CloudNativePG's definitions are too large for
client-side apply's annotation.

The overlay carries everything else the platform needs, so the same apply works by hand and from a
GitOps tool such as Flux. The control plane's database schema is a ConfigMap the overlay generates from
the schema's single copy, created in the same apply as the Postgres cluster that runs it once at bootstrap.
The realm is a `KeycloakRealmImport` in the overlay, which the Keycloak operator imports once Keycloak is
ready.

`kubectl apply` of many documents applies everything it can and exits non-zero if anything failed.
Check the exit code, not the count of lines that say `applied`.

The realm import is one-shot: it creates the `ankka` realm if it does not exist and never updates it,
so a later change to `kustomization/components/keycloak/realm-import.json` is made in Keycloak's console
on an installation that already has the realm. Create the first users there too; the realm holds none.

## After installing

```bash
ankka config set url https://api.example.com
ankka login
```

A publicly trusted certificate needs no `config set ca`. Grant the first administrator the
`platform-admin` realm role in Keycloak's console, then create organizations and projects. See
[Organizations, projects and members](organizations.md).

Upgrading the installation is the same apply with new image tags. The operator, control plane, sidecar
and CLI are released together as one version.

## A spoke installation

Several installations can share one set of users. One of them, the hub, runs Keycloak as described on
this page. The others, the spokes, run the control plane and the operator with no identity provider of
their own, and trust the hub's realm. A person registered once at the hub logs in to every spoke with the
same account.

A spoke's overlay is a copy of the production overlay with three differences:

- It leaves out the `keycloak-operator` and `keycloak` components, and the patch that deletes the Keycloak
  administrator Secret, since there is no Keycloak to administer.
- It sets two variables on the control plane Deployment, both naming the hub's realm by its public
  address:

  ```yaml
  - name: ANKKA_AUTH_ISSUER
    value: https://auth.example.com/realms/ankka
  - name: ANKKA_AUTH_JWKS_URL
    value: https://auth.example.com/realms/ankka/protocol/openid-connect/certs
  ```

- It keeps its own base domain, for example `caladan.example.com`, so its control plane answers at
  `api.caladan.example.com` and its exposed services have hostnames under that domain.

The spoke accepts only tokens whose issuer is the hub's; a token issued anywhere else is refused with
`401`. Its login discovery advertises the hub's issuer, so `ankka login` against the spoke's address opens
the hub's sign-in page. A `platform-admin` in the hub's realm is a platform administrator on every
installation that trusts it. See [Identity and machine accounts](identity.md#the-issuer-the-control-plane-expects).
