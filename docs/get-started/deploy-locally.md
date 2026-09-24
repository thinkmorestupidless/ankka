---
title: Deploy to a local platform
description: Run the whole build, deploy and observe cycle on your own machine — a kind cluster with the ankka platform, your service deployed and exposed over HTTPS, its logs, a restart and its history.
kind: tutorial
languages: [scala, python]
related: [platform/install-local.md, deploy/deploy-a-service.md, deploy/expose.md, operate/status-and-history.md]
---

# Deploy to a local platform

This tutorial takes a service from your machine to a running, exposed deployment on a local ankka
platform, and then operates it: reading its logs, restarting it and reading who did what. It uses the
`orders` service from [Your first service in Scala](first-service-scala.md); a Python service differs in
two places, both shown.

You need Docker, kind, `kubectl`, sbt and the `ankka` CLI, all from [Install the tools](install.md). The
platform runs in a kind cluster, which is a Kubernetes cluster inside a Docker container. It needs a few
gigabytes of memory for Docker, and the first deploy takes several minutes.

## Create the platform

From the root of the ankka repository, create the cluster and deploy the platform into it:

```bash
kind create cluster --name ankka --config kustomization/kind.yaml
./kustomization/deploy-local.sh
```

`just up` does both. The cluster must be created from `kustomization/kind.yaml`, because that file
publishes the platform gateway's ports on your machine, 8080 and 8443, and kind can only do that when a
cluster is created. The deploy script refuses a cluster without them, and refuses any `kubectl` context
other than `kind-ankka`, so it can never touch a real cluster by accident.

The script installs what the platform stands on — CloudNativePG for databases, cert-manager for
certificates, Envoy Gateway for routing, and Keycloak for identity — builds the platform's images, loads
them into the cluster, and deploys the operator and the control plane. It creates a development user,
`dev` with password `dev`, and exports the local certificate authority's root to `~/.ankka/local-ca.crt`.
It ends by printing the control plane's address. [Install a local platform](../platform/install-local.md)
describes each step.

## Point the CLI at it and log in

```bash
ankka config set url https://api.127.0.0.1.sslip.io:8443
ankka config set ca ~/.ankka/local-ca.crt
ankka login
```

`ankka login` prints an address and a code. Open the address in a browser, enter the code, and sign in
as `dev` with password `dev`. The CLI saves a renewable login for this control plane, and `ankka whoami`
shows who you are.

The CLI trusts the local platform's certificate because you told it where the root is. Nothing on your
machine is asked to trust it, and there is no option to skip verification.

`127.0.0.1.sslip.io` is a public DNS convention that resolves any name ending in an address to that
address, so nothing on your machine needs configuring. If your resolver blocks it, see
[Networking and TLS](../platform/networking.md).

## Create an organization and a project

A service lives in a project, and a project belongs to an organization:

```bash
ankka organizations create acme --name "Acme Corp"
ankka projects create checkout --name Checkout -O acme
ankka config set project checkout
```

Setting the project saves passing `-p checkout` on every service command.
[Organizations, projects and members](../platform/organizations.md) explains who may do what.

## Build the image and load it

In the `orders` project, build its image into your local Docker daemon, then copy it into the cluster:

```bash
sbt Docker/publishLocal                              # orders:<version> and orders:latest
kind load docker-image orders:latest --name ankka
```

The local platform has no image registry. `kind load` puts the image on the cluster's node, and the
platform runs every workload with `imagePullPolicy: IfNotPresent`, so the node uses the loaded image
rather than trying to pull it.

For a Python service, build the image from a Dockerfile that contains your process and the SDK, and load
it the same way. The sidecar is the platform's own image and is already loaded.

## Apply the descriptor

The template made `service.json`, the service's desired state:

```json title="service.json"
{
  "name": "orders",
  "service": {
    "image": "orders:latest",
    "runtime": "0.2.0"
  }
}
```

`runtime` is the ankka version the service was built against. The template fills it in from the version
it was created with, and the platform refuses one outside its supported range.
[Upgrade ankka](../deploy/upgrading.md) states the rule.

