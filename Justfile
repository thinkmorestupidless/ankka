# Shortcuts for the things this repository asks you to do more than once.
#
# Deliberately thin: every recipe is one command, or a call to the script that already owns the
# logic. Nothing here knows *how* to deploy — `kustomization/deploy-local.sh` does, and it holds
# the guards (it refuses a kubectl context that is not this kind cluster, and a cluster that does
# not publish the gateway's ports). A Justfile that reimplemented any of that would be a second
# copy to keep in step, and the README already documents the underlying commands for anyone
# without `just`.

cluster := env_var_or_default("ANKKA_KIND_CLUSTER", "ankka")

# List the recipes.
default:
    @just --list --unsorted

# ── The local cluster ───────────────────────────────────────────────────────

# Create the kind cluster, with the gateway's ports published on the host.
cluster-create:
    kind create cluster --name {{cluster}} --config kustomization/kind.yaml

# Delete the kind cluster and everything in it.
cluster-delete:
    kind delete cluster --name {{cluster}}

# Is there a cluster, and is anything running in it?
cluster-status:
    @kind get clusters 2>/dev/null | grep -qx {{cluster}} \
        && echo "cluster '{{cluster}}' exists" \
        || echo "no cluster named '{{cluster}}' — 'just cluster-create'"
    @kubectl --context kind-{{cluster}} get pods -A 2>/dev/null \
        | grep -E 'ankka|cnpg|cert-manager|envoy' || true

# Build the images, load them into the cluster and apply every manifest.
deploy:
    ./kustomization/deploy-local.sh

# Create the cluster if it is not there, then deploy. The whole thing, from nothing.
up:
    @kind get clusters 2>/dev/null | grep -qx {{cluster}} || just cluster-create
    @kubectl config use-context kind-{{cluster}} >/dev/null
    @just deploy

# Delete the cluster and stop the local database — back to nothing.
down:
    -kind delete cluster --name {{cluster}}
    -docker compose down

# Render an overlay without applying it — `just render remote` before touching a real cluster.
render overlay="local":
    kubectl kustomize kustomization/overlays/{{overlay}}

# ── Running a service on this machine ───────────────────────────────────────

# Postgres for the samples: journal, views, timers, offsets.
db-up:
    docker compose up -d

db-down:
    docker compose down

# The shopping cart sample, on :9000. Needs `just db-up`.
run:
    sbt shoppingCart/run

# Build the CLI to cli/target/universal/stage/bin/ankka.
cli:
    sbt cli/stage

# The local console, over whatever services are running on this machine.
console: cli
    cli/target/universal/stage/bin/ankka local console

# ── Building and testing ────────────────────────────────────────────────────

# Everything except the suites that start a Kubernetes cluster. About a minute.
test:
    sbt -Dankka.cluster.tests=off test

# Everything, including the k3s suites. An hour, so hold the machine awake.
test-all:
    caffeinate -i sbt test

fmt:
    sbt scalafmtAll scalafmtSbt

# Refuse unformatted commits before CI does; once per clone. Needs `cs install scalafmt` to be fast.
hooks:
    git config core.hooksPath .githooks

# The operator, the control plane and the sample, into the local Docker daemon.
images:
    sbt docker:publishLocal
