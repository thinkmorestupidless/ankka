---
name: pr-review-typescript
description: The TypeScript rules a code review checks after the general pr-review checklist. It covers promises and async (floating and misused promises, AbortSignal), error handling and the Node runtime (Error subclasses, 'error' listeners, process.exit, sync I/O), ESM and CJS module rules (import extensions, type-only imports, exports maps, type stripping), the type system and nullability (any, assertions, unions, enums, indexed access), security at trust boundaries, and vitest tests. Use whenever a pull request, diff, branch or file under review contains TypeScript (.ts, .tsx, .mts, .cts, .d.ts, plus .js, .mjs, .cjs, package.json, tsconfig.json), whether or not pr-review loaded it, and whenever someone asks for feedback on, a check of, or a second opinion about TypeScript code. Not for writing or fixing TypeScript.
---

# Reviewing TypeScript

TypeScript reviews most often miss what the type checker cannot see: a cast that lies at a trust
boundary, a promise nobody awaits, an emitter with no `'error'` listener, an import that compiles
under `tsc` and throws under `node`. Apply the groups in this order: promises, errors, security,
the Node runtime, modules, then types and nullability, packaging and the public API, tests, idioms.

## Before reading the code

Read the build files first; they decide which half of this checklist applies. A `tsconfig.json`
may `extends` an array, and later entries win.

| Signal | Means | Changes for the review |
|---|---|---|
| `.mts` / `.cts` | always ESM / always CJS | ESM rules apply to `.mts` regardless of `"type"` |
| `"type": "module"` in `package.json` | `.ts` and `.js` are ESM | no `require`, `__dirname`; extensions on relative imports |
| `module: nodenext`, `node18`, `node20` | Node resolution | extensions required, `exports` conditions honoured |
| `module: esnext` + `moduleResolution: bundler`, usually `noEmit` | a bundler consumes the output | extensionless imports and `paths` are fine |
| `erasableSyntaxOnly`, `allowImportingTsExtensions`, `rewriteRelativeImportExtensions`, or scripts running `node file.ts` | Node type stripping | `enum`, parameter properties, runtime `namespace`, decorators, `.tsx`, unmarked type imports and extensionless imports fail at runtime; Node ignores tsconfig (`paths`) |
| `verbatimModuleSyntax` or `isolatedModules` | type-only imports are explicit | skip the type-import item |
| `declaration`, `exports`, `files`, `peerDependencies`, no `private: true` | a library | the public API group applies; explicit export types |
| `private: true`, a `start` script, a Dockerfile | an application | the public API group does not apply |
| `lib` with `dom`, `.tsx`, `jsx`, `@testing-library/*` | browser or React | RTL test items (in the reference) apply; no Node globals in shared code |
| `strict` off, or members disabled (`strictNullChecks`, `noImplicitAny`, `useUnknownInCatchVariables`) | weak checking | half the nullability group is moot; `no-unnecessary-condition` cannot run |
| `noUncheckedIndexedAccess` absent | lookups typed as present | review every indexed access by hand |
| `skipLibCheck: true` | hand-written `.d.ts` unchecked | review declaration files harder |
| `engines.node`, `.nvmrc`, `.node-version` | the Node version | version-gated APIs (`import.meta.dirname` 20.11+, `require(esm)` 22.12+, iterator helpers and Set methods 22+, `using` and `import defer` need runtime support) |
| a repo created since TS 5.9 with none of `tsc --init`'s defaults (`strict`, `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`, `verbatimModuleSyntax`) | opted out deliberately | ask before asking for them back |

Then read the tooling: the typescript-eslint preset in `eslint.config.*` or `.eslintrc*`
(`recommended`, `strict`, `stylistic`, each with a `-type-checked` variant), whether
`parserOptions.projectService` or `project` is set (type-aware rules run), the plugins on
(`import-x`, `n`, `promise`, `unicorn`, `security`, `vitest`/`jest`), rules set `'off'`, any
`eslint-disable` in the diff; `biome.json`; `.prettierrc`; `package.json` scripts for the runner
(`vitest`, `jest`, `node --test`), the loader (`tsx`, `ts-node`, `node file.ts`), the bundler
(`tsup`, `tsdown`, `esbuild`) and whether `tsc --noEmit`, `publint` or `attw` run in CI at all.

### Already enforced: do not comment

