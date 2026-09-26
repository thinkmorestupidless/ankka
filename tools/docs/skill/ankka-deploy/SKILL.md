---
name: ankka-deploy
description: Run, package, deploy, expose, observe and troubleshoot an ankka service with the ankka CLI — the JSON service descriptor and its validation rules, building and loading an image, ankka services apply/get/history/logs/expose/pause/resume/restart/delete, lifecycle states, running locally with the local console and observability endpoints, scaling and rolling updates, CI, upgrading, and the ankka mcp server. Use when the task names the ankka CLI, a service.json descriptor, deploying, an image, a rollout, Ready/Failed/Unavailable states, logs, traces, the local console, or something that does not work after deploying.
pages:
  - reference/service-descriptor.md
  - reference/cli.md
  - reference/lifecycle-states.md
  - reference/configuration.md
  - reference/runtime-endpoints.md
  - reference/error-codes.md
  - deploy/run-locally.md
  - deploy/images.md
  - deploy/deploy-a-service.md
  - deploy/expose.md
  - deploy/scaling-and-rollouts.md
  - deploy/ci.md
  - deploy/upgrading.md
  - operate/local-console.md
  - operate/status-and-history.md
  - operate/logs.md
  - operate/service-lifecycle.md
  - operate/troubleshooting.md
  - concepts/control-plane.md
  - concepts/clustering.md
  - concepts/observability.md
  - get-started/deploy-locally.md
  - get-started/coding-agents.md
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
