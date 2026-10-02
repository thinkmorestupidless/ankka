# Contract: the build, CI, the release and the local installation

## sbt

| Project | Directory | Depends on | Image | Published |
|---|---|---|---|---|
| `proxyCore` | `proxy-core/` | nothing of ankka's, nothing of Pekko's | none | no (`publish / skip`) |
| `proxy` | `proxy/` | `proxyCore`, `runtime`, `http`; `testPki`, `testkit` in Test | `ankka-proxy` | no |
| `cli` | | gains `proxyCore` | | |
| `controlPlane` | | no new classpath dependency; its image task builds the proxy's image | | |

`proxyCore` depending on nothing is a build fact, as `crd` is: it is what lets the CLI's native
image carry the engine. A dependency on `core` would be harmless and is not needed; one on `runtime`
would put an actor system in the CLI.

Both join root's `.aggregate`, so `docker:publishLocal` and `buildAll` build the image with no other
change. The stale comment "exactly the two images" at `build.sbt:662-665` is corrected.

Image tasks, hooked on both `Test / test` and `Test / testOnly`, skipped by
`-Dankka.cluster.tests=off`:

- `controlPlane`'s `sampleImageForClusterTests` also runs `proxy / Docker / publishLocal` and
  `docker build -t sample-shopping-cart-web:<tag> samples/shopping-cart-web`;
- the stand-in process's image is built by the suite that uses it.

The template resource generator's language list (`build.sbt:580`) gains `web`, and merges
`templates/common-service` for Python, TypeScript and Rust only.

`-Dankka.template.tests` gains the value `web`; the switch is already forwarded to forked tests.
`ankka.rendering.pin` is new and joins the forwarded list. The forked tests of `proxyCore`, `proxy`
and `cli` run with `-Djdk.httpclient.allowRestrictedHeaders=host`, the proxy's image is started with
it, and the CLI's native image is built with it and sets it first thing in `main` (research R2).

`proxy` depends on `http % "compile->compile;test->test"`, for the frozen parser its features use.

## Images

| Image | Built by | Loaded by `deploy-local.sh` | Pushed by a release |
|---|---|---|---|
| `ankka-proxy` | sbt | yes | yes |
| `sample-shopping-cart-web` | `docker build` | yes | no |

`ClusterImages.importInto` gains a build hint for each prefix. A suite names both by
`BuildInfo.imageTag`, replacing the `:latest` a checked-in `service.json` carries for the local
installation.

## `deploy-local.sh`

- after `sbt docker:publishLocal`: `docker build -t sample-shopping-cart-web:latest samples/shopping-cart-web`;
- `kind load` of `ankka-proxy:latest` and `sample-shopping-cart-web:latest`;
- the operator's `ANKKA_HTTPS_PORT` comes from the overlay, with nothing in the script: an overlay
  that only works from the script is not an overlay.

The operator is restarted as today; the proxy is injected, so it has nothing to restart.

## Overlays

- `components/operator/operator.yaml`: `ANKKA_PROXY_IMAGE: ankka-proxy:latest` and
  `ANKKA_HTTPS_PORT`, a placeholder the overlays replace from `ankka-platform.httpsPort`.
- `overlays/local`: the replacement.
- `overlays/cloud`: the replacement; an `images:` entry for `ankka-proxy`; a patch,
  `proxy-image.yaml`, on the container **`ankka-operator`**, setting `ANKKA_PROXY_IMAGE` to the
  registry's.

`RemoteOverlaySuite` asserts, for the proxy as for the sidecar: the variable appears exactly once in
the remote render, names the registry, and the default is gone; and that the operator Deployment
has exactly one container.

## `ci.yml`

| Filter | Gains |
|---|---|
| `scala-build` | `proxy/**`, `proxy-core/**` |
| `templates` | `cli/src/main/templates/common-service/**` |
| `web` (new) | the workflow, `scala-build`, `templates`, `cli/src/main/templates/web/**`, `samples/shopping-cart-web/**`, `cli/src/test/scala/**/cli/WebTemplateSuite.scala` |

`samples/**` already claims the sample for the Scala job. `features/**` and `GLOSSARY.md` are
claimed by feature 019's filters (research R17). Every pattern here matches a file this feature
adds, which `ci-coverage.py` requires.

The `web` filter's patterns match files the template, its suite and the sample add, so the job is
added after them. A new job, `web`, on Node 24 with Java and sbt:

```text
cd samples/shopping-cart-web && npm ci && npm run typecheck && npm test
sbt -Dankka.cluster.tests=off -Dankka.template.tests=web 'cli/testOnly *WebTemplateSuite'
```

The suite fails, not skips, when `node` or `npm` is missing and `web` was asked for.

The `build` job runs the loopback suites of `proxy` and `proxy-core` with everything else. The k3s
suites run on a developer's machine, as they do today.

Branch protection gains the `web` check.

## `release.yml`

- `IMAGES` gains `ankka-proxy`; the sbt line gains `proxy/Docker/publish`; the comment naming the
  count is corrected.
- The first release that carries it stops at the public-visibility check, naming the package's
  settings page. It is made public once, by hand, and the tag re-run.
- Nothing else is published by this feature: no library, no package, no crate.

`cli/native-smoke.sh` renders the web template beside the others, and starts `ankka local web`
against a descriptor with no command, requests the calling address for a service that is not
running, and expects the proxy's own 503; then passes a request with a `Host` of its own through
to a listener and expects the listener to have seen it, which fails if the restricted-header option
did not reach the image. A native image that could not carry the engine fails there.

## Documentation

New pages, each in `mkdocs.yml`'s `nav` and a skill's `pages:`:

| Page | Kind | Skill |
|---|---|---|
| `docs/get-started/first-interface.md` | tutorial | `ankka-web` (new) |
| `docs/deploy/web-hosting.md` | guide | `ankka-web`, `ankka-deploy` |
| `docs/reference/web-hosting.md` | reference: `process-contract.md` | `ankka-web`, `ankka-deploy` |

Changed: `reference/service-descriptor.md` (the fields, every refusal, the reserved variables),
`reference/limitations.md` (what web hosting does not do; the logs limitation removed),
`operate/logs.md`, `operate/troubleshooting.md`, `platform/networking.md` (the proxy, the mount
identity, the ports), `build/http-endpoints.md` (the internet is also a request under a mount),
`deploy/expose.md`, `deploy/images.md`, `deploy/run-locally.md`, `deploy/upgrading.md` (the
operator before or with the control plane), `platform/install-cloud.md` and
`reference/configuration.md` (the proxy's image), `reference/glossary.md`,
`get-started/deploy-locally.md` (the cart's interface), `reference/cli.md` and
`reference/control-plane-api.md` (regenerated, and the logs route's prose).

Every `service.json` block is held to the platform's rules by `DocumentationDescriptorsSuite`. The
guide's steps are the ones `WebTemplateSuite` runs.

`CLAUDE.md` gains the module map's two lines, the commands, and the traps this feature finds.
