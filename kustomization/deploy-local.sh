#!/usr/bin/env bash
# Builds, loads and deploys ankka to a local Kubernetes cluster — no image registry.
#
# What "no registry" actually means here: sbt-native-packager builds images tagged
# ankka-operator:latest / ankka-controlplane:latest / sample-shopping-cart:latest straight into the
# local Docker daemon
# (see build.sbt's dockerSettings — DOCKER_REPOSITORY is unset), `kind load docker-image`
# copies it directly into the cluster's node, and every Deployment uses
# `imagePullPolicy: IfNotPresent` so the node never tries to pull it from anywhere. Setting
# DOCKER_REPOSITORY and pointing kustomize at a different cluster is the whole migration once a
# registry exists — nothing else here changes.
#
# Requires: a kind cluster created from kustomization/kind.yaml and current as the kubectl context.
# This script refuses to run against anything else, on purpose — see the guards below.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

CLUSTER_NAME="${ANKKA_KIND_CLUSTER:-ankka}"
CONTEXT="kind-${CLUSTER_NAME}"

current_context="$(kubectl config current-context)"
if [[ "$current_context" != "$CONTEXT" ]]; then
  echo "refusing to deploy: current kubectl context is '$current_context', expected '$CONTEXT'." >&2
  echo "this script only ever targets a local kind cluster, deliberately — switch context with:" >&2
  echo "  kubectl config use-context $CONTEXT" >&2
  echo "or create one with:" >&2
  echo "  kind create cluster --name $CLUSTER_NAME --config kustomization/kind.yaml" >&2
  exit 1
fi

# The gateway's NodePorts must be published on the host, and kind decides that at creation
# (kustomization/kind.yaml). A cluster made with the old one-liner deploys cleanly and then every
# hostname fails to connect — so refuse now, naming the fix, rather than succeed uselessly.
# `|| true` inside the substitutions: `docker port` exits non-zero when a port is not published, and
# under `set -e` that would end the script silently, before the message below.
HTTPS_HOST_PORT="$( { docker port "${CLUSTER_NAME}-control-plane" 30443/tcp 2>/dev/null || true; } | head -1 | sed 's/.*://')"
HTTP_HOST_PORT="$( { docker port "${CLUSTER_NAME}-control-plane" 30080/tcp 2>/dev/null || true; } | head -1 | sed 's/.*://')"
if [[ -z "$HTTPS_HOST_PORT" || -z "$HTTP_HOST_PORT" ]]; then
  echo "refusing to deploy: cluster '$CLUSTER_NAME' does not publish the gateway's ports (30080/30443)." >&2
  echo "port mappings are decided when a kind cluster is created and cannot be added later. Recreate it:" >&2
  echo "  kind delete cluster --name $CLUSTER_NAME" >&2
  echo "  kind create cluster --name $CLUSTER_NAME --config kustomization/kind.yaml" >&2
  exit 1
fi

BASE_DOMAIN="${ANKKA_BASE_DOMAIN:-127.0.0.1.sslip.io}"

# Network policy must be enforced, not merely accepted (feature 014). Every API server admits a
# NetworkPolicy; only a network that implements them refuses anything, and on one that does not, the
# platform's isolation is a set of objects that do nothing. kind has enforced policy since 0.24.
# Proven rather than assumed: a deny-all policy on a pod, and a connection to it that must fail.
KIND_VERSION="$(kind version 2>/dev/null | sed -n 's/^kind v\([0-9]*\.[0-9]*\).*/\1/p')"
if [[ -n "$KIND_VERSION" ]] && awk -v v="$KIND_VERSION" 'BEGIN { split(v, p, "."); exit !(p[1] == 0 && p[2] < 24) }'; then
  echo "refusing to deploy: kind $KIND_VERSION does not enforce network policy; ankka needs a network that does." >&2
  echo "upgrade kind to 0.24 or later and recreate the cluster (see docs/platform/install-local.md)." >&2
  exit 1
fi
# shellcheck source=kustomization/netpol-probe.sh
source kustomization/netpol-probe.sh
probe_status=0
netpol_enforced || probe_status=$?
if [[ $probe_status -eq 1 ]]; then
  echo "refusing to deploy: this cluster accepted a NetworkPolicy and did not enforce it; ankka needs a" >&2
  echo "network that does (kind 0.24 or later does; see docs/platform/install-local.md)." >&2
  exit 1
