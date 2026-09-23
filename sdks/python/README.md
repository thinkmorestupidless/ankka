# ankka for Python

Components in Python, hosted by the ankka sidecar over the sidecar protocol. See
`docs/polyglot.md` at the repository root for the walkthrough, and `protocol/` for the protocol,
the encoding and the fixtures this SDK is built to.

```
uv sync                                          # install
uv run python scripts/proto.py                   # copy protocol/ in and regenerate the stubs
uv run pytest                                    # unit testkits, the encoding fixtures, the servicer
uv run pytest -m slow                            # through a real sidecar and Postgres (Docker)
uv run mypy && uv run mypy examples              # strict
uv run python scripts/conformance.py             # start the reference service, run the platform's conformance suite
uv run python -m examples.shopping_cart.main     # the shopping cart, beside `docker compose --profile polyglot up`
```
