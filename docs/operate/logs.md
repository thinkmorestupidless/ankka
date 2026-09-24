---
title: Logs
description: Read what a deployed service printed with `ankka services logs` — from every instance or one, from the container before the last restart, limited by lines or time — and know what it does not keep.
kind: guide
related: [operate/troubleshooting.md, operate/status-and-history.md, concepts/observability.md, reference/cli.md]
---

# Logs

`ankka services logs <name>` prints what a deployed service's instances wrote to standard output and
standard error. It reads what Kubernetes holds for each of the service's pods at the moment you ask,
through the control plane, so it needs no cluster credentials of your own.

```bash
ankka services logs cart
```

With one instance, lines are printed exactly as the service produced them. With several, each line is
prefixed with the instance it came from, so that output from different pods can be told apart and still
searched with `grep`:

```text
cart-7d9f8b6c4-2xkqp: <a line the first instance printed>
cart-7d9f8b6c4-9wz4t: <a line the second instance printed>
```

## Options

| Option | Effect |
|---|---|
| `--instance <pod>` | Read one instance instead of every instance. |
| `--previous` | Read the container that ran before the last restart instead of the current one. |
| `--tail <n>` | Only the last `n` lines of each instance. |
| `--since <seconds>` | Only lines from the last `n` seconds. |
| `-o json` | The response as JSON: one entry per instance with its output, or the reason it could not be read. |

`--project` (`-p`) selects the project, as for every service command. There is no option to follow the
output as it is written; run the command again, or use `--since` with a short window.

## After a crash, read the previous container

When an instance crashes, Kubernetes starts a new container in the same pod, and the current container's
log begins after the restart. The explanation is usually in the one that died:

```bash
ankka services logs cart --previous --tail 200
```

An instance that has not restarted has no previous container, and says so rather than failing:
`cart-7d9f8b6c4-2xkqp has no previous container — it has not restarted`. One instance that cannot be read
does not stop the others from being printed; its line carries the reason instead.

## A service in another language

`ankka services logs` does not yet read a service with process hosting. Its pods have two containers —
the sidecar, named after the service, and your process, named `<service>-app` — and the logs command
does not choose between them, which Kubernetes refuses for a pod with more than one container. Each
instance's line carries that refusal instead of output. Until the command can choose, read the
containers with `kubectl`, in the project's namespace:

```bash
kubectl -n ankka-checkout logs deploy/cart -c cart-app    # your process
kubectl -n ankka-checkout logs deploy/cart -c cart        # the sidecar
```

## What it is not

`ankka services logs` is not a log store. It keeps nothing, searches nothing, and aggregates nothing.
Kubernetes holds the output of a pod's current container and the one before it, so a service that has
restarted many times has lost everything but its last two containers, and a pod that has been replaced
has taken its logs with it. For retention and search, collect container output with the logging stack of
the cluster you run on.

Reading logs is the only thing that gives the control plane read access to pods. It can read pods and
their logs, and nothing else about them; it cannot execute commands in a pod or change one.