| If the repository runs | then skip |
|---|---|
| Prettier or Biome's formatter | anything about whitespace, quotes, semicolons, line length, braces, trailing commas, blank lines after imports |
| `tsc --strict` | implicit `any`, null and undefined flow, `catch (e)` as `unknown`, uninitialised class fields, `NaN` equality, always-truthy expressions (5.6), never-assigned variables (5.7), regex literal syntax (5.5) |
| `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`, `noImplicitOverride`, `noFallthroughCasesInSwitch`, `noImplicitReturns`, `noUnused*` | index access optionals, `undefined` on `?:`, missing `override`, case fall-through, missing returns, unused locals and parameters |
| `verbatimModuleSyntax` or `isolatedModules` | type-only import and export marking |
| `erasableSyntaxOnly` | `enum`, runtime `namespace`, parameter properties, `import =` / `export =` |
| `moduleResolution: nodenext` | extension-required relative imports, `exports` resolution |
| `isolatedDeclarations` | explicit types on exports |
| `noUncheckedSideEffectImports` | unresolvable side-effect imports |
| typescript-eslint `recommended` / `recommended-type-checked` | `no-explicit-any`, `no-unsafe-*`, `no-floating-promises`, `no-misused-promises`, `await-thenable`, `require-await`, `no-base-to-string`, `restrict-template-expressions` (loose), `only-throw-error`, `prefer-promise-reject-errors`, `unbound-method`, `ban-ts-comment`, `no-namespace`, `no-require-imports`, `no-this-alias`, `no-implied-eval`, `no-for-in-array`, `no-array-constructor`, `no-array-delete`, `no-wrapper-object-types`, `no-empty-object-type`, `no-unnecessary-type-assertion`, `no-unsafe-enum-comparison`, `no-unsafe-declaration-merging`, `triple-slash-reference` |
| typescript-eslint `strict` / `strict-type-checked` | the above plus `no-non-null-assertion`, `no-unnecessary-condition`, `no-unnecessary-type-parameters`, `no-confusing-void-expression`, `no-deprecated`, `no-dynamic-delete`, `no-extraneous-class`, `no-mixed-enums`, `no-misused-spread`, `return-await` (error-handling cases), `unified-signatures`, `use-unknown-in-catch-callback-variable`, `restrict-template-expressions` (strict) |
| typescript-eslint `stylistic` / `stylistic-type-checked` | `array-type`, `consistent-type-definitions`, `consistent-type-assertions`, `prefer-for-of`, `prefer-nullish-coalescing`, `prefer-optional-chain`, `prefer-includes`, `no-inferrable-types`, `dot-notation`, `adjacent-overload-signatures`, `class-literal-property-style` |
| ESLint core `recommended` | `no-async-promise-executor`, `no-constant-binary-expression`, `no-debugger`, `no-empty` (a comment satisfies it), `no-fallthrough`, `no-unused-vars`, `no-useless-catch`, `no-unsafe-finally`; not `eqeqeq`, `prefer-const`, `no-var`, `no-await-in-loop`, `require-atomic-updates`, `no-eval`, `default-case` |
| `import-x/recommended` | `no-unresolved`, `named`, `no-duplicates`; not `no-cycle`, `no-extraneous-dependencies`, `no-default-export`, `extensions`, `no-mutable-exports` |
| `n/recommended` | `no-missing-import`, `no-extraneous-import`, `no-unpublished-import`, `no-unsupported-features/*`, `no-deprecated-api`, `no-process-exit`, `hashbang`; not `no-sync`, `prefer-node-protocol`, `no-path-concat`, `no-top-level-await` |
| `promise/recommended` | `catch-or-return`, `always-return`, `no-nesting`, `no-return-in-finally`, `avoid-new`; not `prefer-await-to-then`, `no-multiple-resolved` |
| `unicorn/recommended` | `no-for-each`, `prefer-node-protocol`, `prefer-top-level-await`, `no-process-exit`, `error-message`, `throw-new-error`, `catch-error-name`, `prefer-module`, `no-new-array`, `prefer-native-coercion-functions`, `no-unnecessary-await`, `no-abusive-eslint-disable`; not `custom-error-definition` |
| `security/recommended` | nothing: its `detect-*` hits need human triage, so treat them as prompts, not verdicts |
| Biome `recommended` | its correctness, suspicious, style, complexity and security groups; `noFloatingPromises` and `noImportCycles` only with the Scanner on; it has no type-aware `no-unsafe-*`, so `any` leakage still needs a reviewer |
| `publint`, `attw`, `tsc --noEmit` in CI | `exports` ordering, format mismatch, `files`, `type`, `engines`, `sideEffects`, declaration resolution |