A Python service's descriptor names how it is hosted and which protocol its SDK speaks:

```json title="service.json"
{
  "name": "cart",
  "service": {
    "image": "cart:latest",
    "hosting": "process",
    "protocol": "1.0"
  }
}
```

Apply it and watch the service come up:

```bash
ankka services apply -f service.json
ankka services list
# NAME    STATUS            INSTANCES  GEN  IMAGE
# orders  UpdateInProgress  0/1        1    orders:latest
```

Run `ankka services list` again after a minute:

```text
NAME    STATUS  INSTANCES  GEN  IMAGE
orders  Ready   1/1        1    orders:latest
```

That descriptor is the whole of it: an image, and nothing about databases or ports. The platform gave the
service a Postgres database of its own with ankka's schema applied, an address inside the cluster, and a
place in its own cluster of instances. It reports `Ready` only once the instance has joined that cluster
and bound its HTTP port. `ankka services get orders` shows the full status, including the database the
platform provisioned. [Service lifecycle states](../reference/lifecycle-states.md) lists every status.

## Expose it

A service is private by default: reachable inside the cluster and nowhere else. Exposing it is a separate
decision:

```bash
ankka services expose orders
# https://orders-checkout.127.0.0.1.sslip.io:8443
```

The hostname is derived by the platform as `<service>-<project>.<base domain>`. Call it over HTTPS with
the local root:

```bash
curl --cacert ~/.ankka/local-ca.crt -XPOST https://orders-checkout.127.0.0.1.sslip.io:8443/items/i1 \
     -H 'content-type: application/json' -d '{"name":"Widget","count":2}'
curl --cacert ~/.ankka/local-ca.crt https://orders-checkout.127.0.0.1.sslip.io:8443/items/i1
# {"id":"i1","name":"Widget","count":2}
```

Exposure changes who can reach an endpoint, not who is allowed to call it. The template's endpoint
declares `Acl.AllowAll`, which is fine here and means "anyone on the internet" on a real installation.
Decide each endpoint's ACL before exposing it; [HTTP endpoints](../build/http-endpoints.md) shows how.

## Read its logs

```bash
ankka services logs orders
ankka services logs orders --tail 50
ankka services logs orders --previous      # the container before the last restart
```

The CLI reads what Kubernetes holds for the service's instances at the moment you ask. It is not a log
store: there is no search and no retention beyond the current and previous container.
[Logs](../operate/logs.md) says more.

## Restart it

```bash
ankka services restart orders
ankka services list
curl --cacert ~/.ankka/local-ca.crt https://orders-checkout.127.0.0.1.sslip.io:8443/items/i1
# {"id":"i1","name":"Widget","count":2}
```

A restart replaces every instance, one at a time. Each new instance joins the running cluster and takes
over its share of entities before an old one stops, so the URL keeps answering throughout and the item is
the same item: its events are in the service's database, not in the instance.

## Read its history

```bash
ankka services history orders
```

Every change to a service is recorded with who asked for it and when: the apply, the expose, the restart.
The control plane keeps its own state as event sourced entities, so its journal is the audit trail.

## Change it and deploy again

Change the code, rebuild the image, load it and apply again:

```bash
sbt Docker/publishLocal
kind load docker-image orders:latest --name ankka
ankka services restart orders
```

The descriptor still says `orders:latest`, so applying it again changes nothing Kubernetes can see, and
the running instances would keep the image they started with. `restart` rolls them onto the image just
loaded. With a registry you would tag each build with its own version, change the descriptor's `image`,
and `apply`, which rolls the instances by itself.
[Scale and roll out](../deploy/scaling-and-rollouts.md) covers both.

## Clean up

```bash
ankka services unexpose orders     # the hostname stops answering; nothing else changes
ankka services delete orders       # the instances go; the database is kept
kind delete cluster --name ankka   # everything
```

Deleting a service never deletes its database. Applying a descriptor with the same name later recovers the
data. Deleting the kind cluster removes everything, databases included.
