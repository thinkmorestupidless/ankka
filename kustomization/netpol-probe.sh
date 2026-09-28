# shellcheck shell=bash
# Proves the current cluster enforces NetworkPolicy (feature 014). Sourced by deploy-local.sh, and by
# the suite that tests it with kubectl stubbed; defines one function and runs nothing.
#
# A server pod, a client pod, a deny-all policy on the server; the client's connection must fail. A
# connection that succeeds means the API server stored a policy the network ignores. The namespace is
# removed whatever happens.
#
# Returns 0 when the network enforces the policy, 1 when it stored the policy and ignored it, and 2
# when the probe could not run at all — which says nothing about the network, and must not be reported
# as if it did.
netpol_enforced() {
  local ns=ankka-netpol-probe
  kubectl delete namespace "$ns" --ignore-not-found --wait=true >/dev/null 2>&1 || true
  kubectl create namespace "$ns" >/dev/null || return 2
  trap 'kubectl delete namespace ankka-netpol-probe --wait=false >/dev/null 2>&1 || true' RETURN
  # A new namespace's default ServiceAccount is created a moment after the namespace, by a controller;
  # a pod created before it exists is refused outright.
  local tries=0
  until kubectl -n "$ns" get serviceaccount default >/dev/null 2>&1; do
    tries=$((tries + 1))
    if [[ $tries -gt 60 ]]; then
      echo "netpol probe: the namespace's default service account never appeared" >&2
      return 2
    fi
    sleep 1
  done
  kubectl -n "$ns" run server --image=busybox:1.36 --restart=Never --labels=probe=server \
    --command -- httpd -f -p 8080 >/dev/null || return 2
  kubectl -n "$ns" run client --image=busybox:1.36 --restart=Never --command -- sleep 600 \
    >/dev/null || return 2
  if ! kubectl -n "$ns" wait --for=condition=Ready pod/server pod/client --timeout=120s >/dev/null; then
    echo "netpol probe: the probe's pods never became ready" >&2
    return 2
  fi
  local ip
  ip="$(kubectl -n "$ns" get pod server -o jsonpath='{.status.podIP}')"
  # Reachable before the policy, or the probe would pass on a broken network for the wrong reason.
  if ! kubectl -n "$ns" exec client -- sh -c "echo | nc -w 3 $ip 8080" >/dev/null 2>&1; then
    echo "netpol probe: the client cannot reach the server even without a policy" >&2
    return 2
  fi
  kubectl -n "$ns" apply -f - >/dev/null <<POLICY
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: deny-all
spec:
  podSelector:
    matchLabels: { probe: server }
  policyTypes: [Ingress]
POLICY
  sleep 3
  if kubectl -n "$ns" exec client -- sh -c "echo | nc -w 3 $ip 8080" >/dev/null 2>&1; then
    return 1
  fi
  return 0
}
