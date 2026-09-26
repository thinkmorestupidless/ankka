# Contract: CI, packaging and release

**Feature**: [spec.md](../spec.md) | **Research**: R1, R7, R8 | **Mirrors**: the `sdk-python` jobs in `.github/workflows/{ci,release}.yml`

## `package.json` (what the package promises)

```json
{
  "name": "ankka",
  "version": "0.0.0",
  "description": "Build services on ankka in TypeScript: entities, views, workflows, agents and endpoints hosted by the ankka sidecar.",
  "type": "module",
  "engines": { "node": ">=22.22.0" },
  "files": ["dist"],
  "main": "./dist/index.js",
  "types": "./dist/index.d.ts",
  "exports": {
    ".":         { "types": "./dist/index.d.ts",         "default": "./dist/index.js" },
    "./testkit": { "types": "./dist/testkit/index.d.ts", "default": "./dist/testkit/index.js" }
  },
  "repository": { "type": "git", "url": "git+https://github.com/thinkmorestupidless/ankka.git", "directory": "sdks/typescript" },
  "license": "Apache-2.0",
  "scripts": {
    "proto": "node scripts/proto.ts",
    "typecheck": "tsc --noEmit -p tsconfig.json",
    "build": "tsc -p tsconfig.build.json",
    "test": "node --test 'test/**/*.test.ts' 'examples/**/*.test.ts'",
    "test:slow": "ANKKA_SLOW=1 node --test 'test/**/*.test.ts' 'examples/**/*.test.ts'",
    "conformance": "node bin/conformance.ts",
    "example": "node examples/shopping-cart/main.ts"
  },
  "dependencies": { "@bufbuild/protobuf": "^2.15.0", "@connectrpc/connect": "^2.2.0", "@connectrpc/connect-node": "^2.2.0" },
  "peerDependencies": { "testcontainers": "^12.1.0", "@testcontainers/postgresql": "^12.1.0" },
  "peerDependenciesMeta": { "testcontainers": { "optional": true }, "@testcontainers/postgresql": { "optional": true } },
  "devDependencies": { "typescript": "^7.0.2", "@bufbuild/buf": "^1.73.0", "@bufbuild/protoc-gen-es": "^2.15.0", "testcontainers": "^12.1.0", "@testcontainers/postgresql": "^12.1.0", "@types/node": "^24" }
}
```

- `version` is the one placeholder; `src/version.ts` is written from it by `scripts/proto.ts`
  (and the build), so the version the SDK reports in discovery is the one on npm (FR-026).
- `files: ["dist"]` overrides `.gitignore` for packing, so the generated `dist/_proto/` ships
  although `src/_proto/` is ignored (R8, confirmed by experiment). No `.npmignore`.
- `repository.directory` is what provenance for a package in a subdirectory expects; `url` must match
  the GitHub repository exactly.
- `engines` is advisory to npm; the floor is enforced at import by `src/index.ts` (FR-018).
- The testkit's Docker dependencies are optional peers: `npm install ankka` brings no Docker tooling;
  a project that uses `ankka/testkit`'s `AnkkaTestKit` adds them (the docs say so, once).

## `.github/workflows/ci.yml` — `sdk-typescript`

Runs on every push, beside `sdk-python`, and is the same shape:

```yaml
  sdk-typescript:
    runs-on: ubuntu-latest
    strategy:
      matrix:
        node: [22, 24]                       # the floor and the recommended line; slow tests on 24 only
    steps:
      - uses: actions/checkout@v4
        with: { fetch-depth: 0 }
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: "21", cache: sbt }
      - uses: sbt/setup-sbt@v1
      - uses: actions/setup-node@v4
        with: { node-version: ${{ matrix.node }}, cache: npm, cache-dependency-path: sdks/typescript/package-lock.json }
      # The SDK carries a copy of the protocol artifact; drift is a bug in whichever side forgot `npm run proto`.
      - run: diff -r protocol/src/main/protobuf sdks/typescript/proto/src/main/protobuf
      - run: diff -r protocol/fixtures sdks/typescript/proto/fixtures
      - run: npm ci
        working-directory: sdks/typescript
      - run: npm run proto
        working-directory: sdks/typescript
      - run: npm run typecheck
        working-directory: sdks/typescript
      - run: npm test
        working-directory: sdks/typescript
      # The package as npm would receive it: built, packed, installed into an empty directory with nothing
      # but the tarball and its declared dependencies, imported.
      - run: |
          npm run build && npm pack --pack-destination "$RUNNER_TEMP"
          mkdir "$RUNNER_TEMP/smoke" && cd "$RUNNER_TEMP/smoke" && npm init -y >/dev/null
          npm install "$RUNNER_TEMP/ankka-0.0.0.tgz" --no-save --ignore-scripts
          node --input-type=module -e "import 'ankka'; import 'ankka/testkit'; console.log('ok')"
        working-directory: sdks/typescript
      # Docker-backed: the sidecar and the Scala cart of this commit, the slow tests, the conformance suite.
      - if: matrix.node == 24
        run: sbt -Dankka.cluster.tests=off -Dankka.template.tests=off sidecar/docker:publishLocal shoppingCart/docker:publishLocal
      - if: matrix.node == 24
        run: npm run test:slow
        working-directory: sdks/typescript
      - if: matrix.node == 24
        run: npm run conformance
        working-directory: sdks/typescript
```

