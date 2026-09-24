# Pause, resume, restart and delete

> What pausing, resuming, restarting and deleting a deployed service do to its instances, its data, its hostname and its generation, and how a suspended service differs from a paused one.

Source: https://docs.ankka.cloud/operate/service-lifecycle/
Four commands change whether a deployed service runs without changing its descriptor. None of them ever
removes the service's data: entities, workflows, sessions, view rows and timers live in the service's
database, and the database outlives all four.

| Command | Instances | Generation | Hostname | Data |
|---|---|---|---|---|
| `ankka services pause <name>` | stopped | unchanged | kept | kept |
| `ankka services resume <name>` | started again | unchanged | kept | kept |
| `ankka services restart <name>` | replaced one at a time | incremented | kept | kept |
| `ankka services delete <name>` | removed | unchanged | removed | kept |

Each is recorded in the service's history with who asked; see
[Status and history](status-and-history.md#see-who-changed-a-service).

## Pause and resume

```bash
ankka services pause cart
ankka services resume cart
```

Pausing stops every instance of a service and keeps everything else: its descriptor, its database, and
whether it is exposed. The service reports `Paused` with zero desired instances. While it is paused
nothing answers its in-cluster address or its hostname, and its timers wait; a timer that fell due while
the service was paused fires once it is running again.

Resuming starts the instances the descriptor asks for. The service reports `UpdateInProgress` until they
have joined their cluster and are ready, then `Ready`. Entities rebuild from the journal as they are
first used.

Pausing is the way to take a service down. Scaling its Kubernetes Deployment to zero by hand does not
work: the operator restores the instance count from the descriptor on its next pass.

## Restart

```bash
ankka services restart cart
```

Restarting replaces every instance without any change to the descriptor, one instance at a time: a new
instance starts, joins the cluster and takes over entities before an old one is stopped. The service
keeps answering throughout, including on its hostname. The generation increments, so the status tracks
the new rollout and ignores reports about the old one.

Restart is for picking up something outside the descriptor that the instances read only at start, such
as a changed secret, or for clearing a problem in a running process. An apply that changes the image or
the environment rolls the instances by itself; an apply that changes only the instance count adds or
removes instances without replacing the others. See [Scale and roll out](../deploy/scaling-and-rollouts.md).

## Delete

```bash
ankka services delete cart
```

Deleting a service removes its instances, its in-cluster address and its route, and the service reports
`NotDeployed`. **Its database is not deleted.** Nothing the platform does can delete a service's
database; the operator is not permitted to by the cluster itself.

Applying a descriptor with the same name in the same project brings the service back with its data:

```bash
ankka services apply -f service.json
ankka services get cart
# ...
# database    recovered existing data
```

The generation continues from where it was, so the history is continuous. A re-created service is
private until it is exposed again, because its route was removed with it.

A service name is a deployment target, so reusing one is intended. Organization and project ids are not
reusable in the same way; see [Tenancy and access](../concepts/tenancy-and-access.md).

## Paused and suspended

A service can also be stopped by a platform administrator disabling its organization. It then reports
`Suspended`, not `Paused`, so it is clear that the members did not stop it, and its members cannot resume
it: every change in a disabled organization is refused. When the organization is enabled again, each
service returns to what its members had chosen. A service they had paused stays `Paused`; everything else
starts again.
