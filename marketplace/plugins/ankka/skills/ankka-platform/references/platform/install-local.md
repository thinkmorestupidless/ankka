# Install a local platform

> Run the whole ankka platform on your own machine in a kind cluster — operator, control plane, identity provider, databases, gateway and TLS — with one script, and point the CLI at it.

Source: https://docs.ankka.cloud/platform/install-local/
A complete ankka platform runs on a laptop in a [kind](https://kind.sigs.k8s.io/) cluster. One script,
`kustomization/deploy-local.sh` in the ankka repository, builds the platform's images, loads them into
the cluster, installs everything the platform depends on and starts it. The result answers at
`https://api.127.0.0.1.sslip.io:8443`, over TLS, with no port-forward.

The local platform is the same platform a cloud cluster runs, with three local choices: images are loaded
into the node rather than pulled from a registry, certificates come from a certificate authority created
inside the cluster, and the base domain resolves to `127.0.0.1`.

## Prerequisites

- Docker, with enough memory for several JVMs; 8 GB for Docker is comfortable.
- [kind](https://kind.sigs.k8s.io/) and `kubectl`.
- A JDK 21 and sbt, because the script builds the platform's images from source.
- A checkout of the ankka repository. Every command on this page runs from its root.
- Optionally [just](https://github.com/casey/just), for the short forms of the commands.

## Create the cluster

```bash
kind create cluster --name ankka --config kustomization/kind.yaml
```

The configuration file matters. It publishes the gateway's node ports on your machine, container port
30080 on host port 8080 and 30443 on 8443, and kind decides port mappings only when it creates a
cluster: they cannot be added later. The deploy script checks for them and refuses a cluster created
without them, naming the fix, because such a cluster would deploy cleanly and then fail every request by
hostname.

## Deploy the platform

```bash
./kustomization/deploy-local.sh
```

Or, with `just`, create the cluster if it is not there and deploy in one step:

```bash
just up
```

The script takes several minutes on its first run. In order, it:

1. **Checks where it is pointed.** It refuses to run unless the current `kubectl` context is
   `kind-ankka`, and unless that cluster publishes the gateway's ports. It never deploys to whatever
   context happens to be current.
2. **Installs the controllers the platform builds on**, each with server-side apply and waiting for
   each to be ready: [CloudNativePG](https://cloudnative-pg.io/) for Postgres,
   [cert-manager](https://cert-manager.io/) for certificates,
   [Envoy Gateway](https://gateway.envoyproxy.io/) for the gateway, and the Keycloak operator for the
   identity provider. Their custom resource definitions must exist before anything that uses them is
   applied.
3. **Builds the platform's images** with `sbt docker:publishLocal`: the operator, the control plane, the
   sidecar that hosts services in other languages, and the shopping cart sample.
4. **Loads them into the cluster's node** with `kind load docker-image`. No registry is involved.
5. **Applies the `AnkkaService` custom resource definition**, then the control plane's namespace.
6. **Applies everything else** with one `kubectl apply -k` of the local overlay: the operator, the control
   plane with its own Postgres cluster and the schema that cluster is created with, the installation's
   gateway with a local certificate authority and a wildcard certificate, and Keycloak with its database
   and the `ankka` realm's import. The overlay is the whole platform, so applying it any other way — by
   hand, or from a GitOps tool — installs the same thing.
7. **Restarts the operator and the control plane** so they run the images just loaded, even when nothing
   in their manifests changed.
8. **Waits** for the control plane's database, the operator, the control plane's three instances, the
   gateway and its certificate, and Keycloak.
9. **Waits for the `ankka` realm to be imported** into Keycloak, then creates a development user `dev`
   with password `dev` and the `platform-admin` role, and a client `ankka-local-smoke` for scripts on this
   machine. The user and the client are the only things the script adds beyond the overlay, and they
   exist only on a local cluster.
10. **Exports the local certificate authority's root** to `~/.ankka/local-ca.crt`.
11. **Checks the platform end to end**: it obtains a token from the identity provider and lists
    organizations through the gateway, which exercises DNS, TLS, both routes, token verification and the
    control plane's database. A problem is printed as a warning naming the likely cause.

It ends by printing the control plane's address and the commands to use it.

## Point the CLI at it

```bash
ankka config set url https://api.127.0.0.1.sslip.io:8443
ankka config set ca ~/.ankka/local-ca.crt
ankka login                      # user dev, password dev, in a browser
```

Nothing on your machine is asked to trust the local certificate authority. The CLI is told about it
with `config set ca`, and `curl` with `--cacert`. There is no option anywhere to skip certificate
verification.

The identity provider's console is at `https://auth.127.0.0.1.sslip.io:8443/admin/`, as `admin` with
password `admin`. Those credentials are public and belong to the local platform only; a cloud
installation removes them. Add users there. See [Identity and machine accounts](identity.md).

From here, [Deploy to a local platform](../get-started/deploy-locally.md) deploys the sample.

## Use another base domain

The base domain `127.0.0.1.sslip.io` relies on a public DNS service that resolves any name ending in an
address to that address. If your resolver blocks it, add hosts-file entries and deploy with a matching
base domain:

```bash
echo '127.0.0.1  api.ankka.local auth.ankka.local cart-checkout.ankka.local' | sudo tee -a /etc/hosts
ANKKA_BASE_DOMAIN=ankka.local ./kustomization/deploy-local.sh
```

A hosts file has no wildcards, so every hostname you use, including `auth.<base domain>` for logins and
each exposed service, needs a line.

## Run it again

The script is safe to run again, and running it again is how the platform picks up a change to its
source: it rebuilds and reloads the images and restarts the operator and the control plane onto them.
Without that restart a re-run would change nothing running, because the manifests and the `latest` tags
are the same as before and Kubernetes sees no difference.

Some things are created once and not updated by a re-run. The Keycloak realm is imported only if it does
not exist, so a change to the realm file on an existing cluster is made in Keycloak's console.

## Tear it down

```bash
just down                        # delete the kind cluster and stop the local compose services
kind delete cluster --name ankka # the same, without just
```

Deleting the cluster deletes every service and every database in it.

## The shortcuts

| Command | Does |
|---|---|
| `just up` | create the cluster if it does not exist, then deploy |
| `just deploy` | run `deploy-local.sh` |
| `just cluster-create` | create the cluster from `kustomization/kind.yaml` |
| `just cluster-status` | whether the cluster exists and what platform pods are running |
| `just down` | delete the cluster and stop the local compose services |
| `just render` | print the local overlay's manifests without applying them |

Each is one command, or a call to the script, which holds the guards. Everything works without `just`
installed.
