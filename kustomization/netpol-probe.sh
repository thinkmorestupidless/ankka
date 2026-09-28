# Proves the current cluster enforces NetworkPolicy (feature 014). Sourced by deploy-local.sh, and by
# the suite that tests it with kubectl stubbed; defines one function and runs nothing.
#
# A server pod, a client pod, a deny-all policy on the server; the client's connection must fail. A
# connection that succeeds means the API server stored a policy the network ignores. The namespace is
# removed whatever happens.
netpol_enforced() {
  local ns=ankka-netpol-probe
  kubectl delete namespace "$ns" --ignore-not-found --wait=true >/dev/null 2>&1 || true
  kubectl create namespace "$ns" >/dev/null
  trap 'kubectl delete namespace ankka-netpol-probe --wait=false >/dev/null 2>&1 || true' RETURN
  kubectl -n "$ns" run server --image=busybox:1.36 --restart=Never --labels=probe=server \
    --command -- httpd -f -p 8080 >/dev/null
  kubectl -n "$ns" run client --image=busybox:1.36 --restart=Never --command -- sleep 600 >/dev/null
  kubectl -n "$ns" wait --for=condition=Ready pod/server pod/client --timeout=120s >/dev/null
  local ip
  ip="$(kubectl -n "$ns" get pod server -o jsonpath='{.status.podIP}')"
  # Reachable before the policy, or the probe would pass on a broken network for the wrong reason.
  if ! kubectl -n "$ns" exec client -- sh -c "echo | nc -w 3 $ip 8080" >/dev/null 2>&1; then
    echo "netpol probe: the client cannot reach the server even without a policy" >&2
    return 1
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
