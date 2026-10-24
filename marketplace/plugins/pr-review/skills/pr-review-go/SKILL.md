---
name: pr-review-go
description: The Go rules a code review checks after the general pr-review checklist. Goroutine lifetimes, contexts and the sync types; error wrapping, inspection and the typed-nil, nil-map and writer-Close traps; slice aliasing and memory retention; the gosec security patterns (injection, traversal, TLS, crypto/rand, unbounded reads); go.mod policy and what the go line changes about the review; Go test conventions; then interfaces, idioms and naming. Use whenever a pull request, diff, branch or file under review contains Go (.go, go.mod, go.sum), whether or not pr-review loaded it, and whenever someone asks for feedback on, a check of, or a second opinion about Go code. It is not for writing Go or for fixing the code under review.
---

# Reviewing Go

Go reviews most often miss what the compiler accepts and the runtime punishes: a goroutine nobody
stops, an error dropped or both logged and returned, an `append` that overwrites the caller's
slice, a request context handed to work that outlives the response. Linters catch the syntax-shaped
half; the reviewer owns the rest. Apply the groups in this order: concurrency and context, errors,
security, slices and memory, modules and build, tests, interfaces and API design, idioms, naming.
Read the `go` line first; three of the groups change meaning with it.

## Before reading the code

**Files.** `*.go`; `go.mod` (`module`, `go`, `toolchain`, `require`, `replace`, `tool`, `godebug`,
`ignore`); `go.sum`; `go.work`; `vendor/modules.txt`; `*_test.go`; `testdata/`; `//go:build` lines;
files headed `// Code generated ... DO NOT EDIT.` (skim, never review line by line). [mod][layout]

**Version.** The `go` line is the module's minimum toolchain and the language-semantics switch for
every file in it (a file can raise its own with `//go:build go1.23`); since 1.21 a toolchain refuses
a newer line, so a bump binds every consumer and CI image. Only the two most recent majors get
security fixes. [mod][loopvar][release-cycle]

| go line | What flips | Review consequence |
|---|---|---|
| ≥1.21 | `min`/`max`/`clear`; `slices`, `maps`, `cmp`, `log/slog`; `context.WithoutCancel`; `sync.OnceFunc/OnceValue`; `errors.Join` | prefer std over hand loops and `x/exp` |
| ≥1.22 | per-iteration loop variables; `for i := range n`; `math/rand/v2`; ServeMux method and wildcard patterns | stop flagging closure capture of loop vars; flag now-dead `x := x`; `rand.Seed` is noise |
| ≥1.23 | range-over-func (`iter.Seq`); `slices.All/Values/Collect/Sorted`, `maps.Keys/Values`; timers collected without `Stop`; vet `stdversion` | `time.After` in a loop no longer leaks; `maps.Keys` returns an iterator, not a slice |
| ≥1.24 | `t.Context()`, `b.Loop()`, `t.Chdir`; `tool` directive; `os.Root`; `omitzero`; `crypto/rand.Text`; vet `tests`, non-constant `printf` format | prefer `t.Context()` in tests and `for b.Loop()` over `b.N` |
| ≥1.25 | `sync.WaitGroup.Go`; `testing/synctest` GA; container-aware GOMAXPROCS; vet `waitgroup`, `hostport`; `ignore` directive; `http.CrossOriginProtection` | prefer `wg.Go(f)`; `synctest.Test` instead of sleeps; `automaxprocs` libraries become redundant |

**Tooling to read.** `.golangci.yml`/`.yaml`/`.toml` (v2 starts `version: "2"`; `linters.default:
standard` means errcheck, govet, ineffassign, staticcheck, unused; then `linters.enable`,
`linters.settings`, `formatters.enable`); `staticcheck.conf`; `Makefile`/`Justfile` targets running
`go vet`, `gofmt -l`, `goimports`, `gofumpt`; workflows using `golangci-lint-action`. [gcl]

### Already enforced: do not comment

