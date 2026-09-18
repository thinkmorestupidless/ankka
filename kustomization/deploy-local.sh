#!/usr/bin/env bash
# Builds, loads and deploys nakka to a local Kubernetes cluster — no image registry.
#
# What "no registry" actually means here: sbt-native-packager builds images tagged
# nakka-operator:latest / nakka-controlplane:latest / sample-shopping-cart:latest straight into the
# local Docker daemon
# (see build.sbt's dockerSettings — DOCKER_REPOSITORY is unset), `kind load docker-image`
# copies it directly into the cluster's node, and every Deployment uses
# `imagePullPolicy: IfNotPresent` so the node never tries to pull it from anywhere. Setting
# DOCKER_REPOSITORY and pointing kustomize at a different cluster is the whole migration once a
# registry exists — nothing else here changes.
#
# Requires: a kind cluster already exists and is the current kubectl context. This script
# refuses to run against anything else, on purpose — see the guard below.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

CLUSTER_NAME="${NAKKA_KIND_CLUSTER:-nakka}"
CONTEXT="kind-${CLUSTER_NAME}"

current_context="$(kubectl config current-context)"
if [[ "$current_context" != "$CONTEXT" ]]; then
  echo "refusing to deploy: current kubectl context is '$current_context', expected '$CONTEXT'." >&2
  echo "this script only ever targets a local kind cluster, deliberately — switch context with:" >&2
  echo "  kubectl config use-context $CONTEXT" >&2
  echo "or create one with:" >&2
  echo "  kind create cluster --name $CLUSTER_NAME" >&2
  exit 1
fi

echo "==> installing CloudNativePG"
# Applied directly, not only through the overlay: its CRDs must exist before anything in
# overlays/local that references a Cluster/Database/DatabaseRole is applied in the same pass,
# and kubectl apply -k gives no ordering guarantee between a CRD and a CR of that kind within
# one invocation. Also listed in the overlay itself so `kubectl apply -k overlays/local` stays
# complete on its own for anyone running it directly.
#
# --server-side, not client-side: CNPG's own install docs specify this, because its CRDs embed
# a large enough OpenAPI schema that client-side apply's kubectl.kubernetes.io/last-applied-
# configuration annotation exceeds Kubernetes' annotation size limit. Discovered the hard way —
# client-side apply here fails with "metadata.annotations: Too long" on the largest CRDs.
kubectl apply -k kustomization/components/cnpg --server-side --force-conflicts
kubectl -n cnpg-system rollout status deployment/cnpg-controller-manager --timeout=90s

echo "==> building images"
# Root-level, not per-project: docker:publishLocal aggregates to every project with
# DockerPlugin enabled (operator, controlPlane, shoppingCart) and silently skips the rest, the same way
# `sbt compile` and `sbt test` already do. Nothing here has to change if a third image is
# ever added.
sbt -batch "docker:publishLocal"

echo "==> loading images into $CONTEXT"
kind load docker-image nakka-operator:latest --name "$CLUSTER_NAME"
kind load docker-image nakka-controlplane:latest --name "$CLUSTER_NAME"
# Not part of the platform — the sample, so that `nakka services apply` has a real nakka service to
# deploy the moment this script finishes. The operator renders imagePullPolicy IfNotPresent on
# every workload, which is what lets an image loaded this way be used at all: Kubernetes' default
# for a :latest tag is Always, which ignores it and fails with ErrImagePull.
kind load docker-image sample-shopping-cart:latest --name "$CLUSTER_NAME"

echo "==> applying the CRD (must exist before anything references it)"
kubectl apply -f kustomization/components/crd/nakkaservice.yaml

echo "==> creating the control plane's namespace, if it does not exist yet"
kubectl create namespace nakka-controlplane --dry-run=client -o yaml | kubectl apply -f -

echo "==> generating the control plane's schema ConfigMap from the single-copy DDL"
# Not a kustomize configMapGenerator: kustomize's load restrictor rejects a file reference
# that resolves outside a component's own directory, with no override available through
# `kubectl apply -k` — so a generator here would force either a real duplicate of the DDL or
# a broken build. `kubectl create configmap --from-file` has no such restriction.
#
# Must exist before the Cluster below: CNPG applies postInitApplicationSQLRefs once, at
# bootstrap, so the ConfigMap has to be there the moment the Cluster is first created.
#
# The 99-grants.sql key is not part of the single-copy DDL — it exists only because CNPG runs
# postInitApplicationSQLRefs as the "postgres" superuser, not as the application's own "nakka"
# owner role, so every table the DDL creates ends up owned by "postgres" and unreadable by the
# role the control plane actually connects as. Discovered by deploying this for real: the
# control plane started cleanly, then every write timed out with "permission denied for table
# projection_management" the first time it touched its own journal.
kubectl create configmap nakka-controlplane-schema \
  --from-file=modules/runtime/src/main/resources/nakka/ddl \
  --from-literal=99-grants.sql="GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO nakka; GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO nakka;" \
  --namespace nakka-controlplane \
  --dry-run=client -o yaml | kubectl apply -f -

echo "==> applying everything else"
# --server-side throughout, now that the overlay includes CNPG's large CRDs too (see the note
# above the dedicated CNPG install step) — consistent with server-side apply being what every
# component in this design already uses against the real cluster.
kubectl apply -k kustomization/overlays/local --server-side --force-conflicts

echo "==> restarting the operator and control plane onto the images just loaded"
# Without this a *re*-run of this script changes nothing that is running. The manifests are
# unchanged and the tag is still :latest, so `kubectl apply` sees no difference, the Deployments do
# not roll, and the pods keep executing whatever image they started with — while every line of
# output reports success. Found by checking pod start times after a rebuild: five hours old.
# Harmless on a first run, where it merely restarts pods that have just started.
kubectl -n nakka-operator rollout restart deployment/nakka-operator
kubectl -n nakka-controlplane rollout restart deployment/nakka-controlplane

echo "==> waiting for the control plane's database"
kubectl -n nakka-controlplane wait --for=jsonpath='{.status.readyInstances}'=1 cluster/nakka-controlplane-db --timeout=180s

echo "==> waiting for the operator"
kubectl -n nakka-operator rollout status deployment/nakka-operator --timeout=120s

echo "==> waiting for the control plane"
# Three instances rolled one at a time (feature 004), each a JVM that has to join the cluster
# before it counts — so a longer wait than one pod needed.
kubectl -n nakka-controlplane rollout status deployment/nakka-controlplane --timeout=420s

cat <<MSG

Deployed. Try:

  kubectl -n nakka-controlplane port-forward svc/nakka-controlplane 9000:9000 &
  nakka config set url http://localhost:9000
  nakka config set token dev-local-token
  nakka organizations create acme --name "Acme Corp"
  nakka projects create checkout --name Checkout -O acme
  nakka config set project checkout
  echo '{"name":"cart","service":{"image":"sample-shopping-cart:latest"}}' > cart.json
  nakka services apply -f cart.json
  nakka services list
  kubectl -n nakka-checkout get nsvc,deploy,svc,pods

  kubectl -n nakka-checkout port-forward svc/cart 8080:9000 &
  curl -XPOST localhost:8080/carts/c1/items -H 'content-type: application/json' \\
       -d '{"productId":"p1","name":"Widget","quantity":2}'
  curl localhost:8080/carts/c1

MSG
