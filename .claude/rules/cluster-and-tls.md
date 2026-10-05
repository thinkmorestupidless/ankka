---
paths:
  - "modules/runtime/**"
  - "modules/http/**"
  - "operator/**"
  - "kustomization/**"
  - "proxy/**"
  - "modules/test-pki/**"
---

# Cluster formation, mutual TLS and the database connection

## Cluster formation is an overlay, chosen by where the process runs

`reference.conf` says nothing about how a node finds its peers — no hostname, no port, no seed
nodes, no join-self. That lives in one overlay per means of execution,
`modules/runtime/src/main/resources/ankka-cluster-<mode>.conf`, selected by `ANKKA_CLUSTER_MODE`:

| mode | set by | formation |
|---|---|---|
| `local` (default) | nobody | join `ANKKA_CLUSTER_SEED_NODES` if given, else join self; loopback, random port |
| `kubernetes` | the operator, never a descriptor | Cluster Bootstrap over the Kubernetes API, `${POD_IP}`, fixed ports 17355/7626 |

`ClusterConfig.load` stacks them — system properties > the service's `application.conf` > the
overlay > `reference.conf` — so a service's own file can override any choice the platform made,
and a *new* means of execution is a new overlay file plus one entry in `ClusterConfig.Modes`.
`ClusterFormation.form` then reads `ankka.cluster.formation` and either joins programmatically
or starts Pekko Management and Cluster Bootstrap; the startup code is identical in every mode.

The Kubernetes overlay's substitutions are `${X}`, not `${?X}`: a missing `POD_IP` must be a
startup failure naming it, not a node that binds loopback and quietly joins nothing. The operator
supplies all five (`ANKKA_CLUSTER_MODE`, `POD_IP`, `ANKKA_CLUSTER_SERVICE`,
`ANKKA_CLUSTER_POD_SELECTOR`, `ANKKA_CLUSTER_CONTACT_POINTS`), and `ServiceSpec.problems`
refuses a descriptor that sets any of them — the same rule as `ANKKA_HTTP_PORT`.

Readiness in that mode is `/ready` on the management port: cluster membership (Bootstrap's
own check) AND every `RuntimeExtension` with an opinion — `HttpServer` says no until it has
bound. An image whose runtime predates this feature has no management endpoint at all, so it
is held un-ready rather than joining itself beside its peers; that is the safety net for old
images, not an accident.

The dependencies this adds to `runtime` are `pekko-management`, `pekko-management-cluster-bootstrap`,
`pekko-management-cluster-http` and `pekko-discovery-kubernetes-api` (`project/Dependencies.scala`,
`pekkoMgmt`); they are what forced the `pekkoHttpFamily` override (`.claude/rules/build-and-release.md`).

The reference for the shape is the substrate project's three-file layout; its
`-Dconfig.resource` selector was rejected because it *replaces* `application.conf`, which
belongs to the service, not the platform.

## Zero trust is an overlay property

In the Kubernetes overlay every port a workload has is mutual TLS with a certificate cert-manager
issues for it, and a network policy decides who may connect at all (feature 014); locally there is
no TLS and every caller is `Caller.Local`. One `RotatingTls` (runtime) reads cert-manager's
`tls.key`/`tls.crt`/`ca.crt`, re-reads on mtime change and serves the HTTP server, management,
bootstrap's client and `ServiceClients`; remoting uses Pekko's own rotating-keys engine over the
same files. Two installation authorities (`ankka-cluster`, `ankka-service`, component `pki`) plus a
per-project database authority the operator renders. The caller is read from the client
certificate's `ankka://<project>/<service>` URI (`ankka://gateway` for the gateway); nothing the
request says is trusted. That makes a project id part of an identity, so `platform`, the project the
control plane's and the console's own certificates name, is reserved: `ProjectId.Reserved` refuses to
create or project it, `Names.ReservedProjectIds` refuses to render it (the operator trusts no writer of
the resource), and `ReservedProjectIdsSuite` holds the two lists to each other and to every
`ankka://<project>/<service>` the manifests under `kustomization/` ask for, so a platform workload
given an identity in an unreserved project fails the build. Readiness has its own plain port, 7627 `probe`. The observe port, 7628, admits exactly the control
plane's identity (`RotatingTls.Peers.Exactly`) and is not part of readiness. The one non-rolling
deployment — a template without `ankka.thinkmorestupidless.com/transport=tls` — is `Transition`:
delete, wait for no pods, apply.

## Traps

- **TLS client authentication belongs to a listener, not a route**, and the kubelet holds no
  certificate — so once management requires the service's certificate, readiness needs a port of
  its own (7627, named `probe`). Renaming it in `Rendering` or the control plane's manifest leaves a
  probe that resolves to nothing, as with `management` before it.