elif [[ $probe_status -ne 0 ]]; then
  echo "refusing to deploy: could not check that this cluster enforces network policy (the reason is" >&2
  echo "above); run the script again once the cluster is healthy." >&2
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

echo "==> installing cert-manager and Envoy Gateway"
# Same reason as CNPG: their CRDs must exist before the overlay's Certificates, Gateway and
# HTTPRoutes are applied in one pass. cert-manager's webhook must also be *serving* before a
# Certificate can be admitted, hence the rollout waits.
kubectl apply -k kustomization/components/certmanager --server-side --force-conflicts
kubectl apply -k kustomization/components/envoy-gateway --server-side --force-conflicts

echo "==> installing the Keycloak operator"
# The fourth CRD-bearing controller (feature 008): its Keycloak and KeycloakRealmImport CRDs must
# exist before the overlay's Keycloak resource is an instance of one. The overlay lists this
# component too, so the second apply is a no-op.
kubectl apply -k kustomization/components/keycloak-operator --server-side --force-conflicts
kubectl -n cert-manager rollout status deployment/cert-manager-webhook --timeout=180s
kubectl -n envoy-gateway-system rollout status deployment/envoy-gateway --timeout=180s

echo "==> installing trust-manager"
# After cert-manager, whose Issuer serves its webhook's certificate; before the overlay, whose
# Bundle it admits (feature 014). The Bundle is how the service authority's root reaches every
# ankka namespace as the ConfigMap the gateway verifies services with.
kubectl apply -k kustomization/components/trust-manager --server-side --force-conflicts
kubectl -n cert-manager rollout status deployment/trust-manager --timeout=180s

echo "==> installing Strimzi"
# The installation's broker is a Kafka run by Strimzi, and Strimzi's CRDs must exist before the
# overlay's Kafka, KafkaNodePool and the operator's KafkaTopics and KafkaUsers are instances of one
# — the same reason as CNPG, and server-side for the same annotation limit. The broker component
# lists ./strimzi too, so the overlay's apply is a no-op over it.
kubectl apply -f kustomization/components/broker/namespace.yaml --server-side --force-conflicts
kubectl apply -k kustomization/components/broker/strimzi --server-side --force-conflicts
kubectl -n ankka-broker rollout status deployment/strimzi-cluster-operator --timeout=300s

echo "==> building images"
# Root-level, not per-project: docker:publishLocal aggregates to every project with
# DockerPlugin enabled (operator, controlPlane, shoppingCart) and silently skips the rest, the same way
# `sbt compile` and `sbt test` already do. Nothing here has to change if a third image is
# ever added.
sbt -batch "docker:publishLocal"
docker build -t ankka-console:latest console
# The shopping cart's interface (feature 021), a web-hosted service: a Node image, built by Docker.
docker build -t sample-shopping-cart-web:latest samples/shopping-cart-web

echo "==> loading images into $CONTEXT"
kind load docker-image ankka-operator:latest --name "$CLUSTER_NAME"
kind load docker-image ankka-controlplane:latest --name "$CLUSTER_NAME"
# Not part of the platform — the sample, so that `ankka services apply` has a real ankka service to
# deploy the moment this script finishes. The operator renders imagePullPolicy IfNotPresent on
# every workload, which is what lets an image loaded this way be used at all: Kubernetes' default
# for a :latest tag is Always, which ignores it and fails with ErrImagePull.
kind load docker-image sample-shopping-cart:latest --name "$CLUSTER_NAME"
# The sidecar for services in another language (feature 009), the operator's to inject.
kind load docker-image ankka-sidecar:latest --name "$CLUSTER_NAME"
# The proxy the operator runs beside every web-hosted service's process (feature 021), the
# operator's to inject as the sidecar is.
kind load docker-image ankka-proxy:latest --name "$CLUSTER_NAME"
# The installation's console (feature 017): a Node image, built by Docker rather than sbt.
kind load docker-image ankka-console:latest --name "$CLUSTER_NAME"
# Not part of the platform: the sample's interface, so `ankka services apply -f
# samples/shopping-cart-web/service.json` has an image to run.
kind load docker-image sample-shopping-cart-web:latest --name "$CLUSTER_NAME"

echo "==> applying the CRD (must exist before anything references it)"
kubectl apply -f kustomization/components/crd/ankkaservice.yaml
kubectl apply -f kustomization/components/crd/cloudresource.yaml

