# Install the tools

> Install what ankka needs on your machine, install the ankka CLI with Homebrew or from a release, and make the Scala libraries and the Python and TypeScript SDKs available to your own projects.

Source: https://docs.ankka.cloud/get-started/install/
ankka is used through three things: libraries your service depends on, the `ankka` command-line tool,
and a platform to deploy to. This page installs the first two. The third has its own page,
[Install a local platform](../platform/install-local.md), and you need it only once you want to deploy.

The CLI is installed with Homebrew, or unpacked from a release; the Python SDK comes from PyPI and the
TypeScript SDK from npm. One piece is not yet packaged: the sidecar image that hosts a Python or TypeScript
service is built from the repository,
and this page says where that applies.

## Prerequisites

| Tool | Needed for | Version |
|---|---|---|
| JDK | Scala services, the CLI (Homebrew installs its own), building the platform's images | 21 |
| [sbt](https://www.scala-sbt.org/) | Scala services, `ankka init` | any recent 1.x |
| Docker | running Postgres locally, integration tests, building images | any recent |
| [uv](https://docs.astral.sh/uv/) and Python | Python services | Python 3.12 |
| [Node.js](https://nodejs.org/) | TypeScript services | 22.22 or later; 24 recommended |
| [kind](https://kind.sigs.k8s.io/) and `kubectl` | a local platform to deploy to | recent |
| [just](https://github.com/casey/just) | optional shortcuts in the repository | any |

Docker is required even for a service you never deploy, because the integration test kits start a
throwaway Postgres in a container. No model API key is needed for anything on this page.

## Install the CLI

On macOS, and on Linux with [Homebrew](https://brew.sh/), the CLI comes from ankka's tap. The formula
installs the JDK it runs on, so nothing else is needed:

```bash
brew install thinkmorestupidless/tap/ankka
ankka version
```

`brew upgrade ankka` moves to a newer release. `ankka init` also needs `sbt` on the `PATH`, because it
runs `sbt new` to expand the service template; every other command works without it.

Anywhere else, every release carries the same CLI as a zip on its
[GitHub release](https://github.com/thinkmorestupidless/ankka/releases): unpack it and put its `bin`
directory on your `PATH`. It needs a JDK 21 on the `PATH` or in `JAVA_HOME`.

```bash
version=0.3.1                                        # a release from the releases page
curl -LO "https://github.com/thinkmorestupidless/ankka/releases/download/v\$version/ankka-cli-\$version.zip"
unzip "ankka-cli-\$version.zip"
export PATH="\$PWD/ankka-cli-\$version/bin:\$PATH"
ankka version
```

## Get the repository

The Python and TypeScript SDKs and the local platform are built from the ankka repository, and so is a CLI newer than
the last release:

```bash
git clone https://github.com/thinkmorestupidless/ankka.git
cd ankka
```

To run the CLI from a checkout rather than a release, `sbt cli/stage` builds it into
`cli/target/universal/stage/bin/ankka`; that CLI reports a snapshot version, and `ankka init` writes
that version into the projects it creates, which is what the `sbt publishLocal` below is for.

## Make the Scala libraries available

A Scala service depends on six libraries, published under the organization `com.thinkmorestupidless`:

| Artifact | What it holds |
|---|---|
| `ankka-core` | effects, identifiers, codecs, component descriptors |
| `ankka-sdk` | the component API: entities, workflows, views, consumers, timers, the component client |
| `ankka-runtime` | the runtime that interprets effects: sharding, persistence, projections, timers |
| `ankka-http` | HTTP endpoints and the server |
| `ankka-agent` | agents: model providers, session memory, tools, the agent loop |
| `ankka-testkit` | unit and integration test support |

Released versions are on Maven Central, and a project created from the template resolves them from
there; with a released CLI there is nothing to do. A CLI built from the repository reports a snapshot
version, and `ankka init` writes that version into the new project, so publish the libraries from the
same checkout for the project to resolve them:

```bash
sbt publishLocal                                     # the six, and the control plane's wire library, into ~/.ivy2/local
```

Run it again whenever you pull a newer version of the repository and want your projects to use it.

## Install the Python SDK

The Python SDK is the package [`ankka`](https://pypi.org/project/ankka/) on PyPI, published at every
release with the platform's version. Add it to your own project, pinned to the version of the platform
you deploy to:

```bash
uv add "ankka==0.5.0"
```

The `testkit` extra (`uv add "ankka[testkit]==0.5.0"`) brings the dependencies of the integration
testkit, which starts Postgres and the sidecar in containers. To use an SDK that is not released yet,
install it from a checkout of the repository instead: generate its protocol stubs with `uv run python
scripts/proto.py` in `sdks/python`, then `uv add --editable /path/to/ankka/sdks/python`.

A Python service also needs the ankka sidecar image, which runs beside your process and hosts
everything stateful. It is not on a public registry yet, so build it into your local Docker daemon from
the repository:

```bash
sbt sidecar/docker:publishLocal                      # ankka-sidecar:latest
```

## Install the TypeScript SDK

The TypeScript SDK is the package [`ankka`](https://www.npmjs.com/package/ankka) on npm, published at every
release with the platform's version. Add it to your own project, pinned to the version of the platform you
deploy to:

```bash
npm install ankka@0.5.0
npm install -D testcontainers @testcontainers/postgresql      # only for the integration testkit
```

Node.js 22.22 or later runs a TypeScript service from source, with no build step. To use an SDK that is not
released yet, build it from a checkout of the repository instead: `npm ci && npm run proto && npm run build`
in `sdks/typescript`, then `npm install /path/to/ankka/sdks/typescript`. A TypeScript service needs the same
`ankka-sidecar` image as a Python one, built into your local Docker daemon with `sbt sidecar/docker:publishLocal`.

## What you have now

- `ankka` on your `PATH`, for creating services, running the local console and operating a platform.
- The Scala libraries resolvable by sbt, or the Python or TypeScript SDK installed in your project.
- For Python or TypeScript, the `ankka-sidecar` image in your local Docker daemon.

Continue with [your first service in Scala](first-service-scala.md),
[your first service in Python](first-service-python.md) or
[your first service in TypeScript](first-service-typescript.md).