In no preset, so review by hand unless the config enables them: `switch-exhaustiveness-check`,
`consistent-type-imports`, `explicit-module-boundary-types`, `prefer-readonly`,
`method-signature-style`, `naming-convention`, `no-unsafe-type-assertion`, `promise-function-async`.

## Checklist

### Promises and async

- **No floating promises.** Signal: a promise-returning call as a statement, `.then(cb)` with no
  rejection handler, `[...].map(async ...)` unawaited, `void p` with no comment and no `.catch`.
  Why: an unhandled rejection crashes Node by default. [`no-floating-promises`; Node process]
- **No promise where a value or void callback is expected.** Signal: `if (isValid())` with an async
  `isValid`, `arr.forEach(async ...)`, `emitter.on('x', async ...)`, `app.get('/', async ...)` on a
  framework that ignores returned promises. Why: truthy, and its rejection is lost.
  [`no-misused-promises`]
- **`return await` inside `try`, `catch`, `finally`, `using`.** Signal: `try { return p() } catch`.
  Why: without `await` the rejection skips the `catch` and `finally` runs before the work ends;
  outside those contexts either style is fine. [`return-await`; Node BP 2.12]
- **Read-modify-write across an `await` is a race.** Signal: `this.total += await f()`;
  `if (!cache[k]) cache[k] = await load()` with no re-check. Why: the state may have changed
  during the await. [`require-atomic-updates`]
- **No async executor; no `new Promise` around promise-shaped APIs.** Signal:
  `new Promise(async (res, rej) => ...)`, `new Promise(r => setTimeout(r, ms))`. Why: errors in an
  async executor are lost; `timers/promises`, `events.once` and `util.promisify` already exist.
  [`no-async-promise-executor`; Node timers]
- **Concurrency on purpose.** Signal: `await` in a loop over independent items; `Promise.all` over
  hundreds of calls with no bound. Why: sequential awaits leave later rejections unhandled once one
  throws; unbounded parallelism exhausts sockets. Ask which case it is first. [`no-await-in-loop`]
- **Cancellation and timeouts via `AbortSignal`.** Signal: `fetch(url)` with no `signal`, a
  hand-rolled `setTimeout` race, `addEventListener('abort', ...)` on a shared signal without
  `{ once: true }`. Why: `AbortSignal.timeout`/`any` and the `signal` option exist; listeners leak.
  [Node globals]
- **Top-level await is a hazard in libraries.** Signal: top-level `await` in a module CJS consumers
  may `require()`. Why: `ERR_REQUIRE_ASYNC_MODULE`; a never-settling one exits with code 13.
  [Node modules; Node esm]

### Error handling

- **Throw and reject with `new Error` or a subclass, nothing else.** Signal: `throw 'string'`,
  `throw { code }`, `reject('msg')`, `Error('x')` without `new`, `new Error()` with no message.
  Why: no stack, and `instanceof` handling fails. [Google TS; `only-throw-error`]
- **Subclass `Error` properly; carry `cause` and a stable `code`.** Signal: a custom error class not
  setting `this.name`; the inner error stringified into the message instead of `{ cause }`; callers
  comparing `error.message` text. Why: messages change, codes do not. [Node errors; Node BP 2.2]
- **Catch narrowly; never swallow.** Signal: `catch {}` with no comment; `catch (e) { log(e) }`
  then continue as if it succeeded; every error mapped to a generic 500 dropping the original.
  [Google TS; `no-empty`]
- **`uncaughtException` and `unhandledRejection` handlers exit, never resume.** Signal:
  `process.on('uncaughtException', log)` with no exit; a handler swallowing rejections to keep the
  server up. Why: the process is in an undefined state; set `process.exitCode`, let it die.
  [Node process; Node BP 2.6]
- **`process.exit()` loses output and pending work.** Signal: `process.exit(1)` after a logger call
  in library or server code. Why: stdout is truncated; set `process.exitCode` or throw. The end of
  a CLI's `main` is the exception. [Node process; `n/no-process-exit`]
- **EventEmitters and streams need an `'error'` listener.** Signal: `new EventEmitter()`,
  `createReadStream`, `net.connect`, `spawn` with no `on('error')`; async listeners without
  `captureRejections`. Why: an unlistened `'error'` throws where `try/catch` cannot see it.
  [Node errors; Node events]
- **Error details stay out of responses.** Signal: `res.json({ error: err })`; `err.stack` or
  `err.message` of an unknown error sent to a client. [Node BP 6.20; OWASP Node]

