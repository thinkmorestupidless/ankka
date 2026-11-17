# Run a whole project on one machine

> Bring every service of a project up on one machine with ankka local up, give it values and containers only a developer's machine has, run one service from its source, and what one machine does not stand in for.

Source: https://docs.ankka.cloud/build/local-projects/
`ankka local up` brings a project of several services up on one machine with one command: one
database server with a database for each service, one broker with the topics the project
declares, every service from its image, and whatever else the project declares beside its
services. Each service answers on the port its `service.json` names, and finds the others by name.
Nothing is installed and nothing outside the project's directory is written, except the local
console's entries and a cache of the platform's schema.

```bash
sbt Docker/publishLocal       # every service's image, built first: the command runs images, it builds none
ankka local up                # everything, and returns when each service is ready
ankka local status            # which service is running, ready, and who started it
ankka local logs billing      # one service's log, by its name; --follow, --tail <n>
ankka local console           # the services, their components and the calls between them
ankka local down              # stop it all; what the services recorded is kept
ankka local down --volumes    # and remove every database and every topic's messages
```

Docker with Compose v2 is needed. `up` prints each service with its address and whether it was
started, restarted (and why) or left running; a second `up` with nothing changed touches nothing,
and one after a change restarts only the services whose image or environment changed.

## What the command reads

A project's directory is one that holds `context-map.json` and a `services/<name>/service.json`
for each service the map names. The command reads:

| File | What it gives |
|---|---|
| `context-map.json` | the services, and the crossings between them |
| `services/<name>/service.json` | each service's image, ports, hosting, runtime and variables |
| `topics.json` | the topics the project's services share |
| `local.json` | what is true only on a developer's machine: values, commands, companions |

What it writes, in `.ankka/local/` (which the template's `.gitignore` already ignores), is never
committed: the compose document it renders and the ports and keys it chose once and keeps, so a
service's address and its secret key are the same on every run.

Everything that is wrong with the project is said at once, before anything starts: the map's
problems, as `ankka context-map check` says them; a descriptor that does not read; a file that does
not parse; two services naming one port; a port already in use on the machine; a variable read
from a secret with no local value; an image that is neither on the machine nor can be pulled. A
second kind of problem can only be found once the database server and the broker run: a topic that
already exists otherwise than it is declared, or a database holding a schema the service's runtime
cannot move from. The command then starts no service, says so, and leaves the database server and
the broker running.

## `topics.json`: the project's topics

```json
{
  "topics": [
    {"name": "orders-placed", "partitions": 6,
     "contract": "order.v1", "schema": "contracts/order.v1.json"},
    {"name": "customers", "partitions": 1, "compacted": true, "retention": "everything"}
  ]
}
```

Each topic is made on the local broker before any service starts, under the project's prefix, as
an installation names it: `shop.orders-placed`. The broker makes no topic of its own: a topic
the file does not declare does not exist. The
same file is what `ankka projects topics apply` sends to a project on an installation, and
`ankka context-map check` holds it to the map: every topic a crossing names must be declared, with
the same contract and schema. [Topics](topics.md) is what each setting means.

## `local.json`: what only a developer's machine has

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

A project with nothing to say has no `local.json`.

**`values`** gives a service a variable on this machine: under its name, or under `"*"` for every
service. A service's own entry wins over `"*"`, and a local value wins over the descriptor's. A
variable the descriptor reads from a secret must have a local value, or the command refuses to
start. The file is committed, so it holds test values and nothing that is a secret anywhere else.
No value is ever printed, a failure's included. The variables the command sets itself (the
service's name, its database, the broker) cannot be given here.

**Companions** are the containers a project needs beside its services: an identity provider, a
mail catcher, a stand-in for a payment provider. Each is started after the databases and before
the services that need it (`neededBy`, every service when absent), and those services start only
once it is ready. A companion that is not ready holds back only the services that need it. With
`database` it is given a database of its own in the project's database server, named for it, and
its `env` may name it with `{{database.host}}`, `.port`, `.name`, `.user`, `.password`, `.url` or
`.jdbc`. A service's value may name a companion with `{{companion.<name>}}` (its host and port) or
`{{companion.<name>.url}}`, and is given the address that reaches the companion from where the
service runs. `ankka local status` and `ankka local logs` list and show a companion by its name.

## One service from its source

To change one service and run it from its source among the others, leave it out of `up` and run it
with `ankka local run`:

```bash
ankka local up --without billing
ankka local run billing           # in the foreground; ctrl-c stops it
```

`run` starts what the service needs when it is not running (the database server, the broker, its
database, the topics and its companions), gives the service the same name, port, database and
values it has as a container, and leaves everything running when it ends. The services started by
`up` find it by name, and it finds them. Its exit code is the service's. A service that is running
as a container is refused: leave it out first. Leaving out a service that is running stops its containers and
keeps its database and its topics' messages. `up billing` starts only `billing` and what it
needs.

The command it runs is, in order: what follows `--` (`ankka local run billing -- sbt billing/run`),
the service's entry under `run` in `local.json`, or the language's own:

| Language | Command | Run in |
|---|---|---|
| Scala | `sbt <service>/run` | the project's directory |
| Java | `./mvnw -q exec:java` | `services/<service>` |
| Kotlin | `./gradlew --no-daemon -q run` | `services/<service>` |
| Python | `uv run python -m <package>.main` | `services/<service>` |
| TypeScript | `npm start` | `services/<service>` |
| Go | `go run .` | `services/<service>` |
| Rust | `cargo module`, then its runtime loads the module | `services/<service>` |
| Web | `npm run dev`, behind the platform's proxy on this machine | `services/<service>` |

A Python, TypeScript or Go service's sidecar runs as a container the command starts, and dials the
process on the machine. A module is built, and the runtime that loads it is a container; a module
that was not built is refused, naming the file.

## Each hosting as a container

A service is started from its image as its hosting says. An embedded service is one container. A
process-hosted service is its sidecar and its process, sharing one network, the platform's own
variables given to the sidecar and the service's own to the process. A module-hosted service is
its image, run once to give up its module, and the runtime that loads it. A web-hosted service is
the platform's proxy and its program behind it, the proxy's mounts and the program's calls reaching
the project's services by name. A web-hosted service has no database and is not listed in the local
console. `ANKKA_SIDECAR_IMAGE` and `ANKKA_PROXY_IMAGE` name other platform images than the CLI's own
release.

## A project made outside the template

The command needs only what the template writes: `context-map.json` naming every service, a
`services/<name>/service.json` for each, and `topics.json` when a crossing names a topic. Write the
map by hand, then check it before the first `up`:

```bash
ankka context-map check     # silent when the map holds; one line per problem otherwise
ankka local up
```

Nothing about a service's own files changes: the command reads the descriptors and runs the images
they name. Ignore `.ankka/` in the project's version control, as the template's `.gitignore` does:
it holds each service's secret key on this machine.

## What it does not stand in for

A project on one machine runs the same images on the same platform, and is not an installation.
On one machine:

- a service has no identity and no certificate;
- every caller is the local machine;
- a topic is not authorised: any service may publish to or read any topic;
- nothing is exposed through a gateway: each service answers on the machine's loopback address;
- there is one instance of each service;
- nothing is rolled out, rolled back or scaled.

A message is not checked against its topic's contract by a project on one machine.
[Limitations](../reference/limitations.md) is the whole list. To deploy what was run here, see
[deploy a project](../deploy/ci.md).
