# Contract: a topic source's consumer group name

One pure function, `ConsumerGroups.name(identity, kind, componentId, version)`, in `runtime`. Every
topic subscription — a view or a consumer, in process, behind a sidecar or in a module — gets its
group id from it and from nowhere else. Entity-sourced views and consumers have no group and are
untouched: their `ShardedDaemonProcess` and `ProjectionId` names stay `ankka-view-<id>` and
`ankka-consumer-<id>`.

## Forms

| The service | Version 1, or none declared | Version N above 1 |
| --- | --- | --- |
| deployed, project `p`, service `s` | `ankka.p.s.<kind>.<id>` | `ankka.p.s.<kind>-v<N>.<id>` |
| local, states the name `s` | `ankka.local.s.<kind>.<id>` | `ankka.local.s.<kind>-v<N>.<id>` |
| local, states no name | `ankka-<kind>-<id>` | `ankka-<kind>.v<N>-<id>` |

`<kind>` is `view` or `consumer`. `<id>` is the component id exactly as declared.

The third row's first cell is the name every group had before this feature. It remains so that a
project with no name stated keeps reading under the group it has.

## Why the version sits beside the kind

A component id may contain `.`, `-` and `_` (`[a-zA-Z0-9][a-zA-Z0-9._-]*`), so a version appended
or prefixed to it can be forged by another id: `summary` at version 2 and `v2.summary` at version
1 would be one group. A project id and a service name are DNS labels and contain no `.`, so in the
dotted forms the kind is always the fourth segment and nothing a component is called can reach it.
In the undotted form the unversioned name always continues `ankka-<kind>-`, and the versioned one
`ankka-<kind>.`, so the two never meet.

Every character is in `[a-zA-Z0-9._-]`, the alphabet a Kafka topic name is held to, though a group
id is held to none: whatever tool a broker's operator reads groups with will accept it.

## Properties a test holds

- **Distinct inputs give distinct names.** For any two different tuples of (identity, kind,
  component id, version), the names differ. This holds because `local` is not a project's id: it
  is reserved, beside `platform`, by the control plane and the operator alike, and
  `ServiceIdentity.deployed` refuses it. Without that, the first two rows of the table above
  would meet. Checked over generated ids that include `.`, `-`, `_`,
  and the strings `v2`, `view` and `consumer` as ids and parts of ids.
- **A prefix names one service.** Every group of project `p`, service `s` starts
  `ankka.p.s.`, and no group of another service does. This is what 027-managed-broker grants on.
- **The longest name fits.** Project 57, service 63, component id 128, version `Int.MaxValue`:
  277 characters. A real broker accepts a subscription under it.
- **Version 1 and no version are the same name.**

## Where the identity comes from

`ServiceIdentity`, resolved once when the service starts and carried on `AnkkaService`.

| Cluster mode | Project and service |
| --- | --- |
| `kubernetes` | Read from the workload's own certificate, `ankka://<project>/<service>` (`RotatingTls.identity`). Never from configuration. A workload whose certificate carries no such identity and that has a topic source is refused at startup, naming the certificate. |
| `local` | No project. The service is `ankka.service.name`, which reads `ANKKA_SERVICE_NAME`; empty means none stated. |

A name stated locally is held to the rule a deployed service's is, `[a-z]([-a-z0-9]{0,61}[a-z0-9])?`,
and a name that breaks it is a startup failure naming it. Without that a local name could carry a
`.` and the property above would not hold.

`ANKKA_SERVICE_NAME` in a descriptor is refused by `ServiceSpec.problems`: on the platform it
would be read by nothing, and a variable that looks as if it renames a service and does not is
worse than one that cannot be set.

## What a deployment upgraded from before this feature sees

Its group changes from `ankka-<kind>-<id>` to the qualified name, which has read nothing, so each
topic source reads from its start position: a view from the earliest retained message unless it
says otherwise, re-applying its own handler to rows it already holds. The old group is left on the
broker with its offsets as they were, and expires by the broker's own retention of idle groups.
