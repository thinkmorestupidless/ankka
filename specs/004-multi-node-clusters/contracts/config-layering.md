# Contract: Configuration Layering

**Satisfies**: FR-006 to FR-012

## The rule that makes it a base

`reference.conf` says **nothing** about how a node finds its peers. Every such setting lives in
exactly one overlay. A reviewer checks this by grepping the base for `seed-nodes`,
`canonical.hostname`, `bootstrap` and `discovery` and finding none.

## The three files

`modules/runtime/src/main/resources/`

**`reference.conf`** — base. Persistence, serialization, sharding, passivation, the split-brain
resolver, `coordinated-shutdown.exit-jvm = on`. Unchanged for every means of execution.

**`nakka-cluster-local.conf`** — peers are named, and default to oneself.

```hocon
nakka.cluster.formation = join-self-or-seeds
nakka.join-self-if-no-seed-nodes = on

pekko.remote.artery.canonical {
  hostname = "127.0.0.1"
  port = 0                          # random — what lets several services share a machine
  port = ${?NAKKA_CLUSTER_PORT}     # fix it on the node others will join
}
nakka.cluster.seed-nodes = ""                            # comma-separated; empty means join self
nakka.cluster.seed-nodes = ${?NAKKA_CLUSTER_SEED_NODES}
```

**Not `pekko.cluster.seed-nodes = ${?NAKKA_CLUSTER_SEED_NODES}`**, tempting as it is: an environment
variable is a string and that key is a list, so the substitution is a type error at load. The
formation step splits nakka's own comma-separated key and joins programmatically — the same call it
already makes to join itself, which is also why a random port can work at all (a seed list cannot
name a port that is not known until bind time).

**`nakka-cluster-kubernetes.conf`** — peers are discovered through the API server.

```hocon
nakka.cluster.formation = bootstrap
nakka.join-self-if-no-seed-nodes = off     # here, joining self IS the split

pekko.remote.artery.canonical { hostname = ${POD_IP}, port = 17355 }

pekko.management {
  http { hostname = ${POD_IP}, bind-hostname = "0.0.0.0", port = 7626 }
  cluster.bootstrap.contact-point-discovery {
    discovery-method          = kubernetes-api
    service-name              = ${NAKKA_CLUSTER_SERVICE}
    required-contact-point-nr = ${NAKKA_CLUSTER_CONTACT_POINTS}
  }
}
pekko.discovery.kubernetes-api.pod-label-selector = ${NAKKA_CLUSTER_POD_SELECTOR}
```

The substitutions are **required**, not optional (`${X}`, not `${?X}`): in Kubernetes mode a missing
`POD_IP` must be a startup failure naming the variable, not a node that quietly binds loopback and
joins nothing.

## Selection

`NAKKA_CLUSTER_MODE`, default `local`. The loader resolves the resource
`nakka-cluster-<mode>.conf`; if it does not exist, startup fails listing the modes that do.

Adding a means of execution is therefore adding a file (FR-012).

## Precedence — verified, research R1

```
system properties  >  the service's application.conf  >  the overlay  >  reference.conf
```

| Requirement | How precedence delivers it |
|---|---|
| a service's own settings still apply (FR-011) | `application.conf` is loaded, not replaced |
| a service can override the platform's choice | it sits *above* the overlay |
| `-Dkey=value` still wins for a one-off | system properties sit above everything |

`resolve()` runs once, last. Earlier, and the overlay's `${POD_IP}` is resolved before the
environment has been merged in.

**Not `-Dconfig.resource`**: it replaces `application.conf` wholesale — correct for an application
that owns its configuration, and a silent discard of the user's for a platform that does not.

## Where the loader is used

`Nakka.start`'s `config` parameter changes its default from `ConfigFactory.load()` to the layered
loader. A caller passing its own `Config` — `NakkaTestKit` does — bypasses it entirely, which is what
keeps every existing suite byte-for-byte as it is.

## Formation — one step, chosen by configuration

| `nakka.cluster.formation` | Does |
|---|---|
| `join-self-or-seeds` | configured seed nodes if any; otherwise join self. Management is **not** started — it binds a fixed port and would collide between two local services |
| `bootstrap` | start Pekko Management, then Cluster Bootstrap. **Never** joins self |

Deliberately not the reference project's "always call bootstrap and let it stand down": it does stand
down (verified), and announces it with a `WARN` on every local start.
