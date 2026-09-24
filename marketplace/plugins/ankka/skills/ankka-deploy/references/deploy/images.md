# Build an image

> Package a Scala or Python ankka service as a container image, tag it, and get it onto a cluster by pushing to a registry or loading it into a local kind node.

Source: https://docs.ankka.cloud/deploy/images/
A service is deployed as a container image, and the descriptor names that image. A Scala service's image
holds the service and the ankka runtime in one JVM. A Python service's image holds only your process and
the SDK; the platform supplies the runtime as a sidecar container beside it.

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
Docker / packageName := serviceName,
dockerBaseImage      := "eclipse-temurin:21-jre",
dockerUpdateLatest   := true,
dockerExposedPorts   := Seq(9000),
Docker / version     := version.value.replace('+', '-')
```

**A Docker tag may not contain `+`.** A version derived from git, such as `0.1.0+3-abc1234-SNAPSHOT`,
does. The last setting replaces `+` with `-` so any version the build produces is a valid tag. Keep it if
you change how the build is versioned.

Set `Docker / dockerRepository` to a registry, for example `registry.example.com/acme`, to tag the image
for that registry, and `sbt Docker/publish` to push it there.

## A Python service

A Python service's image contains your code and the ankka SDK, and nothing else. It needs no JVM and no
runtime: those are the sidecar's, and the platform injects the sidecar at deploy time at the version the
platform runs. For a project that depends on the SDK from PyPI (`ankka==0.3.1` in its `pyproject.toml`),
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

## Tag by version for anything real

`IfNotPresent` has a consequence. A node that already holds `orders:latest` keeps running that copy after
you build and load a new one under the same tag, because the tag did not change. Locally, `kind load`
replaces the node's copy and `ankka services restart orders` starts pods from it. Anywhere else, give
each build its own tag and apply a descriptor that names it. A new tag is a new descriptor, a new
generation and a rolling update, and the history of the service then records which build ran when.
