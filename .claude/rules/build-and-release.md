---
paths:
  - "build.sbt"
  - "project/**"
  - ".github/**"
  - "ankka.g8/**"
  - "homebrew/**"
  - "action/**"
  - "cli/src/main/templates/**"
  - "cli/native-smoke.sh"
  - "cli/src/main/resources/META-INF/**"
  - "sdks/*/package.json"
  - "sdks/python/pyproject.toml"
  - "sdks/rust/ankka/Cargo.toml"
  - "console/package/package.json"
---

# The build, the templates and publishing

## Publishing

Nine modules are published as `com.thinkmorestupidless:ankka-<module>_3`. Eight are libraries a
*service* depends on — `core`, `sdk`, `runtime`, `http`, `grpc`, `auth-oidc`, `agent`, `testkit`;
`ankka-grpc` names grpc-java directly in its POM and no ScalaPB, which is the developer's build's, and a
service adds `auth-oidc` only when it has users of its own whose tokens it verifies, which is why it is
a module and not part of `http` (feature 022). The ninth, `controlplane-api`, is for a *client of the
control plane*: the hosted product in `ankka-cloud` provisions organizations through it (feature 011),
and a client that redefined the wire types by hand would drift from them. It still depends on `core`
alone, and its POM's compile scope says so. `templateArtifacts` names seven, the template being a
service: its own template carries no gRPC, and the suite's last case adds a gRPC endpoint to the
expansion as the documentation says to (FR-040 of feature 020), so `ankka-grpc` must resolve locally
too. The template has no users, so `auth-oidc` is a commented line in it and is not among the seven. Everything else (`crd`,
`operator`, `controlplane`, `cli`, the samples, root) carries `publish / skip := true`: a
platform-side jar cannot reach a repository by accident, and "these are not libraries" is a build
fact rather than a note.

```bash
sbt publishLocal                     # the development loop: ~/.ivy2/local, exactly nine artifacts
sbt 'show version'                   # sbt-dynver: 0.2.0 at tag v0.2.0; 0.2.0+3-sha-SNAPSHOT past it; dirty tree → -SNAPSHOT
sbt -Dankka.release.local=/tmp/repo publishSigned   # the release path against a directory, with a throwaway key
git tag v0.2.0 && git push --tags    # the only thing that publishes; the workflow stages it for approval
```

**Only a tag publishes anything.** The workflow no longer runs on pushes to `main`: the portal's
snapshot repository answers 403 for this namespace (claimed through legacy OSSRH in 2023, and
snapshot publishing there is a separate entitlement from releases), so every commit was a red
build — which is how a real failure goes unnoticed. Snapshots are `sbt publishLocal` now, which is
what the samples and `TemplateSuite` resolve anyway.

**Nothing in the build may write to a tracked file during `publish`.** A `templateVersion` task
used to rewrite `ankka.g8`'s `default.properties` from `version.value`, hung off `core`'s
`publish` and `publishLocal`. It only writes for a non-SNAPSHOT version, so it never fired locally
and always fired on a tag: it dirtied the tree, dynver appended a timestamp and `-SNAPSHOT`,
`ci-release` (which reloads the build before publishing) re-derived the version from the dirtied
tree, and the release went to the snapshot repository — 403, on a namespace with no snapshot
entitlement. Three tagged attempts failed that way. The write was useless besides: the `template`
job checks the repository out afresh, so the publish job's workspace never reached the template
that is pushed. The version is now written by that job, immediately before `git subtree split`.

**A tag publishes, and the workflow does not wait for Central.** `ci-release` stops at sbt's own
`sonaBundle` (`CI_SONATYPE_RELEASE: sonaBundle`), which zips the signed staging directory, and the
next step uploads that zip through the Central Portal's API with `publishingType=AUTOMATIC`: the
portal validates and publishes on its own, and repo1 follows in minutes to an hour. `sonaRelease`
did the same upload and then polled every 30s until PUBLISHED — v0.4.0 sat in `PUBLISHING` for
fifty minutes and v0.5.0 was cancelled by hand — and that wait is a runner doing nothing on the
account's minutes. Nothing on Central can ever be unpublished, only superseded. `sonaUpload` is the
upload that stops short and waits for the Publish button. All three are sbt's own, not a plugin's:
sbt-ci-release 1.12.1 depends on sbt-dynver and sbt-pgp only, so sbt-sonatype's
`sonatypeCentral*` names do not exist here, whatever a stale copy of that plugin in the coursier
cache suggests.

