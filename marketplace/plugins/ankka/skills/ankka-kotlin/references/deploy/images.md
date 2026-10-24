# Build an image

> Package a Scala, Java, Kotlin, Python, TypeScript, Go or Rust ankka service as a container image, tag it, and get it onto a cluster by pushing to a registry or loading it into a local kind node.

Source: https://docs.ankka.cloud/deploy/images/
A service is deployed as a container image, and the descriptor names that image. A Scala, Java or Kotlin service's image
holds the service and the ankka runtime in one JVM. A Python, TypeScript or Go service's image holds only your
process and the SDK; the platform supplies the runtime as a sidecar container beside it. A Rust service's
image holds only its WebAssembly module; the platform copies the module out of it and runs its own runtime
with the module loaded.

## A Scala service

A service created from the template is packaged by sbt-native-packager:

```bash
sbt Docker/publishLocal
```

That builds two tags of one image into the local Docker daemon, both named after the service:

| Tag | Example |
|---|---|
| the build's version | `orders:0.1.0-SNAPSHOT` |
| `latest` | `orders:latest` |

The image is based on `eclipse-temurin:21-jre`, exposes port 9000 and starts the service's main class.
The relevant settings in the template's `build.sbt` are:

```scala
Docker / packageName  := serviceName,
dockerBuildxPlatforms := sys.env.get("ANKKA_IMAGE_PLATFORMS").toSeq.flatMap(_.split(',')).map(_.trim).filter(_.nonEmpty),
dockerBaseImage       := "eclipse-temurin:21-jre",
dockerUpdateLatest    := true,
dockerExposedPorts    := Seq(9000),
Docker / version      := version.value.replace('+', '-')
```

**A Docker tag may not contain `+`.** A version derived from git, such as `0.1.0+3-abc1234-SNAPSHOT`,
does. The last setting replaces `+` with `-` so any version the build produces is a valid tag. Keep it if
you change how the build is versioned.

Set `Docker / dockerRepository` to a registry, for example `registry.example.com/acme`, to tag the image
for that registry, and `sbt Docker/publish` to push it there.

## A Java service

A service created by `ankka init --language java` is packaged by Jib, with no Dockerfile:

```bash
./mvnw package jib:dockerBuild                       # into the local Docker daemon
./mvnw package jib:build -Dimage=registry.example.com/acme/orders:1.4.2   # to a registry
```

The image is based on `eclipse-temurin:21-jre`, exposes port 9000 and starts the service's main class.
Without `-Dimage` it is named `<service>:local`. The project's `Dockerfile` builds the same image in two
stages with the project's own Maven wrapper, for a build machine that has Docker and nothing else.

## A Kotlin service

A service created by `ankka init --language kotlin` is laid out by Gradle's `application` plugin and
copied into an image by the project's `Dockerfile`:

```bash
./gradlew installDist
docker build -t orders:0.1.0 .
```

The image is based on `eclipse-temurin:21-jre`, exposes port 9000 and starts the script `installDist`
wrote. The image only copies files, so one build serves both architectures.

## A Python service

A Python service's image contains your code and the ankka SDK, and nothing else. It needs no JVM and no
runtime: those are the sidecar's, and the platform injects the sidecar at deploy time at the version the
platform runs. For a project that depends on the SDK from PyPI (`ankka==0.5.0` in its `pyproject.toml`),
the image is `python:3.12-slim`, `pip install .` of the project, and a `CMD` that starts its process.
The sample in the ankka repository installs the SDK from its own source tree instead, so its Dockerfile
is the same recipe with the SDK's sources copied in:

```text
FROM python:3.12-slim
WORKDIR /app
COPY pyproject.toml README.md ./
COPY src ./src
COPY proto ./proto
RUN pip install --no-cache-dir . && rm -rf src proto
COPY examples ./examples
ENV ANKKA_PROCESS_PORT=9010
CMD ["python", "-m", "examples.shopping_cart.main"]
```

The process listens on port 9010 for the sidecar. It declares no HTTP port, because the sidecar serves
your routes. Build it with plain Docker:

```bash
docker build -t my-cart:1.0.0 .
```