### Security

- **Validate every external input against a schema.** Signal: `req.body`, `req.query`, `req.params`,
  message payloads, `process.env` used without a parser; `req.body as Foo`, `JSON.parse(...)` or
  `await res.json()` typed by cast. Why: these return `any`, and the cast adds no runtime check;
  `safeParse` and `z.infer` give both. [OWASP Node; Node BP 2.11, 6.10; Effective TS 46, 74]
- **Command injection.** Signal: `` exec(`cmd ${input}`) ``; `spawn(cmd, { shell: true })` with
  input. Why: use `execFile` or `spawn` with an args array. [Node child_process; Node BP 6.19]
- **No dynamic code.** Signal: `eval`, `new Function(str)`, string timers, `vm.runInContext` as a
  sandbox, `import(userInput)`, `require(variable)`. [Node BP 6.15, 6.17; `no-implied-eval`]
- **Path traversal.** Signal: `path.join(root, userPath)` then `fs.*` with no check that
  `path.resolve` stays under `root + sep`. [OWASP Node; `security/detect-non-literal-fs-filename`]
- **Prototype pollution.** Signal: a recursive merge or `set(obj, path, v)` over untrusted JSON;
  `Object.assign(target, req.body)`; `obj[userKey] = v` on a plain object. Why: `__proto__` keys
  reach every object; use `Map`, `Object.create(null)`, `Object.hasOwn`, key allow-lists. [OWASP
  prototype pollution; Node security BP]
- **ReDoS.** Signal: nested quantifiers `(a+)+`, overlapping alternation, user input in
  `new RegExp`, regex validation of long untrusted strings on the request path. Why: TS 5.5 checks
  regex syntax, not complexity. [Node BP 6.16; `security/detect-unsafe-regex`]
- **Timing-safe comparison and real randomness.** Signal: `token === expected` for secrets or
  HMACs; `Math.random()` for tokens or IDs. Why: `crypto.timingSafeEqual`, `randomUUID`,
  `randomBytes`, `scrypt`/`bcrypt` for passwords. [Node security BP; Node BP 6.8]
- **Query, header and redirect injection.** Signal: SQL built by template string; Mongo filters
  from raw objects; `res.setHeader(name, userValue)`; `res.redirect(req.query.next)` with no
  allow-list. [Node BP 6.4, 6.24; OWASP Node]
- **Secrets stay out of source, logs and the tarball.** Signal: tokens in code, fixtures, logs,
  error messages or URLs; `.env` committed; no `files` allow-list. [Node security BP; Node BP 6.3]

### Node runtime

- **No sync I/O or CPU-heavy work on the request path.** Signal: `readFileSync`, `execSync`,
  `pbkdf2Sync`, `zlib.*Sync`, `JSON.parse` of a large body inside a handler. Why: the event loop is
  shared, so one slow request stalls all; sync at startup is fine. [OWASP Node; Node BP 5.3, 7.1]
- **Streams: `pipeline`, not `.pipe()`; honour backpressure.** Signal: `a.pipe(b).pipe(c)` with no
  error wiring; a loop calling `writable.write()` ignoring its boolean; `on('data')` mixed with
  `pipe`. Why: `.pipe()` forwards no errors; unchecked writes buffer until death. [Node stream]
- **Listener hygiene.** Signal: `emitter.on(...)` inside a per-request handler with no
  `removeListener`, `once` or `signal`; `setMaxListeners(0)` to hide `MaxListenersExceededWarning`.
  [Node events; Node globals]
- **Graceful shutdown.** Signal: a server with no `SIGTERM`/`SIGINT` handler, or one calling
  `process.exit()` instead of closing the server and draining. Why: Kubernetes sends SIGTERM and
  in-flight requests are dropped otherwise. [Node process; Node BP 2.6]
- **Structured logs; configuration parsed once.** Signal: `console.log` in server code; whole
  request or error objects logged; `process.env.X!` scattered through modules. Why: levels and a
  request id to stdout; env parsed at startup into a typed, validated object. [Node BP 2.7, 5.15]
- **Type stripping constraints (`node file.ts` repos).** Signal: `enum`, parameter properties,
  runtime `namespace`, decorators, `.tsx`, extensionless relative imports, `paths`, an unmarked
  type import. Why: all fail at runtime; `erasableSyntaxOnly` plus `verbatimModuleSyntax` turn them
  into compile errors. [Node typescript; TS 5.8]

