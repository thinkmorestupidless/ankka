# Measurements: SC-002 to SC-004

**Date**: 2026-11-17
**Machine**: Apple M5, 10 cores, 32 GB, macOS (Darwin 25.6.0)
**Docker runtime**: OrbStack 29.8.2, its VM given 10 CPUs and 21.5 GiB
**CLI**: the JVM build of this branch at `5aba576cf` (`cli/stage`), its libraries published locally
**Project**: `ankka init shop --project --services orders,billing`, then `ankka init <n> --add` for
`stock`, `payments`, `shipping` and `customers`: six Scala services from the template, and one
companion declared in `local.json` (`axllent/mailpit:v1.21`, host port 18025, ready on `/`). Every
image built with `sbt Docker/publishLocal` and the companion's pulled before the first `up`.

| Criterion | Bound | Measured | Holds |
|---|---|---|---|
| SC-003, first `ankka local up`, images present | 5 minutes | 47 s | yes |
| SC-003, second `up` with nothing changed | 30 s | 8 s, every service `running`, nothing restarted | yes |
| SC-002, memory after ten minutes idle, summed over the compose project (`docker stats --no-stream`) | 8 GiB | 2.80 GiB | yes |
| SC-004, ten minutes beside a four-core load | every service ready, nothing lost | `ankka local status`: the six services and the companion `ready`; the item recorded before the load read back whole (`200`, `{"id":"m","name":"pen","count":2}`) | yes |

The memory, by container: the six services 366 to 447 MiB each (each held to 512 MiB), Kafka
333 MiB, Postgres 173 MiB, the companion 12 MiB.

The load was four `yes > /dev/null` loops for 600 seconds, one core each: `stress-ng` is not on the
machine, and the quickstart names it or a build of the repository.

## The first run, and what it found

A first run at `af1f7fa45` (CLI before the review's fixes) measured the first `up` at 44 s and the
idle memory at 2.82 GiB, but its second `up` was refused in 2 s: `the companion "mail" names port
18025, which is in use on this machine`. A running companion's own named host port was not counted
as the project's, so any project with a companion could not be brought up twice. Fixed in
`5aba576cf`, with a scripted case red without it; the figures above are the second run's, on the
fixed CLI, from a fresh project.
