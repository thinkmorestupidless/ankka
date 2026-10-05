# Contract: a project's topics, their refusals, and what a member reads

What a member writes and reads. Held by `features/broker/declaring.feature`,
`features/broker/topics.feature` and the suites named in the plan.

## Declaring a topic

A topic is declared on a project, once, with the partitions it has. A descriptor declares none.

```text
PUT    /projects/{id}/topics/{name}     {"partitions": 12}     declare it, or raise its partitions
DELETE /projects/{id}/topics/{name}                            stop declaring it; the topic is kept
GET    /projects/{id}/topics                                   every declared topic, with its phase
```

```bash
ankka projects topics set transactions --partitions 12 -p money
ankka projects topics unset transactions -p money
ankka projects topics list -p money
```

Any member of the project's organization may declare and remove a topic, as for a project secret.
A component names a topic exactly as it is declared (`transactions`); the broker holds it as
`<project>.<name>` (`money.transactions`).

Naming a broker of one's own is unchanged: any variable starting `ANKKA_KAFKA_` in a service's `env`.
The platform then gives that service no credential on the installation's broker, and the project's
declared topics are not made on the broker the service names.

## Refusals

Printed identically by the CLI before sending and by the control plane, every problem at once.

| Problem | Status | Message |
|---|---|---|
| a name of the wrong shape | 400 | `topic '<name>': a name is lower-case letters, digits, "-" and ".", starting and ending with a letter or digit, at most 100 characters` |
| partitions out of range | 400 | `topic '<name>': partitions <n> is outside the range 1-1000` |
| fewer partitions than the project declares | 409 | `topic '<name>' has <n> partitions and cannot have fewer; <m> was asked` |
| a project the member's organization does not have | 404 | as for any project route |
| removing a topic the project does not declare | 404 | `project '<id>' declares no topic '<name>'` |

Declaring a topic again with the partitions it has succeeds and records nothing.

## What a member reads

`GET /projects/{id}/topics`:

```json
{ "topics": [
  { "name": "transactions", "partitions": 12, "phase": "provisioned" },
  { "name": "entries", "partitions": 3, "phase": "failed", "detail": "the installation has no broker" }
] }
```

| `phase` | When |
|---|---|
| absent | the operator has not reported on the topic yet |
| `waiting for broker` | the broker has not yet made the topic, or not yet grown it to the declared partitions |
| `provisioned` | the topic is ready with its declared partitions |
| `recovered` | as `provisioned`, and the topic was on the broker before it was declared: declared again after its declaration was removed |
| `failed` | a problem waiting will not clear; `detail` says which |

`ankka projects topics list` prints one line per topic: name, partitions, phase.

`GET /services/{project}/{name}` gains two fields, each omitted when absent:

```json
{ "broker": "provisioned", "undeclaredTopics": ["entries"] }
```

| `broker` | When |
|---|---|
| absent | web-hosted; or the installation has no broker; or nothing reported yet |
| `supplied` | the descriptor names its own broker |
| `waiting for broker` | the broker has not yet made the service's credential |
| `provisioned` | the credential is ready |
| `recovered` | as `provisioned`, and the credential was there before this service was applied |
| `broker provisioning failed` | a problem that will not clear by waiting; the service's `detail` says which |

`undeclaredTopics` lists the topics the service's components read or publish to that its project
does not declare, by the names the components gave them. It is read from the service's running
instances, so it is absent when none answered and on listing rows; an empty list means they answered
and every topic is declared. `ankka services get` prints them after `database`:

```text
broker              provisioned
undeclared topics   entries
```

The service's lifecycle is not changed by either: a service whose credential is waiting, or that uses
an undeclared topic, is still deployed and still ready.
