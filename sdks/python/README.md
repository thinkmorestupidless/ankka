# ankka for Python

Components in Python, hosted by the ankka sidecar over the sidecar protocol. See
`docs/polyglot.md` at the repository root for the walkthrough, and `protocol/` for the protocol,
the encoding and the fixtures this SDK is built to.

```
uv sync            # install
uv run proto       # copy protocol/ in and regenerate the stubs
uv run test        # unit testkits and the encoding fixtures
uv run typecheck   # mypy --strict
uv run conformance # start the reference service and run the platform's conformance suite
uv run example     # start the shopping cart against a compose sidecar
```
