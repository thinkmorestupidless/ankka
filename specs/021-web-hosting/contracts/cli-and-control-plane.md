# Contract: the CLI, the control plane's wire and the console

## `ankka services get`

For a web-hosted service, after the lines every service has:

```text
hosting     web
database    none
process     port 3000
callers     the internet, orders, billing/invoices
mounts      /api/cart    → cart
            /api/orders  → orders    (no service)
```

- `callers` always starts with `the internet`; `*` prints as `every service in <project>`.
- A mount whose state is not `ok` has the state in brackets.
- `--output json` is the `ServiceStatus`, as today.
- `process`, `callers` and `mounts` are the three new lines. They come after the lines every
  service has, `hostname` among them, and are printed only for web hosting. `database` is a line
  services already have; `none` is its new value.

`ankka services list` is unchanged: its columns do not include hosting.

## `ankka services logs`

```text
Usage: ankka services logs [--instance <string>] [--previous] [--tail <integer>] [--since <string>] [--platform] <name>
    --platform
        Read the platform's container instead of yours: the sidecar of a process-hosted service, or
        the proxy of a web-hosted one.
```

Without `--platform` the command reads the developer's container, for every hosting. For a service
with one container, `--platform` is refused: `--platform applies to a service with process or web
hosting`.

Route: `GET /services/{projectId}/{name}/logs` gains the query parameter `platform=true`. The
route table does not change; its hand-written section does.

The MCP tool `service_logs` gains the same argument, and the console's logs page a switch.

## `ankka local web`

```text
Usage: ankka local web [--file <path>] [--port <integer>] [--service <name=url>]... [-- <command>...]

Run a web-hosted service's process on this machine as the platform would in a cluster: its mounts
answer at their paths, and it calls services by name.

    --file <path>, -f <path>
        The service's descriptor (default service.json).
    --port <integer>
        Where to listen (default 3000).
    --service <name=url>
        Where a service is, for one that is not running under the local console's eye. Repeatable.
    -- <command>...
        The process to run, on a free port, with PORT and ANKKA_SERVICES_URL set. Without it, both
        are printed and the process is yours to start on the descriptor's port.
```

- The descriptor is validated first with the platform's rules, and refused with all of them.
- Its hosting must be `web`: `ankka local web is for a service with web hosting; this one is
  "<hosting>"`.
- A variable from a secret is named on the error stream and left unset.
- A service is found at `--service`, else as the local console finds one. One that is found
  nowhere is not an error at start: a request for it is answered 503, naming it, so a developer
  can start in any order.
- With a command, the process's port is a free one the command is told through `PORT`; the
  descriptor's `processPort` is for a cluster. Without one, the process is expected at the
  descriptor's `processPort`, and a `--port` equal to it is refused:
  `--port <n> is the process's own port; choose another, or state processPort in <file>`.
- It ends when the command ends, with its exit code, or on interrupt.

## `ankka init --language web`

```text
    --language <string>, -l <string>
        scala (the default), python, typescript or rust; or web, for a user interface.
```

Renders `cli/src/main/templates/web/` and `templates/common/`. `--package` and `--template` are
refused for it, as for TypeScript. The rendered project:

```text
<name>/
├── service.json          # hosting web, one mount of "backend" at /api
├── package.json          # dev, build, start, typecheck, test
├── server/server.ts      # node:http: the built files, one route that calls a service, Vite in dev
├── src/                  # the single-page app
├── test/                 # node --test: the server's call, the app's fetch
├── Dockerfile            # node:24, multi-stage, USER 1000
├── README.md
├── .mcp.json, .claude/skills/…
└── .github/workflows/{ci,deploy}.yml
```

## The wire (`controlplane-api`)

`ServiceSpec` and `ServiceStatus` gain the fields in `data-model.md`. Because the status is also the
listing's row and a fixture of the console's:

- `ServiceRows` copies `mounts`, `callers` and `processPort` from the descriptor on `ServiceApplied`;
- `ControlPlaneFixturesSuite`'s full sample of `ServiceStatus` sets all three, and `MountStatus`
  gets a codec in `Wire` and therefore a fixture;
- `console/package/src/client/schemas.ts` mirrors them, or `fixtures.test.ts` fails on a field the
  schema dropped.

## The console

On a service's page: "Runs as" says, for web hosting, `Your program beside the platform's proxy`.
A web-hosted service's page lists
its mounts with their states and whom it admits, and its database reads `None`. The fake control
plane gains a web-hosted service, and the Playwright suite one case that reads it.
