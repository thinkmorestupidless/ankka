# Contract: CLI Commands

Two commands, deliberately independent of one another (FR-024): the console never needs the
control plane, and logs never need the console.

## `ankka local console`

```
ankka local console [--port <n>] [--no-open]
```

| Behaviour | Detail |
|---|---|
| Starts | an HTTP server on loopback, prints the address, serves until interrupted |
| Default port | 9889, Akka's, so knowing one console means knowing the other |
| Port taken | takes the next free one and says so — never fails on a busy port |
| Discovers | services from the registry directory, dropping stale entries (R3) |
| Needs | nothing: no control plane, no token, no cluster, no configuration |

`--no-open` suppresses opening a browser. Opening one by default is the friendlier behaviour for
the first run, which is what this feature is for; a flag covers headless use and scripting.

**This command talks to no control plane at all.** It is a local development tool, and a developer
who has never run `ankka config set url` must be able to use it.

## `ankka services logs`

```
ankka services logs <name> [-p <project>] [--follow] [--previous]
                           [--instance <id>] [--since <duration>] [--tail <n>]
```

| Flag | Behaviour |
|---|---|
| *(none)* | recent output, then exit (FR-019) |
| `--follow` | stream until interrupted (FR-020) |
| `--instance` | one instance; otherwise all, each line identifying its own (FR-021) |
| `--previous` | the previous container, after a restart — usually where the answer is (FR-022) |
| `--since`, `--tail` | bound the window; a service that has logged for a week cannot be returned whole |

Goes to the **control plane**, over the existing authenticated API, with the same URL, token and
project the developer already uses for `services apply`. No kubeconfig, no cluster credentials,
no second thing to configure (SC-006).

### Required behaviours

- **A paused service, or one with no running instance**: say so plainly and exit non-zero. Never
  hang waiting for a stream that cannot start, and never exit 0 with empty output — both read as
  "the service logged nothing", which is a different and misleading fact (FR-025).
- **Another project's service**: refused by the same rules as every other command. Logs are not a
  side channel around project scoping.
- **A service that does not exist**: the same error `services get` gives. One vocabulary.
- **Interrupted while following**: exit cleanly, leaving no stream open on the control plane.
- **Default window**: bounded. A developer asking for logs wants the recent ones.

### What it is not

Not a log search, not an aggregator, not storage. It reads what Kubernetes holds for a pod right
now and prints it. A service that has restarted many times has lost everything but the last two
containers, and that is Kubernetes' behaviour, which the CLI reports rather than papers over.

## Exit codes

Unchanged from the existing CLI: `0` success, non-zero with a message on stderr otherwise. `main`
stays a one-line wrapper around `run(args, out, err): Int` — the recorded rule, so that `sys.exit`
never runs inside command logic and kill a test JVM.

## Output

Human-readable by default, `--output json` where the existing commands already offer it. Log lines
stream as they are, unwrapped: a developer piping to `grep` must get the service's own output, not
a decorated form of it.
