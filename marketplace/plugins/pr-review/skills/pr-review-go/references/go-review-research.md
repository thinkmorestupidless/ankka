Contents

1. Sources
2. Detecting the language and version
3. Checklist
   - Naming and package layout
   - Idioms and language features
   - Errors
   - Concurrency and context
   - Slices, maps and memory
   - Interfaces and API design
   - Security
   - Tests
   - Modules and build
4. Severity hints
5. Already enforced by tooling
6. Open questions / disagreements

---

# Go review checklist research (Go 1.22+, aware of 1.23–1.25)

Compiled 2026-10-23 from the primary sources listed in section 1. Intended as the language-specific
pass an AI reviewer runs after the language-agnostic checks. Every checklist item cites the source
tag in brackets; the tags are expanded in section 1.

## 1. Sources

Every URL below was fetched and read for this document. Tag in brackets is used in the checklist.

- https://go.dev/wiki/CodeReviewComments — [CRC] the canonical 31-item review list: gofmt, comment sentences, contexts, copying, crypto rand, empty slices, doc comments, don't panic, error strings, examples, goroutine lifetimes, handle errors, imports (blank, dot, rename), in-band errors, indent error flow, initialisms, interfaces, line length, MixedCaps, named results, naked returns, package comments/names, pass values, receiver names/type, synchronous functions, useful test failures, variable names.
- https://go.dev/wiki/TestComments — [TC] 13 test rules: no assert libraries, readable subtest names, compare full structures, stable results, cmp.Diff over reflect.DeepEqual, got before want, identify function and input, keep going (t.Error), t.Helper, print diffs, table-driven vs multiple functions, test error semantics.
- https://go.dev/doc/effective_go — [EG] names, getters, interface names, control flow, named results, defer evaluation, new vs make, slices/append, maps comma-ok, printing verbs, iota, init, receivers, embedding, share-by-communicating, semaphore gating, panic/recover.
- https://google.github.io/styleguide/go/guide — [GSG-G] clarity > simplicity > concision > maintainability > consistency; gofmt mandatory; no line limit; local consistency rules.
- https://google.github.io/styleguide/go/decisions — [GSG-D] the richest rule list: naming (underscores, initialisms, getters, repetition), commentary, imports (4 groups, proto aliases), errors (%w placement, in-band), language (field names, nil slices, copying sync types, Must functions, goroutine lifetimes, interfaces, generics, receivers, any, %q), flags, logging, contexts, crypto/rand, test failures and structure, non-decisions.
- https://google.github.io/styleguide/go/best-practices — [GSG-BP] function naming, test-double naming, shadowing, package size, error wrapping (%v vs %w), logging errors once, panics at API boundary, documentation of concurrency/cleanup/sentinels, variable declarations, option structs vs functional options, tests (TestMain, hermeticity, t.Fatal in goroutines), string concatenation, global state, interfaces.
- https://github.com/uber-go/guide/blob/master/style.md and https://raw.githubusercontent.com/uber-go/guide/master/style.md — [Uber] pointers to interfaces, interface compliance assertion, zero-value mutexes, copy slices/maps at boundaries, defer cleanup, channel size one or none, enums start at one, time types, error types/wrapping/naming/handle once, type assertion comma-ok, don't panic, atomic, mutable globals, embedding in public structs, built-in names, init(), exit in main, field tags, fire-and-forget goroutines, performance (strconv, []byte conversions, capacity), style (imports, `_` globals, nil slices, scope, naked params, raw strings, struct init), test tables, functional options, linting set.
- https://100go.co/ — [100go #n] all 100 mistakes with descriptions; notes which are obsolete since 1.22 (#32, #63), 1.23 (#76), 1.25 (#100).
- https://staticcheck.dev/docs/checks/ — [SC] categories SA1–SA9, S1, ST1, QF1 and every check id used below.
- https://pkg.go.dev/cmd/vet — [vet] the 35 analyzers (appends, atomic, bools, buildtag, composites, copylocks, defers, errorsas, hostport, httpresponse, ifaceassert, loopclosure, lostcancel, nilfunc, printf, shift, sigchanyzer, slog, stdmethods, stdversion, stringintconv, structtag, testinggoroutine, tests, timeformat, unmarshal, unreachable, unsafeptr, unusedresult, waitgroup, …).
- https://golangci-lint.run/docs/linters/ — [gcl] the 115 linters with descriptions; deprecated wsl/gomodguard/exhaustruct.
- https://golangci-lint.run/docs/welcome/quick-start/ — [gcl] the default set: errcheck, govet, ineffassign, staticcheck, unused; `--default=none`.
- https://golangci-lint.run/docs/configuration/file/ — [gcl] `linters.default` ∈ standard|all|none|fast; `formatters.enable` ∈ gci, gofmt, gofumpt, goimports, golines; exclusion presets.
- https://github.com/securego/gosec and https://raw.githubusercontent.com/securego/gosec/master/RULES.md — [gosec Gnnn] all active rules G101–G125, G201–G204, G301–G307, G401–G408, G501–G507, G601–G602, G701–G710.
- https://go.dev/doc/go1.22 — [rel1.22] per-iteration loop variables, range over int, math/rand/v2, ServeMux patterns, vet appends/defers/slog.
- https://go.dev/doc/go1.23 — [rel1.23] range-over-func, iter/unique packages, slices/maps iterators, timers GC'd and unbuffered (gated on go line), vet stdversion, godebug directive.
- https://go.dev/doc/go1.24 — [rel1.24] generic type aliases, `tool` directive, vet tests/printf non-const format, T.Context, B.Loop, T.Chdir, os.Root, omitzero, crypto/rand.Text, synctest experiment, runtime.AddCleanup.
- https://go.dev/doc/go1.25 — [rel1.25] vet waitgroup/hostport, testing/synctest GA, sync.WaitGroup.Go, container-aware GOMAXPROCS, json/v2 experiment, `ignore` directive, CrossOriginProtection, T.Attr/T.Output, AllocsPerRun panics under parallel.
- https://go.dev/ref/mod — [mod] go/toolchain/require/exclude/replace/retract/godebug/tool/ignore directives, go.sum, vendoring consistency, /vN suffix, pseudo-versions, tidy.
- https://go.dev/doc/comment — [doc] doc comment rules: start with name, "reports whether", package comment once, `Deprecated:` paragraph, formatting, common mistakes.
- https://go.dev/blog/go1.13-errors — [blog-errors] Unwrap/Is/As, %w vs %v, "wrapping makes the error part of your API", sentinel and custom error types.
- https://pkg.go.dev/errors — [pkg:errors] Unwrap() []error, errors.Join, ErrUnsupported.
- https://pkg.go.dev/context — [pkg:context] ctx first, not in structs, never nil, WithValue scope, call cancel, WithoutCancel, AfterFunc, causes.
- https://pkg.go.dev/net/http — [pkg:http] Client.Timeout zero = none, Server timeouts semantics, body must be closed, ServeMux pattern syntax, ResponseWriter lifetime, Request.Context cancellation, MaxBytesReader.
- https://pkg.go.dev/testing — [pkg:testing] Parallel, Cleanup order, Helper, Context, TempDir, Setenv/Chdir restrictions, goroutine rules, B.Loop, fuzz shape, Output comments, TestMain.
- https://pkg.go.dev/sync — [pkg:sync] no copy after first use, Mutex not reentrant, Once/OnceFunc/OnceValue, Pool, Map use cases, WaitGroup.Add ordering, WaitGroup.Go, RWMutex caveats.
- https://pkg.go.dev/sync/atomic — [pkg:atomic] prefer typed atomics, 64-bit alignment on 32-bit, low-level only.
- https://pkg.go.dev/testing/synctest — [synctest] bubbles, Test/Wait, fake time, durable blocking, restrictions.
- https://go.dev/blog/loopvar-preview — [loopvar] old vs new semantics, gating on go.mod, `x := x` now dead.
- https://go.dev/blog/range-functions — [rangefunc] Seq/Seq2, yield false means stop, iter.Pull stop, when a plain loop is clearer.
- https://go.dev/blog/when-generics — [when-generics] when type parameters pay, when an interface is right, "write the code first".
- https://go.dev/wiki/CommonMistakes — [CM] the four loop-variable mistakes, all marked obsolete since 1.22.
- https://go.dev/blog/slices-intro — [slices-intro] shared backing arrays, append reallocation, retention gotcha.
- https://go.dev/blog/package-names — [pkgnames] short lowercase, don't steal variable names, no stutter, no util/common/api/types.
- https://go.dev/blog/examples — [examples] ExampleF/ExampleT_M naming, `// Output:`, whole-file examples.
- https://go.dev/blog/subtests — [subtests] t.Run, -run paths, parallel subtests and the parent wait.
- https://go.dev/doc/modules/layout — [layout] internal/, cmd/, server layout.
- https://go.dev/doc/security/best-practices — [sec-bp] govulncheck, keep Go current, fuzz, -race, vet.
- https://go.dev/wiki/Go-Release-Cycle — [release-cycle] six-month cadence, two supported majors.
- https://github.com/golang/go/wiki/Go-Release-Cycle — redirect stub only; contributed nothing.