**The jobs behind `publish` need the version on repo1**, and it may not be there when they start:
a step before `ci-release` asks repo1 whether the version is already published and skips the
upload when it is, so a re-run of the tag once Central has caught up finishes the release without
a second upload the portal would refuse. That is the recovery for a cut-off or cancelled run too,
because a deployment the portal has accepted completes on Sonatype's own schedule, visible at
central.sonatype.com/publishing/deployments.

**There is no `ThisBuild / version`, and there must never be one.** The version comes from the
git tag through `sbt-dynver`; a version set in the build silently overrides the tag, which is the
one thing a release must not do. A *dirty tree* does the same thing quietly: dynver appends a
timestamp and `-SNAPSHOT`, and `ci-release` then takes the snapshot path, so a tag publishes a
snapshot and no release. That is what the first `v0.1.0` did — and the tree was not really dirty,
`git describe --dirty` was reporting stale index stat info after the runner's forced checkout.
The workflow runs `git update-index --refresh` and refuses to build from a tree that is still
dirty, rather than shipping a snapshot named like a release. `com.thinkmorestupidless.ankka.core.BuildInfo.version` carries the same value into code
— the CLI prints it, the control plane compares an application's declared runtime against it.

**The template** is `ankka.g8/` — a Giter8 template, tested by `cli`'s `TemplateSuite`, which
publishes locally, expands it into a temp directory through the real `ankka init`, and runs the
expansion's own `sbt test` and image build as subprocesses (`-Dankka.template.tests=off` skips
it; it needs `sbt` on `PATH` and Docker). CI runs it in its own `template-scala` job, once under
each sbt launcher line, because `sbt new` runs outside any build and the launcher is what expands
it. A template suite whose language is *named* in `-Dankka.template.tests` fails when its tools are
missing rather than skipping: a job that asked for it would otherwise report green having run
nothing (`TemplateSwitch.skip`). For Scala `ankka init` shells out to `sbt new` and carries
no template of its own; it passes its `BuildInfo.version` as `--ankka_version`. The directory is
named `ankka.g8` because sbt's Giter8 resolver only accepts `owner/repo.g8` and
`file://…/x.g8` — a template in a subdirectory of another repository cannot be reached by `sbt
new` at all, which is why the release workflow subtree-pushes it to `thinkmorestupidless/ankka.g8`.

