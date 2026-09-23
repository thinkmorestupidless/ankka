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

echo "==> building images"
# Root-level, not per-project: docker:publishLocal aggregates to every project with
# DockerPlugin enabled (operator, controlPlane, shoppingCart) and silently skips the rest, the same way
# `sbt compile` and `sbt test` already do. Nothing here has to change if a third image is
# ever added.
sbt -batch "docker:publishLocal"

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

echo "==> applying the CRD (must exist before anything references it)"
kubectl apply -f kustomization/components/crd/ankkaservice.yaml

echo "==> creating the control plane's namespace, if it does not exist yet"
kubectl create namespace ankka-controlplane --dry-run=client -o yaml | kubectl apply -f -

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
# postInitApplicationSQLRefs as the "postgres" superuser, not as the application's own "ankka"
# owner role, so every table the DDL creates ends up owned by "postgres" and unreadable by the
# role the control plane actually connects as. Discovered by deploying this for real: the
# control plane started cleanly, then every write timed out with "permission denied for table
# projection_management" the first time it touched its own journal.
kubectl create configmap ankka-controlplane-schema \
  --from-file=modules/runtime/src/main/resources/ankka/ddl \
  --from-literal=99-grants.sql="GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO ankka; GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO ankka;" \
  --namespace ankka-controlplane \
  --dry-run=client -o yaml | kubectl apply -f -

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
  | kubectl apply -f - --server-side --force-conflicts

echo "==> restarting the operator and control plane onto the images just loaded"
# Without this a *re*-run of this script changes nothing that is running. The manifests are
# unchanged and the tag is still :latest, so `kubectl apply` sees no difference, the Deployments do
# not roll, and the pods keep executing whatever image they started with — while every line of
# output reports success. Found by checking pod start times after a rebuild: five hours old.
# Harmless on a first run, where it merely restarts pods that have just started.
kubectl -n ankka-operator rollout restart deployment/ankka-operator
kubectl -n ankka-controlplane rollout restart deployment/ankka-controlplane

echo "==> waiting for the control plane's database"
kubectl -n ankka-controlplane wait --for=jsonpath='{.status.readyInstances}'=1 cluster/ankka-controlplane-db --timeout=180s

echo "==> waiting for the operator"
kubectl -n ankka-operator rollout status deployment/ankka-operator --timeout=120s

echo "==> waiting for the control plane"
# Three instances rolled one at a time (feature 004), each a JVM that has to join the cluster
# before it counts — so a longer wait than one pod needed.
kubectl -n ankka-controlplane rollout status deployment/ankka-controlplane --timeout=420s

echo "==> waiting for the gateway and its certificate"
kubectl -n ankka-gateway wait --for=condition=Ready certificate/ankka-wildcard --timeout=120s
kubectl -n ankka-gateway wait --for=condition=Programmed gateway/ankka --timeout=120s

echo "==> waiting for the identity provider"
# Keycloak's own database first, then the instance the operator runs from it. Both are slow starts
# — a Postgres bootstrap and a JVM — so the timeouts are generous rather than the waits optional.
kubectl -n ankka-auth wait --for=jsonpath='{.status.readyInstances}'=1 cluster/ankka-keycloak-db --timeout=300s
kubectl -n ankka-auth wait --for=condition=Ready keycloak/ankka-keycloak --timeout=420s

echo "==> importing the realm"
# Rendered from the single copy docker-compose also mounts. A KeycloakRealmImport is one-shot: it
# creates a realm that does not exist and never updates or deletes one (research R2), so on a
# re-run this is a no-op and a change to realm.json on an existing cluster is a console job.
# JSON is YAML, so the file is indented straight under spec.realm — the same technique as the
# schema ConfigMap above, and no tool beyond sed.
{
  cat <<'HEADER'
apiVersion: k8s.keycloak.org/v2alpha1
kind: KeycloakRealmImport
metadata:
  name: ankka-realm
  namespace: ankka-auth
spec:
  keycloakCRName: ankka-keycloak
  realm:
HEADER
  sed 's/^/    /' kustomization/components/keycloak/realm.json
} | kubectl apply -f - --server-side --force-conflicts
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
if $KCADM get users -r ankka -q username=dev -q exact=true | grep -q '"username" : "dev"'; then
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
if $KCADM get clients -r ankka -q clientId=ankka-local-smoke | grep -q '"clientId" : "ankka-local-smoke"'; then
  echo "client ankka-local-smoke already exists"
else
  $KCADM create clients -r ankka -s clientId=ankka-local-smoke -s secret="$SMOKE_SECRET" \
    -s publicClient=false -s standardFlowEnabled=false -s serviceAccountsEnabled=true \
    -s 'defaultClientScopes=["basic","profile","email","roles","ankka-controlplane"]' >/dev/null
  $KCADM add-roles -r ankka --uusername service-account-ankka-local-smoke --rolename platform-admin
  echo "created client ankka-local-smoke (service account, platform-admin)"
fi

echo "==> exporting the local certificate authority"
# The root that signed the wildcard the gateway serves. Nothing on this machine trusts it, and
# nothing is made to: the CLI is told about it (config set ca) and so is curl (--cacert). No step
# here or in the README ever turns verification off.
mkdir -p "$HOME/.ankka"
kubectl -n ankka-gateway get secret ankka-root-ca -o jsonpath='{.data.ca\.crt}' | base64 -d > "$HOME/.ankka/local-ca.crt"

API_URL="https://api.${BASE_DOMAIN}:${HTTPS_HOST_PORT}"

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

cat <<MSG

Deployed. The control plane is at $API_URL — no port-forward needed. The identity provider's
console is at $AUTH_URL/admin/ (admin / admin); users are created there.

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
