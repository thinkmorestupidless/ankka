# Logs

> Read what a deployed service printed with `ankka services logs` — from every instance or one, from the container before the last restart, limited by lines or time — and know what it does not keep.

Source: https://docs.ankka.cloud/operate/logs/
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
| `--platform` | Read the platform's container instead of yours, for a service with process or web hosting. |
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

## A service with two containers

A service with process hosting runs your process beside the platform's sidecar, and a web-hosted
service runs it beside the platform's proxy. Each pod holds two containers: the platform's, named after
the service, and yours, named `<service>-app`. `ankka services logs` reads yours. Add `--platform` to read
the platform's instead:

```bash
ankka services logs cart               # your process
ankka services logs cart --platform    # the sidecar, or the proxy
```

For a service whose pod has one container, `--platform` is refused:
`--platform applies to a service with process or web hosting`.

## Lines that name their trace

A line written while a handler runs carries the trace and span it belongs to, in the logging context as
`trace_id` and `span_id`, and the platform's own programs — the sidecar beside a process, the control
plane, and every project `ankka init` makes — end such a line with them:

```text
12:00:01.123 INFO  c.t.a.cart.Cart - an item was added trace_id=4bf92f3577b34da6a3ce929d0e0e4736 span_id=00f067aa0ba902b7
12:00:01.200 INFO  c.t.a.runtime.Ankka - ankka shopping-cart service started
```

A line written outside any handler carries neither, and ends exactly as it did before. That is what lets
a log store join a service's lines to its traces in the collector the installation names. Logs are never
exported: they are gathered from standard output, by [the telemetry store](telemetry.md) on a local
platform and by the installation's own agent anywhere else.

A Scala service whose `logback.xml` predates this prints the ids once its pattern names them; append this
after `%msg`:

```text
%replace( trace_id=%X{trace_id} span_id=%X{span_id}){' trace_id= span_id=$', ''}
```

A Python or TypeScript process's own lines are its own, and carry no ids.

## What it is not

`ankka services logs` is not a log store. It keeps nothing, searches nothing, and aggregates nothing.
Kubernetes holds the output of a pod's current container and the one before it, so a service that has
restarted many times has lost everything but its last two containers, and a pod that has been replaced
has taken its logs with it. For retention and search, collect container output with the logging stack of
the cluster you run on; on a local platform the telemetry store already does.

Reading logs is the only thing that gives the control plane read access to pods. It can read pods and
their logs, and nothing else about them; it cannot execute commands in a pod or change one.