- **Pekko's `reference.conf` arrives already resolved.** Overriding
  `rotating-keys-engine.secret-mount-point` alone leaves `key-file`/`cert-file`/`ca-cert-file` at
  Pekko's default path; the overlay names all three. Found as a missing `ca.crt` under
  `/var/run/secrets/pekko-tls`.
- **Pekko's TLS stage turns endpoint identification back on for a client engine**, so a cluster peer
  reached by pod IP failed "No subject alternative names matching IP address". Cluster contexts use
  `RotatingTls.Peers.SameIdentity`: the chain is checked with the two-argument trust check (no
  hostname) and the peer must carry this process's own `ankka://` URI.
- **Bootstrap counts contact points per host.** Two nodes on one loopback address are one contact
  point; `TlsClusterFormationSuite` tells them apart as `localhost` and `127.0.0.1`.
- **pekko-persistence-r2dbc hands its options customizer the whole configuration**, not the
  connection factory's block — reading `ssl.mode` at the root connected in the clear.
- **libpq refuses a key file anyone else can read**, and a root-owned `0600` Secret is unreadable to
  a non-root runtime. The database volume is `0440` with a pod `fsGroup`.
- **A `cert` rule in `pg_hba` for `all` would lock out every role not yet redeployed.** The rule is
  for members of `ankka_tls`, which a role joins when its service is next deployed; others fall
  through to CNPG's password default until then.
- **`pekko.coordinated-shutdown.exit-jvm = on` belongs in the Kubernetes overlay only, never
  `reference.conf`.** In a pod a process is a node and exiting after a split-brain down is the
  point — without it the pod stays `Running`, never ready, never restarted. Anywhere else it
  turns the first stopped `ActorSystem` into a dead process: put in the base, it killed the
  forked test JVM (`Forked test harness failed: EOFException`) the moment a suite called
  `AnkkaTestKit.stop()`.
- **`pekko.cluster.seed-nodes = ${?ENV}` is a type error at load.** It is a list, and an
  environment variable is a string. The local overlay's `ankka.cluster.seed-nodes` is a
  comma-separated *string* under ankka's own key, and `ClusterFormation` splits it and joins
  programmatically — the same call it makes to join itself.
- **`ClusterConfig.layered` puts the overlay *above* a config a caller built for itself.**
  The obvious precedence (overlay beneath the application) is what `load` does, and it is wrong
  for a config that came from `ConfigFactory.load()` — the test kit's — because that config
  already carries Pekko's reference defaults for every key the overlay sets, a fixed remoting
  port among them. Two test systems on one machine then bind the same port. A config that
  already has `ankka.cluster.formation` came from the loader and passes through untouched.
- **A pod that cannot answer a bootstrap probe must never be a contact point.** Upgrading the
  kind cluster from a feature-003 image deadlocked: the old pods were `Ready` by their tcp probe,
  so the rolling update kept them; the new pods discovered them by the identity labels, got no
  answer on a management port that did not exist, and Pekko's join decider refused to form a
  cluster while any contact point was silent — `Exceeded stable margins but missing seed node
  information from some contact points`, forever. The discovery selector therefore includes
  `ankka.thinkmorestupidless.com/formation=bootstrap`, a label only pod templates rendered since
  feature 004 carry (`Labels.FormationKey`); it is *not* in the Deployment's immutable
  `spec.selector` nor the Service's. Empty-cluster suites cannot see this class of bug — the
  same lesson as the `Recreate` migration, from the other direction.
- **The container port's *name* `management` is load-bearing.** The readiness probe is
  `httpGet` on the port by name, not number, so renaming the port in `Rendering` (or in the
  control plane's own manifest) leaves a probe that resolves to nothing and a pod that is never
  ready, with no error anywhere.
- **"Marking node as UNREACHABLE" for a node that just exited is expected, not a bug.** A
  graceful leave still has the failure detector fire on the survivors between the socket
  closing and the `Removed` propagating; a test asserting *zero* unreachable events during a
  clean leave is asserting something Pekko does not promise. Assert on membership converging.
- **`kubectl delete pod --force` is a graceful leave, not a crash.** It still sends SIGTERM
  and the node runs coordinated shutdown, so it proves nothing about failure detection or the
  split-brain resolver. A crash test is `kill -9` of the JVM from the node (`crictl` inside the
  k3s container), and a partition test is `iptables` — `MultiNodeClusterSuite` does both.
- **Several actor systems serving TLS in one test JVM collide on remoting** unless they are
  `pekko.actor.provider = local`; `TlsServing` (http's test sources) says so for the callees it starts.