echo "==> creating the control plane's namespace, if it does not exist yet"
kubectl create namespace ankka-controlplane --dry-run=client -o yaml | kubectl apply -f -

# The control plane's schema ConfigMap is generated by the postgres component's
# configMapGenerator from the DDL's canonical copy in that component, so `kubectl apply -k`
# below carries it — on kind and on every cluster Flux reconciles alike. It used to be created
# here with `kubectl create configmap`, which meant the overlay only worked from this script.

# Moving the control plane to mutual TLS (feature 014) cannot be a rolling update: a TLS instance and
# a plain one cannot join each other, so the new pods would wait forever for peers. The operator does
# this for every service it deploys; the control plane is applied here, so this does it for that one.
# Once, and only when the running template predates the transport label.
if kubectl -n ankka-controlplane get deployment ankka-controlplane >/dev/null 2>&1; then
  transport="$(kubectl -n ankka-controlplane get deployment ankka-controlplane \
    -o jsonpath='{.spec.template.metadata.labels.ankka\.thinkmorestupidless\.com/transport}')"
  if [[ "$transport" != "tls" ]]; then
    echo "==> the control plane predates mutual TLS: stopping its instances before applying, once"
    kubectl -n ankka-controlplane delete deployment ankka-controlplane --wait=true
    kubectl -n ankka-controlplane wait --for=delete pod -l app.kubernetes.io/name=ankka-controlplane --timeout=120s || true
  fi
fi

echo "==> applying everything else"
# --server-side throughout, now that the overlay includes CNPG's large CRDs too (see the note
# above the dedicated CNPG install step) — consistent with server-side apply being what every
# component in this design already uses against the real cluster.
#
# The base domain and the HTTPS host port are written once, into the ankka-platform ConfigMap, and
# kustomize copies them everywhere they must agree (overlays/local/kustomization.yaml). Overriding
# ANKKA_BASE_DOMAIN here is how a machine whose resolver blocks sslip.io uses a hosts-file name.
# The two substitutions are anchored to the exact lines kustomize produces from the ConfigMap —
# the base domain wherever it was fanned out, and the redirect's port — so nothing else in the
# rendered manifests (which include cert-manager's and Envoy Gateway's) can be touched by accident.
kubectl kustomize kustomization/overlays/local \
  | sed -e "s|127\.0\.0\.1\.sslip\.io|${BASE_DOMAIN}|g" \
        -e "s|^\(  httpsPort: \)\"8443\"|\1\"${HTTPS_HOST_PORT}\"|" \
        -e "s|^\(        port: \)8443$|\1${HTTPS_HOST_PORT}|" \
        -e "s|^\(          value: \)\"8443\"$|\1\"${HTTPS_HOST_PORT}\"|" \
        -e "s|console\.${BASE_DOMAIN}:8443|console.${BASE_DOMAIN}:${HTTPS_HOST_PORT}|g" \
  | kubectl apply -f - --server-side --force-conflicts

echo "==> restarting the operator and control plane onto the images just loaded"
# Without this a *re*-run of this script changes nothing that is running. The manifests are
# unchanged and the tag is still :latest, so `kubectl apply` sees no difference, the Deployments do
# not roll, and the pods keep executing whatever image they started with — while every line of
# output reports success. Found by checking pod start times after a rebuild: five hours old.
# Harmless on a first run, where it merely restarts pods that have just started.
kubectl -n ankka-operator rollout restart deployment/ankka-operator
kubectl -n ankka-controlplane rollout restart deployment/ankka-controlplane
kubectl -n ankka-console rollout restart deployment/ankka-console

echo "==> waiting for the control plane's database"
kubectl -n ankka-controlplane wait --for=jsonpath='{.status.readyInstances}'=1 cluster/ankka-controlplane-db --timeout=180s

echo "==> waiting for the operator"
kubectl -n ankka-operator rollout status deployment/ankka-operator --timeout=120s
echo "==> waiting for the object store"
kubectl -n garage-system rollout status statefulset/garage --timeout=180s

echo "==> waiting for the control plane"
# Three instances rolled one at a time (feature 004), each a JVM that has to join the cluster
# before it counts — so a longer wait than one pod needed.
kubectl -n ankka-controlplane rollout status deployment/ankka-controlplane --timeout=420s

echo "==> waiting for the console"
kubectl -n ankka-console rollout status deployment/ankka-console --timeout=300s

