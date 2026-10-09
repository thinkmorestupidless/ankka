# Contract: what the operator renders

Held by `features/cross-project/topic-grants.feature`, `machine-topics.feature`, the acceptance
feature's "the credential may read" steps, and the operator's rendering suites.

## `AnkkaProject.spec.grants` → the project ConfigMap

`ProjectReconciler` renders every accepted grant of the project into the existing `ankka-project`
ConfigMap as `grants.json` (shape in `data-model.md`) beside `topics.json`. Every platform
container of the project mounts the ConfigMap at `/var/run/ankka/project` already; the operator
adds `ANKKA_PROJECT_GRANTS=/var/run/ankka/project/grants.json` to the same containers (a rendering
addition that `RenderingUnchangedSuite` pins as one env entry per fixture and no new object) and
reports `status.grants: "mounted"` on the `AnkkaService`.

## A grantee service's `KafkaUser`

For `service:<p>/<s>` named by accepted topic grants in any `AnkkaProject`:

| Grant | ACL entry added (literal) |
|---|---|
| `consume` on `<g>.<topic>` | `topic <g>.<topic>`: `Read`, `Describe` |
| `produce` on `<g>.<topic>` | `topic <g>.<topic>`: `Write`, `Describe` |

Never a prefix, never a group entry. The service's own prefix entries are unchanged. A changed
`AnkkaProject` requeues every service its grants name, in any namespace, beside the services of its
own namespace.

## A machine's `KafkaUser` (`machine.<organization>.<name>`, in `ankka-broker`)

```yaml
spec:
  # no authentication: the external listener names the principal from the token's broker_user claim
  authorization:
    type: simple
    acls:
      - resource: {type: topic, name: spinvibe.affiliates.attribution, patternType: literal}
        operations: [Read, Describe]
      - resource: {type: group, name: ankka.machine.affiliates.network., patternType: prefix}
        operations: [Read]
  quotas:
    producerByteRate: 1048576        # AnkkaMachine.spec or ANKKA_MACHINE_PRODUCE_BYTES
    consumerByteRate: 4194304        # … or ANKKA_MACHINE_CONSUME_BYTES
    requestPercentage: 50            # … or ANKKA_MACHINE_REQUEST_PERCENTAGE
```

Rendered by `MachineReconciler` from the `AnkkaMachine` resource and every `AnkkaProject` grant
naming the machine; a deleted `AnkkaMachine` leaves the user with no topic entry; the user is never
removed. Byte rates above `ANKKA_MACHINE_BYTE_RATE_CEILING` are clamped.

## Operator settings

```text
ANKKA_MACHINE_ISSUER, ANKKA_MACHINE_JWKS_URL           rendered onto every platform container (contracts/machines.md)
ANKKA_MACHINE_PRODUCE_BYTES=1048576                    defaults for a machine's quotas
ANKKA_MACHINE_CONSUME_BYTES=4194304
ANKKA_MACHINE_REQUEST_PERCENTAGE=50
ANKKA_MACHINE_BYTE_RATE_CEILING=33554432
```

All are literals in `PlatformVariables` (the one declaration), set on the operator by the
`controlplane` and `broker-external` components' patches.

## RBAC

Operator: `ankkamachines` get/list/watch, `ankkamachines/status` get/update/patch (cluster-scoped).
Control plane: `ankkamachines` get/create/patch/delete; `secrets` unchanged (create, patch).
`OperatorClusterSuite`'s RBAC case gains both directions for the new resource.

## Statuses

- `AnkkaService.status.grants`: `"mounted"`.
- `AnkkaMachine.status`: `user`, `phase` (Waiting | Provisioned | Failed), `detail`.
- `AnkkaProjectStatus` unchanged.
