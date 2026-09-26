---
name: ankka-deploy
description: Run, package, deploy, expose, observe and troubleshoot an ankka service with the ankka CLI — the JSON service descriptor and its validation rules, building and loading an image, ankka services apply/get/history/logs/expose/pause/resume/restart/delete, lifecycle states, running locally with the local console and observability endpoints, scaling and rolling updates, CI, upgrading, and the ankka mcp server. Use when the task names the ankka CLI, a service.json descriptor, deploying, an image, a rollout, Ready/Failed/Unavailable states, logs, traces, the local console, or something that does not work after deploying.
---

# Deploying and operating an ankka service

A service is deployed by applying a JSON descriptor with the `ankka` CLI. The control plane records the
desired state, an in-cluster operator reconciles a namespace, Deployment and database towards it, and
the control plane folds the observed state back so `services get` says where it stands. Exposure,
pausing and restarting are commands, not descriptor fields.

## Rules

1. **The descriptor says what to run, never how the platform runs it.** `name` (a DNS label) and
   `service.image` are required; `runtime` (the ankka version the image was built against, checked to
   the platform's major and one minor below), `hosting` (`embedded` or `process`, the latter with
   `protocol`), `env`, `labels`, `annotations`, `http` (default `true`), `port` (default `9000`),
   `resources.instanceType` (`small`/`medium`/`large`) and `resources.autoscaling.minInstances` (a fixed
   count; there is no autoscaler). No database, hostname, paused flag or YAML: those are provisioned or
   commands. It is validated by the CLI and again by the control plane with the same rules, every problem
   at once.
2. **Never set what the platform sets.** `ANKKA_HTTP_PORT` (use `port`), `ANKKA_CLUSTER_*`, `POD_IP`, the
   process and sidecar addresses are refused. Any `ANKKA_DB_*` variable means "I bring my own database"
   and nothing is provisioned. `env` entries have `name` and exactly one of `value` or `secretKeyRef`.
3. **Say "no HTTP" positively.** `"http": false` for an image that listens on nothing; otherwise the
   service is not `Ready` until port 9000 opens and is `Failed` when the rollout deadline passes.
4. **Use an odd instance count.** Instances form one cluster; with two, a partition leaves no majority
   and both stop. Changing the count adds or removes pods without restarting the rest; only
   `services restart` rolls the pods.
5. **Decide the ACL before `expose`.** Exposure gives a service `https://<service>-<project>.<base>` (one
   DNS label under the wildcard, so at most 63 characters and no collision between `a-b`/`c` and
   `a`/`b-c`) and changes who can reach it, never who may call it. Always HTTPS; locally the root CA is
   in `~/.ankka/local-ca.crt`, passed with `ankka config set ca`, never trusted system-wide.
6. **Read the lifecycle, then the detail.** `services get` shows `Ready`, `UpdateInProgress`, `Paused`,
   `Failed`, `Unavailable`, the ready/desired counts, the database (`provisioning`, `ready`,
   `supplied`), the hostname and `route pending` / `route rejected: <reason>`; `services history` shows
   every generation and observation. Match the state against `references/reference/lifecycle-states.md`
   and the symptom against `references/operate/troubleshooting.md` before guessing.
7. **Local is not Kubernetes.** Locally a service joins itself on a random port with `docker compose
   up -d` for Postgres and `sbt run`; `ANKKA_CLUSTER_SEED_NODES` plus a fixed `ANKKA_CLUSTER_PORT` makes a
   two-node cluster on one machine; the local console reads services registered in `~/.ankka/running`
   and their loopback observability endpoint. The platform sets cluster mode, ports and readiness
   itself. Never copy Kubernetes-mode variables into a local run or a descriptor.
8. **Images are plain.** A Scala service's image comes from `sbt docker:publishLocal` (a `+` in a
   snapshot version becomes `-` in the tag); a kind cluster takes it with `kind load docker-image`, and
   the platform renders `imagePullPolicy: IfNotPresent` so a loaded image is used. A registry is needed
   anywhere else. A process-hosted image holds only the process; the platform adds the sidecar.
9. **Settings resolve per command.** `--url`, `--token`, `--project`, then `ANKKA_URL`/`ANKKA_TOKEN`/
   `ANKKA_PROJECT`, then `~/.ankka/config.json` (`ANKKA_CONFIG` overrides the file; `HOME` does not).
   `ankka login` opens the browser; CI uses a machine account's token. `ankka mcp` exposes the same verbs
   as MCP tools, as the logged-in user, with tenancy administration deliberately absent.
10. **Upgrading is additive within a supported range.** A running service never loses a table or column
    it needs; declare `runtime` so an unsupported version is refused before anything is written.

## Before deploying

- Is the ACL on every endpoint the one you want on the internet?
- Does the descriptor declare `runtime`, and for Python or TypeScript `hosting: "process"` with `protocol`?
- Do model keys and other secrets come from a `secretKeyRef`, not a literal `value` in a committed file?
- For a Python or TypeScript service, do `ANTHROPIC_*`, `ANKKA_MODEL_*` and `ANKKA_DB_*` belong to the sidecar and
  everything else to the process, as intended?
- Is the instance count odd, and does the instance type fit a JVM (a `small` is 512Mi)?