echo "==> waiting for the broker"
# A Kafka is a minute or two on a laptop: a JVM, its storage and Strimzi's entity operator.
kubectl -n ankka-broker wait --for=condition=Ready kafka/ankka --timeout=600s

echo "==> waiting for the gateway and its certificate"
kubectl -n ankka-gateway wait --for=condition=Ready certificate/ankka-wildcard --timeout=120s
kubectl -n ankka-gateway wait --for=condition=Programmed gateway/ankka --timeout=120s

echo "==> waiting for the identity provider"
# Keycloak's own database first, then the instance the operator runs from it. Both are slow starts
# — a Postgres bootstrap and a JVM — so the timeouts are generous rather than the waits optional.
kubectl -n ankka-auth wait --for=jsonpath='{.status.readyInstances}'=1 cluster/ankka-keycloak-db --timeout=300s
kubectl -n ankka-auth wait --for=condition=Ready keycloak/ankka-keycloak --timeout=420s

echo "==> waiting for the realm import"
# The KeycloakRealmImport is part of the keycloak component (realm-import.json, the one copy), so
# the apply above already created it; the operator runs the import once the instance is Ready.
# It is one-shot — it creates a realm that does not exist and never updates or deletes one
# (research R2) — so on a re-run this is a no-op and a change to the realm on an existing cluster
# is a console job.
kubectl -n ankka-auth wait --for=condition=Done keycloakrealmimport/ankka-realm --timeout=300s

echo "==> creating the development user and the smoke-test client"
# Through kcadm.sh inside the Keycloak pod, exactly as docker-compose's keycloak-init does, so the
# two local paths cannot drift. The realm file carries no users at all: this script is the only
# thing that creates one, and this script only ever runs against a local kind cluster — which is
# how a remote installation is guaranteed not to have a "dev" user (research R2). Idempotent, so
# a re-run passes.
KCADM="kubectl -n ankka-auth exec statefulset/ankka-keycloak -- /opt/keycloak/bin/kcadm.sh"
KC_ADMIN_USER="$(kubectl -n ankka-auth get secret ankka-keycloak-admin -o jsonpath='{.data.username}' | base64 -d)"
KC_ADMIN_PASSWORD="$(kubectl -n ankka-auth get secret ankka-keycloak-admin -o jsonpath='{.data.password}' | base64 -d)"
$KCADM config credentials --server http://localhost:8080 --realm master --user "$KC_ADMIN_USER" --password "$KC_ADMIN_PASSWORD" >/dev/null
# Whether a kcadm query's output contains a field. The output is read whole before it is searched:
# piped straight into `grep -q`, grep exits at the first match, kubectl exec is killed writing the
# rest (SIGPIPE, status 141), and under `set -o pipefail` the test then reads as "not found". A long
# answer — the console client's, on a fresh cluster whose realm import created it — failed every
# time, and the create that followed stopped this script with "Client ankka-console already exists".
kc_has() { # kc_has '"clientId" : "x"' get clients -r ankka -q clientId=x
  local want="$1"; shift
  local out
  out="$($KCADM "$@")"
  grep -qF "$want" <<<"$out"
}
if kc_has '"username" : "dev"' get users -r ankka -q username=dev -q exact=true; then
  echo "user dev already exists"
else
  $KCADM create users -r ankka -s username=dev -s email="dev@${BASE_DOMAIN}" -s emailVerified=true \
    -s firstName=Dev -s lastName=User -s enabled=true >/dev/null
  $KCADM set-password -r ankka --username dev --new-password dev
  $KCADM add-roles -r ankka --uusername dev --rolename platform-admin
  echo "created user dev (password dev, platform-admin)"
fi
# A confidential client whose service account is a platform admin, for the smoke test below and
# for scripts on this machine: the same shape as a CI client on a real installation.
SMOKE_SECRET="local-smoke-secret"
if kc_has '"clientId" : "ankka-local-smoke"' get clients -r ankka -q clientId=ankka-local-smoke -q exact=true; then
  echo "client ankka-local-smoke already exists"
else
  $KCADM create clients -r ankka -s clientId=ankka-local-smoke -s secret="$SMOKE_SECRET" \
    -s publicClient=false -s standardFlowEnabled=false -s serviceAccountsEnabled=true \
    -s 'defaultClientScopes=["basic","profile","email","roles","ankka-controlplane"]' >/dev/null
  $KCADM add-roles -r ankka --uusername service-account-ankka-local-smoke --rolename platform-admin
  echo "created client ankka-local-smoke (service account, platform-admin)"
