# Quickstart: checking the notice works

Prerequisites: the branch built (`sbt cli/stage` or `sbt cli/run`), `uv`, a free loopback port.

## 1. A fake latest release

```bash
mkdir -p /tmp/latest && printf '{"version":"99.0.0","released":"2026-11-09"}' > /tmp/latest/latest.json
python3 -m http.server 8765 --directory /tmp/latest &
export ANKKA_LATEST_URL=http://127.0.0.1:8765/latest.json
export ANKKA_CONFIG=$(mktemp -d)/config.json   # a fresh remembered check, not yours
```

## 2. The command line

| Run | Expect |
|---|---|
| `ankka version` | the version, then `ankka version 99.0.0 is available (you're currently using version <X>)` on stderr (a development build: no notice; build from a tag, or see the suite) |
| `ankka config get` twice | the second gives no notice (once a day) |
| `ankka config get -o json` | no notice |
| `CI=true ankka version` | no notice |
| `ANKKA_NO_UPDATE_CHECK=1 ankka version` | no notice |
| `ankka version 2>&1 \| cat` | no notice (error output not a terminal) |
| stop the server, `ankka version` | the version, nothing else, within 2 s |

The suite runs all of these against a release build's version: `sbt 'cli/testOnly *ReleaseCheckSuite'`.

## 3. The MCP server

`sbt 'cli/testOnly *McpServerSuite'` drives the server with a fake latest release: the instructions,
`whoami`, `installation_status`, `search_docs` and `read_doc` each carry what
`contracts/mcp-tools.md` says.

## 4. The documentation

```bash
uv run --project tools/docs docs check                        # green; then add "ankka-go@v0.14.0" by hand
                                                              # to a page outside a pattern: red, naming it
uv run --project tools/docs docs build --release 0.17.0
grep -r "ankka-go@v" target/docs-site/get-started/install/index.html   # 0.17.0
cat target/docs-site/latest.json                              # {"version":"0.17.0",...}
git status --short docs/                                      # nothing: the build wrote no source
uv run --project tools/docs pytest -q tools/docs/tests
sbt 'cli/testOnly *DocsBundleSuite'                           # bundled pages name the CLI's release
```

## 5. Features

`just features` is green.
