# ankka for Python

Components in Python — event sourced and key value entities, views, consumers, workflows, timed actions,
agents and HTTP endpoints — hosted by the ankka sidecar, the same runtime that hosts a Scala service.
Your process decides what should happen; the sidecar owns sharding, the journal, projections, timers,
HTTP, the agent loop and the model key.

- [Your first service in Python](https://docs.ankka.cloud/get-started/first-service-python/)
- [Services in other languages](https://docs.ankka.cloud/concepts/polyglot/) — how the sidecar model works
- [Python SDK reference](https://docs.ankka.cloud/reference/python-sdk/) — the API, and developing this SDK
- [Sidecar protocol](https://docs.ankka.cloud/reference/sidecar-protocol/)

The same pages are in this repository under
[`docs/`](https://github.com/thinkmorestupidless/ankka/tree/main/docs).

```bash
uv sync && uv run pytest -q && uv run mypy && uv run conformance
```