fi
# The console's client (feature 017). A realm imported before the console existed does not have it,
# and an import never updates a realm, so it is added the way docs/platform/console.md tells an older
# installation to add it — with the same values realm-import.json gives a new one.
if kc_has '"clientId" : "ankka-console"' get clients -r ankka -q clientId=ankka-console -q exact=true; then
  echo "client ankka-console already exists"
else
  $KCADM create clients -r ankka -s clientId=ankka-console -s 'name=ankka console' -s secret=dev \
    -s publicClient=false -s standardFlowEnabled=true -s directAccessGrantsEnabled=false \
    -s "redirectUris=[\"https://console.${BASE_DOMAIN}:${HTTPS_HOST_PORT}/*\",\"http://localhost:3000/*\"]" \
    -s 'webOrigins=["+"]' -s 'attributes."pkce.code.challenge.method"=S256' \
    -s 'defaultClientScopes=["ankka-controlplane"]' >/dev/null
  echo "created client ankka-console"
fi

echo "==> exporting the local certificate authority"
# The root that signed the wildcard the gateway serves. Nothing on this machine trusts it, and
# nothing is made to: the CLI is told about it (config set ca) and so is curl (--cacert). No step
# here or in the README ever turns verification off.
mkdir -p "$HOME/.ankka"
# In cert-manager's namespace, beside the ClusterIssuer that signs with it (feature 045).
kubectl -n cert-manager get secret ankka-root-ca -o jsonpath='{.data.ca\.crt}' | base64 -d > "$HOME/.ankka/local-ca.crt"

API_URL="https://api.${BASE_DOMAIN}:${HTTPS_HOST_PORT}"
CONSOLE_URL="https://console.${BASE_DOMAIN}:${HTTPS_HOST_PORT}"
GRAFANA_URL="https://grafana.${BASE_DOMAIN}:${HTTPS_HOST_PORT}"

# A real request to a real route, and the status is checked.
#
# This used to curl "$API_URL/health" and test only curl's exit code. There is no /health endpoint
# — the control plane serves /organizations, /projects and /services — so it got a 404, and a 404
# is a *successful* HTTP exchange: curl exits 0 and the check passed. It could still catch a name
# that does not resolve or a TLS failure, which is what the warning below is about, but it would
# have reported a healthy platform just as happily if every route were broken.
#
# Asking for the organizations listing with a real token exercises the whole path: DNS, TLS
# against the exported root, the gateway's route to the identity provider, a client-credentials
# login there, the gateway's route to a control plane pod, its token verification against the
# in-cluster key set, the ACL, and a database query behind it. Nothing is hardcoded: the client was
# created above and the token is minted now.
AUTH_URL="https://auth.${BASE_DOMAIN}:${HTTPS_HOST_PORT}"
TOKEN="$(curl -s --cacert "$HOME/.ankka/local-ca.crt" -m 15 \
  -d grant_type=client_credentials -d client_id=ankka-local-smoke -d "client_secret=${SMOKE_SECRET}" \
  "$AUTH_URL/realms/ankka/protocol/openid-connect/token" 2>/dev/null \
  | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p' || true)"
if [[ -z "$TOKEN" ]]; then
  echo >&2 "warning: could not obtain a token from $AUTH_URL; the control plane check below will report 401."
fi
# `|| true` rather than `|| echo 000`: curl already prints 000 as %{http_code} when it never got
# a response, so a fallback echo appends a *second* 000 and the case below falls through to the
# wrong branch — reporting a broken control plane where the real answer is that the name did not
# resolve. `set -e` is why some guard is needed at all.
STATUS="$(curl -s --cacert "$HOME/.ankka/local-ca.crt" -H "Authorization: Bearer $TOKEN" \
  -o /dev/null -w '%{http_code}' -m 15 "$API_URL/organizations" || true)"
STATUS="${STATUS:-000}"

case "$STATUS" in
  200) ;;
  000)
    cat >&2 <<MSG

warning: $API_URL did not answer from this machine.
  If 'dig +short api.${BASE_DOMAIN}' does not print 127.0.0.1, your resolver blocks sslip.io;
  add a line to /etc/hosts and redeploy with a matching base domain:
    127.0.0.1  api.ankka.local cart-checkout.ankka.local
    ANKKA_BASE_DOMAIN=ankka.local ./kustomization/deploy-local.sh