### Modules, imports and ESM/CJS

- **Relative imports carry an extension under Node resolution.** Signal: `import './util'` with
  `module: nodenext` and ESM; `.js` (compiled) and `.ts` (stripped) mixed in one package. Why:
  Node resolves no extensions; the one used must match how the code runs. [Node esm;
  `import-x/extensions`]
- **Type-only imports are marked (no `verbatimModuleSyntax`).** Signal: `import { Foo }` where
  `Foo` is an interface; `export { Type }` re-exports. Why: a runtime `SyntaxError` under type
  stripping, and it defeats single-file transpilation. [Node typescript; `consistent-type-imports`]
- **No `require`, `module.exports`, `__dirname`, `__filename` in ESM.** Signal: any of them in a
  `"type": "module"` package or `.mts`. Why: `import.meta.dirname`/`filename`,
  `new URL('./x', import.meta.url)` and `createRequire` cover every case. [Node esm]
- **`node:` prefix for builtins.** Signal: `import fs from 'fs'`. Why: removes typosquat ambiguity
  and is required for some newer builtins. [Node BP 6.27; `unicorn/prefer-node-protocol`]
- **Named exports; no default, no mutable, no barrel of everything.** Signal: `export default`,
  `export let`, a new `index.ts` re-exporting a directory with no `sideEffects` declared. Why:
  defaults have no canonical name; barrels pull every module in unless the consumer tree-shakes;
  a framework that requires a default is the exception. [Google TS; webpack tree-shaking]
- **No import cycles, self-imports or duplicates.** Signal: `a.ts` imports `b.ts` which imports
  `a.ts`. Why: CJS cycles see half-initialised exports; ESM cycles hit TDZ errors. [Node modules;
  `import-x/no-cycle`]
- **CJS interop assumptions.** Signal: `import { named } from 'cjs-package'` relying on best-effort
  detection; `require('esm-with-tla')`; a default expected at the top level rather than `.default`.
  Why: `require(esm)` needs a synchronous graph (Node 22.12+). [Node esm; Node modules; TS 5.8]

### Type system and `any`

- **No new `any`, explicit or implicit.** Signal: `: any`, `as any`, `any[]`, `Record<string, any>`,
  `Function`, untyped parameters when `noImplicitAny` is off. Why: it disables checking for
  everything it touches; `unknown` plus a guard instead. [Google TS; Effective TS 5, 43–46]
- **Type assertions need a reason; double assertions go through `unknown`.** Signal: `as Foo`
  (not `as const`), `as unknown as`, `<Foo>x`. Why: no runtime check; prefer `instanceof`, `in`, a
  predicate, or hide the unavoidable one in a small typed function with a comment. [Google TS;
  Effective TS 9, 45]
- **Annotation or `satisfies`, not `as`, on object literals.** Signal: `const cfg = {...} as Cfg`.
  Why: `as` suppresses excess-property checks, so a stale literal compiles when `Cfg` gains a
  field; `satisfies` keeps the narrow type for config maps. [Google TS; TS 4.9; Effective TS 11]
- **Discriminated unions and exhaustiveness via `never`.** Signal: `error?: E; data?: T`; a
  `kind: string`; a `switch` over a union with no `default` and no `assertNever`, or a `default`
  that silently returns. Why: adding a member should fail compilation somewhere; the lint rule
  does not accept a bare `default`. [TS narrowing; Effective TS 29, 34, 59; `switch-exhaustiveness`]
- **Enums: `as const` objects and literal unions; never `const enum`, never numeric.** Signal: a
  new `enum`, `const enum`, numeric or mixed enum. Why: numeric enums accept any number, `const
  enum` breaks under `isolatedModules`, every `enum` fails under type stripping; a string enum with
  explicit values if one must exist. [TS enums; Total TS enums; TS 5.8]
- **Property syntax for function-typed members.** Signal: `method(x: Dog): void` in an interface
  used as a callback contract. Why: method shorthand is bivariant even under `strictFunctionTypes`;
  `method: (x: Dog) => void` is checked contravariantly. [Total TS method shorthand]
- **`Omit` and `Pick` on unions collapse them.** Signal: `Omit<SomeUnion, 'id'>`. Why: `keyof` of a
  union is the common keys only, and `K` is unconstrained so typos pass; use a distributive helper.
  [TS utility types; Total TS distributive omit]