The descriptor then says the image is a process, and which protocol version its SDK speaks. See
[Deploy a service](deploy-a-service.md#services-in-another-language).

## A TypeScript service

A TypeScript service's image is the same idea on Node: your code and the SDK, no JVM, no runtime. Node runs
the service from source, so there is no build step in the image either. For a project that depends on the
SDK from npm (`ankka@0.6.0` in its `package.json`):

```text
FROM node:24-slim
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev
COPY . .
ENV ANKKA_PROCESS_PORT=9010
CMD ["node", "main.ts"]
```

The sample in the ankka repository runs against the SDK's own source tree instead, so its Dockerfile copies
the SDK's sources in, generates the protocol stubs with `npm run proto`, and starts the process with
`node --conditions=ankka-source`, a flag a project that installed the package does not need. The process
listens on port 9010 for the sidecar and declares no HTTP port. Build it with plain Docker:

```bash
docker build -t my-cart:1.0.0 .
```

## A Go service

A Go service's image holds one static executable and nothing else: no language runtime, no SDK sources
and no shell. The template `ankka init --language go` writes a two-stage `Dockerfile`. The first stage,
`golang:1.27-alpine`, downloads the module's dependencies and builds the service with `CGO_ENABLED=0`, so
the executable links no C library. The second, `gcr.io/distroless/static-debian12:nonroot`, copies that
executable in and runs it as an unprivileged user:

```text
# Your process, nothing else: the platform runs the sidecar beside it in the same pod.
# Go builds one static executable, so the image that runs it holds no language runtime and no shell.
FROM golang:1.27-alpine AS build
ENV CGO_ENABLED=0
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN go build -trimpath -ldflags='-s -w' -o /service .

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /service /service
# The port the sidecar dials, on loopback inside the pod.
ENV ANKKA_PROCESS_PORT=9010
USER nonroot
ENTRYPOINT ["/service"]
```

The image has no shell, so there is no shell to `docker exec` into and an `ENTRYPOINT` in shell form
cannot work; the service's logs and the platform's traces are how it is observed. The process listens on
port 9010 for the sidecar and declares no HTTP port. Build it with plain Docker:

```bash
docker build -t my-cart:1.0.0 .
```

## A Rust service

A Rust service's image carries its WebAssembly module and one command that copies it. The platform runs
the image once per pod, as an init container with a shared volume mounted at `/ankka/module`, and then
starts its own runtime image with the module loaded; the image never runs as the service. Build the module
first, then an image from `busybox` that copies it. The example cart's Dockerfile, built with its cargo
workspace as the context; in your own project the module under `target/` is named after your crate, with
`-` as `_`:

```text
# The module image for a wasm-hosted service: the module and one command that copies it where the
# platform's runtime loads it from. The platform runs this once per pod, as an init container with
# /ankka/module mounted; the runtime image is the platform's own. Build the module first, from
# sdks/rust: cargo build -p shopping-cart --release --target wasm32-unknown-unknown
FROM busybox:1.37
COPY target/wasm32-unknown-unknown/release/shopping_cart.wasm /service.wasm
CMD ["cp", "/service.wasm", "/ankka/module/service.wasm"]
```

```bash
cargo build --release --target wasm32-unknown-unknown
docker build -t my-cart-module:1.0.0 .
```

**The image's contract is exactly that copy.** Run with `/ankka/module` mounted, it writes one file,
`service.wasm`, there and exits 0. An image that exits non-zero, or cannot be pulled, fails the pod's start,
and `ankka services get` reports the reason. The image can be any base with a `cp`; `busybox` keeps it a
few hundred kilobytes more than the module. The descriptor says `"hosting": "wasm"` and the protocol the
crate speaks. See [Deploy a service](deploy-a-service.md#services-in-another-language).

## A web-hosted service

A web-hosted service's image is any program that serves HTTP on the port the `PORT` variable names, with
nothing of the platform's in it. The template `ankka init --language web` writes a multi-stage
`Dockerfile` for a Node server that runs as an unprivileged user:

```bash
npm install && docker build -t shop-web:latest .
```

The platform runs its proxy beside the image in the same pod. See [Deploy a user
interface](web-hosting.md).

## Build for the cluster's architecture

The platform runs on amd64 and arm64 nodes, and every image of the platform's own — the operator, the
control plane, the sidecar, the proxy, the storage mover, the console and the sample — is published for
both under one tag, so a cluster of either pulls the same tag and is served its architecture. Your
service's image is yours to build, and an image built on an amd64 laptop runs on amd64 nodes only.

| Hosting | How to build for both, or for the one your cluster has |
|---|---|
| Scala | `ANKKA_IMAGE_PLATFORMS=linux/amd64,linux/arm64 sbt Docker/publish`. The template's build reads the variable; unset, the image is this machine's architecture, which is what `kind load` wants. A multi-platform build pushes and cannot be loaded, so it needs `Docker / dockerRepository` set. |
| Java | `./mvnw package jib:build -Dimage=…`; Jib builds for the platforms its `<from><platforms>` lists, `linux/amd64` unless set. |
| Kotlin, Python, TypeScript, Go, web | `docker buildx build --platform linux/amd64,linux/arm64 --push -t registry.example.com/acme/orders:1.4.2 .` |
| Rust | nothing: a WebAssembly module has no architecture, and the runtime that loads it is the platform's. |

Building for the architecture your machine is not needs buildx with a `docker-container` builder
(`docker buildx create --use`) and QEMU, which Docker Desktop and OrbStack provide; a JVM or Node image
copies files and compiles nothing under emulation. A Go image's build stage does compile under emulation
for the other architecture, which is slower than a native build and needs nothing more. The deploy
workflow a generated project carries builds for both.

An image with no build for the node it lands on cannot start, and the service's report says so rather
than reporting a crash loop:

```text
Failed    image ghcr.io/acme/orders:1.4.2 has no build for this node's architecture (arm64)
```

Rebuild for that architecture, or for both, and apply again.

## Get the image onto the cluster

A cluster runs an image it can find. There are two ways for it to find yours.

**Push to a registry** the cluster can pull from, and name the image by its full reference in the
descriptor:

```bash
docker tag orders:0.1.0 registry.example.com/acme/orders:0.1.0
docker push registry.example.com/acme/orders:0.1.0
```

**Load it into a local kind node**, which copies the image from your Docker daemon straight into the
cluster's container runtime, with no registry:

```bash
kind load docker-image orders:latest --name ankka
```

The platform renders every workload with `imagePullPolicy: IfNotPresent`, so a node uses an image that is
already present and pulls only when it is not. That is what makes a loaded image usable at all:
Kubernetes' own default for an image tagged `latest` is `Always`, which would ignore the loaded copy, try
to pull, and fail with `ErrImagePull`.

## A private registry

A registry that requires authentication needs the cluster to hold a credential, because it is the
cluster that pulls, not you. Register one per project:

```bash
ankka projects registry set checkout --server ghcr.io --username octocat --password-stdin
```

`--password-stdin` reads the secret from standard input, so it is in no process listing and no shell
history. `--password <secret>` is there for interactive use.

The credential is written straight into the project's Kubernetes namespace as a Secret, and the
platform records only that it exists, who set it and when. There is no route, command or output
format that returns it: a lost credential is replaced rather than recovered.

```bash
ankka projects get checkout
```

```text
id            checkout
name          Checkout
organization  acme
services      3
registry      ghcr.io as octocat, set 2026-09-25 by sam@example.com
```

One credential serves every service in the project, and applies to the next deploy of each — a
service already running keeps the credential it started with until `ankka services restart` replaces
its pods.

Any organization member may register a registry, including a
[deploy token](../platform/identity.md#machine-accounts), because the job that pushes an image is
the natural thing to register where it pushed it.

### Clearing one

```bash
ankka projects registry clear checkout
```

The project stops claiming the credential and the next deploy of each of its services stops naming
it. The Secret itself stays in the cluster: the platform can write a credential and can never read
or delete one, and a Secret nothing references does nothing. Delete it with cluster access if it
must be gone.

A service running an image that is already on its node keeps working after this, because a node does
not re-pull an image it holds. The next *new* image is what fails, with `ImagePullBackOff` in
`ankka services get`.

### GitHub Container Registry

A package published to `ghcr.io` is private by default. Either make the package public, or register a
credential whose password is a personal access token with the `read:packages` scope:

```bash
ankka projects registry set checkout --server ghcr.io --username octocat --password-stdin
```

The token that a GitHub Actions job uses to *push* is not the one to register here: it lives only for
the length of that job. See [Deploy from GitHub Actions](ci.md).

## Tag by version for anything real

`IfNotPresent` has a consequence. A node that already holds `orders:latest` keeps running that copy after
you build and load a new one under the same tag, because the tag did not change. Locally, `kind load`
replaces the node's copy and `ankka services restart orders` starts pods from it. Anywhere else, give
each build its own tag and apply a descriptor that names it. A new tag is a new descriptor, a new
generation and a rolling update, and the history of the service then records which build ran when.
