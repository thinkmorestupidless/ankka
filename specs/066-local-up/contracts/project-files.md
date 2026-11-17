# Contract: the files a project's repository holds

Two files at the project's root, beside `context-map.json`. Both are JSON, read strictly: a field
the platform does not know is refused by name, as in a descriptor. Both are committed.

## `topics.json`: the topic declarations

```json
{
  "topics": [
    {"name": "orders-placed", "partitions": 6,
     "contract": "order.v1", "schema": "contracts/order.v1.json"},
    {"name": "customers", "partitions": 1, "compacted": true, "retention": "everything"}
  ]
}
```

| Field | Type | Required | Meaning |
|---|---|---|---|
| `name` | string | yes | the topic's declared name, as components name it; no project prefix |
| `partitions` | integer | yes | 1 to 1000 |
| `compacted` | boolean | no, `false` | the broker keeps the last message under each key |
| `contract` | string | with `schema` | the contract's name |
| `schema` | string | with `contract` | the schema document's path, relative to the project's root |
| `retention`, `retentionSize`, `cleanup`, `tombstoneWindow`, `minCompactionLag`, `maxCompactionLag` | string | no | as `ankka projects topics set`'s options of the same names |
| `copies`, `minInSync` | integer | no | as `set`'s; an installation's, not applied to a local broker |

Each field is the `set` option of the same name, so the file says nothing the command line could
not. There is no `removes`: that is an answer given when a declaration is sent, not part of one.

**Problems** (each a line of `ankka context-map check` and a refusal of `ankka local up` and
`ankka projects topics apply`):

- `topics.json is not a topic declarations file: <the parser's message, naming the field>`
- `topics.json declares "<name>" twice`
- `the topic "<name>" is not a valid topic name: <rule>`
- `the topic "<name>" declares <n> partitions; a topic has 1 to 1000`
- `the topic "<name>" names a contract and no schema` (or the reverse)
- `the topic "<name>" names the schema <path>, which does not exist` / `is not a JSON document`
- whatever `ProjectTopics.problems` says of the declaration's settings, prefixed with the topic

A declared topic no crossing names is not a problem: one service may publish and read it, or a
reader may be outside the project.

**Where it goes**: to an installation by `ankka projects topics apply`; to a local project by
`ankka local up`, as `<project>.<name>` on the local broker.

## `local.json`: what is true only on a developer's machine

Optional. A project with nothing to say has none.

```json
{
  "values": {
    "*":      {"LOG_LEVEL": "debug"},
    "orders": {"PAYMENT_KEY": "test-key",
               "ANKKA_AUTH_STAFF_JWKS_URL": "{{companion.identity.url}}/realms/shop/protocol/openid-connect/certs"}
  },
  "run": {
    "billing": ["./gradlew", ":billing:run"]
  },
  "companions": [
    {
      "name": "identity",
      "image": "quay.io/keycloak/keycloak:26.0",
      "command": ["start-dev", "--import-realm"],
      "env": {"KC_DB": "postgres", "KC_DB_URL": "{{database.jdbc}}",
              "KC_DB_USERNAME": "{{database.user}}", "KC_DB_PASSWORD": "{{database.password}}"},
      "ports": [{"port": 8080, "host": 8081}],
      "files": [{"from": "local/realm.json", "to": "/opt/keycloak/data/import/realm.json"}],
      "ready": {"http": {"port": 8080, "path": "/realms/shop"}},
      "database": true,
      "neededBy": ["orders"]
    }
  ]
}
```

### `values`: the local values

A map from a service's name, or `"*"` for every service, to a map from a variable's name to its
value on a developer's machine. A service's own entry wins over `"*"`, and a local value wins
over the descriptor's literal (FR-017). They are for a developer's machine, are committed, and
hold nothing that is a secret anywhere else.

A value may hold `{{companion.<name>}}` (host and port of the companion's first port) or
`{{companion.<name>.url}}` (`http://` before it); the command writes the address that reaches the
companion from where the service runs.

### `run`: one service from its source

A map from a service's name to the command, as a list of words, that runs it from its source in
the project's directory. Needed only where the language's default does not fit.

### `companions`

| Field | Type | Required | Meaning |
|---|---|---|---|
| `name` | string | yes | a DNS label; not a service's name, nor `ankka-postgres` or `ankka-kafka` |
| `image` | string | yes | |
| `command` | list of strings | no | the container's command |
| `env` | map | no | may hold `{{database.host}}`, `.port`, `.name`, `.user`, `.password`, `.url` (`postgres://...`) and `.jdbc` (`jdbc:postgresql://...`) when `database` is true, and `{{companion.<other>}}` |
| `ports` | list of `{port, host?}` | yes, at least one | `port` in the container; `host` on the machine, chosen and kept by the command when absent; published on loopback |
| `files` | list of `{from, to}` | no | a file or directory of the repository mounted read-only |
| `ready` | `{"http": {"port", "path"}}` or `{"tcp": port}` | yes | asked from the host; `http` is ready on any answer below 500 |
| `database` | boolean | no, `false` | a database named for the companion in the project's database server |
| `neededBy` | list of service names | no | the services started only once it is ready; absent, every service |
| `memory` | string | no | a limit, `512m`; absent, none |

### Problems

- `local.json is not a local values file: <the parser's message, naming the field>`
- `local.json gives values to "<name>", which is not a service of <project>` (likewise `run`, `neededBy`)
- `local.json gives "<service>" <VARIABLE>, which the command line sets itself` (research R9's list)
- `"<service>" reads <VARIABLE> from a secret and local.json gives it no value`
- `local.json names the companion "<x>" in a value of "<service>", and declares no such companion`
- `the companion "<name>" ...`: `is named twice`; `has the name of a service`; `is not a valid name`;
  `declares no port`; `declares no ready check`; `uses {{database...}} and asks for no database`;
  `mounts <from>, which does not exist`; `names port <n>, which "<other>" also names` or
  `which is in use on this machine`

## What the command writes, and nobody commits

`.ankka/local/` in the project's directory (the template's `.gitignore` already has `.ankka/`):

| File | Holds |
|---|---|
| `compose.json` | the rendered compose document, local values included |
| `state.json` | the ports the command chose (database server, broker, each observability port, process ports, companion ports), each service's secret key, written once and read back |

and one entry per running container in the local console's directory,
`<project>.<service>.json`: `{"name", "instanceId", "observabilityAddress", "owner", "startedAt"}`,
`owner` the project's directory (research R8).