The images are built by this job so the SDK is tested against the sidecar of the same commit, and
the testkit is told the tag through `ANKKA_SIDECAR_IMAGE` where the default `:latest` would not be
the one this job built (the Python job relies on `publishLocal` producing `:latest`; do the same, and
say so).

## `.github/workflows/release.yml` — `sdk-typescript`

```yaml
  sdk-typescript:
    # The TypeScript SDK to npm, as the package `ankka`: https://www.npmjs.com/package/ankka. The version is
    # `version` in sdks/typescript/package.json — 0.0.0 in the tree, written here from the tag by the same rule
    # as the template, the plugin, the formula and the Python SDK — and `src/version.ts` is generated from it,
    # so the version the SDK reports to the sidecar in discovery is the one on npm.
    #
    # No token: npm trusts this workflow directly (trusted publishing, OIDC), the same shape as the PyPI job.
    # Unlike PyPI, npm cannot create a package this way: the FIRST publish of `ankka` was made by hand, at the
    # tag's version, after that tag's `publish` job succeeded (CLAUDE.md, Publishing), and the trusted
    # publisher was attached afterwards — owner `thinkmorestupidless`, repository `ankka`, workflow
    # `release.yml`, environment `npm`, with `npm publish` allowed (new configurations default to stage-only).
    #
    # It waits for the publish job for the reason the Python job does: a release that never reached Maven
    # Central should not exist on npm either. A version can never be re-uploaded to npm, only superseded.
    needs: publish
    if: startsWith(github.ref, 'refs/tags/v')
    runs-on: ubuntu-latest
    environment:
      name: npm
      url: https://www.npmjs.com/package/ankka
    permissions:
      contents: read
      id-token: write   # the OIDC token npm verifies
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: "24", registry-url: "https://registry.npmjs.org" }   # npm ≥ 11.5.1 is what trusted publishing needs
      - name: Write the version, generate the stubs, build, pack
        working-directory: sdks/typescript
        run: |
          set -euo pipefail
          version="${GITHUB_REF_NAME#v}"
          npm version "$version" --no-git-tag-version
          grep -q "\"version\": \"$version\"" package.json
          npm ci && npm run proto && npm run build
          npm pack --pack-destination dist-pack
          test -f "dist-pack/ankka-$version.tgz" || { echo "::error::the tarball is not versioned as the tag"; exit 1; }
      - name: The tarball installs on its own and is the release it claims to be
        working-directory: sdks/typescript
        run: |
          set -euo pipefail
          version="${GITHUB_REF_NAME#v}"
          mkdir "$RUNNER_TEMP/smoke" && cd "$RUNNER_TEMP/smoke" && npm init -y >/dev/null
          npm install "$GITHUB_WORKSPACE/sdks/typescript/dist-pack/ankka-$version.tgz" --no-save --ignore-scripts
          node --input-type=module -e "import { VERSION } from 'ankka'; if (VERSION !== '$version') { console.error(VERSION); process.exit(1) }; console.log('ankka', VERSION)"
      - name: Publish, unless this version is already on npm
        working-directory: sdks/typescript
        run: |
          set -euo pipefail
          version="${GITHUB_REF_NAME#v}"
          if npm view "ankka@$version" version >/dev/null 2>&1; then echo "ankka@$version is already published"; exit 0; fi
          npm publish "dist-pack/ankka-$version.tgz" --access public
```

Provenance is attached automatically under trusted publishing (R8). The "already published" guard
is the same idea as the repo1 check before `ci-release`: a re-run of a tag finishes what a cancelled
run left, without a second upload the registry would refuse.

## The one-time manual publish (recorded in `CLAUDE.md`)

For the first tag that carries this SDK, after that tag's `publish` job is green:

```bash
git checkout vX.Y.Z && cd sdks/typescript
npm version X.Y.Z --no-git-tag-version && npm ci && npm run proto && npm run build
npm pack --pack-destination /tmp && npm publish /tmp/ankka-X.Y.Z.tgz --access public   # 2FA prompt
git checkout -- package.json package-lock.json
```

Then on npmjs.com: package settings → Trusted Publisher → GitHub Actions: `thinkmorestupidless` /
`ankka` / `release.yml` / environment `npm`, allow `npm publish`; and enable "Require two-factor
authentication and disallow tokens". From the next tag the job above publishes. The `npm`
environment on the repository is where a required reviewer goes if a release ever wants a Publish
button, as `pypi` is for the Python SDK.

## `.gitignore`

```
# TypeScript (the SDK under sdks/typescript)
sdks/typescript/node_modules/
sdks/typescript/src/_proto/
sdks/typescript/dist/
sdks/typescript/dist-pack/
```

## The sample image

`examples/shopping-cart/Dockerfile`, build context `sdks/typescript`: `node:24-slim`; copy the
manifests, `proto/`, `scripts/`, `src/` and `examples/`; `npm ci && npm run proto` (the script generates
from the committed `proto/` copy when the repository's `protocol/` is not there, which in an image it is
not); no build, because the service runs from source; `ENV ANKKA_PROCESS_PORT=9010`;
`CMD ["node", "--conditions=ankka-source", "examples/shopping-cart/main.ts"]` — the condition points
`ankka` at the SDK's sources in the image, a flag a project that installed the package from npm does not
need. The image holds only the process; the platform supplies the sidecar. Its descriptor is
`{"name": "cart", "service": {"image": "sample-shopping-cart-typescript:latest", "hosting": "process", "protocol": "1.0"}}`.