Not fetched (deliberately not cited): Dave Cheney's blog, golangci-lint linter source repos, the Go spec.

## 2. Detecting the language and version

**Files.** `*.go`; `go.mod` at the module root (`module`, `go`, optional `toolchain`, `require`,
`replace`, `tool`, `godebug`, `ignore`); `go.sum`; optional `go.work`/`go.work.sum`; `vendor/` with
`vendor/modules.txt`; `*_test.go`; `testdata/` directories (ignored by the go tool); `//go:build`
lines at the top of files; `// Code generated ... DO NOT EDIT.` headers on generated files. [mod][layout]

**Reading the version.** The `go` line in `go.mod` (`go 1.24.2`) is the module's minimum version
*and* the language-semantics switch for every file in the module; a file can raise its own with
`//go:build go1.23`. Since Go 1.21 the line is mandatory and a toolchain refuses to build a module
whose `go` line is newer than itself (or downloads the toolchain named by `toolchain`), so bumping
the line is a requirement placed on every consumer and every CI image. [mod][loopvar]

What the version changes for review:

| go line | What flips | Review consequence |
|---|---|---|
| ≥ 1.21 | `min`, `max`, `clear` builtins; `slices`, `maps`, `cmp`, `log/slog` in std; `context.WithoutCancel`, `sync.OnceFunc/OnceValue`, `errors.Join` (1.20) | prefer std over hand loops and `x/exp` |
| ≥ 1.22 | **per-iteration loop variables**; `for i := range n`; `math/rand/v2`; ServeMux method + wildcard patterns | stop flagging goroutine/closure capture of loop vars; flag now-dead `x := x`; `rand.Seed` is noise |
| ≥ 1.23 | **range-over-func iterators** (`iter.Seq`); `slices.All/Values/Collect/Sorted`, `maps.Keys/Values`; **timers GC'd without Stop and channels unbuffered**; vet `stdversion` | `time.After` in a loop no longer leaks; `maps.Keys` now returns an iterator, not a slice |
| ≥ 1.24 | `t.Context()`, `b.Loop()`, `t.Chdir`; `tool` directive; `os.Root`; `omitzero` tag; `crypto/rand.Text`; vet `tests` and non-constant `printf` format | prefer `t.Context()` over `context.Background()` in tests; `for b.Loop()` over `b.N` |
| ≥ 1.25 | **`sync.WaitGroup.Go`**; **`testing/synctest` GA**; container-aware GOMAXPROCS; vet `waitgroup`, `hostport`; `ignore` directive; `http.CrossOriginProtection`; `json/v2` behind GOEXPERIMENT | prefer `wg.Go(f)`; `synctest.Test` instead of sleeps in concurrency tests; `automaxprocs` libraries become redundant |

Only the two most recent majors receive security fixes; a `go` line two or more minors behind the
current release means the module is tested on an unsupported toolchain. [release-cycle]

**Tooling to glance at before flagging style.** `.golangci.yml`/`.yaml`/`.toml` (v2 files start
with `version: "2"`; read `linters.default` — `standard` means errcheck, govet, ineffassign,
staticcheck, unused — plus `linters.enable`, `linters.settings`, and `formatters.enable` for gofmt /
gofumpt / goimports / gci / golines); `staticcheck.conf`; `Makefile`/`Justfile`/`Taskfile` targets
running `go vet`, `gofmt -l`, `goimports -local <module>`, `gofumpt`; `.github/workflows/*.yml`
using `golangci/golangci-lint-action`; `.pre-commit-config.yaml`; `tool` directives or a
`tools.go`. If gofmt is enforced, never comment on whitespace, brace placement, `gofmt -s`
simplifications or alignment; if goimports/gci is enforced, never comment on import ordering; if
gofumpt is enforced, also skip empty lines at block starts, `0o` octal literals, grouped `var`
blocks and `//` comment spacing. Section 5 maps the rest. [gcl]

## 3. Checklist

Each item: rule, the concrete signal in a diff, why it matters, source.

### Naming and package layout

- **MixedCaps, never underscores or ALL_CAPS.** Grep identifiers for `[a-z]_[a-z]` and `const [A-Z][A-Z_]+ =`; exceptions are `Test_`/`Benchmark_`/`Example_` suffixes, generated code and cgo/OS bindings. Go readers parse `maxLength`, not `MAX_LENGTH`. [CRC][GSG-G][GSG-D]
- **Initialisms keep one case.** Grep `\bId\b|Url\b|Http[A-Z]|Json\b|Api\b|Grpc|Uuid|Sql\b|Xml\b|Html\b`; `ID`, `URL`, `ServeHTTP`, `appID`, `userIDs`, `XMLAPI`. Mixed-case initialisms become all-upper or all-lower on export change (`iOS` → `IOS`). Generated protobuf is exempt. [CRC][GSG-D] (ST1003 catches most.)
- **Package names short, lowercase, singular, one word, no vague buckets.** Flag `package util|utils|common|helpers?|misc|shared|lib|models?|types|api|interfaces`, underscores, plurals (`urls`), and names that steal variable names (`buf`, `count`). Clients read `pkg.Name` as a phrase. [EG][pkgnames][GSG-D][Uber]
- **No stutter between package and identifier.** Flag exported names that begin with their own package name: `http.HTTPServer`, `widget.NewWidget`, `yamlconfig.ParseYAMLConfig`, `chubby.ChubbyFile`; `New` alone when it returns the package's own type. [CRC][GSG-BP][pkgnames]
- **Getters drop `Get`; setters keep `Set`.** Grep `func \(\w+ \*?\w+\) Get[A-Z]`; `Owner()` / `SetOwner()`. Use `Compute`/`Fetch` when the call is expensive or remote. Protobuf getters are the exception (see `protogetter`). [EG][GSG-D]
- **Receiver names one or two letters, consistent per type, never `this`/`self`/`me`.** Grep `func \((this|self|me|that) `. Inconsistent receivers across methods of one type are ST1016. [CRC][GSG-D][SC ST1006/ST1016]
- **Name length scales with scope; no type words or dropped letters.** Flag `userSlice`, `numUsers`, `strName`, `Sbx`; accept `i`, `r`, `w`, `c` for tiny scopes. Qualify only when two forms coexist (`limitRaw`, `limit`). [GSG-D][CRC]
- **Don't shadow predeclared identifiers or imported packages.** Grep `\b(len|cap|new|make|copy|close|error|string|any|min|max|url|path|time|log|ctx)\s*:?=`. A local `url :=` hides `net/url` for the rest of the function; `error :=` reads as a type. [Uber][GSG-BP][gcl predeclared]
- **Error names: `ErrFoo` for sentinel vars, `FooError` for types.** Grep `var \w+ = errors.New` whose name lacks `Err`/`err`, and `type \w+Err struct`. Readers and `errname` rely on it. [Uber][SC ST1012][gcl errname]
- **Imports: std first, blank line, then others; alias only on collision or when the path's last element differs from the package name; no dot imports; blank imports only in `main` or tests.** Grep `^import \. `, `^\s+_ "` outside `main`/`_test.go`, and aliases equal to the package name. Google adds two more groups (project, protobuf, side effects); Uber uses two. [CRC][GSG-D][Uber][SC ST1001]
- **Doc comment on every exported identifier, starting with the name, full sentence; one package comment per package (`// Package foo …`, `// Command foo …` for binaries) with no blank line before `package`.** Grep `^func [A-Z]`, `^type [A-Z]`, `^var [A-Z]`, `^const [A-Z]` with no preceding `//`. Boolean docs say "reports whether", not "returns true if". [CRC][doc][GSG-D][SC ST1000/ST1020–22]
- **Layout: `internal/` for anything not meant for import, `cmd/<name>/main.go` per binary, no one-type-one-file rule, `doc.go` holds only the package comment.** Flag exported packages that only one binary uses (should be internal), `pkg/` or `src/` wrappers (not in the official layouts), and giant single files. [layout][GSG-BP]
- **Test-double packages are `<pkg>test`; doubles are `Stub`/`Fake`/`AlwaysDeclines`; variables holding doubles are prefixed (`spyCC`).** Grep `type Mock\w+ struct` exported from a production package. [GSG-BP]
- **Flags are `snake_case` names bound to `camelCase` variables and defined only in `main`.** Grep `flag\.\w+\("` outside `package main`. Libraries take configuration through their API. [GSG-D]
- **Function names omit types, receiver and parameter names unless disambiguating.** `Parse`, not `ParseYAMLConfig`; `(c *Config) WriteTo`, not `WriteConfigTo`; `ParseInt64` only when `ParseInt` also exists. [GSG-BP]

