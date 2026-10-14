# Quickstart: Images for Both Architectures

How to see the feature work, from a unit test to a registry. Each step names what proves it; the
decisions are in [research.md](research.md).

## Prerequisites

Docker with buildx (Docker Desktop and OrbStack both have it), `kubectl` for the overlay suite,
`uv` for the features check, `gh` for the workflows. A laptop of either architecture; an Apple
silicon one is an arm64 cluster already.

## 1. Offline: the detail, the workflows' shape, the template

```bash
sbt -Dankka.cluster.tests=off 'operator/testOnly *LifecycleRulesSuite *PodProblemSuite'   # "image X has no build for this node's architecture (arm64)"
sbt -Dankka.cluster.tests=off 'cli/testOnly *WorkflowShapeSuite'                          # release.yml, cluster.yml, cluster-suites.py pinned
sbt -Dankka.template.tests=scala 'cli/testOnly *TemplateSuite'                            # the expansion's build names ANKKA_IMAGE_PLATFORMS
python3 .github/cluster-suites.py | python3 -m json.tool | grep -c '"arch"'               # twice the number of suites
just features                                                                             # 0 findings
just docs                                                                                 # the three pages build
```

## 2. The local build is unchanged

```bash
sbt docker:publishLocal                      # host architecture only, same tags as before
docker image inspect ankka-operator:latest --format '{{.Architecture}}'   # arm64 on an Apple silicon laptop, amd64 elsewhere
ANKKA_IMAGE_PLATFORMS=linux/amd64,linux/arm64 sbt operator/Docker/publish   # refused: names DOCKER_REPOSITORY
```

Expected: the first two as they always were; the third fails before any build with a message naming
both variables.

## 3. A multi-platform publish to a throwaway registry

```bash
docker run -d -p 5000:5000 --name reg registry:2
DOCKER_REPOSITORY=localhost:5000 ANKKA_IMAGE_PLATFORMS=linux/amd64,linux/arm64 sbt operator/Docker/publish
docker buildx imagetools inspect localhost:5000/ankka-operator:$(sbt -no-colors 'print operator/Docker/version' | tail -1)
docker rm -f reg
```

Expected: the inspect lists `linux/amd64` and `linux/arm64` under one tag, and `latest` the same.
A `docker-container` builder is needed (`docker buildx create --use` once); the `docker` driver
cannot push a multi-platform image.

## 4. The wrong architecture, on k3s

```bash
caffeinate -i sbt 'set controlPlane / Test / logBuffered := false' 'controlPlane/testOnly *SampleDeploymentClusterSuite -- *architecture*'
```

Expected: the case builds a sample image for the other architecture from a two-line Dockerfile,
loads it, applies it, and the report reads `Failed` with the image and `arm64` or `amd64` named
within two minutes. The k3s log line with the kubelet's own words is printed once (V1).

## 5. The cluster workflow, both legs, on demand

```bash
gh workflow run cluster --ref 049-multi-architecture-images -f suite=SampleDeploymentClusterSuite -f arch=arm64
gh workflow run cluster --ref 049-multi-architecture-images                      # every suite, both legs
gh run watch
```

Expected: one job per suite per architecture, named `<Suite> (arm64)` and `<Suite> (amd64)`, each
green; the sidecar's arm64 leg builds the Rust module.

## 6. The release, on the next tag

After the tag's `images` job: each of the seven images at the version and at `latest` lists both
platforms on ghcr.io and on the installations' cache:

```bash
for i in ankka-operator ankka-controlplane ankka-sidecar ankka-proxy ankka-storage-mover sample-shopping-cart ankka-console; do
  docker buildx imagetools inspect ghcr.io/thinkmorestupidless/$i:<version> --format '{{range .Manifest.Manifests}}{{.Platform.OS}}/{{.Platform.Architecture}} {{end}}'
done
```

Then tupile: the overlay in the infrastructure repository at that tag, the control plane and the
operator `Ready`, the sample applied and exposed. Record the time to `Ready` in research's
Measurements (SC-002).