| If the repository runs | then skip |
|---|---|
| gofmt or gofumpt (`formatters.enable`) | formatting, braces, alignment, `gofmt -s` simplifications; with gofumpt also block-start blank lines, `0o` literals, grouped `var`, `//` spacing |
| goimports or gci | import ordering and grouping, unused imports, the `-local` module group |
| errcheck (default) | unchecked errors: `_ =`, bare calls |
| govet (default) | printf verb mismatch and non-constant format (1.24+); copied `sync` types; `cancel` not called; non-pointer `errors.As` target; `wg.Add` placement and `Sprintf` host:port (1.25); `t.Fatal` from a goroutine; tag syntax; unkeyed foreign literals; `time.Since` at `defer`; `string(int)`; symbols newer than the `go` line; loop capture below 1.22; unbuffered signal channel; `//go:build` syntax; malformed `Test*` signatures |
| staticcheck SA (default) | std-library misuse, `time.Tick`, `Timer.Reset`; `wg.Add` in goroutine; nil-map write; `defer Close` before the error check; spin loops; `defer` in a range loop; non-octal permission literal; first-constant-only typed iota |
| staticcheck ST (some off by default) | package comment, dot imports, initialism casing, error string format, receiver names, error-last results, Yoda conditions, doc starting with the name |
| errorlint, errname, nilerr, nilnil, nilnesserr | `%v` of an error, `==` or type assertion on errors; `Err*`/`*Error` naming; `return nil` after `err != nil`; `nil, nil` |
| bodyclose, rowserrcheck, sqlclosecheck | response body, `Rows.Err`/`Rows.Close`, `Stmt.Close` |
| noctx, contextcheck, containedctx, fatcontext | requests without a context; non-inherited context; context in a struct; context rebuilt in a loop |
| copyloopvar, intrange, exhaustive, forcetypeassert | `x := x` copies (1.22+); `for i := 0; i < n` rewrites; non-exhaustive enum switches; single-value type assertions |
| gochecknoinits, gochecknoglobals, reassign, recvcheck, interfacebloat, ireturn, iface | `init()` presence; package-level vars; reassigned package vars; mixed receiver kinds; wide or returned interfaces |
| thelper, paralleltest, tparallel, usetesting, testifylint, testableexamples, testpackage | missing `t.Helper`; `t.Parallel` presence and consistency; `os.*` where `t.*` exists; testify argument order; examples without `// Output:`; external test package |
| gosec; govulncheck; `go test -race` in CI | injection, traversal, TLS, weak crypto, permissions, `math/rand`, pprof, decompression bombs (G1xx to G7xx); vulnerable dependencies; data races the tests exercise |
| durationcheck, makezero, nosprintfhostport, prealloc, perfsprint, exptostd, modernize | `d * time.Second` on a Duration; `make([]T, n)` then `append`; `Sprintf` host:port; preallocation; `fmt.Sprint` to `strconv`; `x/exp` to std; modern idioms |
| mnd, goconst, nakedret, nonamedreturns, musttag, protogetter | magic numbers; repeated literals; naked and named returns; tags on marshaled structs; proto getters |
| gomoddirectives, gomodguard_v2, depguard, importas, godot, godoclint, misspell, nolintlint, sloglint | `replace`/`exclude`/`retract` policy; banned modules; import aliases; comment periods and doc practice; spelling; `nolint` hygiene; logger key-value pairs |

Whatever runs, the reviewer still owns goroutine lifetime and bounds, API shape, `%w` versus `%v`
as an API decision, slice aliasing and retention, map-order assumptions, request-context propagation,
test design, `go.mod` policy, security logic no pattern matcher sees, and every `//nolint`.

## Checklist

### Concurrency and context

