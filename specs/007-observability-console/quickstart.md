# Quickstart: Seeing What a Service Is Doing

How to prove the feature, cheapest first.

## Tier 1 — Pure (seconds)

```bash
sbt 'runtime/testOnly com.thinkmorestupidless.ankka.runtime.RecorderSuite'
# the ring bounds; oldest overwritten; a partial trace is labelled, not silently truncated
sbt 'runtime/testOnly com.thinkmorestupidless.ankka.runtime.TraceSuite'
# nesting from flat records; unattributed time is reported and never redistributed;
# an orphan span stays at the root with an unknown parent
sbt 'cli/testOnly com.thinkmorestupidless.ankka.cli.DiscoverySuite'
# a stale registry entry is dropped and its file removed; -D override keeps $HOME out of it
```

## Tier 2 — The budget (a minute, and it is a gate)

```bash
sbt 'runtime/testOnly com.thinkmorestupidless.ankka.runtime.RecorderBenchmark'
```

Throughput and median latency, recording on versus off, same workload. **Within 5% (SC-003) or the
always-on decision goes back to the user** — not quietly replaced by sampling, which would leave
the spec's assumption and its checklist both reading as though they still held.

Memory (SC-004): the same workload at 10,000 and 100,000 requests holds the same trace footprint.
A ring that grows is a ring that is not a ring.

## Tier 3 — The console, by hand (~5 minutes, the feature itself)

```bash
docker compose up -d
sbt shoppingCart/run &            # and, in another terminal, a second service
ankka local console               # → http://localhost:9889
```

Then, without touching a terminal again:

1. **Services** — both services listed. Kill one with `kill -9`; it disappears within 5s (SC-007)
   and its registry file is gone.
2. **Components** — the cart's entities, views and endpoints, grouped.
3. **Invoke** — `POST /carts/{id}/items`, fill the form, send. Response shown.
4. **Traces** — open that request. Endpoint → entity, nested, each with a duration, and
   unattributed time on its own row.
5. **Agents** — with the multi-agent sample, open a session: its memory, its tokens, its cost.

```bash
ANTHROPIC_API_KEY=sk-ant-… sbt multiAgentPlanner/run
```

The negative worth doing by hand: point the console at a service whose endpoint ACL denies you and
confirm the refusal is identical to `curl`'s (FR-012). The console is not a way round an ACL, and
this is the check that it stayed that way.

## Tier 4 — Deployed logs (~10 minutes, needs the local cluster)

```bash
./kustomization/deploy-local.sh
ankka services apply -f cart.json && ankka services expose cart
curl --cacert ~/.ankka/local-ca.crt https://cart-checkout.127.0.0.1.sslip.io:8443/carts/c1

ankka services logs cart                 # the request appears
ankka services logs cart --follow        # streams; ctrl-c exits cleanly
ankka services restart cart
ankka services logs cart --previous      # what the old instance said before it went
ankka services pause cart
ankka services logs cart                 # says so, exits non-zero — never hangs, never empty-0
```

**Prove it with no cluster credentials** (SC-006):

```bash
H=$(mktemp -d); mkdir -p $H/.ankka
cp ~/.ankka/local-ca.crt $H/.ankka/                        # a TLS trust anchor, not a credential
printf '{"ca":"%s/.ankka/local-ca.crt"}' "$H" > $H/config.json

env -u KUBECONFIG HOME=$H kubectl get pods -A              # fails: there are no kube credentials here
env -u KUBECONFIG HOME=$H ANKKA_CONFIG=$H/config.json \
  ankka services logs cart -p checkout --url "$URL" --token "$TOKEN" --tail 2
env -u KUBECONFIG HOME=$H ANKKA_CONFIG=$H/config.json \
  ankka services logs cart -p checkout --url "$URL" --token wrong --tail 2   # 403, exit 1
```

Two details this command gets wrong if written the obvious way, both found by writing it the
obvious way first:

- **`HOME=…` alone does not move the CLI's config.** `Settings.path` resolves `~` through the
  JVM's `user.home`, which the launcher fixes at startup — so the process reads the developer's
  *real* `~/.ankka/config.json` and the isolation is a fiction. `ANKKA_CONFIG` is the override
  that exists for exactly this, and the one a test uses.
- **The CA has to come too, and that is not a hole in the proof.** It is a TLS trust anchor: it
  says which certificate authority to believe, not who the caller is. It is needed only because
  kind's gateway uses a private CA — an installation with a publicly-issued certificate needs
  none. The credential is the token, and the third command is what shows it: the same call with
  the wrong token is refused by the endpoint's ACL, not by Kubernetes.

## Tier 5 — The RBAC, against a real cluster

Two halves, because neither alone proves it.

**What the manifest says**, in CI, in milliseconds:

```bash
sbt 'controlPlane/testOnly com.thinkmorestupidless.ankka.controlplane.LogsRbacSuite'
```

Catches a later edit that adds `delete` to make a cleanup easier, or `pods/exec` to make debugging
easier — either of which breaks the argument that let this widening in.

**What the API server agrees to**, against a real cluster, asking as the control plane's own
ServiceAccount rather than as an admin:

```bash
SA=system:serviceaccount:ankka-controlplane:ankka-controlplane
for c in "get pods/log" "get pods" "create pods" "delete pods" "patch pods" \
         "create pods/exec" "delete deployments" "create deployments"; do
  printf "  %-22s %s\n" "$c" "$(kubectl auth can-i ${=c} -n ankka-checkout --as=$SA)"
done
```

Expected, and verified on kind:

```
  get pods/log           yes
  get pods               yes
  create pods            no
  delete pods            no
  patch pods             no
  create pods/exec       no
  delete deployments     no
  create deployments     no
```

`CLAUDE.md` records why only this half proves the *granted* side: the suites mostly use admin
credentials, so a missing verb fails silently in CI and loudly on a real deploy — which has already
happened once, when `ensureNamespace` needed `patch` and had only `create`.

**Still outstanding**: an automated cluster suite that mints the ServiceAccount's token and reads a
log through it, so the granted side is covered in CI too rather than by a command someone
remembers to run. `OperatorClusterSuite` already does this for the operator and is the pattern.

## Tier 6 — Metrics

```bash
kubectl -n ankka-checkout port-forward deploy/cart 7626:7626
curl -s localhost:7626/ankka/metrics | grep ankka_
```

Counts and durations by component and handler; agent tokens and cost by model. A service that has
served nothing returns zeroed series rather than an error. A model with no configured price shows
**no cost series at all** — absent, not zero.

## Reviewer's checklist

- [ ] `runtime` and `cli` gained **no** dependency. `git diff project/Dependencies.scala` is empty.
- [ ] `EntityProtocol.Command` is unchanged; trace identity travels as `Metadata`.
- [ ] The console reaches handlers only over the service's own HTTP port — no privileged path.
- [ ] Unattributed time is reported, never redistributed; no orphan is reattached by guessing.
- [ ] The registry directory honours a system property, so no suite writes to `$HOME`.
- [ ] The control plane's new rights are `get` on `pods` and `pods/log` and nothing else.
- [ ] `ankka local console` works with no control plane configured; `services logs` works with the
      console never started.
- [ ] `README.md`'s "Not implemented" gains: the console is local-only; no persistence, sampling or
      log search; the control plane can now read every service's output.
- [ ] Every existing suite passes, and no existing component changed to be observed (SC-008).
- [ ] The seam holds: the UI and the aggregation API reference `Source`, never the registry; a
      service carries an `instances` list; `partial` is never described in terms of eviction. Grep
      the UI for "registry", "pid" and "aged out" — none should appear.
