---
name: pr-review-rust
description: The Rust rules a code review checks after the general pr-review checklist: soundness of unsafe code (SAFETY comments, aliasing, static mut, Send and Sync, FFI), panics and error types in libraries versus binaries, lock guards and blocking across .await, overflow and lossy as casts, edition 2024 hard errors, Cargo features, semver and supply chain, rustdoc sections, test hygiene and WebAssembly targets. Use whenever a pull request, diff, branch or file under review contains Rust (.rs, Cargo.toml, Cargo.lock, rust-toolchain.toml), whether or not pr-review loaded it, and whenever someone asks for feedback on, a check of, or a second opinion about Rust code. Not for writing Rust or fixing the code; it tells a reviewer what to look for.
---

# Reviewing Rust

Rust reviews most often miss what the compiler cannot see: a safe function whose unsafe body is
sound only for the arguments the author imagined, a lock guard alive across an `.await`, an `as` cast
that truncates, a `let _ =` that drops a write error, a public enum that cannot gain a variant without
a major version. Apply the groups in this order: unsafe, errors and panics, concurrency and async,
ownership and types; then edition 2024 and WebAssembly when the manifest switches them on; then
Cargo, API design, tests and documentation. Idioms come last, where rustfmt and clippy leave any.

## Before reading the code

Read each crate's `Cargo.toml` in the diff before any `.rs` file; the manifest decides which rules
apply.

| Read | Where | What it changes |
|---|---|---|
| Edition | `[package] edition` (absent: 2015); `edition.workspace = true` defers to `[workspace.package]`; members may differ | `"2024"` switches on the Edition 2024 group: `unsafe extern`, `#[unsafe(no_mangle)]`, `static_mut_refs` as an error, `unsafe_op_in_unsafe_fn` warnings, RPIT capture and temporary-scope changes; 2024 members of a virtual workspace need `resolver = "3"` |
| MSRV | `[package] rust-version`, possibly inherited; clippy reads it as `msrv` | anything newer in the diff is a defect: `let else` 1.65, `OnceLock` 1.70, async fn in traits 1.75, `LazyLock` 1.80, `#[expect]` 1.81, `use<..>` 1.82, edition 2024 1.85 |
| Toolchain and targets | `rust-toolchain.toml` (`channel`, `components`, `targets`) or the one-line `rust-toolchain`; `.cargo/config.toml [build] target`; `wasm-bindgen`, `js-sys`, `web-sys`; `#[cfg(target_arch = "wasm32")]` | `nightly` allows `#![feature]` and Miri; a dated nightly pin forbids newer nightly features; any `wasm32-*` target switches on the WebAssembly group |
| Workspace | root `[workspace]`, `members`, `resolver`; one `Cargo.lock` at the root | members inherit version, edition, license, `rust-version`, dependencies and `[lints]` with `workspace = true`; a member hard-coding what the workspace provides is a divergence |
| Library or binary; published | `src/lib.rs` or `[lib]` versus `src/main.rs`, `src/bin/`, `[[bin]]`; `crate-type = ["cdylib"]` is FFI or wasm, `"proc-macro"` a macro; `version` with no `publish = false` means crates.io | libraries return typed `Result`s and never panic on input; binaries may use `anyhow`, `main() -> Result` and `expect` at the edge; a published crate gets semver, C-METADATA, C-STABLE, `Cargo.lock` and `cargo semver-checks` |