- **`Record<string, T>` and arrays mean "may be absent".** Signal: `dict[key].foo` unchecked;
  `Object.keys(obj) as (keyof typeof obj)[]`. Why: without `noUncheckedIndexedAccess` TS pretends
  every lookup succeeds; prefer `Map`, `Record<KnownKeys, T>`, `Object.entries`. [Effective TS 16,
  17, 60, 61]

### Nullability and optionals

- **Optional `?:` over `| undefined`; no `| null` inside a type alias.** Signal: `type Id = string
  | null` used everywhere; `field: T | undefined`. Why: nullability belongs at the use site; under
  `exactOptionalPropertyTypes`, `{ x: undefined }` no longer compiles for `x?: T`. [Google TS;
  Effective TS 32, 33, 37]
- **Truthiness is not a null check.** Signal: `if (value)` or `value || default` where `value` may
  be `0`, `''`, `false` or `NaN`. Why: `!= null`, `??` and `?.` say what is meant. [TS narrowing;
  Google TS; `prefer-nullish-coalescing`]
- **Non-null assertions are assertions.** Signal: `x!.y`, `map.get(k)!`, `arr[0]!`. Why: each `!`
  is an unchecked promise; a guard or `?? throwIfMissing()` instead, and a comment on any that
  stay. [Google TS; `no-non-null-assertion`]
- **Lookups return optionals.** Signal: `items[0].id`, `env[name].trim()`, `Object.groupBy`,
  `Map.get` and `find` results used unchecked in a repo without `noUncheckedIndexedAccess`. Why:
  the compiler does not warn; the runtime does. [tsconfig; TS 5.4]
- **Filter with a real predicate, not `Boolean`.** Signal: `.filter(Boolean)` expecting `T[]`;
  `filter(x => !!x)` on numbers. Why: TS 5.5 infers predicates only for iff checks like
  `x !== undefined`, so `0` and `''` vanish silently. [TS 5.5]
- **`catch (e)` is `unknown`.** Signal: `catch (e) { e.message }`, `catch (e: any)`,
  `.catch(e => e.code)`. Why: narrow with `instanceof Error` or a helper. [tsconfig;
  `use-unknown-in-catch-callback-variable`]

### Build, packaging and dependencies

- **`exports` is correct and complete.** Signal: `exports` added without every previously public
  subpath; `types` not first or `default` not last in a condition; the root `types` field relied on
  when `exports` exists (it is ignored). [Node packages; publint `EXPORTS_*`; TS 4.7]
- **One `.d.ts` per entrypoint and format.** Signal: one `index.d.ts` shared by `import` and
  `require` conditions; a `tsc` run emitting CJS and ESM from the same files. Why: attw reports
  `FalseCJS`/`FalseESM`; run `publint` and `attw --pack` in CI. [attw; choosing compiler options]
- **`files` allow-list; `rootDir` and `outDir`.** Signal: no `files`, or `.npmignore` only; tests,
  `.env` or `src/` in the tarball so consumers load `.ts` over `.d.ts`. Why: `npm pack --dry-run`
  shows the truth. [npm package.json; publint `USE_FILES`]
- **Dependency sections.** Signal: `typescript`, `@types/*`, test or build tools in
  `dependencies`; a runtime import from a `devDependency` or a transitive dependency; an `@types/*`
  the `.d.ts` exposes left in `devDependencies`; a framework a plugin extends not in
  `peerDependencies`. [Effective TS 65, 66; TS publishing; `import-x/no-extraneous-dependencies`]
- **Lockfile and install.** Signal: `package.json` changed without the lockfile; `npm install` in
  CI; `--legacy-peer-deps` added to silence a conflict. Why: `npm ci` fails on mismatch and never
  writes. [npm ci; Node BP 5.4]
- **`engines.node`, `.nvmrc`, CI and the Dockerfile agree; `target` fits.** Signal: a Node 22 API
  with `engines` at `>=18`; `target: es5` in a Node 22 app; `lib: ["dom"]` in a server package.
  [Node BP 4.4, 5.17; publint `USE_ENGINES_NODE`; choosing compiler options]
- **Lifecycle scripts, provenance, bundler choice.** Signal: `postinstall` added; `prepare` running
  a full build on install; a release workflow without `--provenance`/`id-token: write`; tsup chosen
  (unmaintained, points at tsdown) with no why. [npm package.json; npm provenance; tsup README]

### Public API and types (libraries only)

- **Explicit types on every export.** Signal: `export function f(x) { return ... }` with an
  inferred return; `export const x = compute()`. Why: stabilises the `.d.ts` and stops an internal
  change silently changing the public type. [TS 5.5; `explicit-module-boundary-types`]