MSG
    ;;
  401)
    echo >&2 "warning: $API_URL did not accept a token from $AUTH_URL."
    echo >&2 "  The control plane derives the issuer it expects from ANKKA_BASE_DOMAIN and ANKKA_HTTPS_PORT;"
    echo >&2 "  compare it with: curl --cacert ~/.ankka/local-ca.crt $AUTH_URL/realms/ankka/.well-known/openid-configuration"
    ;;
  *)
    echo >&2 "warning: $API_URL answered $STATUS for /organizations, not 200."
    echo >&2 "  kubectl -n ankka-controlplane logs -l app.kubernetes.io/name=ankka-controlplane --tail=50"
    ;;
esac

# The telemetry store: Grafana answering through the gateway (a 200, not merely an exchange), then
# a trace from the control plane in it — which exercises the control plane's exporter, the store's
# network policy and the route at once. Warnings only, like the check above: the platform works
# without a store, and a developer is told what to look at.
echo "==> waiting for the telemetry store"
if ! kubectl -n ankka-telemetry rollout status deployment/lgtm --timeout=300s; then
  echo >&2 "warning: the telemetry store did not become ready; kubectl -n ankka-telemetry describe pod -l app.kubernetes.io/name=lgtm"
fi
GRAFANA_STATUS="$(curl -s --cacert "$HOME/.ankka/local-ca.crt" -o /dev/null -w '%{http_code}' -m 15 \
  "$GRAFANA_URL/api/health" || true)"
if [[ "${GRAFANA_STATUS:-000}" != "200" ]]; then
  echo >&2 "warning: $GRAFANA_URL answered ${GRAFANA_STATUS:-000} for /api/health, not 200."
else
  TRACED=""
  for _ in $(seq 1 30); do
    # Tempo's search, through Grafana's own data source proxy, for anything the control plane sent.
    if curl -s --cacert "$HOME/.ankka/local-ca.crt" -u admin:admin -m 10 \
        "$GRAFANA_URL/api/datasources/proxy/uid/tempo/api/search?tags=service.name%3Dcontrolplane&limit=1" \
        | grep -q '"traceID"'; then
      TRACED=yes
      break
    fi
    sleep 2
  done
  if [[ -z "$TRACED" ]]; then
    echo >&2 "warning: no trace from the control plane reached the telemetry store within 60s."
    echo >&2 "  kubectl -n ankka-controlplane logs -l app.kubernetes.io/name=ankka-controlplane --tail=50 | grep telemetry"
  fi
fi

cat <<MSG

Deployed. The control plane is at $API_URL — no port-forward needed. The console is at
$CONSOLE_URL — sign in as dev / dev, in a browser that trusts ~/.ankka/local-ca.crt
(on macOS: open it in Keychain Access and mark it trusted for SSL). The identity provider's
console is at $AUTH_URL/admin/ (admin / admin); users are created there. Every service's traces,
metrics and logs are at $GRAFANA_URL (admin / admin): the telemetry store, which keeps nothing
when it restarts.

  ankka config set url $API_URL
  ankka config set ca ~/.ankka/local-ca.crt
  ankka login                      # user dev, password dev — opens $AUTH_URL; a code to type if not
  ankka organizations create acme --name "Acme Corp"
  ankka projects create checkout --name Checkout -O acme
  ankka config set project checkout
  echo '{"name":"cart","service":{"image":"sample-shopping-cart:latest"}}' > cart.json
  ankka services apply -f cart.json
  ankka services list

Expose it, and call it by hostname with the certificate verified:

  ankka services expose cart
  curl --cacert ~/.ankka/local-ca.crt -XPOST https://cart-checkout.${BASE_DOMAIN}:${HTTPS_HOST_PORT}/carts/c1/items \\
       -H 'content-type: application/json' -d '{"productId":"p1","name":"Widget","quantity":2}'
  curl --cacert ~/.ankka/local-ca.crt https://cart-checkout.${BASE_DOMAIN}:${HTTPS_HOST_PORT}/carts/c1
  curl -I http://cart-checkout.${BASE_DOMAIN}:${HTTP_HOST_PORT}/carts/c1     # 301 to https
  ankka services unexpose cart

MSG
