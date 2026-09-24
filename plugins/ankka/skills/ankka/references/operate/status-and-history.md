# Status and history

> Read a deployed service's status with `ankka services list` and `get`, understand every field including unconfirmed readings, and see who changed a service with `ankka services history`.

Source: https://docs.ankka.cloud/operate/status-and-history/
`ankka services list` shows every service in a project, one line each. `ankka services get <name>` shows
one service in full. `ankka services history <name>` shows who changed it and when. All three read the
control plane, which records both what you asked for and what the cluster last reported; see
[Desired and observed state](../concepts/control-plane.md).

Every command acts on the project named by `--project` (`-p`), or on the one saved with
`ankka config set project <id>`.

## List the services in a project

```bash
ankka services list
```

```text
NAME  STATUS  INSTANCES  GEN  IMAGE                        HOSTNAME
cart  Ready   1/1        1    sample-shopping-cart:latest  https://cart-checkout.127.0.0.1.sslip.io:8443
```

| Column | Meaning |
|---|---|
| `NAME` | The service's name, from its descriptor. |
| `STATUS` | Its lifecycle state, followed by `(unconfirmed)` when the reading is not current. |
| `INSTANCES` | Ready instances over desired instances. |
| `GEN` | The generation: how many applies and restarts the service has had. |
| `IMAGE` | The image the current descriptor names. |
| `HOSTNAME` | The URL it answers on outside the cluster, or `-` when it is not exposed. |

The lifecycle states are listed in [Service lifecycle states](../reference/lifecycle-states.md). The one
to learn first is `UpdateInProgress`: the service is moving towards what its descriptor asks for and has
not got there yet.

## Show one service

```bash
ankka services get cart
```

```text
name        cart
project     checkout
status      Ready
instances   1/1
generation  1
image       sample-shopping-cart:latest
hosting     embedded
hostname    https://cart-checkout.127.0.0.1.sslip.io:8443
database    provisioned
```

| Field | Meaning |
|---|---|
| `status` | The lifecycle state, marked `(unconfirmed)` when it is not a current reading. |
| `instances` | Ready over desired. A paused or suspended service desires zero. |
| `generation` | Increments on every apply and every restart. A report about an older generation is ignored. |
| `hosting` | `embedded` for a service whose image is an ankka runtime, `process` for one that runs beside a sidecar. |
| `protocol` | For a process-hosted service, the sidecar protocol version it declared. |
| `hostname` | The external URL, `not exposed`, or a note that it is exposed but the control plane has no base domain configured. |
| `database` | What the platform did about the service's database, once something has reported: `waiting for database`, `provisioned`, `recovered existing data`, `supplied` or `database provisioning failed`. |
| `detail` | Why the service is in its state, when there is something to say: a rollout problem, a refused version, an unreachable cluster, or a route the gateway has not accepted (`route rejected: <reason>`). |

`detail` is shown only by `get`, which is why `list` marks unconfirmed readings in the status column
itself.

### Confirmed and unconfirmed

A status is **confirmed** when it is a report from the cluster about the service's current generation.
It is **unconfirmed** when the control plane is restating what it last knew — because it cannot reach the
cluster, for example, or because no operator has reported on the service at all. `detail` says which. An
unconfirmed `Ready` means "was ready when last seen", not "is ready".

## Script against the status

Every command takes `-o json` (`--output json`) and prints the status exactly as the control plane's API
returns it:

```bash
ankka services get cart -o json | jq -r .lifecycle
# Ready

ankka services list -o json | jq -r '.[] | select(.lifecycle != "Ready") | .name'
```

The JSON has every field, including `confirmed`, `exposed`, `paused` and `suspended`, which the table
folds into other columns. The field list is in [Control plane HTTP API](../reference/control-plane-api.md).

The CLI's exit code is `0` when the command succeeded, `1` when it failed — a refusal from the control
plane, an invalid descriptor, no project selected — and `2` when the command line itself could not be
parsed. A script can therefore tell a mistyped command from a failed apply. Errors are printed to standard error as `error: <detail>`.

A deployment script that must wait for a service to become ready polls the status:

```bash
until [ "$(ankka services get cart -o json | jq -r '.lifecycle + "/" + (.confirmed|tostring)')" = "Ready/true" ]; do
  sleep 5
done
```

## See who changed a service

```bash
ankka services history cart
```

```text
WHEN                      KIND       GEN  BY
2026-09-24T10:12:03.114Z  restarted  2    alice@example.com
2026-09-24T09:58:41.902Z  exposed    1    alice@example.com
2026-09-24T09:57:10.337Z  applied    1    bob@example.com (admin)
```

Each row is one change, newest first: `applied`, `restarted`, `paused`, `resumed`, `exposed`,
`unexposed`, `deleted`, `suspended` or `reinstated`. `GEN` is the generation the service had after the
change, and `BY` is who asked, by display name or subject. `(admin)` marks a change that was allowed by
the platform-admin role rather than by membership of the organization. A service keeps its last 50
changes. Reports from the cluster are not changes and do not appear.

Changes recorded before the platform began attributing them show `-` for who and when.
