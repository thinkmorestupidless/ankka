# Service lifecycle states

> What each of a deployed service's eight lifecycle states means, what usually causes it, what to do about it, and what an unconfirmed status is.

Source: https://docs.ankka.cloud/reference/lifecycle-states/
A service's lifecycle is one word summarising what the platform last observed about it. `ankka services
list` shows it in the `STATUS` column and `ankka services get` beside the ready and desired instance
counts and a detail line. The detail usually names the cause, so read it first.

The lifecycle is derived afresh on every reconciliation from what the cluster reports, rather than
stepped through as a state machine. A service therefore cannot be stuck in a state: when the cause goes,
the state follows.

## The states

| State | Meaning | Instances |
|---|---|---|
| `UpdateInProgress` | The desired state changed and the cluster has not caught up. | Rolling towards the desired count |
| `Ready` | Every desired instance is ready. | All ready |
| `PartiallyReady` | Some desired instances are ready and some are not. | Some ready |
| `Unavailable` | No instance is ready, or the platform refused to run the service. | None ready |
| `Failed` | The rollout gave up, or provisioning failed. | Varies |
| `Paused` | The project's members paused the service. | Zero desired |
| `Suspended` | The organization was disabled, which stopped every service in it. | Zero desired |
| `NotDeployed` | The service was deleted, or has nothing deployed. | None |

### UpdateInProgress

The service was applied, restarted or resumed, and the new generation is not fully rolled out: the
Deployment does not exist yet, the cluster has not acted on the new specification, fewer instances run
the new version than desired, or instances of the previous version are still running. It is also shown
before any operator has reported on the service at all, in which case the status is unconfirmed and the
detail says `no operator has reported on this service`.

A rollout usually passes through this state in seconds to a minute; a service's first deployment takes
longer while its database is created. If it lasts, the detail names what a pod is waiting on, such as
an image pull. A control plane with no cluster behind it leaves every service in this state.

### Ready

Every desired instance has joined the service's cluster and bound its HTTP port. Readiness is the
runtime's own check, not a call to one of your routes, so `Ready` means the platform can route to the
service, not that your application logic is healthy.

### PartiallyReady

At least one instance is ready and at least one is not, outside a rollout. The service answers, with
less capacity. Look at `ankka services logs <name>` for the instance that is not ready.

### Unavailable

No instance is ready although some are desired, outside a rollout. Instances may be crashing on start;
`ankka services logs <name> --previous` shows the output of the container before its last restart.

The control plane also reports `Unavailable` without starting anything when it refuses to run a
service: a declared `runtime` outside the versions the platform supports, or a `protocol` it does not
speak. The detail names both versions. Rebuild against a supported ankka version, or change the
declaration.

### Failed

The rollout did not complete within its deadline, or the platform could not provision what the service
needs. The detail gives the most specific reason available, preferring a pod's own reason, for example
`ImagePullBackOff`, to the Deployment's. A failed database provisioning also reports `Failed`, with the
database problem in the detail.

An image that is not an ankka service never becomes ready, because it has no readiness endpoint, and is
reported `Failed` when the deadline passes. That is the intended outcome, not a gap.

Fix the cause and apply the descriptor again, or `ankka services restart <name>`.

### Paused

Set by `ankka services pause`: the service keeps its descriptor, database and hostname, and runs no
instances. Applying a new descriptor to a paused service records it without starting anything.
`ankka services resume` starts it again, and `ankka services restart` is refused until then. Pausing is
the desired state, so a paused service whose pods are still terminating shows `Paused`, never `Failed`.

### Suspended

A platform administrator disabled the service's organization. Every service in its projects is stopped,
and members can read but change nothing. When the organization is re-enabled each service returns to
what it was: a service its members had paused stays paused.

### NotDeployed

The service was deleted, or the cluster has nothing deployed for it and nothing desired. A deleted
service's name can be applied again; its database is kept, so re-applying recovers its data.

## Confirmed and unconfirmed

Every status carries `confirmed`. It is `true` when the status describes an observation the operator
reported for the service's current generation. It is `false` when the control plane cannot confirm it:
the cluster could not be reached and the control plane is restating what it last knew, with the detail
`could not reach the cluster: <reason>`, or no operator has reported on the service yet.

An unconfirmed `Ready` means "was ready when last seen". Scripts that wait for a deployment should
wait for `lifecycle` to be `Ready` and `confirmed` to be `true`:

```bash
ankka services get cart -o json | jq '.lifecycle == "Ready" and .confirmed'
```

## Generation

`generation` increments on every apply and every restart. An observation states the generation it
describes, and one about a superseded generation is discarded, so a late report from an old rollout
never overwrites the status of a newer one. `ankka services list` shows it in the `GEN` column.