**The Python, TypeScript and Rust templates are the CLI's own**, in `cli/src/main/templates/{python,
typescript,rust,common}`, rendered by `ankka init --language` (`Scaffold`). The Rust template's
`.cargo/config.toml` defines `cargo module` (the release build for `wasm32-unknown-unknown`) rather
than setting a build target, so a plain `cargo test` in the project still runs natively. Neither language has a template
tool its developers all have, so there is no second front door to drift from, and the template is
always the CLI's version. The build copies each language's files, `common/` (the compose file) and the
rendered skills from `marketplace/` onto the classpath with an `index.txt` — walked by hand, because
`unmanagedResources` drops hidden files and a template is mostly `.github/`, `.claude/` and
`.gitignore` — and the native image carries them by the `ankka/templates/**` glob, which
`native-smoke.sh` checks by rendering both. Rendering replaces exact tokens (`{{name}}`, `{{module}}`,
`{{ankka_version}}`, `{{protocol_version}}`) and nothing else, so `${{ … }}` in a workflow needs no
escaping — the opposite of the Giter8 trap. Two things a template must get right that no compiler
checks: **an event type starts as a union**, because the Python codec writes a lone dataclass with no
`type` field and a union's members with one, so a one-event service that later gains a second would
change the stored format of the events it already has; and **the schema comes out of the sidecar
image** (the compose file's `schema` service copies `/opt/docker/ddl`, as the testkits do), so a
project holds no DDL to fall out of step with its sidecar. `PythonTemplateSuite` and
`TypeScriptTemplateSuite` render through `Main.run`, assert the pin names the CLI's version, point it at
this repository's SDK, and run the project's own type check and tests — insisting nothing skipped. A
released SDK's testkit, and a generated project's compose file, start
`ghcr.io/thinkmorestupidless/ankka-sidecar:<version>`; an unreleased SDK (0.0.0) starts
`ankka-sidecar:latest`, so the suites and the SDK jobs test against the sidecar of the same commit. `-Dankka.template.tests` takes `off`, or a list of
languages (`python`, `typescript,scala`); only `scala` pays for the local publish, which is how each SDK
job in CI runs its own template's suite against the SDK and sidecar it just built.

**Every project `ankka init` makes carries a `.mcp.json`** naming `ankka mcp` by command (in `common/`
and in `ankka.g8/`), and `ankka mcp install` (`cli/mcp/McpInstall`) writes the same entry — the template
suites assert the two agree. Two choices there are deliberate. It never edits `~/.claude.json`: for
Claude Code in every project it runs `claude mcp add --scope user`, because that file is Claude Code's
to write, and its tests drive that branch through a scripted runner so they never touch the developer's
own. And no template pre-approves the project's server (`enabledMcpjsonServers`): Claude Code asks each
person once, and a repository that could start a program without asking could start any. Claude
Desktop's file is `-Dankka.claude.desktop.config`-overridable for the same reason `-Dankka.config` is.

**The CLI ships as a native executable per platform**, from the release workflow's `cli-native`
matrix: GraalVM's `native-image` over the same jar, one runner per platform because it cannot
cross-compile (`linux-x64`, `linux-arm64`, `macos-arm64`, `macos-x64`), each attached to the tag's
release as `ankka-cli-<version>-<platform>.tar.gz` with a `.sha256`. It waits for `cli`, which creates
the release and attaches the JVM build as a zip (`cli/Universal/packageBin`, a JDK 21 its only need) —
the install route for any platform without a native build. The Linux legs build on Ubuntu 22.04
because the binary links the build machine's glibc; none runs on musl. What the image must carry is
declared in the jar, in `cli/src/main/resources/META-INF/native-image/`, and **a missing resource is
not a build failure**: the first image built without the resource globs served `ankka mcp` with zero
pages and no error. So the job runs `cli/native-smoke.sh` on each binary, which asks it for the docs
and the console's files, and a new resource the CLI reads needs a glob there.

**The GitHub Action ships as its own repository.** `action/` is a composite action, subtree-pushed to
`thinkmorestupidless/ankka-action` by the release workflow's `action` job exactly as `ankka.g8/`,
`marketplace/` and `homebrew/` are pushed, and the job then moves the `v<version>` and `v<major>` tags
so `uses: thinkmorestupidless/ankka-action@v1` resolves. It installs the native build for the runner,
chosen from `RUNNER_OS`/`RUNNER_ARCH` (a Windows runner is refused before any download), and
**refuses to install without a published checksum** — an action that silently skipped verification when
the `.sha256` was missing would verify nothing on exactly the release where something went wrong. It
needs no Java. The job waits for `cli-native`, so no action is published pointing at assets that do
not exist yet.

**The CLI ships through Homebrew**, from `thinkmorestupidless/homebrew-tap` (`brew install
thinkmorestupidless/tap/ankka`). The formula is canonical in `homebrew/Formula/ankka.rb` with version
`0.0.0` and four checksums of zeros, one per platform in `on_macos`/`on_linux` × `on_arm`/`on_intel`
blocks, each line ending in a comment naming its platform — deliberate, like the plugin's `0.0.0`. The
release workflow's `homebrew` job waits for every leg of `cli-native`, reads each `.sha256` from the
release, writes the version and each checksum onto the line naming its platform, refuses to push if a
placeholder survives, then clones the tap, writes `Formula/ankka.rb` and the README, and pushes an
ordinary commit with one fetch-and-rebase retry — the way the marketplace goes, never a subtree
force-push: the tap holds other projects' formulae (ankka-flow's among them), and a force push would
erase them. `HomebrewFormulaSuite` pins the placeholders and the comments the job's `sed` matches.
The formula needs no JDK. `brew audit --strict` passes, and the proof of the whole thing is a throwaway
local tap (`brew tap-new`) pointed at a locally built tarball by `file://` URL — with the test formula
renamed and `keg_only` if a real `ankka` is installed, and `HOMEBREW_NO_AUTOREMOVE=1` on the uninstall:
removing a test formula once auto-removed the JDK an installed `ankka` from an untapped tap needed.

**The images ship through GitHub Container Registry**, public: `ghcr.io/thinkmorestupidless/<image>`
for the operator, the control plane, the sidecar and the shopping cart sample, from the release
workflow's `images` job, pushed with the workflow's own token (each image's
`org.opencontainers.image.source` label links its package to this repository). Public because a Python
or TypeScript developer runs the sidecar on their own machine. **A package ghcr.io has not seen before
is created private**, so the job asks ghcr.io for each image with no credential and fails, naming the
package's settings page, until it is made public — once per package. **The clusters do not pull from
ghcr.io**: they pull through `europe-west2-docker.pkg.dev/ankka-ops/ghcr`, an Artifact Registry remote
repository caching it (ankka-deployments, `modules/artifact-registry`), whose paths mirror ghcr.io's.
In-region, and a cached version is still served when ghcr.io is down — but a version is cached only
once something pulls it, so the job ends by pulling every image through the cache as the release
identity and comparing the digests. One registry is published to, so the two cannot drift. The
standard `ankka-ops/ankka` repository holds releases up to 0.6.4 and is written to no longer.

**The Python SDK ships through PyPI**, as the package `ankka`, from the release workflow's `sdk-python`
job. Its version is `__version__` in `sdks/python/src/ankka/__init__.py` — `0.0.0` in the tree, like the
formula and the plugin, and read by hatchling as the package version (`dynamic = ["version"]`) so there
is one placeholder for the job's `sed` to rewrite and one value the SDK reports to the sidecar in
discovery. The job generates the stubs from the tag's `protocol/`, builds with `uv build`, imports the
wheel from an isolated interpreter (`uv run --isolated --no-project --with dist/*.whl`) and uploads with
`pypa/gh-action-pypi-publish` under **trusted publishing** — no token, the same OIDC shape as the images
job. The one action outside the repository is registering the publisher on PyPI (project `ankka`,
workflow `release.yml`, environment `pypi`). The `ci` workflow builds and smoke-imports the wheel on every
commit, because the stubs under `src/ankka/_proto/` are gitignored and hatchling honours a project's
`.gitignore`: the wheel carries them only because `[tool.hatch.build] artifacts` names them. A version
on PyPI can never be re-uploaded, only superseded, same as Central.

**The TypeScript SDK ships through npm**, as the package `ankka`, from the release workflow's
`sdk-typescript` job. Its version is `version` in `sdks/typescript/package.json` — `0.0.0` in the tree, like
the other placeholders — and `npm run proto` writes `src/version.ts` from it, so the version the SDK reports
to the sidecar in discovery is the one on npm. The job writes the tag's version with `npm version`, generates
the stubs from the tag's `protocol/`, builds `dist/`, packs, installs the tarball into an empty directory and
imports both entry points, and publishes under **trusted publishing**, the PyPI job's OIDC shape, on Node 24
because trusted publishing needs npm 11.5.1 and Node 22 bundles 10. **npm cannot create a package this way**:
a trusted publisher is attached to an existing package, and a pending-publisher shape (npm/cli#8544) does
not exist. So the first release that carries the SDK publishes it **once by hand**, after that tag's `publish`
job is green:

```bash
git checkout vX.Y.Z && cd sdks/typescript
npm version X.Y.Z --no-git-tag-version && npm ci && npm run proto && npm run build
mkdir -p dist-pack && npm pack --pack-destination dist-pack && npm publish ./dist-pack/ankka-X.Y.Z.tgz --access public   # 2FA prompt
git checkout -- package.json package-lock.json src/version.ts   # all three are 0.0.0 in the tree; CI packs ankka-0.0.0.tgz
```

Then on npmjs.com, package settings → Trusted Publisher → GitHub Actions: owner `thinkmorestupidless`,
repository `ankka`, workflow `release.yml`, environment `npm`, with `npm publish` allowed (a new configuration
defaults to stage-only since September 2026); and "Require two-factor authentication and disallow tokens".
From the next tag the job publishes, and its `npm view` guard makes a re-run of a tag finish what a cancelled
run left without a second upload. The `npm` environment on the repository is where a required reviewer would
go, as `pypi` is for the Python SDK. The `ci` workflow's `sdk-typescript` job runs the fast tests on Node 22
and 24 (the floor and the documented line) and the Docker-backed tests and the conformance suite on 24.

**The Rust crate ships through crates.io**, as `ankka`, from the release workflow's `sdk-rust` job. Its
version is `version` in `sdks/rust/ankka/Cargo.toml` — `0.0.0` in the tree, like the other placeholders —
and the crate reports `env!("CARGO_PKG_VERSION")` in discovery, so the version a module declares is the
one on crates.io. The job writes the tag's version with `sed`, diffs the crate's protocol copy against
`protocol/`, tests, packages, builds the package alone for `wasm32-unknown-unknown`, and publishes under
**trusted publishing** (`rust-lang/crates-io-auth-action` exchanges the OIDC token for a short-lived one),
environment `crates-io`, beside `npm` and `pypi`. **crates.io, like npm, attaches a trusted publisher only
to a crate that exists**, so the first release that carries the crate publishes it once by hand, after
that tag's `publish` job is green:

```bash
git checkout vX.Y.Z && cd sdks/rust
sed -i '' 's/^version = "0.0.0"/version = "X.Y.Z"/' ankka/Cargo.toml
cargo publish -p ankka --allow-dirty          # a crates.io token with the publish-new scope
git checkout -- ankka/Cargo.toml Cargo.lock
```

Then on crates.io, the crate's settings → Trusted Publishing → GitHub: owner `thinkmorestupidless`,
repository `ankka`, workflow `release.yml`, environment `crates-io`. From the next tag the job publishes,
and its guard, which asks crates.io's API (`cargo info` inside the workspace answers from the local package, so it reported every version as published and v0.9.0 uploaded nothing), makes a re-run of a tag finish what a cancelled run left.

**The console ships twice**: as the `ankka-console` image, beside the other images from the `images` job, and
as the `ankka-console` npm package from the `console-package` job, which a product builds its own host on. The
package's version is `0.0.0` in `console/package/package.json`, written from the tag like the SDK's, and its
first publish is by hand for the same reason, after that tag's `publish` job is green:

```bash
git checkout vX.Y.Z && cd console
npm version X.Y.Z --no-git-tag-version -w package && npm ci && npm run build -w package
cd package && mkdir -p dist-pack && npm pack --pack-destination dist-pack && npm publish ./dist-pack/ankka-console-X.Y.Z.tgz --access public
git checkout -- package.json ../package-lock.json
```

Then attach the trusted publisher to `ankka-console` exactly as for `ankka`.

**Compatibility** (`com.thinkmorestupidless.ankka.controlplane.api.Compatibility`): a descriptor's declared `runtime` is
checked against `BuildInfo.version` when the control plane *projects* the service — same major,
minor equal or one below — and an unsupported one takes the existing "cannot project" path as
`ClusterView.Refused` → `Unavailable` with both versions in the detail, before any resource is
written. Undeclared is unchecked. The rule lives in `controlplane-api` so the CLI can one day
print it without the control plane. **A consequence for DDL changes**: within a supported range
the schema is additive — a running application must never lose a table or column it needs.

## Traps

- **pekko-management pulls `pekko-http` 1.1.0, and eviction lifts only part of the family.**
  `pekko-http` goes to 1.4.0 but `pekko-http-spray-json` stays, and Pekko HTTP checks family
  versions at startup — every HTTP suite died in `beforeAll`. `dependencyOverrides ++=
  pekkoHttpFamily` in `commonSettings` pins all of them; add any new pekko-http artifact to that
  list, not only to `libraryDependencies`.
- **`dependencyOverrides` never reaches a POM.** Feature 004 pinned the Pekko HTTP family with an
  override in `commonSettings`; the first build *outside* this repository (feature 006) got
  `pekko-http-spray-json 1.1.0` from pekko-management beside `pekko-http 1.4.0` and Pekko HTTP
  refused to start. Anything a consumer must see is a direct `libraryDependencies` entry in the
  published module — `ankka-runtime` now declares the family.
- **A Docker tag may not contain `+`, and a dynver snapshot version does.** `docker:publishLocal`
  failed on every image the moment `ThisBuild / version` went: `invalid tag
  "ankka-operator:0.0.0+12-…"`. `dockerSettings` sets `Docker / version` with `+` → `-`; a
  release version has no `+` and tags exactly as itself. The template's build does the same.
- **`JavaAppPackaging` enables `DockerPlugin`**, so `sbt cli/stage` for the CLI also made root's
  `docker:publishLocal` build a CLI image. The CLI's `Docker / publishLocal` and `Docker / publish`
  are no-ops; the CLI is a local binary, never an image.
- **A task dependency on `root / publishLocal` publishes nothing.** Aggregation is how the
  *command line* fans a task out to the aggregated projects; in the task graph, `(root /
  publishLocal).value` runs the root's own — skipped — publish and returns in 0s. `templateArtifacts`
  names the six service modules. The same is true of `root / test` and `root / compile`.
- **`testOnly` does not go through `test`.** A dependency hung on `Test / test` is bypassed by
  `sbt module/testOnly X`, which is exactly how one suite is run; hook both.
- **A test must never name an image by a literal tag.** `EndToEndClusterSuite` said
  `sample-shopping-cart:0.1.0-SNAPSHOT` — the fixed version feature 006 deleted — and kept passing
  for two features on a stale image of that name in the Docker daemon, until the rename made that
  image one that reads environment variables the operator no longer sets: it started, was never
  `Ready`, and only the cases where it was the *only* pod timed out. The second tag is
  `BuildInfo.version` with `+` → `-` (what `Docker / version` produces), and `testOnly` builds the
  images too, so the tag a suite asks for is the one this sbt session made.
- **A top-level `require(...)` is not an sbt DSL entry** (`required: sbt.internal.DslEntry`); a
  check in a `build.sbt` is a `val` whose body calls `sys.error`.
- **Giter8 reads `default.properties` from `src/main/g8/`, not the template root** — at the root
  it is silently ignored ("Ignoring unrecognized parameter: name"). An empty directory needs
  `sbt --allow-empty`.
- **`sbt new` must be `new` and its arguments as separate arguments, never one command string.**
  sbt's launcher (1.x and 2.x) runs `new` outside any build only when it sees `new` as an argument of
  its own. `ankka init` used to run `sbt --allow-empty -batch "new <template> --name=…"`; the sbt 2
  launcher, not recognising that as `new`, sent it through its thin client (the default under sbt
  2), which appends `sbtCompleteExec <id>`, `resumeFromFailure` and `shell` — and giter8 refused them
  as `Unknown argument`, for every template. The Scala template suite did not run in CI then, so the
  first sign was a laptop with the sbt 2 launcher; the `template-scala` job now runs it under both
  launcher lines. `Init.command` builds the arguments; `InitSuite` refuses one that holds a space.
- **`actions/checkout` hijacks pushes to any other GitHub repository.** It persists the workflow's
  token as `http.https://github.com/.extraheader`, which matches *every* github.com URL and beats
  the `x-access-token:<token>@host` credentials written into a push URL. The release's template
  job pushed to `ankka.g8` as `github-actions[bot]` and got "Permission to … denied", a 403 that
  reads exactly like a repository that does not exist — it did exist, and the token was never
  tried. `persist-credentials: false` on that checkout is the fix.
- **`${{` in a Giter8 template is `\${{` or it is gone.** Giter8 reads `$` as its own syntax, so an
  unescaped GitHub expression is *deleted*: the workflow still parses, the YAML is still valid, and the
  secret simply arrives empty. There is no syntax check that can see this, which is why `TemplateSuite`
  expands the template and asserts on the expanded files — no surviving `\$`, balanced `${{`/`}}`, and
  the expressions that must be there by name.
