# Contract: `ankka openapi`

```text
ankka openapi [<service directory>]
    [--out <file> | --out -]      default: openapi.json in the service's directory
    [--strict]
    [--tag <tag>]...  [--prefix <path>]...
    [--api-version <version>]     default: 0.0.0
    [--check <file>]
ankka openapi --local <service>
```

`<service directory>` defaults to the current directory. In a project of several services it is
the service's directory; the build is run where `ankka local run` runs it.

## What it does

1. Finds the service's language as `ankka local run` does. Python, TypeScript, Go, Rust and a
   web-hosted service: exit `2`, `a <language> service has no OpenAPI document yet`.
2. Runs the language's run command (`sbt <service>/run`, `./mvnw -q exec:java`, `./gradlew
   --no-daemon -q run`; `local.json`'s `run` when it has one) with these added to
   `JAVA_TOOL_OPTIONS`, and with no database, broker or companions started:

   | Property | Value |
   |---|---|
   | `ankka.document` | `openapi` |
   | `ankka.document.out` | a temporary file |
   | `ankka.document.strict` | `true` with `--strict` |
   | `ankka.document.tags`, `ankka.document.prefixes` | comma-separated |
   | `ankka.document.version` | `--api-version` |

3. The service's `main` reaches `start`, which writes the document and ends the JVM (below).
4. On success: moves the temporary file to `--out`, or prints it for `-`, and prints `wrote <file>:
   <n> routes, <m> incomplete` to standard error. With `--check`: compares with the kept file;
   the same, exit `0` and `<file> is current`; different, exit `1` and one line per route added,
   removed or changed and per schema changed, writing nothing.
5. On failure: prints the problems the JVM wrote, exits `1`. No file is written or changed.

`--local <service>` instead reads `/observability/openapi` of a service running on this machine
and prints it; `no local service named <service>` exits `1`.

## Exit status

| Status | When |
|---|---|
| `0` | written, printed, or current |
| `1` | the document could not be built; a kept document differs; strict and a route is incomplete; no such local service |
| `2` | usage; a language with no document |

## Problems, one per line on standard error

```text
PATCH /players/{playerId}: incomplete, lacks request body
GET /players/{playerId}: the description gives the answer 200 a shape, and its type gives another
no route carries the tag "affiliate"
the endpoint ReportsEndpoint could not be built: <cause>
```

## The mode in `start`

Read by `ServiceBuilder.start` first, before any check that needs the environment and before an
actor system exists. With `ankka.document` set:

- the registry is validated as `start` validates it;
- each extension is asked `document(kind, options)`; an HTTP server answers for `openapi`;
- no extension answering: `this service has no HTTP endpoints`, exit `1`;
- a document: written to a temporary file beside `ankka.document.out`, moved over it, exit `0`;
- problems: written to standard error, exit `1`.

Nothing is bound, formed or connected. A value of `ankka.document` the service does not know is
`unknown document kind`, exit `1`.

A developer without the CLI runs their build's run task with the same properties
(`sbt -Dankka.document=openapi -Dankka.document.out=openapi.json run`).

## Reference

`ankka openapi` is a built-in word: in `builtIn`, in the root command, and in the generated CLI
reference with a hand-written section.