- **Every `go` statement has a stop, a wait and a bound.** Signal: `^\s+go (func|\w+)\(` with no
  `ctx.Done()`, done channel, `WaitGroup` or `errgroup` bounding it; `for … { go … }` without a
  semaphore or `errgroup.SetLimit`; `go` in `init`; a background type with no `Stop` that also
  waits. Why: a goroutine nobody can stop leaks until the process dies. [Go CRC][Uber][100go #59, #62]
- **`wg.Add(n)` before `go`, never inside the goroutine (`wg.Go` on Go ≥1.25).** Signal:
  `go func() {` followed by `wg.Add(1)`; `defer wg.Done()` not first. Why: `Wait` may return before
  the goroutine has registered. [100go #71][pkg:sync][vet waitgroup]
- **Shared state written from two goroutines needs a mutex or a channel.** Signal: writes to a
  package-level or struct map or slice inside `go func`; `append` on a shared slice; a map header
  copied out of a lock then iterated. Why: a race is undefined behaviour that tests pass through;
  `-race` belongs in CI. [100go #58, #68–70][sec-bp]
- **`sync` types are never copied after first use.** Signal: a value receiver on a struct holding a
  mutex; `sync.WaitGroup` by value; an embedded or exported mutex; `Lock()` without `defer
  mu.Unlock()` right after; `RWMutex` re-entered. Why: a copied mutex guards nothing. [Uber][pkg:sync]
- **`cancel` is called on every path, deferred right after creation.** Signal:
  `WithCancel/WithTimeout/WithDeadline` without `defer cancel()`; a `defer cancel()` inside a loop.
  Why: an uncancelled context holds its timer and children until the parent ends. [pkg:context]
- **Context first, named `ctx`, never a struct field, never nil.** Signal: `ctx context.Context` as
  a field; `nil` for a `Context` (use `context.TODO()`); `context.Background()` outside `main`, `init`,
  tests (`t.Context()` on Go ≥1.24) and detached work; a handler not using `r.Context()`. [pkg:context]
- **Request contexts do not outlive the response.** Signal: `go func` capturing `r.Context()`,
  `ctx` or `w http.ResponseWriter` for work finished after the handler returns. Why: the context is
  cancelled when the handler returns; `context.WithoutCancel` (Go ≥1.21) with a comment saying
  why. [100go #61][pkg:context][pkg:http]
- **`http.Client` and `http.Server` carry timeouts in production.** Signal: `http.Get(`,
  `http.DefaultClient`; `&http.Server{` without `ReadHeaderTimeout`; bare `http.ListenAndServe(`.
  Why: zero timeout means none, so one slow peer holds a connection open. [100go #81][gosec G112/G114]
- **Close what you open: body, rows, lazy connections.** Signal: no `defer resp.Body.Close()` after
  the error check; a `rows` loop without `rows.Close()` and `rows.Err()`; `sql.Open` with no
  `PingContext`; `http.NewRequest`, `db.Query` where a `…Context` variant exists. Why: an
  unreturned body exhausts the pool. [100go #78–79][pkg:http][gcl noctx]
- **Only the sender closes; no `default` spin in a `select` loop.** Signal: `close(` in a consumer;
  `default:` as the last case of a `select` inside `for`; `make(chan T, NN)` with an unexplained
  size; `chan bool` as a signal. Why: closing twice or from the receiver panics, and a spin starves
  the goroutine it waits on. [EG][Uber][100go #65, #67][SC SA5002]
- **Loop-variable capture is a bug only below Go 1.22; `time.After` leaks only below 1.23.**
  Signal: a closure or `go v.Method()` over the range variable when the `go` line is <1.22;
  `time.After` in a `select` loop when <1.23; `time.Tick` or a `Ticker` unstopped, always. [loopvar][100go #76]

### Errors

- **Never discard an error without a comment saying why.** Signal: `_ = f(`, `_, _ :=`, bare
  `f.Close()`, `w.Write(b)`, `json.Unmarshal(`, `tx.Rollback()`. Why: the write, the decode or the
  rollback failed and nothing knows. [Go CRC][100go #53][gcl errcheck]
- **Check `err` before the value, and before deferring `Close`.** Signal: `v, err := f()` then
  `defer v.Close()` or `v.X` with `if err != nil` after. Why: a nil `*os.File` closes with a panic.
  [SC SA5001]
- **`defer f.Close()` on a writer loses the write error.** Signal: `os.Create`, `OpenFile(… O_WRONLY)`,
  `gzip.NewWriter`, `bufio.NewWriter` with `defer x.Close()`. Why: buffered bytes flush at `Close`;
  check its error via a named result or `errors.Join`; read-only files may ignore it. [100go #54]
- **`http.Error(w, …)` is followed by `return`.** Signal: `http.Error(` with no `return` next.
  Why: the handler keeps running and writes a second response. [100go #80]
- **`errors.Is`/`errors.As`, never `==`, a type assertion or `err.Error()` text.** Signal:
  `err == pkg.ErrX`, `err.(*T)`, `switch err.(type)`, `strings.Contains(err.Error()`; swapped
  `errors.Is(target, err)`; `err != io.EOF` is the accepted exception. Why: wrapping breaks them. [100go #50–51]
- **`%w` when callers may inspect the cause, `%v` to hide it; `%w` last, or first for a sentinel.**
  Signal: `fmt.Errorf(".*%v", err)`; `%w` of a non-error; `"failed: %w"` adding nothing; `"open %s:
  %w", path, err` when `err` is a `*PathError`. Why: wrapping makes the wrapped error part of the
  API. Google defaults to `%v`, Uber to `%w`; see below. [GSG-BP][blog-errors][Uber][gcl errorlint]
- **Return `error`, not `*MyError`; no `nil, nil`; no `return nil` after `err != nil`.** Signal:
  `func … \*\w+Error$`; `return nil, nil`; `if err != nil {` then `return nil`. Why: a nil `*T` in
  an `error` is non-nil, and `nil, nil` makes callers check twice. [GSG-D][100go #45][gcl nilnil]
- **Handle an error once: do not log and return it.** Signal: `log…(err)` immediately followed by
  `return …err`. Why: the log spams and hides where the error was decided. [Uber][100go #52][GSG-BP]
- **No `panic` for ordinary failures; `recover` only at the package boundary, for its own panics.**
  Signal: `panic(` outside `init`, `Must*` and tests; `recover()` that swallows and continues;
  library code panicking on input. Why: a library panic ends the caller's process. [Go CRC][Uber][100go #48]
- **`log.Fatal`/`os.Exit` only in `main`, ideally once via `func run() error`.** Signal:
  `log.Fatal|os.Exit` outside `package main` or in a test (use `t.Fatal`). Why: it skips every
  `defer`. [Uber][GSG-BP]
- **Sentinels are documented `ErrX` vars returned wrapped; the last result is `error` or `ok bool`.**
  Signal: `var \w+ = errors.New` without `Err`; an error type with no `Unwrap()`; `-1`, `""` or
  `nil` meaning failure; `(error, T)` order; `errors.New("[A-Z]`, `"failed to `. [blog-errors][SC ST1005/ST1008]

### Security

- **SQL only with placeholders.** Signal:
  `(Query|Exec|QueryRow|Prepare)\w*\(\s*(fmt\.Sprintf|"[^"]*"\s*\+|\w+\s*\+)`. [gosec G201/G202/G701]
- **`exec.Command` never takes user input as the program or through `sh -c`.** Signal:
  `exec\.Command(Context)?\(` with a non-literal first argument or `"sh", "-c"`. Why: arguments are
  allow-listed or the shell is the attacker's. [gosec G204/G702]
- **`filepath.Join(base, userPath)` does not confine; `..` escapes.** Signal:
  `filepath.Join(.*(r\.|req\.|params|input|name))`, `http.ServeFile(w, r, …+r.URL.Path)`,
  `http.Dir("/")`, zip or tar extraction loops. Why: use `os.OpenRoot` (Go ≥1.24),
  `filepath.IsLocal`, or `Clean` plus a prefix check. [gosec G304/G305/G111/G703][rel1.24]
- **TLS verified, TLS 1.2 or later, host keys checked.** Signal: `InsecureSkipVerify: true`,
  `MinVersion` below `tls.VersionTLS12`, `ssh.InsecureIgnoreHostKey`. Why: a test-only exception
  needs a build tag or a flag, not a literal. [gosec G402/G106/G123]
- **No MD5, SHA-1, DES, RC4 or MD4 for security; RSA ≥2048; no fixed IV or nonce.** Signal:
  `crypto/md5`, `crypto/sha1`, `crypto/des`, `crypto/rc4`, `rsa.GenerateKey(.*1024`, a `[]byte`
  literal nonce. MD5 for a plain checksum is fine, with a comment. [gosec G401/G403/G405–G407/G501–G507]
- **Secrets, tokens, nonces and session IDs come from `crypto/rand`, never `math/rand`.** Signal:
  `math/rand` in files mentioning token, secret, key, nonce, password or session; `rand.Text()` on
  Go ≥1.24. [Go CRC][gosec G404][rel1.24]
- **No hardcoded credentials; secrets excluded from marshaling and stored `0600`.** Signal:
  `(password|passwd|secret|token|api_?key)\s*[:=]\s*"[^"]+"`; an exported `Password string` without
  `json:"-"`; `0[67][67][67]\b` or `, 644\)` (a decimal literal is not the permission it looks
  like). [gosec G101/G117/G301/G302/G306][SC SA9002]
- **Bound and guard untrusted input.** Signal: `io.ReadAll(r.Body)` or `json.NewDecoder(r.Body)`
  without `http.MaxBytesReader`; `io.Copy(.*gzip.NewReader` without `LimitReader`; unguarded index,
  nil map or type assertion on input; `int32(n)`, `uint8(len(b))` narrowing input. Why: a request
  must not exhaust memory, overflow or panic the process. [gosec G110/G120/G602/G115][100go #18]
- **HTML goes through `html/template`, never `text/template` or `fmt`.** Signal: `text/template`
  in a package that writes HTML; `template.HTML(`, `template.JS(` around user input. [gosec G203/G705/G708]
- **Network and browser exposure is deliberate.** Signal: `_ "net/http/pprof"` (registers on
  `DefaultServeMux`); listening on `0.0.0.0`; `http.Get(userURL)`; `http.Redirect(w, r,
  r.FormValue(…))`; `&http.Cookie{` lacking `Secure`, `HttpOnly`, `SameSite`; mutating browser
  endpoints without CSRF (`http.CrossOriginProtection` on Go ≥1.25). [gosec G108/G102/G107/G704/G124]

### Slices, maps and memory

- **`append` to a sub-slice with spare capacity overwrites the parent.** Signal:
  `append(\w+\[:\w*\],`, `append` to a slice parameter, `s[:0]` reuse. Why: use the full slice
  expression `s[a:b:b]`, `slices.Clip` or `slices.Clone`. [100go #25][slices-intro]
- **A small sub-slice or substring pins the whole backing array.** Signal: `buf[n:m]` stored in a
  long-lived struct; `string(b)[:k]`; `strings.Split` results kept from a large input. Why: copy out
  with `slices.Clone`, `bytes.Clone`, `strings.Clone`. [100go #26, #41][slices-intro]
- **Writing to a nil map panics; reading is fine.** Signal: `var m map[` then `m[k] =` without
  `make` or a literal; a struct map never initialised in the constructor. [SC SA5000][GSG-BP]
- **Nil slices are the default; `make([]T, n)` then `append` doubles the length.** Signal:
  `:= []T{}` (prefer `var s []T`), `if s == nil` as an emptiness check; `make(\[\]\w+, \w+)` then
  `append(`; `make([]T, 0, n)` then `copy`. Why: APIs do not distinguish nil from empty; JSON
  `null` versus `[]` is the one place it matters. [Go CRC][Uber][GSG-D][100go #22–24][gcl makezero]
- **Map iteration order is random; sort keys when order matters.** Signal: `for k := range m`
  feeding output, a test, a hash or a protocol; insertion during iteration. Why:
  `slices.Sorted(maps.Keys(m))` on Go ≥1.23. [100go #33]
- **`range` yields copies; the range expression is evaluated once.** Signal: assignment to the
  range value variable (`for _, v := range s { v.X = 1 }`); ranging over an array copies it. [100go #30–31]
- **Equality: `slices.Equal`, `maps.Equal`, `bytes.Equal`, `cmp.Equal`; `time.Time` with `Equal`.**
  Signal: `reflect.DeepEqual` in non-test code (nil versus empty, slow); `==` on `time.Time`
  (monotonic reading); `float == float`. [100go #19, #29, #77][SC SA4012]
- **Strings are bytes.** Signal: `len(s)` as a character count; `s[i]` as a character;
  `TrimLeft(s, "prefix")` (a cutset, not a prefix); `[]byte("const")` inside a loop. Why: `range s`
  yields runes at byte offsets; `[]rune(s)` allocates. [100go #36–38, #40][SC SA1024]

### Modules and build

- **A `go` line bump is a feature, not a chore.** Signal: `^go 1\.` in the diff with no reason in
  the description. Why: it changes loop-variable (1.22), timer (1.23) and vet (1.24) semantics and
  forces every consumer onto that toolchain; the line must also sit within the two supported
  releases. [mod][loopvar][rel1.23][release-cycle]
- **`replace` in a library's `go.mod` is ignored by consumers.** Signal: `^replace `. Why: it breaks
  `go install …@latest`; in an application it is temporary with an issue link; `go.work` for local
  development. [mod][gcl gomoddirectives]
- **`go.sum` changes match `go.mod` changes; tidy leaves no diff; `toolchain` lines are churn.**
  Signal: `go.sum` growth with no `require` change or the reverse; wrong `// indirect` markers; a
  `toolchain` line appearing alone. Why: `go mod tidy -diff` (Go ≥1.23) proves it; commit a
  toolchain deliberately or strip it. [mod]
- **Build constraints are `//go:build`, blank line after, and run somewhere in CI.** Signal:
  `// +build` in new files; `//go:build` without a blank line; a constrained test no job runs.
  [vet buildtag][SC SA4019/SA9009]
- **Generated code is committed, gofmt'd, headed `// Code generated … DO NOT EDIT.`, and CI proves
  `go generate` leaves no diff.** Signal: `//go:generate` whose output is absent or hand-edited. [GSG-G]
- **`tool` directives (Go ≥1.24) replace `tools.go`; `godebug` and `//go:debug` lines are temporary.**
  Signal: `tools.go` with `_ "…"` imports in a ≥1.24 module; `asynctimerchan=1`, `httpmuxgo121=1`
  with no removal plan. Why: each reverts behaviour the module has not caught up with. [rel1.24][mod]
- **Incompatible changes go to `/v2`; a pseudo-version means an untagged dependency.** Signal: a
  `require` on `v0.0.0-2024…-abcdef`; `golang.org/x/exp` where std has the function; no
  `govulncheck` in CI. Why: ask for a tag, prefer std, scan dependencies. [mod][sec-bp][gcl exptostd]

### Tests

- **Table-driven with `t.Run` when cases share logic; separate functions when checks differ.**
  Signal: `setupMock func(…)` fields or `if tt.x { … } else { … }` inside the table loop; subtest
  names with spaces or slashes (`-run` escapes them). Why: a table with branching is several tests
  in disguise. [TC][GSG-D][Uber][100go #85]
- **Failure messages name the function and input, actual before expected.** Signal:
  `t.Errorf("expected %v, got %v"`; `case #%d`; large values without `cmp.Diff` and `(-want +got)`.
  Why: `Func(%v) = %v, want %v` tells the reader what failed without the source. [Go CRC][TC][GSG-D]
- **`t.Error` to keep going, `t.Fatal` for setup; never `Fatal`, `FailNow`, `Skip` or `Parallel`
  from a goroutine the test started.** Signal: `t.Fatal` inside `go func`. Why: the test exits
  while the goroutine runs on; `t.Error` plus `return` there. [TC][GSG-BP][pkg:testing]
- **No `time.Sleep` as synchronisation.** Signal: `time.Sleep(` in `_test.go`. Why: wait on a
  channel, `WaitGroup` or a condition with a deadline; inject the clock; `synctest.Test` with its
  fake clock on Go ≥1.25. [100go #86–87][synctest][rel1.25]
- **Helpers call `t.Helper()` first and do setup, not assertions.** Signal:
  `func \w+\(t \*testing.T` with no `t.Helper()`; `*testing.T` before `context.Context`. Why:
  without it the failure points at the helper. [TC][GSG-D][gcl thelper]
- **Use the testing package's facilities; `for b.Loop()` in benchmarks (Go ≥1.24).** Signal:
  `os.MkdirTemp`, `os.Setenv`, `defer` cleanup, `context.Background()` in `_test.go`; `b.N` loops in
  a new benchmark on ≥1.24. Why: `t.TempDir()`, `t.Setenv`/`t.Chdir` (incompatible with
  `t.Parallel`), `t.Cleanup` (LIFO, after subtests), `t.Context()`. [pkg:testing][gcl usetesting][100go #89]
- **`cmp.Equal`/`cmp.Diff` over `reflect.DeepEqual`; `errors.Is` over string matching.** Signal:
  `reflect.DeepEqual` or `err.Error() == "` in `_test.go`; field-by-field assertions; comparing
  serialized output you do not control instead of parsing it. Why: `cmpopts.EquateErrors` and
  `protocmp.Transform()` express intent; `wantErr bool` when only presence matters. [TC][GSG-D]
- **CI runs `go test -race -shuffle=on`; integration tests are gated.** Signal: a concurrency test
  with no `-race` in the Makefile or workflow; integration tests with no build tag, env var or
  `-short` guard; `-count=1` missing where caching hides a flake. [100go #82–84][sec-bp]

### Interfaces and API design

- **Accept interfaces, return concrete types; define the interface where it is consumed.** Signal:
  `type \w+ interface` beside its sole implementation; `func New\w+\(\) \w+Interface`; an interface
  mirroring a whole struct's method set; `*io.Reader` (interfaces go by value). Why: no interface
  before a second implementation, and never one just for mocking. [Go CRC][GSG-BP][100go #5–7][Uber]
- **Assert compliance at compile time.** Signal: a type that exists to satisfy an interface without
  `var _ io.Writer = (*T)(nil)`. Why: the break is reported at the type, not at a far call. [Uber][EG]
- **The zero value is useful and documented.** Signal: `New()` that only returns `&T{}`; a type that
  panics on its zero value. Why: constructors are for invariants; `sync.Mutex`'s zero value is
  valid. [EG][doc][Uber]
- **Export the minimum.** Signal: a newly exported identifier used from one package; helpers outside
  `internal/`. Why: unexported until a second caller appears. [100go #12][layout]
- **Take `io.Reader`/`io.Writer`, not filenames, `[]byte` or `string`.** Signal:
  `func \w+\(path string\)` that just opens and reads; build-then-write instead of
  `fmt.Fprintf(w, …)`. [100go #46][GSG-BP]
- **Doc comments say what the type does not guarantee.** Signal: a new type with goroutines or a
  mutex and no concurrency note (default: not safe for concurrent use); a background type without
  "Call Stop to release…"; a sentinel without a doc. [GSG-BP][doc]
- **A changed exported signature in a `v1` module breaks consumers; `// Deprecated:` first.**
  Signal: changed exported signatures in a tagged module without a `/v2` path; an identifier deleted
  with no prior `Deprecated:` paragraph naming the replacement. [mod][doc]

### Idioms and language features

- **Watch `:=` shadowing of `err`, `ctx`, `conn`, `resp` in inner scopes.** Signal:
  `ctx, cancel := context.WithTimeout(…)` or `err :=` inside an `if` or `for` body where the outer
  variable is read afterwards. Why: the outer never changes; vet's shadow pass is off by default.
  [100go #1][GSG-BP]
- **Receiver kind is consistent per type.** Signal: one type with both `func (x T)` and
  `func (x *T)`. Why: pointer when mutating, holding a mutex or large; value for small immutable
  types, maps, funcs and channels; mixing makes `T` and `*T` method sets differ. [Go CRC][GSG-D][100go #42]
- **Avoid `init()` and package-level mutable state.** Signal: `^func init\(\)` doing I/O, reading
  env or starting goroutines; a top-level `var` assigned anywhere; registries; `var now = time.Now`
  patched in tests. Why: `init` runs on import and cannot return an error; globals make tests
  order-dependent. [Uber][100go #3][GSG-BP]
- **Embedding is an API decision.** Signal: an embedded type in an exported struct (promoted methods
  leak; an embedded `time.Time` hijacks `MarshalJSON`); an embedded `sync.Mutex` (exports `Lock`);
  embedded fields not at the top. [Uber][100go #10, #77][EG]
- **Struct tags on every marshaled struct; `omitzero` (Go ≥1.24) for `time.Time` and structs.**
  Signal: `json.Marshal(` of a struct with untagged exported fields; `omitempty` on `time.Time`.
  Why: a renamed field silently changes the wire format. [Uber][rel1.24][vet structtag][gcl musttag]
- **No `defer` in a loop body; `defer` arguments are evaluated at the statement.** Signal: `defer`
  at loop depth (files and locks accumulate until return); `defer fmt.Println(x)`, `defer x.Close()`
  where `x` is reassigned later. Why: wrap the body in a function; use a closure. [100go #35, #47][SC SA9001]
- **Use `for i := range n` and delete `x := x` (Go ≥1.22); `for v := range seq` (Go ≥1.23).**
  Signal: `for i := 0; i < n; i++` using `i` only as an index; `\b(\w+) := \1$` inside a loop; an
  iterator body ignoring `yield`'s result. Why: below 1.22 do the opposite and keep the copies.
  [rel1.22][loopvar][rangefunc][gcl intrange/copyloopvar]
- **`time.Duration` wherever a period is meant.** Signal: `time.Sleep(\d+)`, `Timeout: 30,`,
  `d * time.Second` where `d` is already a Duration. Why: `time.Sleep(1000)` is one microsecond.
  [Uber][100go #75][SC SA1004][gcl durationcheck]
- **`Must*` functions only at init, startup or test setup.** Signal: `Must\w+\(` inside a handler or
  request path. Why: it panics on what should be an error. [GSG-D]

### Naming and package layout

- **MixedCaps, never underscores or ALL_CAPS; initialisms keep one case.** Signal: `[a-z]_[a-z]`
  identifiers, `const [A-Z][A-Z_]+ =`; `\bId\b|Url\b|Http[A-Z]|Json\b|Api\b|Grpc|Uuid|Sql\b` (`ID`,
  `URL`, `ServeHTTP`, `appID`). Exempt: `Test_` suffixes, generated code, cgo. [Go CRC][GSG-D][SC ST1003]
- **Package names short, lowercase, singular, one word, no vague buckets, no stutter.** Signal:
  `package util|utils|common|helpers?|misc|shared|lib|models?|types|api|interfaces`; plurals; names
  that steal variable names (`buf`); an exported name starting with its package (`http.HTTPServer`,
  `widget.NewWidget`). [EG][pkgnames][GSG-D][Uber][Go CRC]
- **Do not shadow predeclared identifiers or imported packages.** Signal:
  `\b(len|cap|new|make|copy|close|error|string|any|min|max|url|path|time|log|ctx)\s*:?=`. Why: a
  local `url :=` hides `net/url` for the rest of the function. [Uber][GSG-BP][gcl predeclared]
- **Imports: std first, then others; alias only on collision; no dot imports; blank imports only in
  `main` or tests.** Signal: `^import \. `; `^\s+_ "` outside `main` and `_test.go`; an alias equal
  to the package name. [Go CRC][GSG-D][Uber][SC ST1001]
- **Doc comment on every exported identifier, starting with its name; one package comment.**
  Signal: `^(func|type|var|const) [A-Z]` with no preceding `//`; "returns true if" instead of
  "reports whether"; a blank line between `// Package foo` and `package`. [Go CRC][doc][GSG-D][SC ST1000]
- **Layout: `internal/` for the private, `cmd/<name>/main.go` per binary, no `pkg/` or `src/`.**
  Signal: an exported package only one binary uses; `pkg/` wrappers; flags defined outside
  `package main`; `type Mock\w+ struct` exported from a production package (use `<pkg>test`).
  [layout][GSG-BP][GSG-D]

## Severity

**Blocking.** Shared state written from two goroutines; every `go` statement has a stop, a wait and
a bound; `wg.Add` before `go`; never discard an error (writes, closes of writers, `Unmarshal`,
`Rollback`); check `err` before the value and before deferring `Close`; writing to a nil map; no
`nil, nil` and no `*MyError` (typed nil); `http.Error` followed by `return`; SQL placeholders,
`exec.Command`, `html/template`; `filepath.Join` traversal; TLS verified; `crypto/rand` for secrets;
no hardcoded credentials; bound untrusted input; no `panic` in library code on input;
`log.Fatal`/`os.Exit` only in `main`; `http.Client` and `http.Server` timeouts; close body and rows;
`cancel` on every path; loop-variable capture below Go 1.22; `select` default spin; `sync` types
never copied; `replace` in a library `go.mod`; a `go` line bump without justification; `t.Fatal`
from a goroutine; `time.Sleep` as test synchronisation; a changed exported signature in a `v1` module.

**Should fix.** `%w` versus `%v` and `errors.Is`/`As` over `==`; error strings and string-matching
errors; handle once; interfaces beside their sole implementation; receiver kind consistent; `init()`
and package-level mutable state; context in a struct or `context.Background()` in a request path;
`time.After` in a loop below 1.23; `append` aliasing; sub-slice retention; missing doc comments on
new exported identifiers; initialisms and stutter; shadowed `err`/`ctx`; `defer` in a loop;
`reflect.DeepEqual` in tests; failure messages without function, input and got/want; helpers without
`t.Helper`; `b.N` loops on 1.24+; `go.sum` churn; `// +build`; missing struct tags and `omitempty` on
`time.Time`; `Must*` on user input; bare-integer durations.

**Nit (defer to the repository).** `any` versus `interface{}` in untouched code; `_` prefix on
unexported globals; `new(T)` versus `&T{}`; import grouping with no formatter; `tests`/`tt` versus
`test`; got/want wording when the message says which is which; `var s []T` versus `[]T{}` outside
APIs; comment punctuation; line length; `%q` versus `'%s'`; `strconv` versus `fmt` off hot paths;
Yoda conditions; `default` placement; `for i := range n` rewrites of correct loops; a buffer of 1 or 0.

## Where the sources disagree

- Assertion libraries or plain `if got != want`: follow the repository; if it uses testify, enforce
  testify's conventions (`assert.Equal(t, expected, actual)`, no `require` from goroutines). [TC][GSG-D]
- `%w` by default or `%v` by default: follow the repository; if none, prefer `%w` because
  `errorlint` and Uber assume it, and use `%v` only where the cause must stay hidden. [GSG-BP][Uber]
- Pointer or value receivers by default: follow the repository; if none, pointer when in doubt and
  value for small immutable types, maps, funcs and channels; never mix on one type. [Go CRC][GSG-D]
- Non-decisions, never flag: `new(T)` or `&T{}`; `var s T` or `T{}`; `_` prefix on unexported globals
  (Uber only); golden files and `-update` flags; `interface{}` to `any` rewrites in untouched code. [GSG-D][Uber]
- Two or four import groups, and line length: if a formatter or `lll`/`golines` runs, say nothing;
  otherwise the repository's; Google sets no line limit and says refactor instead. [GSG-D][Uber]
- Channel buffers "one or none" or sized deliberately: follow the repository; if none, accept a
  larger buffer with a comment explaining the number (pools and rate limits are legitimate). [Uber][100go #67]
- Functional options or an `Options` struct, enum zero invalid or meaningful: follow the repository
  and decide per type; if none, the struct when most callers set options. [GSG-BP][Uber][100go #11]
- `package foo_test` or the same package, and `t.Parallel()` everywhere or not: repository policy;
  if none, the same package (`_test` for integration tests and import cycles), and no `t.Parallel`
  where `t.Setenv`, `t.Chdir` or `AllocsPerRun` appear. [GSG-D][gcl testpackage/paralleltest][pkg:testing]
- `go.uber.org/atomic` or `sync/atomic`: prefer std typed values (`atomic.Int64`, `atomic.Bool`,
  `atomic.Pointer[T]`) in new code unless the repository already depends on Uber's. [Uber][pkg:atomic]
- Exported interfaces and mocks for mocking: Google forbids them; mockgen and mockery codebases do
  it; flag only if the repository does not already. [GSG-BP]
- `wrapcheck`, `err113`, `nonamedreturns`, `gochecknoglobals`, `mnd`, `varnamelen`: opinionated;
  do not reintroduce their rules by hand unless the config enables them. Named results likewise:
  use them for same-typed results or a `cancel func()`, sparingly. [gcl][GSG-D][100go #43–44]
- Log or return at a given layer: both agree never both; which side wins is the layer's call. [GSG-BP][Uber]

## Reference

Open `references/go-review-research.md` for an item's full signal list, a source tag's URL, the
complete linter-to-rule table, or the research's own wording of a finding; it is the research this
skill was condensed from, unchanged. Its sections: 1. Sources; 2. Detecting the language and
version; 3. Checklist (Naming and package layout; Idioms and language features; Errors; Concurrency
and context; Slices, maps and memory; Interfaces and API design; Security; Tests; Modules and
build); 4. Severity hints; 5. Already enforced by tooling; 6. Open questions / disagreements.
