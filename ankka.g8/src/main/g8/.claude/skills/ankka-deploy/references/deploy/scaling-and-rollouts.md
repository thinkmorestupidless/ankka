# Scale and roll out

> Choose how many instances a service runs and how large each is, and understand how deploys, restarts and scaling change the running pods without refusing requests.

Source: https://docs.ankka.cloud/deploy/scaling-and-rollouts/
A service runs as a fixed number of instances, set by `resources.autoscaling.minInstances` in its
descriptor, and each instance's size is set by `resources.instanceType`. The instances form one cluster:
the service's entities are sharded across them, and each entity lives on exactly one instance at a time.
Deploys and restarts replace instances one at a time without refusing requests, and scaling adds or
removes instances without touching the others.

```json title="service.json"
{
  "name": "orders",
  "service": {
    "image": "registry.example.com/acme/orders:1.4.2",
    "resources": {
      "instanceType": "medium",
      "autoscaling": { "minInstances": 3 }
    }
  }
}
```

## Instance count

`minInstances` is honoured as a fixed count. It defaults to 1, which suits a development cluster. Set it
explicitly for production.

`maxInstances` (default 10) and `targetCpuPercent` (default 80) are validated and stored, but nothing
acts on them: the platform renders no autoscaler, and a service does not scale on load. `maxInstances`
must be at least `minInstances`, and `targetCpuPercent` between 1 and 100, or the descriptor is refused.

**Use an odd count.** When the network partitions a service's instances, the side with the majority
keeps running and the minority stops, so that two halves never both act as the service. With two
instances a partition leaves one on each side, neither is a majority, and both stop until Kubernetes
restarts them. Three instances survive losing one. One instance has no partition to survive. See
[Clusters and instances](../concepts/clustering.md).

## Instance size

`instanceType` names a size, so a descriptor carries no raw CPU and memory numbers. Requests equal
limits:

| `instanceType` | CPU | Memory |
|---|---|---|
| `small` (default) | 500m | 512Mi |
| `medium` | 1000m | 1024Mi |
| `large` | 2000m | 2048Mi |

For a Python service the size applies to the sidecar, which runs the runtime. Your process's container
has a small fixed size of 100m CPU and 128Mi memory.

## Deploys roll without downtime

Applying a descriptor that changes what the instances run, such as the image or an environment variable,
replaces the instances one at a time. The Deployment rolls with `maxSurge: 1` and `maxUnavailable: 0`:

1. One new instance starts beside the existing ones.
2. It joins the service's existing cluster and becomes ready.
3. Only then is one old instance stopped, and its entities hand off to the instances that remain.
4. Repeat until every instance runs the new version.

This holds at one instance too: the new instance joins the old one's cluster before the old one stops,
so a single-instance service deploys with no gap.

Each stopping instance keeps serving for five seconds before it is told to stop. A pod leaves its
Service's endpoints the moment its deletion starts, but each node's network proxy learns that up to a
second later, and in that second a request can still be routed to the old instance. The pause, a
`preStop` sleep, lets those requests be answered rather than refused.

## Scaling does not roll

Changing `minInstances` from 3 to 4 starts one new instance, which joins the cluster and takes a share of
the entities. The three running instances keep running. Changing it back stops one. Only a change to
what the instances run causes a rollout.

## Restart rolls

```bash
ankka services restart orders
```

Restart replaces every instance, with the same rolling procedure as a deploy, even though nothing in the
descriptor changed. Use it to pick up a new image pushed under the same tag, or to clear a problem held
in memory. Entities rebuild their state from the journal, so a restart loses nothing that was persisted.

## Scale with the descriptor, not kubectl

The platform restores each service's Deployment to what its descriptor says on every reconciliation, so
`kubectl scale` is undone within moments. Change `minInstances` and apply the descriptor again, or pause
the service:

```bash
ankka services pause orders     # zero instances; the descriptor and the data are kept
ankka services resume orders
```

See [Pause, resume, restart and delete](../operate/service-lifecycle.md).

## No liveness probe

Instances have a readiness probe and deliberately no liveness probe. An instance that is slow, for
example during a long garbage collection, is left to recover rather than restarted: restarting it would
move its entities to other instances and back, which costs more than the pause did.
