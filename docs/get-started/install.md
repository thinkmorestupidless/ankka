---
title: Install the tools
description: Install what ankka needs on your machine, build the ankka CLI from the repository, and make the Scala libraries and Python SDK available to your own projects.
kind: tutorial
languages: [scala, python]
related: [get-started/first-service-scala.md, get-started/first-service-python.md, reference/limitations.md]
---

# Install the tools

ankka is used through three things: libraries your service depends on, the `ankka` command-line tool,
and a platform to deploy to. This page installs the first two. The third has its own page,
[Install a local platform](../platform/install-local.md), and you need it only once you want to deploy.

Some of ankka is not yet packaged. The CLI is built from the repository rather than installed, and the
Python SDK is installed from the repository rather than from PyPI. This page says where that applies.

## Prerequisites

| Tool | Needed for | Version |
|---|---|---|
| JDK | Scala services, the CLI, building the platform's images | 21 |
| [sbt](https://www.scala-sbt.org/) | Scala services, the CLI, `ankka init` | any recent 1.x |
| Docker | running Postgres locally, integration tests, building images | any recent |
| [uv](https://docs.astral.sh/uv/) and Python | Python services | Python 3.12 |
| [kind](https://kind.sigs.k8s.io/) and `kubectl` | a local platform to deploy to | recent |
| [just](https://github.com/casey/just) | optional shortcuts in the repository | any |

Docker is required even for a service you never deploy, because the integration test kits start a
throwaway Postgres in a container. No model API key is needed for anything on this page.

## Get the repository

The CLI, the Python SDK and the local platform are built from the ankka repository:

```bash
git clone https://github.com/thinkmorestupidless/ankka.git
cd ankka
```

## Build the CLI

The CLI is a JVM program built with sbt. There is no binary release or package yet:

```bash
sbt cli/stage                                        # builds cli/target/universal/stage/bin/ankka
export PATH="$PWD/cli/target/universal/stage/bin:$PATH"
ankka version
```

Add the `export` line to your shell profile, with the absolute path, to keep `ankka` on your `PATH`.
`ankka init` also needs `sbt` on the `PATH`, because it runs `sbt new` to expand the service template.

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
there. A CLI built from the repository reports its own version, which is a snapshot unless you built
it from a release tag, and `ankka init` writes that version into the new project. Publish the libraries
from the same checkout so the project can resolve them:

```bash
sbt publishLocal                                     # the six libraries, into ~/.ivy2/local
```

Run it again whenever you pull a newer version of the repository and want your projects to use it.

## Install the Python SDK

The Python SDK lives in the repository under `sdks/python` and is not on PyPI. Its protocol stubs are
generated from the repository's protocol definitions, so generate them once before installing:

```bash
cd sdks/python
uv sync                                              # the SDK and its development tools
uv run python scripts/proto.py                       # generate src/ankka/_proto from protocol/
cd ../..
```

Then add it to your own project as an editable path dependency:

```bash
uv add --editable /path/to/ankka/sdks/python
```

A Python service also needs the ankka sidecar image, which runs beside your process and hosts
everything stateful. Build it into your local Docker daemon from the repository:

```bash
sbt sidecar/docker:publishLocal                      # ankka-sidecar:latest
```

## What you have now

- `ankka` on your `PATH`, for creating services, running the local console and operating a platform.
- The Scala libraries resolvable by sbt, or the Python SDK installed in your project.
- For Python, the `ankka-sidecar` image in your local Docker daemon.

Continue with [your first service in Scala](first-service-scala.md) or
[your first service in Python](first-service-python.md).