- **No `any` or unexported types in the public surface.** Signal: a public parameter or return
  typed `any`, or referencing a type consumers cannot name. [Effective TS 67; Do's and Don'ts]
- **Accept `readonly`, return owned values.** Signal: `(items: T[])` on a non-mutating function;
  an internal array returned by reference; constructor-only fields without `readonly`. [Google TS;
  Effective TS 14, 30]
- **No default exports, `namespace`, `export =`, runtime `enum` in a published API.** Signal: any
  of them. Why: `export =` is not erasable and ties consumers to CJS. [Google TS; TS enums; TS 5.8]
- **Augmentations scoped and documented.** Signal: `declare global`, `declare module 'express'`.
  Why: fine for a framework's request type; a hazard leaking from a library into every consumer.
  [Effective TS 71; `no-unsafe-declaration-merging`]
- **Deprecate before removing.** Signal: a public symbol removed or re-typed in a minor release
  with no `@deprecated` TSDoc on the old one. [Effective TS 68; `no-deprecated`]

### Tests

- **Async assertions are awaited.** Signal: `expect(p).rejects.toThrow()` or `.resolves` without
  `await`; `toThrow` on a call rather than a function. Why: vitest fails the test late and other
  runners pass vacuously. [vitest expect]
- **No `.only`, `.skip`, `.todo` left behind.** Signal: `test.only`, `describe.skip`,
  `allowOnly: true`. Why: vitest fails `.only` in CI by default; Node's runner needs `--test-only`.
  [vitest allowOnly; Node test]
- **Mock at the seam you own; prefer injection to `vi.mock`.** Signal: `vi.mock` of a module to
  stub one function a parameter could take; mocking the unit's own internals (internal calls bypass
  it); a factory using outer variables without `vi.hoisted`. [vitest mocking; Node BP 4.10]
- **Mocks and stubs reset between tests.** Signal: no `restoreMocks`/`mockReset` config and no
  `afterEach(vi.restoreAllMocks)`; `vi.setSystemTime`, `vi.stubEnv`, `vi.stubGlobal` with no
  unstub. Why: `mockClear` keeps implementations, `mockReset` drops them. [vitest mock]
- **Fake timers, not real waits.** Signal: `await new Promise(r => setTimeout(r, 500))`;
  `useFakeTimers` without `useRealTimers` in `afterEach`; `advanceTimersByTime` where
  `advanceTimersByTimeAsync` is needed; relying on `nextTick` being faked (it is not). [vitest vi]
- **Flaky waits and isolation.** Signal: arbitrary sleeps; `waitFor` with side effects inside; a
  fixed port (use `0`); module-level mutable state shared across tests; `isolate: false` added for
  speed. [vitest expect; Node BP 4.5, 4.12]
- **Snapshots and coverage are deliberate.** Signal: large `toMatchSnapshot` on whole objects;
  snapshots updated wholesale; `coverage.thresholds` lowered or an `exclude` added for the new
  code. [vitest features; vitest coverage]
- **No `any` or `!` to make a test compile; type tests actually run.** Signal: `as any` on fixtures;
  `@ts-expect-error` on a line that errors for an unrelated reason; `expectTypeOf` in `*.test-d.ts`
  without `--typecheck`. [Google TS; vitest testing-types]

### Idioms and language features

- **`const` by default, never `var`; `===` always, `== null` the one exception.** Signal: `let`
  never reassigned; `==` anywhere else. [Google TS; `prefer-const`; `no-var`; `eqeqeq`]
- **Coercion is explicit.** Signal: `+str`, `parseInt(str)` for base 10, `'' + n`, `!!x` on an enum.
  Why: `Number(str)` with `Number.isNaN`. [Google TS; `unicorn/prefer-number-properties`]
- **`for..of` over indexed `for` and `forEach`; `for..in` only via `Object.keys`/`entries`.**
  Signal: `for (k in arr)`; `arr.forEach(async ...)`. Why: `forEach` swallows returned promises
  and cannot `break`. [Google TS; `no-for-in-array`; `unicorn/no-for-each`]
- **`switch` ends with `default`, last, and no fall-through.** Signal: a non-empty case without
  `break`, `return` or `throw`; `default` in the middle. [Google TS; `default-case`]
- **Unbound methods lose `this`.** Signal: `const fn = obj.method`, `arr.map(obj.method)`,
  `on('x', this.handle)`. Why: pass an arrow or `bind`, or declare `this: void`. [`unbound-method`]
