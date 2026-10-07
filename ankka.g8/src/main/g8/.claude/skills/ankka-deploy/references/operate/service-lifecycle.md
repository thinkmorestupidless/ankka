# Pause, resume, restart, roll back and delete

> What pausing, resuming, restarting, rolling back and deleting a deployed service do to its instances, its data, its hostname and its generation, and how a suspended service differs from a paused one.

Source: https://docs.ankka.cloud/operate/service-lifecycle/
Four commands change whether a deployed service runs without changing its descriptor, and a fifth, roll
back, applies a descriptor the service ran before. None of them ever removes the service's data: entities,
workflows, sessions, view rows and timers live in the service's database, and the database outlives all
five.

| Command | Instances | Generation | Hostname | Data |
|---|---|---|---|---|
| `ankka services pause <name>` | stopped | unchanged | kept | kept |
| `ankka services resume <name>` | started again | unchanged | kept | kept |
| `ankka services restart <name>` | replaced one at a time | incremented | kept | kept |
| `ankka services rollback <name>` | replaced one at a time, from the earlier descriptor | incremented | kept | kept |
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

## Roll back

```bash
ankka services rollback cart                     # the most recent generation with a different descriptor
ankka services rollback cart --to-generation 4   # a named one
```

Rolling back applies the descriptor the service recorded at an earlier generation again, as a new
generation. It is an apply in every respect: the instances roll to the earlier image and environment one
at a time, the generation increments, and the history records it as `rolled-back to 4`. Nothing is
rewound, and the generation that went wrong stays in the history beside the one that put it right.

With no generation named, the platform takes the most recent generation whose descriptor differs from
the one the service has now. Restarts recorded no descriptor and applies of the same descriptor change
nothing, so both are passed over, and rolling back twice returns to where you started. To choose a
generation, read the history first: each apply shows its image and a digest of its descriptor, and
`ankka services history cart --generation 4` prints the whole descriptor. See
[Status and history](status-and-history.md#see-who-changed-a-service).

A rollback changes only what a descriptor states. A paused service stays paused, an exposed one stays
exposed, and the database keeps everything written since; a rollback is not a way to undo data. The
earlier descriptor is checked against the platform's rules as they are now and against the organization's
quota, exactly as an apply is, and a rollback in a disabled organization is refused like any change.

A service keeps the descriptors of its last 50 applies. A rollback is refused, and changes nothing, when
the generation named:

| Refusal | Why |
|---|---|
| `service 'cart' has no generation 9` | the service never had it |
| `the descriptor of generation 3 is no longer kept; the oldest kept is generation 11` | it is older than the last 50 applies |
| `generation 3 was a restart and ran the descriptor of generation 2` | a restart recorded no descriptor; name the one it ran |
| `service 'cart' already has the descriptor of generation 2` | rolling back to it would change nothing; use `restart` to replace the instances |

A deploy token can roll back, as it can apply. The console offers a rollback on each row of a service's
history that can be rolled back to; see [The console](console.md#services).

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