## Troubleshooting order

`services get` → `services history` → `services logs` (every instance; a process-hosted service has two
containers) → the console's traces locally. Common shapes: `UpdateInProgress` with "no operator has
reported" means the operator is not running; `Unavailable` with a version detail means `runtime` is out
of range; `Failed` after the rollout deadline with an image that runs means the port never opened (or
`"http": false` is missing); `waiting for database` for a minute after a first deploy is normal; a
`route rejected` reason is the gateway's own. Two services that lose timers share a database.

## Mistakes to check for

- A descriptor with `ANKKA_HTTP_PORT`, a hostname, or `paused` in it.
- An even instance count; a `pause` implemented by scaling the Deployment (the operator restores it).
- A literal `:latest` image with no load or push; a snapshot tag with a `+` in it.
- `HOME=$(mktemp -d)` to isolate the CLI; use `ANKKA_CONFIG`.
- Testing a Service through a port-forward, which bypasses the Service's selector.

## Reference files

Open the one a task needs; each is one topic and stands alone.

### Get started

- `references/get-started/deploy-locally.md` — Run the whole build, deploy and observe cycle on your own machine — a kind cluster with the ankka platform, your service deployed and exposed over HTTPS, its logs, a restart and its history.
- `references/get-started/coding-agents.md` — Give a coding agent ankka's documentation as a skill, and its hands through `ankka mcp` — the CLI's verbs, the local services on your machine, and this version's docs as MCP tools.

### Concepts

- `references/concepts/clustering.md` — How a service's instances form one cluster, how entities are spread across it, how nodes find each other locally and in Kubernetes, and what that means for rollouts, failures and instance counts.
- `references/concepts/control-plane.md` — How the control plane records what you asked for, how the operator makes the cluster match it, and how the status you read is kept honest with generations and confirmation.
- `references/concepts/observability.md` — What ankka records about every request — spans, traces, unattributed time, token usage — where you can read it locally and in a cluster, and what it deliberately does not do.

### Run and deploy

- `references/deploy/run-locally.md` — Run an ankka service on your own machine against a local Postgres, configure its database and HTTP port, form a two-node cluster in two terminals, and run a Python service beside the sidecar.
- `references/deploy/images.md` — Package a Scala, Python or TypeScript ankka service as a container image, tag it, and get it onto a cluster by pushing to a registry or loading it into a local kind node.
- `references/deploy/deploy-a-service.md` — Write a service descriptor, apply it with the ankka CLI, and follow the service from UpdateInProgress to Ready, including environment variables, secrets, version declarations and Python services.
- `references/deploy/expose.md` — Make a deployed service reachable from outside the cluster at its platform-derived HTTPS hostname, understand why the hostname has the shape it does, and remove the route again.
- `references/deploy/scaling-and-rollouts.md` — Choose how many instances a service runs and how large each is, and understand how deploys, restarts and scaling change the running pods without refusing requests.
- `references/deploy/ci.md` — Deploy a service from a GitHub workflow — create a deploy token, add three secrets, and use the ankka action to install and authenticate the CLI — plus the environment variables, exit codes and output formats any CI system needs.
- `references/deploy/upgrading.md` — Move a service to a new ankka version by changing the library version and the descriptor's runtime declaration together, refreshing the local schema, and checking what a deployed instance actually runs.

### Observe and operate

- `references/operate/local-console.md` — Use `ankka local console` to see every ankka service running on your machine — its components, a form per HTTP route, the traces of recent requests, entity state and agent sessions.
- `references/operate/status-and-history.md` — Read a deployed service's status with `ankka services list` and `get`, understand every field including unconfirmed readings, and see who changed a service with `ankka services history`.
- `references/operate/logs.md` — Read what a deployed service printed with `ankka services logs` — from every instance or one, from the container before the last restart, limited by lines or time — and know what it does not keep.
- `references/operate/service-lifecycle.md` — What pausing, resuming, restarting and deleting a deployed service do to its instances, its data, its hostname and its generation, and how a suspended service differs from a paused one.
- `references/operate/troubleshooting.md` — Symptoms you are likely to meet building, running, deploying and operating ankka services, with the cause of each and what to do about it.

### Reference

- `references/reference/cli.md` — Every `ankka` command and option, how the CLI resolves its settings and credentials, its output formats and its exit codes.
- `references/reference/service-descriptor.md` — Every field of the JSON service descriptor that `ankka services apply` takes, with its type, default and validation rules, and the environment variables the platform reserves.
- `references/reference/lifecycle-states.md` — What each of a deployed service's eight lifecycle states means, what usually causes it, what to do about it, and what an unconfirmed status is.
- `references/reference/configuration.md` — Every environment variable and configuration key a running ankka service reads, their defaults, how configuration is layered, and which variables the platform sets for you.
- `references/reference/runtime-endpoints.md` — The ports and HTTP endpoints every running ankka service exposes besides its own routes — readiness, version and metrics on a deployed instance, and the loopback observability endpoint a local one serves the console.
- `references/reference/error-codes.md` — The eight error codes a component can refuse with, the HTTP status each becomes, how a refusal travels from a handler to a caller, and how it differs from a failure.