- **Classes: `readonly` fields, pure getters, `override`, no static containers.** Signal: a class
  of only statics; a getter with side effects; an unmarked override; parameter properties in a
  type-stripping repo. [Google TS; `noImplicitOverride`; `no-extraneous-class`]

## Severity

**Blocking.** No floating promises; no promise where a value or void callback is expected;
`return await` inside `try`, `catch`, `finally`, `using`; no async executor; read-modify-write
across an `await`; EventEmitters and streams need an `'error'` listener; streams: `pipeline`, not
`.pipe()`; `uncaughtException` handlers exit, never resume; `process.exit()` in library or server
code; command injection; no dynamic code; path traversal; prototype pollution; query, header and
redirect injection; secrets in source or logs; validate every external input against a schema; a
type assertion or `!` hiding a real `undefined`; exhaustiveness when union members were added in
the same PR; type stripping constraints; relative imports carry an extension under Node resolution;
`exports` is correct and complete; one `.d.ts` per entrypoint and format; a runtime import of a
`devDependency`; lockfile out of sync; `.only` left behind; async assertions not awaited; coverage
thresholds lowered.

**Should fix.** No new `any`; type assertions need a reason; `Omit` on a union; `Record<string, T>`
indexing unchecked; truthiness is not a null check; numeric or `const` enums in new code;
property syntax for function-typed members; concurrency on purpose; cancellation via `AbortSignal`;
`new Promise` around promise-shaped APIs; throws and rejections that are not `Error`s; catch
narrowly; listener hygiene; sync I/O on the request path; structured logs and configuration parsed
once; named exports; no import cycles; `node:` prefix; explicit types on every export; accept
`readonly`; mock at the seam; mocks reset; fake timers; snapshots deliberate; `engines.node`
agrees; lifecycle scripts (`postinstall`).

**Nit.** `interface` vs `type` consistency; `T[]` vs `Array<T>`; `for..of` over `forEach`; `??`
and `?.` over `||` and `&&` chains; `at()`; `const` by default; coercion; `readonly` on private
fields; braces on one-line `if`s; declaration vs arrow for named functions; naming conventions;
`satisfies` over a widening annotation; import ordering; test names; `toStrictEqual` vs `toEqual`.

## Where the sources disagree

- `interface` or `type`: repo convention; else what the diff uses, because only mixing is a finding.
- Plain `enum` or `as const`: repo convention; else `as const`, because numeric enums are unsound.
- Default or named exports: repo convention; else named, unless the framework demands a default.
- Throw or result type: repo convention per boundary; else thrown `Error` subclasses; flag mixing.
- `return await` outside error handling: repo convention; else flag neither, both camps have a case.
- `noUncheckedIndexedAccess` off: review indexed access by hand; turning it on is a project choice.
- `#private` or `private`: repo convention; else `#private` under type stripping, else `private`.
- Explicit return types: repo convention; else exports only, because on everything it is noise.
- `no-await-in-loop`: ask which case first, because rate limits and dependent steps justify a loop.
- `prefer-readonly-parameter-types`: flag only obvious pure functions, because the rule is noisy.
- Dual CJS/ESM or ESM-only: a design question, not a defect; insist on attw either way.
- `vi.mock` or injection: both defensible; flag module mocks of the unit's own neighbours only.
- `security/detect-*` hits: questions, not findings, because the plugin says they need triage.
- `using` and decorators: check `engines.node` and the loader first, because support lags.
- tsup, tsdown or `tsc`: a bundler choice in a new library PR needs a why; tsup is unmaintained.

## Reference

Open `references/typescript-review-research.md` for an item's full signal list and lint rules, the
source behind a finding, the complete rule-to-preset table, the items left out of this file
(generics and overloads, suppression comments, branded types, spread, JSON import attributes,
side-effect imports, `paths` aliases, body limits and timeouts, timers, memory, callback types,
JSON shapes, equality matchers, test naming, RTL) and the arguments behind each disagreement.
Sections: 1 Sources; 2 Detecting the language, runtime and configuration; 3 Checklist (3.1 Type
system and `any`, 3.2 Nullability, 3.3 Idioms, 3.4 Promises and async, 3.5 Error handling, 3.6
Modules and ESM/CJS, 3.7 Node runtime, 3.8 Security, 3.9 API design, 3.10 Tests, 3.11 Build and
packaging); 4 Severity hints; 5 Already enforced by tooling; 6 Open questions and disagreements.
