# Contract: CI, release, the template and the manual first publish

## `ci.yml`: the `sdk-rust` job

Filter (`changes`): `*workflow`, `*scala-build`, `*templates`, `sdks/rust/**`,
`cli/src/main/templates/rust/**`, `cli/src/test/scala/**/cli/RustTemplateSuite.scala`. The host
itself is in `sidecar/`, which `*scala-build` already covers, so a host change runs the Scala job
and this one.

Steps, on `ubuntu-24.04`:

1. checkout, Java 21, sbt, `dtolnay/rust-toolchain@stable` with `targets: wasm32-unknown-unknown`,
   `Swatinem/rust-cache`.
2. `diff -r protocol/src/main/protobuf sdks/rust/protocol/src/main/protobuf`, and the same for
   `fixtures`, `ENCODING.md` and `WASM-ABI.md`.
3. `cargo fmt --check`, `cargo clippy --all-targets -- -D warnings`.
4. `cargo test -p ankka` (unit kit, fixtures both ways, registration refusals).
5. `sbt -Dankka.cluster.tests=off -Dankka.template.tests=off sidecar/docker:publishLocal
   shoppingCart/docker:publishLocal` (the runtime image and the Scala cart for journal portability).
6. `cargo build -p shopping-cart --release --target wasm32-unknown-unknown`.
7. `cargo test -p shopping-cart --features slow` (integration through the runtime image, journal
   portability both ways).
8. `./conformance.sh` (both shapes).
9. `cargo package -p ankka --allow-dirty` and a build of the packaged crate for the WebAssembly
   target from an empty directory, so an undeclared dependency or a file `include` misses fails on
   the commit.
10. `sbt -Dankka.cluster.tests=off -Dankka.template.tests=rust 'cli/testOnly *RustTemplateSuite'`.

## `release.yml`: the `sdk-rust` job

```yaml
sdk-rust:
  needs: publish
  if: startsWith(github.ref, 'refs/tags/v')
  runs-on: ubuntu-24.04
  environment: { name: crates-io, url: https://crates.io/crates/ankka }
  permissions: { contents: read, id-token: write }
  steps:
    - checkout; rust stable with the wasm target
    - write the tag's version into sdks/rust/ankka/Cargo.toml (0.0.0 → ${GITHUB_REF_NAME#v}); grep it back
    - diff the protocol copy (as CI)
    - cargo test -p ankka; cargo package -p ankka --allow-dirty; build the packaged crate for wasm32-unknown-unknown alone
    - skip the upload if `cargo info ankka@$version` already answers (a re-run finishes a cancelled release)
    - uses: rust-lang/crates-io-auth-action@v1   → id: auth
    - cargo publish -p ankka --allow-dirty, with CARGO_REGISTRY_TOKEN: ${{ steps.auth.outputs.token }}
```

The version the crate reports in discovery is `env!("CARGO_PKG_VERSION")`, so it is the tag's.
Nothing in the build writes a tracked file; the job's `sed` runs on the runner.

## The manual first publish

A trusted publisher is attached to a crate that exists. After the first tag carrying the SDK is
green through `publish`:

```bash
git checkout vX.Y.Z && cd sdks/rust
sed -i '' 's/^version = "0.0.0"/version = "X.Y.Z"/' ankka/Cargo.toml
cargo publish -p ankka --allow-dirty          # with a crates.io token that has publish-new scope
git checkout -- ankka/Cargo.toml Cargo.lock
```

Then on crates.io: the crate's settings → Trusted Publishing → GitHub: owner `thinkmorestupidless`,
repository `ankka`, workflow `release.yml`, environment `crates-io`. From the next tag the job
publishes. Recorded in `CLAUDE.md` beside npm's.

## The template: `ankka init --language rust`

`cli/src/main/templates/rust/` renders with `{{name}}`, `{{module}}` (the crate name, `-` kept),
`{{module_snake}}` (the same with `_`, which is the module's file name), `{{ankka_version}}` and
`{{protocol_version}}`:

```text
Cargo.toml                      # [package] name = "{{module}}"; ankka = "{{ankka_version}}"; crate-type cdylib; features slow
.cargo/config.toml              # alias `cargo module` (the release build for wasm32); the wasm32 target's stack size.
                                # Not `[build] target`: that would make a plain `cargo test` build for wasm32 and fail
rust-toolchain.toml
src/lib.rs, domain.rs, item_entity.rs, item_rows.rs, api.rs
tests/item.rs                   # unit; and integration behind `slow`, which the suite insists runs
Dockerfile                      # busybox + cp (contracts/descriptor-and-rendering.md)
service.json                    # hosting wasm, protocol {{protocol_version}}
README.md, .gitignore, .dockerignore
.github/workflows/{ci,deploy}.yml
```

`common/docker-compose.yml` gains a `wasm` profile: the runtime with the bind-mounted module, no
process port. `RustTemplateSuite extends PolyglotTemplateSuite(Language.Rust, "cargo")`: renders
through `Main.run`, asserts the pin names `Version`, points the dependency at `sdks/rust/ankka` by
path, and runs `cargo clippy`, `cargo module` and `cargo test --features slow`, insisting nothing
ignored. `-Dankka.template.tests=rust` selects it.
`native-smoke.sh` renders the Rust template too.

## Documentation and skills

The pages and skills named in `plan.md`'s structure; `docs check` requires every new page in
`mkdocs.yml` and in a skill's `pages:`; the CLI reference and control plane routes pages are
unchanged (no new command, no new route). The rendered skills are regenerated into `marketplace/`
and the templates by `just docs-sync`.