### Idioms and language features

- **Early return; happy path left-aligned; no `else` after a terminating `if`.** Grep `} else {` following `return`/`continue`/`break`, and `if err == nil {` blocks that wrap the whole function body. [CRC][EG][Uber][100go #2]
- **Watch `:=` shadowing of `err`, `ctx`, `conn`, `resp` in inner scopes.** Signal: `ctx, cancel := context.WithTimeout(...)` or `err :=` inside an `if`/`for` body where the outer variable is read afterwards; the outer never changes. Use `=` with the pre-declared variable. `go vet`'s shadow pass is off by default, so this is a human check. [100go #1][GSG-BP]
- **Receiver kind consistent per type; pointer when mutating, holding a mutex, or large; value for small immutable types, maps, funcs, channels.** Grep a type with both `func (x T)` and `func (x *T)`. Mixing makes a method set differ between `T` and `*T` and loses assignments silently (SA4005). [CRC][GSG-D][100go #42][gcl recvcheck]
- **Avoid `init()`; never do I/O, read env, or start goroutines in it.** Grep `^func init\(\)`. It runs on import, cannot return an error, and orders by file name. Prefer explicit constructors or `main`. [Uber][100go #3][GSG-BP][gcl gochecknoinits]
- **No package-level mutable state; inject dependencies.** Grep top-level `var` that is assigned anywhere (`reassign`), registries, singletons, `var now = time.Now` patched in tests. Globals make tests order-dependent and APIs untestable. [Uber][GSG-BP][gcl gochecknoglobals]
- **`any`, not `interface{}`, in new code — but `any` in a signature says nothing.** Grep `interface{}` in changed lines; separately flag new exported `func(... any)`/`map[string]any` where a concrete or constrained type exists. [GSG-D][100go #8]
- **Generics only for code that would otherwise be copied per type.** Flag `[T any]` where `T` is used only to call a method (use an interface), a generic with a single instantiation, or per-type behaviour inside a type switch on `T`. "Write the code first." [when-generics][GSG-D][100go #9]
- **Embedding is an API decision.** Flag embedded types in exported structs (promoted methods and interface satisfaction leak; an embedded `time.Time` hijacks `MarshalJSON`), embedded `sync.Mutex` (exports `Lock`), and embedded fields not at the top of the struct. [Uber][100go #10, #77][EG]
- **`iota` enums: start at 1 unless zero is a meaningful default; give the type a `String()`; switches over the enum are exhaustive.** Grep `= iota` as the first value where zero means "unset"; SA9004 if only the first constant is typed. [Uber][SC SA9004][gcl exhaustive]
- **Struct tags on every marshaled struct; `omitzero` (1.24) rather than `omitempty` for `time.Time` and structs.** Grep `json.Marshal(` of a struct with untagged exported fields; grep `omitempty` on `time.Time`. A renamed field silently changes the wire format. [Uber][rel1.24][vet structtag][gcl musttag]
- **Options: an `Options` struct as the last parameter when most callers set some; variadic functional options when most need none; never `context.Context` inside an options struct; options take parameters (`FailFast(bool)` not `EnableFailFast()`); last option wins.** [GSG-BP][Uber][100go #11]
- **Named results only when they document (`(left, right *Node)`, `cancel func()`) or a deferred closure must set them; naked `return` only in short functions; remember named results start at zero.** Grep bare `return$` in functions > ~15 lines, and `(err error)` named only to enable `return`. [CRC][GSG-D][100go #43–44][gcl nakedret]
- **No `defer` in a loop body; wrap the body in a function.** Grep `for .*{` … `defer ` at loop depth. Deferred calls accumulate until the function returns (files, locks). [100go #35][SC SA9001]
- **`defer` arguments are evaluated at the `defer` statement.** Grep `defer fmt.Println(x)`, `defer log(time.Since(start))` (vet `defers` catches the latter), `defer x.Close()` where `x` is reassigned later. Use a closure. [100go #47][EG][vet defers]
- **1.22+: use `for i := range n`; delete `x := x` copies; 1.23+: `for v := range seq`.** Grep `for i := 0; i < n; i++` with unused body dependence on `i` only as index (intrange), and `\b(\w+) := \1$` inside loops (copyloopvar). On modules below 1.22 do the opposite: keep the copies. [rel1.22][loopvar][gcl intrange/copyloopvar]
- **Iterators (1.23): an iterator returns when `yield` returns false; `iter.Pull`'s `stop` is called (defer it); don't write an iterator where a slice or plain loop is clearer.** Grep `func(yield func(` bodies that ignore yield's result. [rangefunc][rel1.23]
- **Prefer std `slices`/`maps`/`min`/`max`/`clear` over hand loops and `golang.org/x/exp`.** Grep `x/exp/slices`, `x/exp/maps`, hand-rolled contains/index loops. [rel1.22][gcl exptostd/modernize]
- **Strings: `+` for a few pieces, `fmt.Sprintf` for formats, `strings.Builder` in loops, `fmt.Fprintf(w, …)` instead of building then writing; `strconv.Itoa` over `fmt.Sprint(n)` on hot paths; backtick literals for multi-line constants.** Grep `+=` on a string inside a loop. [GSG-BP][100go #39][Uber][gcl perfsprint]
- **`time.Duration` everywhere a period is meant; never bare integers.** Grep `time.Sleep\(\d+\)`, `Timeout: 30,`, `poll(10)`, `time.Duration(n) * time.Second` is fine; `d * time.Second` where `d` is already a Duration is wrong (`durationcheck`). `time.Sleep(1000)` is one microsecond. [Uber][100go #75][SC SA1004][gcl durationcheck]
- **Time is a dependency.** Grep `time.Now()` in logic that tests then sleep around; inject `now func() time.Time` or a clock. Compare `time.Time` with `Equal`, never `==` (monotonic reading). [Uber][100go #77, #87]
- **`math/rand/v2` for non-secret randomness (1.22+); `rand.Seed` is dead.** Grep `rand.Seed(`, `rand.Intn(` from v1 in new code. Secrets: see Security. [rel1.22]
- **Type assertions use the comma-ok form unless a panic is intended.** Grep `:= \w+\.\([*\w.\[\]]+\)$` (single-value). [Uber][EG][gcl forcetypeassert]
- **Format strings are constants and printf-style helpers end in `f`.** Grep `Printf(msg)` with a non-constant `msg` (vet flags since 1.24), `func Wrap(format string, args ...any)` without the `f`. Lets vet check arguments. [Uber][vet printf]
- **Printing verbs: `%v` default, `%+v` for structs with field names, `%q` for strings (never `'%s'`), `%d` on a pointer prints an address, `%s` on a non-Stringer struct is wrong, `%w` only in `fmt.Errorf`.** [EG][GSG-D][vet printf]
- **Octal literals as `0o644`; integer conversions can overflow silently; floats compared with tolerance.** Grep `0\d{3}` permissions without `o`, `int32(x)` from wider types at boundaries, `float == float`. [100go #17–19][SC SA9002][gosec G115]
- **`Must*` functions only at package init, startup, or test setup — never on user input.** Grep `Must\w+\(` inside handlers or request paths. [GSG-D]
- **Synchronous API by default; callers add goroutines.** Flag exported functions that spawn a goroutine and return a channel when a plain return would do. [CRC][GSG-D]
- **Type aliases (`type A = B`) only for migrations; new types use `type A B`.** [GSG-D]
- **Switch: no trailing `break`; `default` first or last; variable on the left of comparisons.** [GSG-D][SC ST1015/ST1017]

### Errors

- **Never discard an error.** Grep `_ = \w+\(`, `_, _ :=`, and bare calls to functions that return `error` (`f.Close()`, `w.Write(b)`, `json.Unmarshal(...)`, `tx.Rollback()`). A discard needs a comment saying why it is safe. [CRC][EG][100go #53][gcl errcheck]
- **Check `err` before using the other result, and before deferring `Close`.** Grep `v, err := f()` followed by `defer v.Close()` or `v.X` on the next line and `if err != nil` after. A nil `*os.File` closes with a panic or a second error. [SC SA5001]
- **`%w` when callers may inspect the cause; `%v` to hide it at a boundary; `%w` at the end of the message, or first for a sentinel (`"%w: invalid header", ErrParse`).** Grep `fmt.Errorf(".*%v", err)` (errorlint flags it) and `%w` of a non-error. Wrapping makes the wrapped error part of your API. Google defaults to `%v`, Uber and `errorlint` to `%w`; see section 6. [GSG-BP][blog-errors][Uber][gcl errorlint]
- **`errors.Is`/`errors.As`, never `==` or a type assertion on a possibly wrapped error.** Grep `err == \w+\.Err`, `err != io.EOF` (EOF is the accepted exception when unwrapped), `err.(\*`, `switch err.(type)`. `errors.As` needs a pointer target (vet `errorsas`); `errors.Is(target, err)` swapped is SA1032. [100go #50–51][blog-errors][vet errorsas][SC SA1032]
- **Never match on `err.Error()` text.** Grep `strings.Contains(err.Error()`, `err.Error() ==`. Expose a sentinel or a type instead. [GSG-BP][TC]
- **Sentinels are exported `var ErrX = errors.New(...)`, documented, and returned wrapped so callers use `errors.Is`; custom types implement `Unwrap()` and are documented with whether they are pointer types.** Grep new sentinels without doc comments; new error types returned as concrete types. [blog-errors][GSG-BP]
- **Error strings: lowercase, no trailing punctuation, no `failed to`/`error:` filler, name the origin.** Grep `errors.New("[A-Z]`, `Errorf("[A-Z]`, `\.")`, `"failed to `. They get embedded in other messages. [CRC][Uber][EG][SC ST1005]
- **Handle an error once: don't log and return it.** Grep `log.*(err)` immediately followed by `return .*err`. Double handling spams logs and hides where it was decided. [Uber][100go #52][GSG-BP]
- **No `panic` for ordinary failures; `recover` only at a package's public boundary and only for its own panics; re-panic foreign values.** Grep `panic(` outside `init`/`Must*`/tests, `recover()` that swallows and continues. [CRC][Uber][GSG-BP][100go #48]
- **`log.Fatal`/`os.Exit` only in `main`, ideally once, via `func run() error`.** Grep `log.Fatal|os.Exit` outside `package main` and in tests (use `t.Fatal`). It skips every `defer`. [Uber][GSG-BP]
- **Return the `error` interface, not `*MyError`; beware the typed-nil trap.** Grep `func .*\) \*\w+Error$`. A nil `*T` stored in an `error` is non-nil. [GSG-D][100go #45]
- **No `nil, nil`; no `return nil` after `if err != nil`.** Grep `return nil, nil` and `if err != nil {\n\s*return nil\n`. [gcl nilnil/nilerr/nilnesserr]
- **`defer f.Close()` on a writer or `bufio.Writer`/`gzip.Writer` loses the write error.** Grep `os.Create|OpenFile(.*O_WRONLY|RDWR)` paired with `defer \w+.Close()`. Check `Close()`'s error explicitly, via a named result, or `errors.Join`. Read-only files may ignore it with a comment. [100go #54]
- **`errors.Join` (1.20) and multi-`%w` for aggregates; callers must use `Is`/`As`, not `==`, on the result.** Grep hand-built `[]error` concatenated into strings. [pkg:errors]
- **Add context callers lack; never repeat what they already know.** Flag `fmt.Errorf("open %s: %w", path, err)` when `err` is already an `*os.PathError` naming the path, and `fmt.Errorf("failed: %w", err)` adding nothing. [GSG-BP]
- **No in-band errors; `error` or `ok bool` is the last result.** Grep functions returning `-1`, `""`, or `nil` to mean failure; `(error, T)` ordering. [CRC][GSG-D][SC ST1008]
- **`http.Error(w, …)` must be followed by `return`.** Grep `http.Error(` not followed by `return`. The handler keeps running and writes twice. [100go #80]
- **`errgroup` may drop all but the first error — acceptable, but say so; `errors.Join` when every error matters.** [GSG-BP][100go #73]

### Concurrency and context

- **Every `go` statement has a visible way to stop and a way to wait.** Grep `^\s+go (func|\w+)\(`; look for the `ctx.Done()`/`done` channel/`WaitGroup`/`errgroup` that bounds it. No `go` in `init`; a background type exposes `Stop`/`Shutdown`/`Close` that also waits. [CRC][Uber][100go #62][GSG-D]
- **No unbounded goroutine creation.** Grep `for … { go …}` without a semaphore channel, worker pool, or `errgroup.SetLimit`. Size to the workload: CPU-bound ≈ GOMAXPROCS, I/O-bound ≈ downstream capacity. [EG][100go #59]
- **`wg.Add(n)` before `go`, never inside the goroutine; `defer wg.Done()` first; 1.25+: `wg.Go(func)`.** Grep `go func() {\n\s*wg.Add(1)`. Otherwise `Wait` may return early. [100go #71][pkg:sync][rel1.25][vet waitgroup][SC SA2000]
- **Channels: only the sender closes; never close a receive-only channel; signal with `chan struct{}`; declare direction in signatures (`<-chan T`, `chan<- T`); buffer size 0 or 1 unless the number is explained.** Grep `make(chan \w+, \d{2,})`, `chan bool` used as a signal, `close(` in a consumer. [EG][Uber][100go #65, #67][GSG-BP]
- **No busy loops: `for { select { … default: } }` or `for cond {}` with an empty body.** Grep `default:\s*}` inside a `for`. [SC SA5002/SA5004]
- **Context is the first parameter, named `ctx`, never a struct field, never nil (use `context.TODO()`), `context.Background()` only in `main`, `init`, tests (prefer `t.Context()` on 1.24+) and deliberately detached work.** Grep `context.Background()` outside those; `ctx context.Context` as a struct field; `nil` passed where a `Context` is expected (SA1012). HTTP handlers use `r.Context()`; cobra uses `cmd.Context()`. [CRC][GSG-D][pkg:context][SC SA1012][gcl containedctx/contextcheck]
- **Don't hand a request context to work that outlives the response; use `context.WithoutCancel` (1.21) and say why.** Grep `go func` capturing `r.Context()` or `ctx` after the response is written. [100go #61][pkg:context]
- **Always call `cancel`, on every path, as `defer cancel()` right after `WithCancel/WithTimeout/WithDeadline`.** vet `lostcancel` catches most; check the deferred one is not inside a loop (then it is also a leak until return). [pkg:context][vet lostcancel]
- **`context.WithValue` only for request-scoped data crossing boundaries; keys are an unexported custom type, never `string` or built-ins.** Grep `context.WithValue(ctx, "`. [pkg:context][SC SA1029]
- **Don't rebuild contexts in a loop (`ctx = context.WithTimeout(ctx, …)` each iteration).** [gcl fatcontext]
- **Use the `…Context` variants: `http.NewRequestWithContext`, `db.QueryContext`, `ExecContext`, `net.Dialer.DialContext`.** Grep `http.Get(|http.Post(|http.NewRequest(|\.Query(|\.Exec(` in request paths. [gcl noctx]
- **Timers: on go < 1.23 `time.After` inside a `select` loop leaks until it fires — use `time.NewTimer` + `Stop`; `time.Tick` always leaks; `Stop` every `Ticker`; don't misread `Timer.Reset`'s return.** On ≥ 1.23 the `time.After` leak is gone (timers are collected); still stop tickers for clarity. [100go #76][rel1.23][SC SA1015/SA1025]
- **Data races: any shared map/slice/struct written from two goroutines needs a mutex or a channel; `append` on a shared slice races; copying a slice or map header out of a lock and iterating still races; `%v` of a struct whose `String()` takes the lock you hold deadlocks.** Grep writes to a package-level or struct map inside `go func`. `-race` must be in CI. [100go #58, #68–70, #83][sec-bp]
- **`sync` types are never copied after first use: no value receivers on structs holding a mutex, no passing `sync.WaitGroup` by value, no `*sync.Mutex` fields when a value works, mutex unexported and not embedded, `defer mu.Unlock()` right after `Lock()`; `RWMutex` is not recursive and cannot be upgraded.** vet `copylocks` catches the copies. [Uber][pkg:sync][100go #74][GSG-D][vet copylocks]
- **Typed atomics (`atomic.Int64`, `atomic.Bool`, `atomic.Pointer[T]`) over `atomic.AddInt64(&x, 1)`; never mix atomic and plain access to one variable; atomics are for counters and flags, not for replacing a mutex around compound state.** Grep `atomic\.(Add|Load|Store|CompareAndSwap)\w+\(&`. [pkg:atomic][Uber][SC SA1027]
- **`sync.Map` only for write-once/read-many caches or disjoint key sets; otherwise `map` + `sync.Mutex`.** Grep new `sync.Map` fields. [pkg:sync]
- **`sync.Once`: `Do` never reruns after a panic and deadlocks if re-entered; prefer `sync.OnceValue`/`OnceFunc` (1.21) for lazy values.** [pkg:sync]
- **`sync.Pool` holds pointer-shaped values, items are reset before `Put`, and it is not a free list for short-lived objects.** Grep `Pool.Put(` of a non-pointer (SA6002) or without a reset. [pkg:sync][SC SA6002]
- **`errgroup.Group` (with `SetLimit`) for fan-out that must propagate an error and cancel siblings.** Flag hand-rolled WaitGroup + error channel. [100go #73][GSG-BP]
- **Loop-variable capture: on go ≥ 1.22 it is fixed — do not flag `go func() { use(v) }()`; on < 1.22 flag every closure or `go v.Method()` capturing the range variable.** [loopvar][CM][rel1.22]
- **`select` picks a ready case at random; a nil channel disables a case.** Flag code relying on case order for priority (use a nested `select` or a priority check). [100go #64, #66]
- **`http.Client`: never `http.DefaultClient`/`http.Get` for production traffic; set `Timeout` or transport deadlines. `http.Server`: set `ReadHeaderTimeout`, `ReadTimeout`, `WriteTimeout`, `IdleTimeout`; never bare `http.ListenAndServe(`.** Grep `http\.(Get|Post|Head|PostForm)\(`, `http.DefaultClient`, `&http.Server{` lacking `ReadHeaderTimeout`, `http.ListenAndServe\(`. Zero timeout means none. [100go #81][pkg:http][gosec G112/G114]
- **Close what you open: `defer resp.Body.Close()` after the error check (and drain for connection reuse); `rows.Close()` and `rows.Err()` after the loop; `sql.Open` is lazy — `PingContext` to verify.** [100go #78–79][pkg:http][gcl bodyclose/rowserrcheck/sqlclosecheck]
- **`ResponseWriter` and `Request.Body` are not used after `ServeHTTP` returns; the request context is cancelled when the client disconnects or the handler returns.** Grep `go func` capturing `w http.ResponseWriter`. [pkg:http]
- **`signal.Notify` needs a buffered channel; `net.Dial(fmt.Sprintf("%s:%d"))` breaks on IPv6 — use `net.JoinHostPort`.** [vet sigchanyzer/hostport][SC SA1017][gcl nosprintfhostport]
- **Mutex vs channel: parallel access to shared data → mutex; coordination, signalling, ownership transfer → channel.** A lens, not a flag. [100go #57]

### Slices, maps and memory

- **`append` to a sub-slice with spare capacity overwrites the parent.** Grep `append(\w+\[:\w*\],`, functions that `append` to a slice parameter, and `s[:0]` reuse. Use the full slice expression `s[a:b:b]`, `slices.Clip`, or `slices.Clone`. [100go #25][slices-intro]
- **A small sub-slice (or substring) pins the whole backing array.** Grep `buf[n:m]` stored in a long-lived struct, `string(b)[:k]`, `strings.Split` results kept from a large input. Copy out with `slices.Clone`, `bytes.Clone`, `strings.Clone`. [100go #26, #41][slices-intro]
- **Slicing `[]*T` or `[]T` with pointer fields keeps the pointed objects alive; nil them or copy.** [100go #26]
- **Nil slices are the default; emptiness is `len(s) == 0`; APIs do not distinguish nil from empty; JSON `null` vs `[]` is the one place it matters.** Grep `:= []T{}` (prefer `var s []T`), `if s == nil` as an emptiness check, `return []T{}`. [CRC][Uber][GSG-D][100go #22–23]
- **Writing to a nil map panics; reading is fine.** Grep `var m map[` followed by `m[k] =` without `make`/literal; struct maps never initialised in the constructor. [SC SA5000][GSG-BP]
- **Map iteration order is random and insertion during iteration is unspecified; sort keys when order matters (`slices.Sorted(maps.Keys(m))`).** Grep `for k := range m` feeding output, tests, or hashing. [100go #33]
- **Maps never shrink; a long-lived map with churn needs periodic recreation.** [100go #28]
- **`copy` copies `min(len(dst), len(src))`; `make([]T, 0, n)` then `copy` copies nothing; `make([]T, n)` then `append` doubles the length.** Grep `make(\[\]\w+, \w+)` followed by `append(`. [100go #24][gcl makezero]
- **Preallocate when the length is known, but only after measuring in hot paths.** Grep `var s []T` followed by a `for … append` over a known-size input (prealloc). [Uber][GSG-BP][gcl prealloc]
- **`range` yields copies: `for _, v := range s { v.X = 1 }` mutates nothing; the range expression is evaluated once; ranging over an array copies the array.** Grep assignments to the range value variable. [100go #30–31]
- **Equality: `==` on structs containing slices/maps/funcs fails to compile; `reflect.DeepEqual` distinguishes nil from empty and is slow; use `slices.Equal`, `maps.Equal`, `bytes.Equal`, `cmp.Equal`; `time.Time` with `Equal`; NaN never equals itself.** Grep `reflect.DeepEqual` in non-test code. [100go #19, #29, #77][SC SA4012]
- **Strings are bytes: `len(s)` counts bytes, `s[i]` is a byte, `for i, r := range s` yields runes at byte offsets, `[]rune(s)` allocates; `TrimLeft/TrimRight` take cutsets, `TrimPrefix/TrimSuffix` take strings; hoist `[]byte("const")` out of loops.** Grep `len(s)` used as a character count, `TrimLeft(s, "prefix")`. [100go #36–38, #40][Uber][SC SA6003/SA1024]
- **Integer conversions at trust boundaries overflow silently (`int32(n)`, `uint8(len(b))`); `strconv.Atoi` into a narrower type; `string(i)` is a rune conversion.** [100go #18][gosec G115/G109][vet stringintconv]
- **Copy slices and maps at API boundaries: a setter stores a copy, a getter returns a copy or a read-only view.** Grep `d.items = items` and `return d.items`. [Uber]
- **Pass small values by value, not `*string`/`*io.Reader`; pass large structs and protobufs by pointer.** [CRC][GSG-D]
- **Sorting: `slices.Sort`/`SortFunc`/`SortStableFunc` over `sort.Slice`; `sort.Slice` on a non-slice is SA1028; use the stable variant when equal keys must keep order.** [SC SA1028][rel1.23]

### Interfaces and API design

- **Accept interfaces, return concrete types; define the interface in the consuming package; never export an interface just for mocking; no interface before a second implementation.** Grep `type \w+ interface` beside its sole implementation, `func New\w+\(\) \w+Interface`, interfaces mirroring a whole struct's method set. [CRC][GSG-BP][100go #5–7][gcl ireturn/iface]
- **Interfaces are small; one-method interfaces end in `-er`; don't reuse `Read`/`Write`/`String` with other semantics.** Grep interfaces with more than ~5 methods. [EG][GSG-BP][gcl interfacebloat]
- **Assert compliance at compile time: `var _ io.Writer = (*T)(nil)`.** Expect it when a type exists to satisfy an interface. [Uber][EG]
- **Never a pointer to an interface (`*io.Reader`); pass interfaces by value.** [Uber][CRC]
- **The zero value is useful and documented ("The zero value is ready to use"); constructors only when invariants need them; `sync.Mutex` zero value is valid.** Flag `New()` that only returns `&T{}` and types that panic on zero value. [EG][doc][Uber]
- **Export the minimum; unexported until a second caller appears; keep helpers in `internal/`.** Grep newly exported identifiers used from one package. [100go #12][layout]
- **Take `io.Reader`/`io.Writer`, not filenames, `[]byte` or `string`; write with `fmt.Fprintf(w, …)`.** Grep `func \w+\(path string\)` that just opens and reads. [100go #46][GSG-BP]
- **Doc comments: document concurrency safety (default assumption: not safe for concurrent use), cleanup requirements ("Call Stop to release…"), each sentinel error and whether an error type is a pointer, and cross-cutting error conventions in the package doc; don't restate that cancellation cancels.** [GSG-BP][doc]
- **`// Deprecated:` paragraph with the reason and the replacement before anything is removed; examples as `ExampleT_M` with `// Output:` in `_test.go`.** Grep removed exported identifiers without a prior `Deprecated:`. [doc][examples]
- **Don't export test doubles or wrap generated RPC clients in interfaces; design so the real implementation is testable.** [GSG-BP]
- **Embedding a type that satisfies an interface makes the outer type satisfy it too — check that is intended (`MarshalJSON` from an embedded `time.Time`, `Lock` from an embedded mutex).** [Uber][100go #10, #77]
- **Enumerations: a named type with `iota` constants, a `String()` method, and validation on decode; zero means "unspecified" unless a default is intended.** [Uber][EG]
- **Getters/setters are not the default; exported fields are fine when there is no invariant.** [100go #4][EG]
- **Semantic versioning is real: changing an exported signature or behaviour in a `v1` module breaks consumers; incompatible changes go to `/v2`.** Grep changed exported signatures in a tagged module. [mod]
- **`error` is the return type; never a concrete error type; never `any` where a type is known.** [GSG-D][100go #8]

### Security

- **SQL only with placeholders.** Grep `(Query|Exec|QueryRow|Prepare)\w*\(\s*(fmt\.Sprintf|"[^"]*"\s*\+|\w+\s*\+)`. [gosec G201/G202/G701]
- **`exec.Command` never takes user input as the program or via `sh -c`; arguments are allow-listed.** Grep `exec\.Command(Context)?\(` with a non-literal first argument or `"sh", "-c"`. [gosec G204/G702]
- **`filepath.Join(base, userPath)` does not confine; `..` escapes.** Grep `filepath.Join(.*(r\.|req\.|params|input|name))`, `os.Open(filepath.Join(root, …))`, `http.ServeFile(w, r, …+r.URL.Path)`, `http.Dir("/")`, zip/tar extraction loops. Use `os.OpenRoot` (1.24), `filepath.IsLocal`, or `Clean` + prefix check. [gosec G304/G305/G111/G703][rel1.24]
- **TLS: no `InsecureSkipVerify: true`, no `MinVersion` below `tls.VersionTLS12`, no `ssh.InsecureIgnoreHostKey`.** Grep those literals; a test-only exception needs a build tag or a flag. [gosec G402/G106/G123]
- **No MD5/SHA-1/DES/RC4/MD4 for security, RSA ≥ 2048, no hardcoded IV/nonce.** Grep `crypto/md5`, `crypto/sha1`, `crypto/des`, `crypto/rc4`, `rsa.GenerateKey(.*1024`, fixed `[]byte` nonces. MD5 for a non-security checksum is legitimate — say so in a comment or `//nolint:gosec`. [gosec G401/G403/G405–G407/G501–G507]
- **Secrets, tokens, nonces, session IDs come from `crypto/rand` (`rand.Text()` on 1.24+), never `math/rand`.** Grep `math/rand` in files mentioning token/secret/key/nonce/password/session. [CRC][gosec G404][rel1.24]
- **File permissions: no `0777`/`0666`; `0600` for secrets, `0644`/`0755` otherwise; literals written as octal.** Grep `0[67][67][67]\b`, `, 644\)`. [gosec G301/G302/G306/G307][SC SA9002]
- **Bound untrusted input: `http.MaxBytesReader` before `io.ReadAll(r.Body)` or `json.NewDecoder(r.Body)`; `io.CopyN`/`io.LimitReader` on decompressed streams (zip bombs); `ParseMultipartForm` with a limit; cap element counts.** Grep `io.ReadAll(r.Body)`, `io.Copy(.*gzip.NewReader`. [gosec G110/G120][pkg:http]
- **HTML goes through `html/template`, never `text/template` or `fmt`; no `template.HTML(userInput)`.** Grep `text/template` in packages that write HTML, `template.HTML(`, `template.JS(`. [gosec G203/G705/G708]
- **No hardcoded credentials; secrets excluded from marshaling (`json:"-"`).** Grep `(password|passwd|secret|token|api_?key)\s*[:=]\s*"[^"]+"`, exported `Password string` fields with json tags. [gosec G101/G117]
- **`net/http/pprof` is not imported into production binaries (it registers on `DefaultServeMux`); listening on `0.0.0.0` is a conscious choice.** Grep `_ "net/http/pprof"`. [gosec G108/G102]
- **User-controlled URLs (`http.Get(userURL)`), redirects (`http.Redirect(w, r, r.FormValue(...))`) and log lines with raw user input are injection points.** [gosec G107/G704/G706/G710]
- **Cookies set `Secure`, `HttpOnly`, `SameSite`; browser-facing mutating endpoints have CSRF protection (`http.CrossOriginProtection` on 1.25+).** Grep `http.SetCookie(` / `&http.Cookie{` lacking those fields. [gosec G124][rel1.25]
- **Untrusted input must not panic the process: guard index/slice bounds, nil maps, type assertions; recover only at the API boundary.** [gosec G602][GSG-BP]
- **`unsafe` and cgo need a reason; `govulncheck` runs in CI; `go.sum` is committed; dependency bumps are reviewed, not rubber-stamped.** [gosec G103][sec-bp]

### Tests

- **Table-driven tests with `t.Run` subtests when the cases share logic; separate functions when the checks differ; no branching flags, per-case mock-setup functions, or conditional logic inside the table loop.** Grep tables with `setupMock func(...)` or `if tt.x { … } else { … }` bodies. Uber names them `tests`/`tt` with `give`/`want` fields; Google uses `test`. [TC][GSG-D][Uber][100go #85]
- **Subtest names read as identifiers (no spaces, slashes, punctuation that `-run` escapes); long descriptions go in a `desc` field.** [TC][GSG-D][subtests]
- **Failure messages: `Func(%v) = %v, want %v`, actual before expected, name the function and the input, never the table index; `%q` for strings, `%+v` for small structs; large values as `cmp.Diff` with `(-want +got)` on a new line.** Grep `t.Errorf("expected %v, got %v"` (wrong order and no function name) and `case #%d`. [CRC][TC][GSG-D]
- **`t.Error` to keep going; `t.Fatal` for setup failures or inside a subtest; `t.Fatal`/`FailNow`/`Skip`/`Parallel` never from a goroutine the test started — `t.Error` + `return` there.** [TC][GSG-BP][pkg:testing][vet testinggoroutine][SC SA2002]
- **Helpers call `t.Helper()` first and do setup/teardown, not assertions; `*testing.T` comes after `context.Context` in the signature.** Grep `func \w+\(t \*testing.T` without `t.Helper()`. Google forbids assertion helpers altogether. [TC][GSG-D][gcl thelper]
- **Use the testing package's own facilities: `t.Cleanup` (runs after subtests, LIFO) over `defer`; `t.TempDir()`; `t.Setenv`/`t.Chdir` (incompatible with `t.Parallel`); `t.Context()` (1.24) over `context.Background()`.** Grep `os.MkdirTemp`, `os.Setenv`, `context.Background()` in `_test.go`. [pkg:testing][GSG-D][gcl usetesting]
- **`t.Parallel()`: when used, both parent and subtests call it consistently; nothing mutates globals or env; `testing.AllocsPerRun` panics under it (1.25); on go < 1.22 the range variable must be copied first.** [pkg:testing][subtests][gcl paralleltest/tparallel][rel1.25]
- **`cmp.Equal`/`cmp.Diff` (with `cmpopts`, `protocmp.Transform()`) over `reflect.DeepEqual`; compare whole structs, not field by field; multiple returns compared individually; never compare serialized output you don't control — parse and compare.** [TC][GSG-D]
- **Errors in tests: `errors.Is`/`cmpopts.EquateErrors`, never `err.Error()` string matching; when only presence matters, `wantErr bool`.** Grep `err.Error() == "` in tests. [TC][GSG-D]
- **No `time.Sleep` as synchronisation; wait on a channel, `WaitGroup`, or a condition with a deadline; inject the clock; on 1.25+ use `synctest.Test` with its fake clock for concurrent code.** Grep `time.Sleep(` in `_test.go`. [100go #86–87][synctest][rel1.25]
- **CI runs `go test -race -shuffle=on`; integration tests are gated by build tags, env vars, or `-short`; `-count=1` where caching would hide flakiness.** Check the Makefile/workflow when a test touches concurrency. [100go #82–84][sec-bp]
- **Benchmarks use `for b.Loop()` (1.24) — no `b.ResetTimer`, results kept alive; older `b.N` loops need `ResetTimer` after setup and a sink for results.** Grep `for i := 0; i < b.N; i++` in new benchmarks on ≥ 1.24. [pkg:testing][rel1.24][100go #89]
- **Fuzz tests are `FuzzXxx(f *testing.F)` with `f.Add` seeds; `testdata/fuzz/` corpus is committed.** [pkg:testing][GSG-BP]
- **Examples are `ExampleF`, `ExampleT_M`, `ExampleF_suffix`, in `_test.go`, with `// Output:` (or `// Unordered output:`) so they run.** Grep `func Example` without an output comment. [examples][pkg:testing][gcl testableexamples]
- **`httptest.NewServer`/`NewRecorder` for HTTP; `iotest` for reader failure paths; mocks implement consumer-side interfaces and are not exported from production packages.** [100go #88][GSG-BP]
- **Tests are hermetic: each case resets any global it touched; `TestMain` only for expensive shared setup that needs teardown (call `flag.Parse()` if flags are read); `sync.Once` setup only when there is no teardown.** [GSG-BP][pkg:testing]
- **Golden files live under `testdata/`, are regenerated by an explicit `-update` flag, and are diffed, not just compared for equality.** (Common practice; the `testdata/` convention is from the go tool; no single primary source fetched.)
- **vet's `tests` analyzer (1.24) catches malformed `Test`/`Benchmark`/`Fuzz`/`Example` signatures; `Test_foo` underscores are allowed in test names.** [rel1.24][GSG-D][Uber]

### Modules and build

- **A `go` line bump is a feature, not a chore: it changes loop-var semantics (1.22), timer semantics (1.23), vet behaviour (1.24) and forces every consumer onto that toolchain.** Grep `^go 1\.` in the diff; require the PR to say why. [mod][loopvar][rel1.23]
- **`toolchain` lines appear from `go get go@…` and are usually churn; commit them deliberately or strip them.** [mod]
- **`replace` in a library's `go.mod` is ignored by consumers and breaks `go install …@latest`; in an application it must be temporary with an issue link; prefer `go.work` for local development.** Grep `^replace `. [mod][gcl gomoddirectives]
- **`go.sum` changes match `go.mod` changes; `go mod tidy` leaves no diff (`go mod tidy -diff` on 1.23+); `// indirect` markers are correct; `go.sum` is committed.** Flag `go.sum` growth with no `require` change and vice versa. [mod]
- **`exclude` and `retract` are for specific bad versions; `retract` belongs in the retracted module's own `go.mod`.** [mod]
- **Vendoring: `vendor/modules.txt` must match `go.mod` or the build fails; never hand-edit `vendor/`.** [mod]
- **Build constraints are `//go:build` (not `// +build`), followed by a blank line, syntactically valid, not duplicated; constrained tests run somewhere in CI.** Grep `// +build` in new files, `//go:build` without a blank line. [vet buildtag][SC SA4019/SA9009][gcl gocheckcompilerdirectives]
- **`internal/` is compiler-enforced privacy; `cmd/<name>/` holds each binary's `main`; server code lives under `internal/`.** [layout]
- **Generated code (`stringer`, mocks, protobuf, `//go:generate`) is committed, gofmt'd, carries the `// Code generated … DO NOT EDIT.` header, and CI proves `go generate` leaves no diff.** Grep `//go:generate` directives whose output is absent. [GSG-G]
- **Semver and paths: incompatible changes need `/v2` in the module path; a `require` on a pseudo-version (`v0.0.0-2024…-abcdef`) means an untagged dependency — ask for a tag.** [mod]
- **`tool` directives (1.24) replace `tools.go` blank-import files; `ignore` (1.25) excludes directories from `./...`.** Grep `tools.go` with `_ "…"` imports in a ≥ 1.24 module. [rel1.24][rel1.25][mod]
- **`godebug` directives and `//go:debug` lines (`asynctimerchan=1`, `httpmuxgo121=1`) revert behaviour and are temporary; each needs a removal plan.** [rel1.23][rel1.22][mod]
- **cgo is a build-system decision: cross-compilation, `CGO_ENABLED`, vet `cgocall`; `#cgo noescape`/`nocallback` (1.24) only with proof.** [vet cgocall][rel1.24]
- **Dependency hygiene: `govulncheck` in CI, `depguard`/`gomodguard_v2` for banned modules, `golang.org/x/exp` replaced by std where possible.** [sec-bp][gcl]
- **The module's `go` line is within the two supported releases.** [release-cycle]

## 4. Severity hints

**Blocking (correctness, safety, or a contract break).**
Data races on shared state; goroutine with no stop/wait; `wg.Add` inside the goroutine; ignored
errors on writes, closes of writers, `Unmarshal`, `Rollback`; `err` used after the value; `defer
x.Close()` before the error check; nil-map write; `nil, nil` returns; typed-nil error returns;
`http.Error` without `return`; SQL/command/template injection; path traversal; `InsecureSkipVerify`;
`math/rand` for secrets; hardcoded credentials; unbounded body/decompression reads; `panic` on
input in library code; `log.Fatal`/`os.Exit` outside `main`; `http.Client` without timeout and
`http.Server` without timeouts in production; response body or `sql.Rows` not closed; `cancel` not
called; loop-variable capture when `go < 1.22`; `select` default busy loop; copying a `sync` type;
`replace` in a library `go.mod`; a `go` line bump without justification; `t.Fatal` from a goroutine;
`time.Sleep` as test synchronisation (flake); exported API signature changes in a `v1` module.

**Should fix (idiom and maintainability; cheap now, expensive later).**
`%w`/`%v` choice and `errors.Is/As` over `==`; error string format; string-matching errors; logging
and returning; interface pollution and producer-side interfaces; mixed receivers; `init()` doing
work; package-level mutable state; context in a struct or `context.Background()` in a request path;
`time.After` in a loop below 1.23; slice aliasing via `append`; sub-slice memory retention; function
atomics instead of typed; `sync.Map` without the use case; missing doc comments on new exported
identifiers; initialism casing and stutter; shadowed `err`/`ctx`; `defer` in a loop; unbuffered
signal channel; `reflect.DeepEqual` in tests; failure messages without function/input/got-want;
helpers without `t.Helper`; `b.N` loops on 1.24+; `go.sum` churn; `// +build`; missing struct tags
on marshaled types; `omitempty` on `time.Time`; `Must*` on user input; `strings.Builder` missing in a
loop; `time.Sleep(1000)`-style durations.

**Nit (consistency; defer to repo convention).**
`any` vs `interface{}` in old code; `_` prefix on unexported globals; `new(T)` vs `&T{}`; import
grouping when no formatter enforces it; `tests`/`tt` vs `test`; got/want wording as long as the
message says which is which; `var s []T` vs `[]T{}` outside APIs; comment punctuation; line length;
`%q` vs `'%s'`; `strconv` vs `fmt` off hot paths; Yoda conditions; `default` case placement;
`for i := range n` rewrites of correct loops; channel buffer of 1 vs 0 when either works.

## 5. Already enforced by tooling

Skip these when the repository's config (section 2) shows the tool runs in CI. Linter names are
golangci-lint's; staticcheck ids where that is the engine.

| Item | Tool |
|---|---|
| Formatting, brace placement, alignment, `gofmt -s` literal simplification | gofmt / gofumpt (`formatters.enable`) |
| Import ordering and grouping, unused imports, `-local` module group | goimports / gci |
| Unchecked errors (`_ =`, bare calls) | errcheck (default) |
| Printf verb/argument mismatch, non-constant format (1.24+), `%w` outside Errorf | govet `printf` (default) |
| Copying `sync.Mutex`/`WaitGroup`, value receivers with locks | govet `copylocks` |
| `cancel` not called on all paths | govet `lostcancel` |
| `errors.As` with non-pointer target | govet `errorsas` |
| `wg.Add` placement (1.25), `Sprintf("%s:%d")` to `net.Dial` (1.25) | govet `waitgroup`, `hostport` |
| `t.Fatal` from a goroutine | govet `testinggoroutine`, SA2002 |
| Struct tag syntax, unkeyed composite literals of other packages' structs | govet `structtag`, `composites` |
| `time.Since` evaluated at `defer`, `append` with no values, `string(int)` | govet `defers`, `appends`, `stringintconv` |
| Symbols newer than the `go` line | govet `stdversion` |
| Loop-variable capture (only on `go < 1.22`) | govet `loopclosure` |
| Unbuffered `signal.Notify` channel | govet `sigchanyzer`, SA1017 |
| `//go:build` syntax | govet `buildtag` |
| Malformed Test/Benchmark/Example signatures (1.24) | govet `tests` |
| Dead stores, unused params/results/identifiers | ineffassign, unused (default), unparam, wastedassign |
| Standard-library misuse (regex, time layouts, `exec.Command`, nil ctx, deprecated APIs, `time.Tick`, `Timer.Reset`) | staticcheck SA1xxx (default) |
| `wg.Add` in goroutine, empty critical section, deferred Lock | staticcheck SA2000/SA2001/SA2003 |
| Useless code, self-assignment, unobserved `append`, impossible comparisons | staticcheck SA4xxx |
| Nil-map write, `defer Close` before err check, spin loops, infinite recursion, bad struct tag, impossible assertion | staticcheck SA5xxx |
| `defer` in range loop, non-octal permission literal, first-const-only typed | staticcheck SA9001/SA9002/SA9004 |
| Code simplifications (`gosimple` merged into staticcheck in v2) | staticcheck S1xxx |
| Package comment, dot import, identifier casing/initialisms, error string format, receiver names, error-last, Duration naming, Yoda, doc starts with name | staticcheck ST1000–ST1023 (`stylecheck` merged into staticcheck in v2; some ST checks off by default) |
| `%v` of an error, `==` on errors, type assertion on errors | errorlint |
| `errors.New`/`Errorf` naming (`Err*`, `*Error`) | errname |
| `return nil` after `err != nil`; `nil, nil`; returning a different nil error | nilerr, nilnil, nilnesserr |
| Unwrapped errors from external packages (opinionated) | wrapcheck |
| HTTP body not closed; `Rows.Err`/`Rows.Close`; `Stmt.Close` | bodyclose, rowserrcheck, sqlclosecheck |
| Requests/queries without a context; non-inherited context; context in struct; context rebuilt in a loop | noctx, contextcheck, containedctx, fatcontext |
| `x := x` loop copies (1.22+); `for i := 0; i < n` → `range n` | copyloopvar, intrange |
| Non-exhaustive enum switches | exhaustive, gochecksumtype |
| Single-value type assertions | forcetypeassert |
| `init()` presence; package-level vars; reassigned package vars | gochecknoinits, gochecknoglobals, reassign |
| Mixed receiver kinds | recvcheck |
| Interfaces with many methods; returning interfaces; interface misuse | interfacebloat, ireturn, iface |
| Missing `t.Helper`; `t.Parallel` presence/consistency; `os.*` vs `t.*` in tests; testify misuse; examples without output; external test package | thelper, paralleltest, tparallel, usetesting, testifylint, testableexamples, testpackage |
| Security patterns (G1xx–G7xx: injection, traversal, TLS, weak crypto, permissions, `math/rand`, pprof, bombs) | gosec |
| `d * time.Second` on a Duration; `make([]T, n)` then `append`; `Sprintf` host:port; canonical header keys | durationcheck, makezero, nosprintfhostport, canonicalheader |
| Preallocation, `fmt.Sprint` → `strconv`, `x/exp` → std, `bytes`/`strings` mirror misuse, modern idioms | prealloc, perfsprint, exptostd, mirror, modernize |
| Magic numbers, repeated string literals, naked returns in long functions, named returns | mnd, goconst, nakedret, nonamedreturns |
| Struct tags present on marshaled structs; proto field access via getters | musttag, protogetter |
| `replace`/`exclude`/`retract` policy; banned modules; import aliases | gomoddirectives, gomodguard_v2, depguard, importas |
| `nolint` hygiene | nolintlint |
| Doc comment practices, comment periods, misspellings | godoclint, godot, misspell |
| `slog`/logger key-value pairs; OpenTelemetry span end/err | sloglint, loggercheck, spancheck |
| Embedded fields at top; function ordering | embeddedstructfieldcheck, funcorder |
| Vulnerable dependencies | govulncheck (not a golangci-lint linter) |
| Data races at runtime | `go test -race` |

Reviewer still owns: goroutine lifetime and bounds, API shape (interfaces, options, exported
surface), `%w` vs `%v` as an API decision, slice aliasing and retention, map iteration order
assumptions, request-context propagation into background work, test design (what is asserted, got
/want clarity, hermeticity, sleeps), `go.mod` policy, security logic the pattern matchers cannot
see (authorization, allow-lists, limits), and everything in a `//nolint` comment.

## 6. Open questions / disagreements

- **Assertion libraries.** Go wiki (TestComments) and Google forbid them: plain `if got != want { t.Errorf(...) }`, `cmp.Diff`, helpers return values or errors rather than taking `*testing.T`. Industry practice (and golangci-lint's `testifylint`) accepts `testify`; its argument order is `assert.Equal(t, expected, actual)` — the opposite of Go's got-then-want message order — and `require` must not be used from goroutines. Follow the repo; if it uses testify, enforce testify's conventions, not the wiki's. [TC][GSG-D][gcl testifylint]
- **`%w` by default or `%v` by default.** Google: `%v` unless callers must inspect, because wrapping exposes the cause as API; Uber and `errorlint`: `%w` by default, `%v` only to hide. Both agree on placement and on `errors.Is/As`. [GSG-BP][Uber][blog-errors]
- **Receiver default.** CodeReviewComments: pointer by default; Google decisions: value for small, non-mutating, map/func/chan/slice types; 100go: pointer when in doubt. All agree: never mix on one type. [CRC][GSG-D][100go #42]
- **`new(T)` vs `&T{}`; `var s T` vs `T{}`.** Google calls it a non-decision (but shows `new(T)` in best practices); Uber prefers `&T{}` and `var user User`. Don't flag either. [GSG-D][GSG-BP][Uber]
- **Unexported globals.** Uber prefixes them `_` (`_defaultPort`); nobody else does; Google says avoid globals instead. Repo convention only. [Uber][GSG-BP]
- **Import groups.** Google: four (std, project, protobuf, side effects); Uber: two; `gci` makes it configurable. If a formatter runs, don't comment. [GSG-D][Uber]
- **Line length.** Google and Effective Go: no limit, refactor instead; Uber: soft 99; `lll`/`golines` configurable. [GSG-G][Uber]
- **Channel buffer sizes.** Uber: "one or none", anything larger scrutinised; 100go: size deliberately (worker pools, rate limiting are legitimate). [Uber][100go #67]
- **Enum zero value.** Uber: start at `iota + 1` so zero is invalid; the standard library often uses a meaningful zero. Decide per type. [Uber]
- **Functional options vs option struct.** Uber documents functional options as the pattern; Google prefers an `Options` struct when most callers set options and functional options only when most need none; 100go recommends functional options. [Uber][GSG-BP][100go #11]
- **Test package.** `testpackage` (and 100go #90) push `package foo_test` for black-box tests; Google's default is the same package, `_test` only for integration tests or import cycles. [GSG-D][gcl testpackage][100go #90]
- **`t.Parallel()` everywhere.** `paralleltest` demands it in every test; Google says nothing beyond hermeticity; tests using `t.Setenv`/`t.Chdir`/`AllocsPerRun` cannot. Treat as repo policy. [pkg:testing][gcl paralleltest]
- **Atomics package.** Uber's guide still says `go.uber.org/atomic`; since Go 1.19 the standard `sync/atomic` typed values (`atomic.Int64`, `atomic.Bool`, `atomic.Pointer[T]`) cover the same ground. Prefer std in new code unless the repo already depends on uber's. [Uber][pkg:atomic]
- **Mocks from exported interfaces.** Google forbids exporting interfaces or doubles for mocking and wrapping RPC clients; mockgen/mockery-driven codebases do exactly that. Flag only if the repo doesn't already. [GSG-BP]
- **`wrapcheck`, `err113`, `nonamedreturns`, `gochecknoglobals`, `mnd`, `varnamelen`.** Opinionated linters many repos disable; don't reintroduce their rules by hand unless the config enables them. [gcl]
- **Named results.** Google: use when they clarify multiple same-typed results or a `cancel func()`; `nonamedreturns` forbids them; 100go: sparingly and beware zero-value side effects. [GSG-D][100go #43–44]
- **Loop variables, `time.After`, GOMAXPROCS.** 100go marks #32/#63 (loop vars) obsolete at 1.22, #76 (`time.After`) at 1.23, #100 (container CPU) at 1.25; a reviewer must read the `go` line before raising any of them. [100go][rel1.22][rel1.23][rel1.25]
- **Golden files, `-update` flags and `testdata/`.** Widespread, but no primary style source fetched here codifies them; treat as repo convention.
- **`interface{}` → `any` rewrites in untouched code.** Google says prefer `any` in new code; mass rewrites are churn. Flag only in changed lines. [GSG-D]
- **Logging errors with `log.Error` vs returning.** Google: never both, and `log.Error` is expensive and must be actionable; Uber: handle once. Agreement on principle; disagreement only on which side (log or return) wins at a given layer. [GSG-BP][Uber]