Then the tooling configuration, so the review never repeats a tool: `[lints.*]` in `Cargo.toml` or
`[workspace.lints]` (`unsafe_code = "forbid"` means no unsafe review unless the diff removes it; a group
without `priority = -1` loses to its own overrides); `clippy.toml` (`msrv`, `allow-unwrap-in-tests` and
friends, `disallowed-methods`/`-types`/`-macros` as the project's own bans, the thresholds);
`rustfmt.toml`; `deny.toml`, `supply-chain/`, `.cargo/audit.toml`; CI and the `Justfile` for which cargo
tools run; and the crate-root attributes in `lib.rs` (`#![forbid(unsafe_code)]`, `#![warn(missing_docs)]`,
`#![no_std]`, the anti-pattern `#![deny(warnings)]`).

### Already enforced: do not comment

| If the repository runs | then skip |
|---|---|
| `cargo fmt --check` | all layout: line length, import order, trailing commas, brace style |
| `cargo clippy -- -D warnings`, or `[lints] clippy` with `CARGO_BUILD_WARNINGS=deny` | every on-by-default lint. Correctness: `derive_ord_xor_partial_ord`, `derived_hash_with_manual_eq`, `uninit_vec`, `not_unsafe_ptr_arg_deref`, `mut_from_ref`, `unused_io_amount`, `out_of_bounds_indexing`, `wrong_transmute`, `lint_groups_priority`, `deprecated_semver`. Suspicious: `await_holding_lock`, `await_holding_refcell_ref`, `mutable_key_type`, `declare_interior_mutable_const`, `missing_transmute_annotations`, `cast_nan_to_int`, `float_equality_without_abs`, `arc_with_non_send_sync`, `test_attr_in_doctest`. Style: `ptr_arg`, `new_without_default`, `wrong_self_convention`, `result_unit_err`, `missing_safety_doc`, `must_use_unit`, `unwrap_or_default`, `mem_replace_option_with_none`, `single_match`, `match_like_matches_macro`, `needless_doctest_main`. Complexity: `too_many_arguments`, `vec_box`, `borrowed_box`, `needless_lifetimes`, `clone_on_copy`, `transmute_ptr_to_ref`. Perf: `large_enum_variant`, `result_large_err`, `box_collection`, `redundant_allocation`, `expect_fun_call`, `unnecessary_to_owned` |
| `[lints.clippy]` or `#![warn(..)]` enabling `pedantic`, `nursery`, `cargo` or named `restriction` lints | those too. Pedantic: `needless_pass_by_value`, `cast_*`, `must_use_candidate`, `missing_errors_doc`, `missing_panics_doc`, `wildcard_imports`, `float_cmp`, `option_option`, `manual_let_else`, `should_panic_without_expect`, `ignore_without_reason`. Nursery: `redundant_clone`, `future_not_send`, `significant_drop_tightening`, `or_fun_call`, `derive_partial_eq_without_eq`. Cargo: `cargo_common_metadata`, `multiple_crate_versions`, `wildcard_dependencies`, `negative_feature_names`. Restriction: `unwrap_used`, `expect_used`, `panic`, `todo`, `indexing_slicing`, `arithmetic_side_effects`, `as_conversions`, `dbg_macro`, `print_stdout`, `undocumented_unsafe_blocks`, `shadow_unrelated`, `let_underscore_must_use`, `allow_attributes_without_reason`. With `allow-unwrap-in-tests` in `clippy.toml`, an `unwrap` in a test is not a finding |
| rustc with `-D warnings` | `unused_must_use`, `unused_unsafe`, `static_mut_refs` (2021), `dead_code`, `deprecated`, `invalid_value`, `unnecessary_transmutes`, `improper_ctypes`, `async_fn_in_trait`, `boxed_slice_into_iter`; in 2024 also `unsafe_op_in_unsafe_fn`, `missing_unsafe_on_extern`, `unsafe_attr_outside_unsafe`; plus whatever `[lints.rust]` enables (`missing_docs`, `unreachable_pub`, `unused_results`, `missing_debug_implementations`) |
| `cargo doc` with `RUSTDOCFLAGS=-Dwarnings` | broken intra-doc links, links to private items, bare URLs, invalid code block attributes |
| `cargo deny check` | the licence allow-list, banned and duplicate crates, RustSec advisories, yanked crates, dependency sources; do not re-litigate a licence `deny.toml` allows |
| `cargo vet` | "has this new dependency been reviewed" |
| `cargo semver-checks` | removed items, changed signatures, new required trait items, added variants; still review changed field and parameter types, generics and lifetimes, feature-subset breaks, `#[doc(hidden)]` items |
| `cargo hack check --rust-version` or `cargo msrv` | APIs newer than `rust-version` |
| `cargo miri test` | out-of-bounds, use-after-free, uninitialised reads, aliasing on executed paths; still review FFI, unexecuted paths, and whether the new unsafe code has a test at all |
| `cargo nextest` | shared state between tests in one binary; retries can hide flakiness, so ask for the report rather than guessing |

## Checklist

### Unsafe

- **A `// SAFETY:` comment above every `unsafe` block and impl.** Signal: `unsafe {` or `unsafe impl`
  with no comment, or one restating the operation rather than the invariant that makes it sound. Why:
  the comment is where soundness is argued; writing it finds bugs. [std-dev-guide; `clippy::undocumented_unsafe_blocks`]
- **No safe public function may cause UB for any argument.** Signal: a safe `pub fn` dereferencing a
  raw-pointer parameter, calling `get_unchecked(i)` with a caller's `i`, or returning `&mut` derived from
  `&`. Why: soundness means safe code cannot reach UB through the public API; check, or make it
  `unsafe fn` with the precondition written. [UCG glossary; `clippy::not_unsafe_ptr_arg_deref`, `mut_from_ref`]
- **Invalid values and aliasing violations are UB the moment they exist.** Signal: `transmute` or raw
  `read` yielding a `bool` not 0 or 1, a bad enum discriminant, a null or dangling `&T`/`Box`, zero in
  `NonZero`/`NonNull`; two live `&mut` to one place; `&T` to memory written through a raw pointer without
  `UnsafeCell`; `transmute::<&T, &mut T>`. Why: the optimiser assumes the rules, even inside private
  fields. [Reference UB list; Nomicon transmutes; `invalid_value`; `clippy::as_ptr_cast_mut`]
- **No references to a `static mut`, ever (an error in 2024).** Signal: `static mut X` with `&X`,
  `&mut X`, `X.len()` or `println!("{X:?}")`. Why: the reference is UB if any other exists. Replace with
  an atomic, `Mutex<T>` in a plain `static`, `OnceLock`/`LazyLock`, or `&raw mut X` for FFI. [`static_mut_refs`]
- **`unsafe impl Send`/`Sync` argues about every field.** Signal: `unsafe impl<T> Send for Foo<T> {}`
  without `where T: Send`; an impl on a type holding `Rc`, `RefCell`, `*mut T` or `MutexGuard`; `Sync` on
  a type that mutates through `&self` without a lock. Why: the auto trait was declined for a reason; a
  data race is UB. [Nomicon send-and-sync; C-SEND-SYNC; `clippy::non_send_fields_in_send_ty`]
- **`transmute` is the last resort and fully typed.** Signal: `mem::transmute` without `::<Src, Dst>`;
  between `repr(Rust)` types (even `Vec<i32>` to `Vec<u32>`); integer to pointer; where `from_ne_bytes`,
  `as` or `ptr::cast` would do. Why: `repr(Rust)` layout is unspecified, and inference may pick an
  unintended type. [Nomicon transmutes; `clippy::transmute_undefined_repr`]
- **Uninitialised memory: `MaybeUninit` and `ptr::write`, never `set_len` first.** Signal:
  `mem::zeroed()` for a type with references or a niche; `MaybeUninit::uninit().assume_init()`;
  `Vec::with_capacity(n)` then `set_len(n)` before writing; `=` into uninit memory. Why: reading uninit
  memory is UB, and assignment drops the garbage it overwrites. [Nomicon unchecked-uninit; `clippy::uninit_vec`]
- **FFI: `repr(C)`, exact signatures, nullability, no unwinding into C.** Signal: a Rust struct passed
  to C without `#[repr(C)]`; `Box<T>` or `&T` where C may pass null (use `Option<&T>` or `*const T`); an
  `extern "C" fn` callback that can panic without `catch_unwind`. Why: a layout or nullability
  mismatch is UB, and unwinding across the C boundary aborts at best. [Nomicon ffi; `improper_ctypes`]
- **`Pin` contract and structural pinning.** Signal: `Pin::new_unchecked`, `get_unchecked_mut`, a manual
  `impl Unpin`, `Drop` on a pinned type moving fields. Why: pinned memory must not be invalidated
  before `drop` runs; prefer `pin!`, `Box::pin`, `pin-project`. [std::pin]

### Errors and panics

- **Libraries return `Result`; panics are for broken invariants only.** Signal: `unwrap()`, `expect()`,
  `panic!`, `assert!` on inputs, I/O, parsing or the environment in a crate with `src/lib.rs`; any of
  them in a body that already returns `Result` (`?` was one character away). Why: a panic decides on the
  caller's behalf that the situation is unrecoverable. [Book ch09-03; C-FAILURE; `clippy::unwrap_used`, `unwrap_in_result`]
- **`?` through `From`; never drop the cause.** Signal: `.map_err(|e| MyError::Io(e))?` repeated where
  `impl From` or `#[from]` would do; `.map_err(|_| MyError::Other)` discarding the source. Why: `?` calls
  `From::from`; a dropped source breaks the chain. [RBE wrap_error; `clippy::map_err_ignore`]
- **Typed errors in libraries; `anyhow` or `eyre` in binaries.** Signal: `anyhow::Result` or
  `Box<dyn Error>` in a library's public API; an error enum without `impl std::error::Error`. Why: a
  public error must be `Error + Send + Sync + 'static` so callers can match, `?` and `downcast_ref`.
  [C-GOOD-ERR; thiserror and anyhow docs; RBE boxing_errors]
- **Error enums: `#[non_exhaustive]`, `source()`, lowercase `Display` without trailing punctuation.**
  Signal: a new public error enum without `#[non_exhaustive]`; a wrapping variant with no `#[source]` or
  `#[from]`; `Display` repeating the inner error's text. Why: a new variant is otherwise a major change,
  a missing source breaks the chain, and repeated text prints twice. [C-GOOD-ERR; Cargo semver; thiserror]
- **A discarded `Result` needs a comment.** Signal: `let _ = fallible();`, `fallible().ok();`, `_ = f();`
  on a write, flush, send, remove or lock; a `#[must_use]` value dropped. Why: `let _ =` silences
  `unused_must_use`, so each one must say why the error is irrelevant. [`unused_must_use`; `clippy::let_underscore_must_use`]
- **Partial I/O counts are never ignored.** Signal: `.read(&mut buf)` or `.write(&bytes)` with the
  returned count dropped. Why: both may process part of the buffer; use `write_all`, `read_exact`, or
  loop on the count. [`clippy::unused_io_amount`]
- **Destructors never fail, panic or block; `process::exit` skips them all.** Signal: `impl Drop`
  containing `unwrap`, `panic!`, `.lock().unwrap()`, blocking I/O or `block_on`; `process::exit(..)`
  anywhere but the top of `main`. Why: a panic during unwinding aborts, and `exit` runs no destructor on
  any thread; give teardown a `close(self) -> Result`. [C-DTOR-FAIL; std::process::exit; `clippy::exit`]

### Concurrency and async

- **Never hold a `std` guard or `RefCell` borrow across `.await`.** Signal: `let g = m.lock().unwrap();`
  then `.await` in the same scope; a guard in an `if let` scrutinee whose `else` locks again (held there
  in 2021); a `tokio::sync::Mutex` around plain data (the same mistake made legal and slow; the async
  mutex is for a resource held across awaits). Why: the task may be parked holding the lock and resumed
  on another thread; `RefCell` panics on overlap. [Tokio shared-state, sync::Mutex; `clippy::await_holding_lock`]
- **Poisoning is deliberate; `RwLock` is unfair and re-entrant reads deadlock.** Signal:
  `.lock().unwrap()` throughout a long-lived service; `into_inner()` recovery without restoring a
  consistent value; a reader taking a second read lock while a writer may wait. Why: one panic poisons
  the lock for every later caller, and `RwLock` priority is OS-dependent. Options: propagate and
  document, `clear_poison` (1.77), `parking_lot`. [std::sync::Mutex, RwLock]
- **No blocking inside async.** Signal: `std::thread::sleep`, `std::fs`, `reqwest::blocking`,
  `Command::output()`, `block_on`, `par_iter()` or heavy CPU in an `async fn`. Why: the worker stalls
  every task on it; aim for 10 to 100 µs between awaits. Use `spawn_blocking` (bounded I/O; cannot be
  aborted once started), `rayon` plus `oneshot`, or a dedicated thread. [ryhl.io async-blocking; Tokio spawn_blocking]
- **A detached task or thread is a decision, written down.** Signal: `tokio::spawn(..);` or
  `std::thread::spawn(..)` with the handle dropped and no supervision. Why: dropping a `JoinHandle`
  detaches; the panic is lost unless awaited (`JoinError::is_panic`). Keep handles in a `JoinSet`,
  `TaskTracker` or struct, or comment the fire-and-forget. [Tokio JoinHandle; std::thread::spawn]
- **`select!` branches must be cancellation safe.** Signal: `tokio::select!` in a `loop` recreating a
  non-cancellation-safe future each iteration (`read_exact`, `write_all`, an `async fn` with partial
  state). Why: losing a branch drops its future and its partial input; pin one future outside the loop
  or use `mpsc::recv`. [Tokio select]
- **Futures handed to `tokio::spawn` must be `Send`.** Signal: `Rc`, `RefCell`, `MutexGuard`, `*mut T` or
  `Box<dyn Error>` without `+ Send + Sync` alive across an `.await`; a public `async fn` in a trait
  (1.75+) callers will spawn. Why: the compile error appears at the spawn site, far from the cause, and
  only the trait author can add the bound (`-> impl Future<Output = T> + Send` or `trait-variant`).
  [`clippy::future_not_send`; `async_fn_in_trait`; async-fn-in-traits blog]
- **Atomics: `SeqCst` is fine; `Relaxed` needs one line; `Release` and `Acquire` pair.** Signal:
  `Ordering::Relaxed` on a flag that publishes other data; `Release` on a load or `Acquire` on a store;
  `const C: AtomicUsize` or `const M: Mutex<..>`. Why: a mismatched pair degrades to `Relaxed`, and a
  `const` is copied at every use, so mutations vanish. [std::sync::atomic::Ordering; `clippy::declare_interior_mutable_const`]

### Ownership, borrowing and types

- **Integer overflow is a decision, not an accident.** Signal: `a + b`, `a * b`, `x << n`, `len() - 1` on
  unbounded values; `as usize` of a possibly negative value; tests that pass only because debug overflow
  checks panic. Why: debug panics, release wraps; division by zero panics in every mode. Use `checked_`,
  `saturating_`, `wrapping_`, or prove the bound in a comment. [Book ch03-02; `clippy::arithmetic_side_effects`]
- **`as` truncates, wraps and saturates silently.** Signal: `x as u8` from a wider integer
  (`1234u16 as u8 == 210`); `as i32` of a possibly NaN float (gives 0); a hand-written `impl Into`. Why:
  `as` never fails, so the loss is silent; `u8::try_from(x)` and `From`/`TryFrom` impls make it visible,
  and `Into` comes free from `From`. [C-CONV-TRAITS; `clippy::cast_possible_truncation`]
- **Indexing that can panic on data the function did not check.** Signal: `v[i]`, `&s[a..b]`, `map["k"]`;
  byte-slicing a string (`&s[0..1]` panics off a char boundary). Why: in a library an index panic on
  caller data is a bug unless documented; prefer `.get(i)`, `.get(a..b)`, `is_char_boundary`. [`clippy::indexing_slicing`]
- **Floats: no `==`, no derived `Ord`, order with `total_cmp`.** Signal: `a == b` on `f64`;
  `(a - b) < EPSILON` without `.abs()`; `partial_cmp(..).unwrap()` in a sort; `f32` fields in a type
  deriving `Ord`. Why: NaN breaks `Eq` and `Ord`, so the unwrap panics and the derive does not compile;
  `f64::total_cmp` gives a total order. [`clippy::float_cmp`, `float_equality_without_abs`; std::cmp::Ord]
- **`Ord`, `PartialOrd`, `PartialEq`, `Eq` and `Hash` agree.** Signal: one derived and another
  hand-written; `partial_cmp` not `Some(self.cmp(other))`; a manual `PartialEq` ignoring a field the
  derived `Hash` includes; a `HashMap` key containing `Cell`, `RefCell` or `Mutex`. Why: `BTreeMap` and
  `HashMap` break silently; derive all or write all. [std::cmp::Ord; `clippy::derive_ord_xor_partial_ord`, `mutable_key_type`]
- **Borrow in parameters; `&str` and `&[T]` over `&String`, `&Vec<T>`, `&Box<T>`.** Signal: `fn f(s: String)`
  whose body only reads; `fn f(x: &T)` followed by `x.clone()` to store it; `&String`, `&Vec<T>`, `&PathBuf`
  in a signature. Why: the caller decides where to copy; a slice accepts every owner by deref coercion.
  [C-CALLER-CONTROL; Book ch04-03; `clippy::ptr_arg`, `needless_pass_by_value`]

### Edition 2024 (when `edition = "2024"` or the diff migrates to it; the last item, any edition)

- **`unsafe extern` with honest `safe` and `unsafe` qualifiers.** Signal: `extern "C" {` without
  `unsafe`; `safe fn` on an import with pointer preconditions; a `cargo fix --edition` run that marked
  everything `unsafe` with no signature review. Why: the bare block is a hard error, and a wrong `safe`
  makes UB reachable from safe code; the lint does not check signatures. [Edition guide unsafe-extern]
- **`#[unsafe(no_mangle)]`, `export_name` and `link_section` carry a SAFETY comment.** Signal: the bare
  attribute forms; an export named like a libc symbol. Why: the symbol namespace is global; a collision
  crashes safe code. [Edition guide unsafe-attributes; `unsafe_attr_outside_unsafe`]
- **`unsafe_op_in_unsafe_fn` is not silenced; one small block per operation.** Signal:
  `#![allow(unsafe_op_in_unsafe_fn)]` added to make the migration compile; one `unsafe {}` around a whole
  body. Why: each operation gets its own justification. [`clippy::multiple_unsafe_ops_per_block`]
- **RPIT captures every in-scope lifetime; `use<..>` narrows it.** Signal:
  `fn f<'a>(x: &'a T) -> impl Iterator<Item = U>` whose callers need the result to outlive `'a`;
  `impl_trait_overcaptures` suggestions accepted blindly. Why: 2024 captures `'a` and the result stops
  compiling at the caller (add `+ use<>`); each `use<..>` is public API. [Edition guide rpit]
- **Temporaries drop earlier; `Box<[T]>` iterates by value; `env::set_var` is `unsafe`.** Signal:
  migrated code that relied on a lock or `Ref` living into an `if let`'s `else`; `tail_expr_drop_order`
  warnings on significant-`Drop` types; `boxed_slice.into_iter()` where the body expects `&T`;
  `unsafe { env::set_var(..) }` with the `// TODO: Audit` comment left in. Why: each is a silent
  behaviour change, and `set_var` races parallel tests. [Edition guide temporary scopes, intoiterator-box-slice, newly-unsafe-functions]
- **Modern std over crates, MSRV permitting (any edition).** Signal: `lazy_static` or `once_cell` (use
  `LazyLock`/`OnceLock`); `async_trait` where no `dyn` is needed (1.75); `#[allow(lint)]` where
  `#[expect(lint)]` (1.81) would do. Why: `expect` warns the day the lint stops firing, and an `allow`
  without `reason =` tells the next reader nothing. [Reference diagnostics; `clippy::allow_attributes`]

### WebAssembly (when the crate builds for `wasm32-unknown-unknown`, `wasm32-wasip1` or `wasm32v1-none`)

- **No threads, no filesystem, silent stdout on `wasm32-unknown-unknown`.** Signal: `thread::spawn`
  `thread::sleep`, `std::fs`, `println!` for output (use `web_sys::console`), `std::time::Instant` (use
  `web_time`). Why: the target imports nothing from the host, so these panic, error or do nothing. Gate
  with `#[cfg(target_arch = "wasm32")]` and test the gated code under `wasm-bindgen-test` or `wasmtime`. [rustc platform-support]
- **Panics abort; install `console_error_panic_hook`.** Signal: `catch_unwind` relied on; no
  `set_once()` in the init path; `Result` paths replaced by `unwrap` "because wasm". Why: the default is
  `panic=abort`, and without the hook a panic reads "unreachable executed". [rustc platform-support; rustwasm debugging]
- **Randomness needs an explicit backend, in the binary only.** Signal: `rand`, `uuid` or `getrandom`
  without the `wasm_js` feature (and `--cfg getrandom_backend="wasm_js"` for 0.3) in the cdylib; the
  feature enabled in a library. Why: without it the link fails; with it in a library, every non-web
  wasm consumer breaks. [getrandom docs]
- **Size is a feature.** Signal: a wasm `cdylib` on the default release profile; `format!` in hot paths.
  Why: every byte ships to the browser; `opt-level = "s"`, `lto = true`, `codegen-units = 1` and
  `wasm-opt` shrink it, and `format!` pulls in the formatting machinery. [rustwasm code-size]
- **`#[wasm_bindgen]` boundaries: what crosses, what copies, what throws, who frees.** Signal: an
  exported `fn` with lifetimes or generics; `pub String` fields without `getter_with_clone`;
  `Result<T, E>` where `E` is not `Into<JsValue>`; exported structs created in a loop with no JS `free()`.
  Why: the first two do not compile, `Err` must become a JS throw, and JS-owned Rust objects leak. [wasm-bindgen exported types, result]
- **Async on wasm is single-threaded.** Signal: `tokio` with `rt-multi-thread` in a wasm target;
  futures `Send` on native but `!Send` on wasm without a cfg-gated trait alias. Why: there is no
  blocking pool and no second thread; use `wasm-bindgen-futures::spawn_local` and `gloo-timers`. [rustc platform-support; Tokio spawn_blocking]

### Cargo, features and dependencies

- **`Cargo.lock` committed, `rust-version` true, `publish = false` on internal crates, metadata on public
  ones.** Signal: `Cargo.lock` in a library's `.gitignore` or deleted by the PR; no `rust-version` in a
  published crate, or a bump with no changelog line; a member with `version` and no `publish = false`; a
  public crate missing `description`, `license`, `repository`. Why: the lockfile makes CI and bisects
  deterministic; MSRV and metadata are what crates.io users see. [Cargo FAQ, manifest; C-METADATA]
- **Features are additive; `std`, never `no-std`.** Signal: a feature that removes behaviour; names
  `no-*`, `use-*`, `with-*`, `*-support`; two features that need a `compile_error!` together; a feature
  removed or dropped from `default` in a minor. Why: Cargo unifies features across the graph, so two
  crates enabling different sets must still compile; removing one is major. [Cargo features; C-FEATURE]
- **Version requirements: bare caret, no `*`, no `=`, no upper bounds; git deps pinned by `rev`.** Signal:
  `foo = "*"`, `">=2.0, <2.4"`, `"=1.2.3"` in a library; `{ git = ".." }` with no `version` in a publishable
  crate; `branch = "main"` (floats). Why: caps break resolution for others; pin in `Cargo.lock`. [Cargo specifying-dependencies]
- **`[profile]` is root-only and reasoned; `build.rs` stays local, deterministic and cheap.** Signal:
  `[profile.*]` in a member; `panic = "abort"` without noting `catch_unwind` stops working; a build
  script with network access, writes outside `OUT_DIR`, `cfg!(target_os)`, or no `rerun-if-changed`.
  Why: member profiles are ignored, `cfg!` in a build script checks the host not the target (use
  `CARGO_CFG_TARGET_OS`), and a network fetch makes every build non-reproducible. [Cargo profiles, build-scripts]
- **Supply chain: `deny.toml`, RustSec, `cargo vet`, and the licence story.** Signal: a dependency outside
  the licence allow-list, from a git source `[sources]` forbids, duplicating a crate at another major,
  unmaintained or yanked; a tiny crate replacing ten lines; a new `unsafe`-heavy crate where the project
  forbids unsafe. Why: each is a review item the tooling will reject or that quietly widens the trust
  boundary. [cargo-deny; RustSec; cargo-vet; `clippy::multiple_crate_versions`]
- **`#![deny(warnings)]` in source is an anti-pattern; `forbid(unsafe_code)` is never downgraded
  quietly.** Signal: `#![deny(warnings)]` in `lib.rs`; `forbid(unsafe_code)` removed or
  `#[allow(unsafe_code)]` added for one block. Why: a new rustc then breaks every downstream build (deny
  in CI instead), and the forbid was the crate's soundness guarantee. [patterns deny-warnings; `unsafe_code`]

### Traits, generics and API design

- **Naming: `as_`/`to_`/`into_` by cost, no `get_`, `new` agrees with `Default`.** Signal: `get_name()`;
  `to_bytes(&self) -> &[u8]` (a free borrow is `as_bytes`); `into_x(&self)`; `HTTPClient`; a no-argument
  `new()` that differs from `default()`; out-parameters. Why: the prefix tells the caller the cost and
  ownership, and two constructors that disagree surprise everyone. [C-CASE, C-CONV, C-GETTER, C-CTOR; `clippy::wrong_self_convention`]
- **Generics, `impl Trait` or `dyn Trait` for a stated reason; accept the most general input.** Signal:
  `Box<dyn Trait>` in a hot path with one concrete type; `impl Trait` in argument position of a public
  API; a returned `impl Iterator` callers must name; `fn f(v: &Vec<i64>)` that only iterates;
  `&Option<T>`. Why: generics inline and infer, `dyn` gives one copy and heterogeneity, neither is free;
  `impl IntoIterator`, `&[i64]` and `Option<&T>` accept every caller. [C-GENERIC, C-OBJECT; `clippy::ref_option`]
- **Dyn-compatibility is decided early.** Signal: a trait meant to be boxed gaining a generic method,
  `-> Self`, `async fn` or `-> impl Trait` without `where Self: Sized`. Why: `dyn Trait` stops compiling;
  async fn in traits is not dyn-compatible. [C-OBJECT; async-fn-in-traits blog]
- **Future-proof: private fields, `#[non_exhaustive]`, sealed traits, no derivable bounds on structs.**
  Signal: a public struct with all-`pub` fields; a growing public enum without `#[non_exhaustive]`; a
  public trait that will gain methods with no `private::Sealed` supertrait; `struct Foo<T: Clone>` where
  only impls need the bound. Why: a new field, variant or required method is otherwise a major change.
  [C-STRUCT-PRIVATE, C-SEALED, C-STRUCT-BOUNDS; Cargo semver]
- **Builders for many optional inputs; `#[must_use]` on builders and pure functions.** Signal: a
  constructor with more than four parameters or several `bool`s and `Option`s; `fn with_x(self, ..) -> Self`
  or `fn checked_add` without `#[must_use]`. Why: a dropped builder step or checked result is a silent
  no-op the compiler could have caught. [C-BUILDER; `clippy::return_self_not_must_use`]
- **Common traits eagerly; `serde` behind a feature; no `Copy` on large types.** Signal: a new public type
  without `Debug`, `Clone`, `PartialEq`; unconditional serde derives in a library; `#[derive(Copy)]` on a
  type with arrays. Why: the orphan rule means only this crate can add them, serde is a dependency
  every consumer then pays for, and `Copy` hides a `memcpy`. [C-COMMON-TRAITS, C-SERDE; `clippy::large_types_passed_by_value`]
- **Every public change is semver-classified.** Signal: a renamed or removed public item, a tightened
  bound, a variant on an exhaustive enum, a field on an all-public struct, a raised MSRV, a removed
  feature, a `0.x` type in a `1.x` public API. Why: each is major; `cargo semver-checks` misses type
  changes in fields and feature subsets. [Cargo semver; C-STABLE; cargo-semver-checks]

### Tests

- **Unit tests in `#[cfg(test)] mod tests`; integration tests in `tests/`; helpers in
  `tests/common/mod.rs`.** Signal: `#[test]` outside `cfg(test)`; `tests/common.rs`; test crates under
  `[dependencies]`; `#[cfg(test)]` on a `pub use` an integration test needs. Why: the first two compile
  into the artifact or run as an empty test crate; integration tests are external, so they need a
  `test-util` feature. [Book ch11-03; `clippy::tests_outside_test_module`]
- **`#[should_panic(expected = "..")]`, never bare; `#[ignore]` with a reason.** Signal: `#[should_panic]`
  without `expected`, or on a `-> Result` test (not allowed); `#[ignore]` with no `= "reason"` and no
  `--include-ignored` job. Why: a bare `should_panic` passes on any panic, an unrelated bug included.
  [Book ch11-01; Reference testing; `clippy::should_panic_without_expect`, `ignore_without_reason`]
- **Assertions compare values and say why.** Signal: `assert!(a == b)`; `assert_eq!(r.is_ok(), true)`;
  `assert!(x.is_err())` without checking which error. Why: `assert_eq!` prints `left` and `right` on
  failure (their order is a project convention, not a rule). [Book ch11-01; `clippy::bool_assert_comparison`]
- **`Result`-returning tests use `?`; `unwrap` in tests is fine.** Signal: a test unwrapping a dozen
  `Result`s where `-> anyhow::Result<()>` reads better; an `unwrap_used` complaint in a test when
  `allow-unwrap-in-tests` is set. Why: in a test a panic is the right failure. [Book ch09-03; clippy lint_configuration]
- **No order dependence; `--test-threads=1` is not a fix.** Signal: `-- --test-threads=1` added to CI;
  tests sharing `static mut`, `env::set_var`, a fixed port or temp path; `serial_test` with no
  explanation. Why: libtest runs tests in parallel in one process; the shared state is the bug. A
  project that serialises deliberately documents why. [Book ch11-01; nextest how-it-works]
- **New unsafe code has a test Miri runs, and a benchmark if it is for speed.** Signal: an unsafe-heavy
  module with no `cargo miri test`; `#[cfg(miri)]` skipping the test that matters; `get_unchecked` or
  `unwrap_unchecked` introduced for performance with no numbers. Why: Miri sees only executed paths;
  the checked version usually optimises the same. [Miri README; C-VALIDATE]

### Documentation

- **Every public item has a doc comment whose first line stands alone; hide what is not API.** Signal:
  `pub fn` or `pub struct` without `///`; a first paragraph longer than a sentence; a public
  `From<PrivateError>` or `__private` module without `#[doc(hidden)]`. Why: rustdoc uses the first
  paragraph as the summary, and whatever is visible is API. [rustdoc how-to-write; C-HIDDEN; `missing_docs`]
- **`# Errors` on every public `-> Result`, `# Panics` where it can panic, `# Safety` on `unsafe`.**
  Signal: a `pub fn .. -> Result` without `# Errors`; `unwrap`, indexing or `assert!` in a public fn
  without `# Panics`; a `pub unsafe fn` without `# Safety`, or a safe fn with one. Why: the caller must
  know what can fail, when it aborts and what obligation it takes on. [C-FAILURE; `clippy::missing_errors_doc`, `missing_safety_doc`]
- **`# Examples` compile, use `?` and show why; doc tests are tests.** Signal: a new public item with no
  example; `unwrap()` in examples; ```` ```ignore ```` without a reason or to make CI green; `no_run` on
  an example that could run; ```` ```compile_fail ```` without an error code; `#[test]` inside a doctest.
  Why: examples are copied, so `unwrap` spreads; an ignored or `#[test]` doctest is silently never run. [C-EXAMPLE; rustdoc doctests]
- **Crate-level `//!` docs are the front page.** Signal: `lib.rs` without `//!`; features undocumented;
  a README example that differs from the `//!` one. Why: docs.rs opens here; `#![doc =
  include_str!("../README.md")]` keeps the two from drifting. [C-CRATE-DOC; Cargo features]
- **Intra-doc links, not bare names or docs.rs URLs.** Signal: `` the `Foo` type `` without `` [`Foo`] ``;
  `[Foo](https://docs.rs/..)`; identifiers outside backticks. Why: a link follows renames and versions;
  a URL rots. [C-LINK; `clippy::doc_markdown`]
- **Deprecations carry `since` and `note`; the changelog and the prose keep up.** Signal: `#[deprecated]`
  without them; a public change with no changelog entry; a sealed trait or `#[non_exhaustive]` enum
  whose docs say nothing about it. Why: a downstream reading the docs cannot otherwise know what they
  may implement or match exhaustively. [C-RELNOTES, C-SEALED; `clippy::deprecated_semver`]

## Severity

**Blocking.** No safe public function may cause UB; invalid values and aliasing violations; references
to a `static mut`; `unsafe impl Send`/`Sync` with an unbounded `T` or a `!Send` field; `transmute` between
`repr(Rust)` types; uninitialised memory or `set_len` first; FFI without `repr(C)` or unwinding into C;
`Pin` contract broken; a `std` guard or `RefCell` borrow across `.await`; blocking inside async;
re-entrant `RwLock` reads; panics on caller-controlled input in a library, or any panic in `Drop`; a
discarded `Result` or partial I/O count; a public change in a published crate without a major bump;
edition 2024 hard errors or behaviour changes accepted without review; `build.rs` touching the network
or writing outside `OUT_DIR`; a dependency that fails `cargo deny`, a `*` version, an unversioned git
dependency, `forbid(unsafe_code)` removed; on wasm, threads, sleep, `std::fs`, `getrandom` without a backend.

**Should fix.** Missing `// SAFETY:` comments and `# Safety`/`# Errors`/`# Panics` sections; unsafe
code with no Miri test; `anyhow` in a library API; error enums without `#[non_exhaustive]` or
`source()`; a dropped error cause; detached tasks without a reason; `Relaxed` without a comment;
disagreeing `Ord`/`Eq`/`Hash`; missing common traits or `Copy` on a large type; `&String`/`&Vec`
parameters; hand-written `Into`; overflow-prone arithmetic; truncating `as`; `partial_cmp().unwrap()`;
`==` on floats; `#![deny(warnings)]` in source; missing `rust-version` or `publish = false`; `Cargo.lock`
ignored; `[profile]` in a member; non-additive features; bare `#[should_panic]`; `#[ignore]` without a
reason; order-dependent tests; `Result` tests that `unwrap` everything; a new public item with no
doctest; `#[allow]` without `reason`; on wasm, no panic hook, a default release profile.

**Nit.** Naming (`get_`, `as_`/`to_`/`into_` mismatch, name repetition); `let else`, `matches!` and
`mem::take` rewrites; `unwrap_or_default`; `Rc::clone(&x)` spelling; redundant clones; `#[inline]`
placement; `#[must_use]` and `const fn` candidates; lifetime elision; wildcard imports; doc Markdown
backticks; `Option<Option<T>>` in private code. Layout when rustfmt runs is not even a nit.

## Where the sources disagree

- `anyhow` in a library or a typed error: follow the repository's convention; if none, prefer typed
  errors for anything without `publish = false`, because callers cannot `?` or match an `anyhow::Error`.
- `unwrap` never, or `expect("<invariant>")`: follow the convention; if none, treat `unwrap` on
  caller-controlled data in a library as blocking and `expect` on a provable fact as fine (clippy left
  `unwrap_used` in `restriction` for exactly this disagreement).
- `dyn Trait` or generics: follow the convention; if none, review for the reason being stated, because
  size-sensitive crates prefer `dyn` and hot paths prefer generics.
- `#[must_use]` everywhere or only on builders: follow the crate's practice; the lint has false positives.
- `Cargo.lock` for a library: follow the convention; if none, commit it, because the 2023 guidance
  defaults to committing, with a latest-deps job for floating dependencies.
- The `Send` bound on an async trait: the trait author decides; if undecided, prefer `trait-variant`
  generating both, because callers cannot add the bound later.
- `std::sync::Mutex` or `parking_lot`: follow the convention; flag only mixing them for the same data.
- `Relaxed` or `SeqCst` for statistics counters: accept `Relaxed` with one comment line.
- `#![deny(warnings)]` in source: accept in a binary with a pinned toolchain; reject in a library, where
  a new rustc then breaks every downstream.
- Which restriction lints are policy: a per-crate decision in `[lints.clippy]`, never a review comment.
- Graphemes or `chars()`: depends on the domain; the Book only says the two differ.
- Edition 2024 now or later: `rust-version` decides; a 2021 crate at `rust-version = "1.85"` should move.
- Allocator on wasm: a measured decision; do not recommend `wee_alloc`, which is unmaintained.

## Reference

`references/rust-review-research.md` is the full research this skill condenses: open it when an item's
signal list here is too short to confirm a finding, for the source behind a tag, for the complete
tooling tables (every clippy group, rustc lint and cargo tool), or for a disagreement's full argument.
Its sections: 1. Sources; 2. Detecting the language, edition and toolchain; 3. Checklist (3.1 Ownership
and types, 3.2 Errors, 3.3 Unsafe, 3.4 Traits and API, 3.5 Concurrency and async, 3.6 Edition 2024,
3.7 Cargo, 3.8 Documentation, 3.9 Tests, 3.10 WebAssembly); 4. Severity; 5. Enforced by tooling; 6. Open questions.
