# TypeScript review rules (TS 5.x strict, Node 22/24 ESM first)

## Contents

1. Sources
2. Detecting the language, runtime and configuration
3. Checklist
   - 3.1 Type system and `any`
   - 3.2 Nullability and optionals
   - 3.3 Idioms and language features
   - 3.4 Promises and async
   - 3.5 Error handling
   - 3.6 Modules, imports and ESM/CJS
   - 3.7 Node runtime
   - 3.8 Security
   - 3.9 API design and public types (libraries)
   - 3.10 Tests
   - 3.11 Build, packaging and dependencies
4. Severity hints
5. Already enforced by tooling
6. Open questions and disagreements

Research notes for a language-specific checklist an AI reviewer applies to a pull request after
the general, language-agnostic checks. Compiled 2026-10-23 from the primary sources listed below.
Scope: TypeScript 5.x in strict mode; Node 22/24 server code first; browser/React only where the
rule is general; ESM. Where a rule exists in typescript-eslint, ESLint core or a plugin, its name
is given so the reviewer can skip what the repo's tooling already enforces (section 5).

## 1. Sources

Every URL below was fetched and read for this document. Nothing is listed that was not read.

- https://google.github.io/styleguide/tsguide.html — Google TS style guide: visibility, constructors, `#private` banned in favour of `private`, `readonly`, getters pure, wrapper types, `Array()` constructor, coercion (`Number()` + `NaN` check, no unary `+`, no `parseInt` for base 10), `===` with the `== null` exception, `const`/`let`, `new Error()` and `Error` subclasses only, empty catch needs a comment, `for..of` over `for..in`, spread rules, braces, `switch` must have a `default` last and no fall-through, function declarations over arrows for named functions, `this` rules, no new decorators; type system: inference, `|null`/`|undefined` not in aliases, optionals over `|undefined`, interfaces over type aliases for objects, `T[]` for simple types, `Record`, mapped/conditional restraint, `any` → `unknown`, tuples, assertions need comments and `as` syntax, `: Foo` over `as Foo` on literals, no `const enum`, no builtin modification, `eval`/`Function` banned, `debugger` banned, `@ts-ignore`/`@ts-expect-error`/`@ts-nocheck` banned; source organisation: named exports only, `export let` banned, no container classes, `import type`/`export type`, namespaces banned, `require` banned.
- https://www.typescriptlang.org/docs/handbook/declaration-files/do-s-and-don-ts.html — no `Number`/`String`/`Boolean`/`Symbol`/`Object` types, no unused type parameters, `any` only during migration, `void` not `any` for ignored callback returns, no optional callback params, overload ordering, optional params and unions over overloads.
- https://www.typescriptlang.org/tsconfig/ — definitions of `strict` and what it bundles (`strictBuiltinIteratorReturn` included), `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`, `noImplicitOverride`, `noPropertyAccessFromIndexSignature`, `useUnknownInCatchVariables`, `noFallthroughCasesInSwitch`, `noImplicitReturns`, `noUnused*`, `moduleResolution` values, `types` (default `[]` in 6.0+), `allowImportingTsExtensions`, `rewriteRelativeImportExtensions`, `resolveJsonModule`, `declaration`.
- https://www.typescriptlang.org/docs/handbook/modules/reference.html — `node16`/`node18`/`nodenext` format detection by extension and `"type"`, extensions required in ESM, `bundler` semantics, `module: preserve`, interop rules, `moduleDetection`, guidance per project kind.
- https://www.typescriptlang.org/docs/handbook/modules/guides/choosing-compiler-options.html — recommended tsconfigs for a library, a bundled library, a Node app, a bundled app, tsx/ts-node; the library checklist (`module node18/nodenext`, `.js` extensions in relative imports, lowest `target`, `strict`, `verbatimModuleSyntax`, `declaration`, `rootDir`/`outDir`, no dual emit from one compilation, run attw); the app checklist (one tsconfig per environment, project references).
- https://www.typescriptlang.org/docs/handbook/2/narrowing.html — `typeof null === "object"`, truthiness pitfalls (`0`, `""`), `!= null`, `in`, `instanceof`, type predicates, discriminated unions, `never` exhaustiveness pattern.
- https://www.typescriptlang.org/docs/handbook/enums.html — numeric vs string enums, union enums, reverse mappings, `const enum` pitfalls (`isolatedModules`, version mismatch), the "Objects vs Enums" `as const` recommendation.
- https://www.typescriptlang.org/docs/handbook/declaration-files/publishing.html — `types` field, `@types` deps that your `.d.ts` exposes belong in `dependencies`, no `/// <reference path>`, `typesVersions`.
- https://www.typescriptlang.org/docs/handbook/utility-types.html — the utility type list incl. `Awaited`, `NoInfer`; `Omit`'s loose key constraint.
- https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-7.html — `node16`/`nodenext`, `.mts`/`.cts`, `exports` with `types` first, variance annotations `in`/`out`, `moduleDetection`, instantiation expressions.
- https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-9.html — `satisfies`, `in` narrowing on `unknown`, `accessor`, `NaN` equality error.
- https://www.typescriptlang.org/docs/handbook/release-notes/typescript-5-0.html — TC39 decorators (no parameter decorators, no `emitDecoratorMetadata`), `const` type parameters, `moduleResolution bundler`, `verbatimModuleSyntax` replacing `importsNotUsedAsValues`/`preserveValueImports`, `export type *`, all enums are union enums, resolution flags, deprecations.
- https://www.typescriptlang.org/docs/handbook/release-notes/typescript-5-2.html — `using`/`await using`, `Symbol.dispose`, `DisposableStack`, `SuppressedError`, decorator metadata, union-of-arrays methods.
- https://www.typescriptlang.org/docs/handbook/release-notes/typescript-5-3.html — import attributes `with { type: "json" }`, `switch (true)` narrowing, `Symbol.hasInstance`, `super` on instance fields.
- https://www.typescriptlang.org/docs/handbook/release-notes/typescript-5-4.html — `NoInfer`, preserved narrowing in closures after last assignment, `Object.groupBy`, `module: preserve`.
- https://www.typescriptlang.org/docs/handbook/release-notes/typescript-5-5.html — inferred type predicates (and the `!!x` caveat), `--isolatedDeclarations`, regex literal checking, Set methods, deprecated options now no-ops.
- https://www.typescriptlang.org/docs/handbook/release-notes/typescript-5-6.html — always-truthy/nullish check errors, iterator helpers, `strictBuiltinIteratorReturn`, `--noUncheckedSideEffectImports`, `--noCheck`.
- https://www.typescriptlang.org/docs/handbook/release-notes/typescript-5-7.html — never-initialised variable checks, `--rewriteRelativeImportExtensions`, `es2024`, JSON imports need `type: "json"` under `nodenext`, TypedArray generics.
- https://www.typescriptlang.org/docs/handbook/release-notes/typescript-5-8.html — `--erasableSyntaxOnly` (bans `enum`, runtime `namespace`, parameter properties, `import =`/`export =`), `require(esm)` under `nodenext`, `module node18`, granular return-branch checks, `assert` → `with`.
- https://www.typescriptlang.org/docs/handbook/release-notes/typescript-5-9.html — `import defer`, `module node20`, the new minimal `tsc --init` defaults (`strict`, `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`, `verbatimModuleSyntax`, `isolatedModules`, `types: []`, `skipLibCheck`), `ArrayBuffer`/`Uint8Array` breaking change.
- https://typescript-eslint.io/users/configs/ — what `recommended`, `recommended-type-checked`, `strict`, `strict-type-checked`, `stylistic`, `stylistic-type-checked`, `disable-type-checked` contain and their superset relations; `projectService`.
- https://typescript-eslint.io/rules/ — the full rule table with preset membership, type-info requirement, fixability.
- https://typescript-eslint.io/rules/no-floating-promises/ — what counts as handled, `void` marker, arrays of promises, options.
- https://typescript-eslint.io/rules/no-misused-promises/ — `checksConditionals`, `checksVoidReturn` contexts, `checksSpreads`, `forEach(async)` and event handler cases.
- https://typescript-eslint.io/rules/return-await/ — `in-try-catch` default, `error-handling-correctness-only` in strict, why `return await` in `try`/`finally`/`using`, `never` deprecated.
- https://typescript-eslint.io/rules/switch-exhaustiveness-check/ — options and how `default` interacts.
- https://typescript-eslint.io/rules/restrict-template-expressions/ — recommended vs strict allow-lists.
- https://typescript-eslint.io/rules/unbound-method/ — `this` loss, `expect(obj.method)` false positive.
- https://typescript-eslint.io/rules/no-unnecessary-condition/ — what it flags, the indexed-access caveat and `noUncheckedIndexedAccess`.
- https://typescript-eslint.io/rules/consistent-type-imports/ — options, overlap with `verbatimModuleSyntax`, decorator metadata caveat.
- https://typescript-eslint.io/rules/naming-convention/ — selectors/formats; needs type info; feature-frozen.
- https://eslint.org/docs/latest/rules/eqeqeq — options incl. `null: ignore`.
- https://eslint.org/docs/latest/rules/no-param-reassign — `props` option, rationale.
- https://eslint.org/docs/latest/rules/prefer-const — options.
- https://eslint.org/docs/latest/rules/no-await-in-loop — rationale and the legitimate sequential cases.
- https://eslint.org/docs/latest/rules/no-promise-executor-return — rationale, `allowVoid`.
- https://eslint.org/docs/latest/rules/no-async-promise-executor — lost errors; in `eslint:recommended`.
- https://eslint.org/docs/latest/rules/require-atomic-updates — read-modify-write across `await`.
- https://eslint.org/docs/latest/rules/no-constant-binary-expression — `x === []`, `a + b ?? c`; recommended.
- https://github.com/sindresorhus/eslint-plugin-unicorn — review-relevant rules and recommended status (`no-for-each`, `prefer-node-protocol`, `no-process-exit`, `error-message`, `throw-new-error`, `no-await-expression-member`, `no-thenable`, `no-instanceof-builtins`, `prefer-top-level-await`, ...).
- https://github.com/un-ts/eslint-plugin-import-x — `no-cycle`, `no-unresolved`, `no-extraneous-dependencies`, `no-default-export`, `no-mutable-exports`, `no-duplicates`, `extensions`, `no-unused-modules`, TS resolver setup.
- https://github.com/eslint-community/eslint-plugin-security — the `detect-*` rules and the "many false positives, needs human triage" caveat.
- https://github.com/eslint-community/eslint-plugin-n — recommended set (`no-missing-import`, `no-extraneous-import`, `no-unpublished-import`, `no-unsupported-features/*`, `no-deprecated-api`, `no-process-exit`, `hashbang`) and opt-in rules (`no-sync`, `prefer-node-protocol`, `no-path-concat`, `prefer-promises/fs`).
- https://github.com/eslint-community/eslint-plugin-promise — rule list and recommended membership (`catch-or-return`, `always-return`, `no-nesting`, `no-return-in-finally`, `no-multiple-resolved`, `prefer-await-to-then`).
- https://github.com/danvk/effective-typescript — the 83 item titles of Effective TypeScript, 2nd ed.
- https://effectivetypescript.com/2024/05/21/second-edition/ — confirms 83 items, 24 new, two new chapters.
- https://effectivetypescript.com/2020/05/26/iterate-objects/ — why `for..in`/`Object.keys` give `string`, when `keyof typeof` is unsafe, prefer `Object.entries`.
- https://www.totaltypescript.com/why-i-dont-like-typescript-enums — numeric enum unsafety, nominal enums, advice (string enums if any, none in new code).
- https://www.totaltypescript.com/clarifying-the-satisfies-operator — annotation vs `satisfies` vs `as`; `satisfies` for config objects.
- https://www.totaltypescript.com/type-vs-interface-which-should-you-use — `type` by default, `interface` for `extends`; declaration merging and implicit index signature arguments.
- https://www.totaltypescript.com/method-shorthand-syntax-considered-harmful — method shorthand is bivariant; use property syntax; `method-signature-style`.
- https://www.totaltypescript.com/concepts/the-prettify-helper — the `Prettify` display helper.
- https://totaltypescript.com/four-essential-typescript-patterns — branded types as validation boundaries, `declare global`, type predicates and assertion functions, `asserts this is`.
- https://www.totaltypescript.com/workshops/typescript-pro-essentials/objects/understanding-distributive-omit-and-pick-in-typescript — `Omit`/`Pick` collapse unions; `DistributiveOmit`.
- https://github.com/goldbergyoni/nodebestpractices and https://raw.githubusercontent.com/goldbergyoni/nodebestpractices/master/README.md — sections 2 (errors), 3 (patterns), 4 (testing), 5 (production), 6 (security), 7 (performance): TL;DR and "Otherwise" lines.
- https://cheatsheetseries.owasp.org/cheatsheets/Nodejs_Security_Cheat_Sheet.html — request size limits, no event-loop blocking, input validation, output escaping, `eval`/`exec`, ReDoS, headers, cookies, `uncaughtException` handling, emitter `error` listeners.
- https://cheatsheetseries.owasp.org/cheatsheets/Prototype_Pollution_Prevention_Cheat_Sheet.html — `Map`/`Set`, `Object.create(null)`, `Object.freeze` of prototypes, `--disable-proto`.
- https://nodejs.org/en/learn/getting-started/security-best-practices — HTTP DoS timeouts, `files` allowlist, request smuggling, `timingSafeEqual`, supply chain (`--ignore-scripts`, exact pins, lockfile, `npm ci`, `npm audit`, `--min-release-age`), prototype pollution mitigations, `--permission`.
- https://nodejs.org/api/typescript.html — type stripping: default since 22.18/23.6, stable in 24.12/25.2; erasable vs non-erasable syntax; `.ts` extensions mandatory; `import type` required; no tsconfig reading; recommended tsconfig; `node_modules` never stripped; `tsx` for the rest.
- https://nodejs.org/api/packages.html — `"type"`, `exports` encapsulation and subpath patterns, condition ordering (`types` first, `default` last, `import`/`require`/`module-sync`), `imports` (`#`), self-reference, dual package hazard pointer.
- https://nodejs.org/api/esm.html — mandatory extensions, no `__dirname`/`require` in ESM, `import.meta.dirname`/`filename`/`resolve`/`main`, `createRequire`, CJS named-export detection is best effort, JSON needs `with { type: 'json' }`, top-level await exit code 13.
- https://nodejs.org/api/modules.html — `require(esm)`: synchronous graph only, `ERR_REQUIRE_ASYNC_MODULE`, `__esModule`, `'module.exports'` named export, cycles see partial exports.
- https://nodejs.org/api/process.html — `uncaughtException` is cleanup-then-exit only, `unhandledRejection`, `--unhandled-rejections` default `throw`, `process.exit()` truncates output; prefer `process.exitCode`; signal handling.
- https://nodejs.org/api/errors.html — `new Error(msg, { cause })`, use `error.code` not `message`, propagation by API style, `'error'` with no listener crashes.
- https://nodejs.org/api/events.html — `'error'` crash, `MaxListenersExceededWarning` as leak hint, per-request listener leak and fix, `captureRejections`, `events.once`/`on` with `AbortSignal`, `EventTarget` differences.
- https://nodejs.org/api/stream.html — `.pipe()` does not forward errors, `pipeline` does and supports `signal`; `write()` return value and `'drain'`; `finished`; mixing consumption styles.
- https://nodejs.org/api/timers.html — `timers/promises`, `signal` option, `unref()`, `setInterval` async iterator.
- https://nodejs.org/api/globals.html — `AbortSignal.timeout`, `AbortSignal.any`, `throwIfAborted`, `{ once: true }` to avoid abort-listener leaks, `structuredClone`, global `fetch`.
- https://nodejs.org/api/child_process.html — never pass unsanitised input to `exec`; `execFile`/`spawn` without `shell`; `maxBuffer` default 1 MiB; `promisify(exec)` error carries `stdout`/`stderr`.
- https://nodejs.org/api/cli.html — `--disable-proto`, `--frozen-intrinsics`, `--enable-source-maps`, `--env-file`, `--permission`, `--import`.
- https://nodejs.org/api/test.html — `node:test` basics, subtests must be awaited, `--test-only`, `mock.fn/method/module/timers`, coverage thresholds, isolation.
- https://vitest.dev/guide/mocking.html — `vi.mock` hoisted; internal calls not mocked; date/global/env stubs persist unless reset.
- https://vitest.dev/api/vi.html — `vi.hoisted`, `vi.doMock`, `importOriginal`, clear/reset/restore-all, fake timers (`nextTick`/`queueMicrotask` not faked by default), `advanceTimersByTimeAsync`, `vi.waitFor`.
- https://vitest.dev/api/mock.html — `mockClear` vs `mockReset` vs `mockRestore` table; `mockResolvedValue`; args stored by reference; `clearMocks`/`mockReset`/`restoreMocks` config.
- https://vitest.dev/api/expect.html — `toBe` vs `toEqual` vs `toStrictEqual`; `expect.assertions`; `resolves`/`rejects` must be awaited or the test fails; `toThrow` needs a function; `expect.poll`; `expect.soft`.
- https://vitest.dev/guide/testing-types.html — `expectTypeOf`, `*.test-d.ts`, `--typecheck`, `@ts-expect-error` typo false passes.
- https://vitest.dev/guide/features.html — `.only/.skip/.todo/.concurrent`, isolation and pools, sharding, unhandled error reporting.
- https://vitest.dev/config/allowonly — default `!process.env.CI`: `.only` fails the suite in CI.
- https://vitest.dev/config/coverage — provider, `thresholds` (per-file, `autoUpdate`, `100`, glob thresholds), negative numbers as max uncovered.
- https://vitest.dev/config/ — config file lookup and precedence (index page only).
- https://kentcdodds.com/blog/common-mistakes-with-react-testing-library — `screen`, `*ByRole` first, `find*` over `waitFor(getBy)`, no side effects in `waitFor`, `userEvent` over `fireEvent`, `query*` only for absence.
- https://publint.dev/rules — all publint rules: `types` first/`default` last in `exports`, format mismatches, `FILE_NOT_PUBLISHED`, `USE_FILES`, `USE_EXPORTS`, `USE_TYPE`, `USE_SIDE_EFFECTS`, `USE_ENGINES_NODE`, `TYPES_NOT_EXPORTED`, `LOCAL_DEPENDENCY`.
- https://github.com/arethetypeswrong/arethetypeswrong.github.io — attw problem kinds (`FalseCJS`, `FalseESM`, `CJSResolvesToESM`, `NoResolution`, `UntypedResolution`, `FallbackCondition`, `FalseExportDefault`, `MissingExportEquals`, ...).
- https://docs.npmjs.com/cli/v10/configuring-npm/package-json — `files` (always-included and never-included lists), `engines` advisory unless `engine-strict`, dependency kinds, `overrides` semantics, range syntax, lifecycle scripts.
- https://docs.npmjs.com/cli/v10/commands/npm-ci — fails on lockfile mismatch, never writes, deletes `node_modules`, `--ignore-scripts`, `--omit=dev`.
- https://docs.npmjs.com/generating-provenance-statements — `--provenance`, `id-token: write`, `repository` must match, trusted publishing, `npm audit signatures`.
- https://webpack.js.org/guides/tree-shaking/ — `sideEffects: false`/globs, barrel files, `/*#__PURE__*/`, ESM required.
- https://zod.dev/basics — `parse` throws, `safeParse` returns a discriminated result, `z.infer`/`z.input`/`z.output`.
- https://biomejs.dev/linter/ — rule groups, recommended rules, Scanner-backed rules (`noFloatingPromises`, `noImportCycles`), formatter owns formatting.
- https://github.com/egoist/tsup — now unmaintained; recommends tsdown (the tsup.egoist.dev page returned only a title).

Not fetched, so not cited: Jest docs, eslint-plugin-jest/vitest docs, tsdown docs, SLSA, TS 5.1 notes.

## 2. Detecting the language, runtime and configuration

Read these before applying any rule, so the reviewer flags what the tooling does not.

**File extensions.** `.ts` (format decided by the nearest `package.json` `"type"`), `.tsx` (JSX; not
runnable by Node's type stripping), `.mts` (always ESM, emits `.mjs`, declarations `.d.mts`),
`.cts` (always CJS, `.d.cts`), `.d.ts` (hand-written or emitted declarations; a repo committing
`.d.ts` beside `.ts` sources is usually describing untyped JS or shipping types). JavaScript
siblings `.mjs`/`.cjs`/`.js` follow the same rule. [TS modules reference; Node packages]

**tsconfig flags to read first** (the file may `extends` an array; later entries win):
- `strict` on or off, and which members are disabled individually (`strictNullChecks`,
  `noImplicitAny`, `useUnknownInCatchVariables`, `strictFunctionTypes`,
  `strictPropertyInitialization`, `strictBuiltinIteratorReturn`). Without `strictNullChecks`,
  half of the nullability checklist is moot and `no-unnecessary-condition` cannot run.
- Extra strictness: `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`,
  `noImplicitOverride`, `noPropertyAccessFromIndexSignature`, `noFallthroughCasesInSwitch`,
  `noImplicitReturns`, `noUnusedLocals`/`noUnusedParameters`, `noUncheckedSideEffectImports`
  (5.6), `allowUnreachableCode: false`. TS 5.9's `tsc --init` now turns on `strict`,
  `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`, `verbatimModuleSyntax`,
  `isolatedModules`, `types: []`, `moduleDetection: force`, `skipLibCheck`; a repo created
  since 5.9 with none of these has opted out deliberately. [tsconfig; TS 5.9]
- `module`/`moduleResolution`: `nodenext`/`node18`/`node20` means Node semantics (extensions
  required in ESM imports, `exports` conditions honoured, format from `"type"`); `esnext` +
  `bundler` means a bundler consumes the output (extensionless imports allowed, usually
  `noEmit`); `commonjs`/`node10` is legacy and `paths` aliases are a smell under Node. `module:
  preserve` keeps syntax as written. [TS modules reference; choosing compiler options]
- Type-stripping signals: `erasableSyntaxOnly` (5.8), `allowImportingTsExtensions` (needs
  `noEmit`), `rewriteRelativeImportExtensions` (5.7), `verbatimModuleSyntax`, `noEmit`. If these
  are set, or scripts run `node file.ts`, the repo runs TS directly under Node: `enum`, runtime
  `namespace`, parameter properties, `import x = require()`, decorators and `.tsx` are errors at
  runtime, and every relative import must carry `.ts`. Node ignores tsconfig entirely (no
  `paths`; use `imports` with `#`). [Node typescript; TS 5.8]
- `verbatimModuleSyntax` vs `isolatedModules`: either makes type-only imports explicit; the
  former also forbids ESM syntax in a CJS-emitting file. If present, skip the
  `consistent-type-imports` review item. [TS 5.0; consistent-type-imports]
- `declaration`/`declarationMap`/`composite`/`isolatedDeclarations`: a library. `references`:
  a multi-environment app (server, DOM, worker, tests each with their own tsconfig).
- `target`/`lib`: a library should target the lowest supported runtime; an app the runtime it
  ships to. `lib` with `dom` in a Node project means browser globals type-check silently.
  `types: []` means `@types/*` globals are opt-in.
- `skipLibCheck: true` means `.d.ts` in `node_modules` and the repo's own hand-written `.d.ts`
  are not checked; review hand-written declaration files harder.

**package.json.** `"type": "module"` (ESM) or absent/`"commonjs"`; `"engines": { "node" }` and
`.nvmrc`/`.node-version` give the Node version (advisory unless `engine-strict`); `exports`
present means a library with an encapsulated public surface (`types` first, `default` last);
`main` only means legacy or app; `files` means publishing; `private: true` means an app;
`sideEffects` means a bundler consumes it; `peerDependencies` means a plugin/library;
`bin` means a CLI. Scripts reveal the runner (`vitest`, `jest`, `node --test`), the loader
(`tsx`, `ts-node`, `node file.ts`), the bundler (`tsup`, `tsdown`, `esbuild`), and whether `tsc
--noEmit` runs in CI at all. [npm package.json; Node packages; publint]

**ESLint config** (`eslint.config.*` flat or `.eslintrc*`). Find which typescript-eslint preset is
applied: `recommended` (no type info), `recommended-type-checked`, `strict`,
`strict-type-checked`, plus `stylistic`/`stylistic-type-checked`; `parserOptions.projectService`
or `project` means type-aware rules run. Note plugins: `eslint-plugin-import-x`/`import`,
`eslint-plugin-n`, `eslint-plugin-promise`, `eslint-plugin-unicorn`, `eslint-plugin-security`,
`eslint-plugin-vitest`/`jest`. Note disabled rules (`'off'`) and `eslint-disable` comments in the
diff. Biome (`biome.json`) replaces both Prettier and much of ESLint; its Scanner-backed rules
(`noFloatingPromises`, `noImportCycles`) are opt-in. Prettier (`.prettierrc`) or Biome's formatter
means no formatting comments at all. [typescript-eslint configs; Biome]

**Library vs application.** Library: `exports`/`types`/`files`/`declaration`, no `private`,
`peerDependencies`, a `prepack`/`prepublishOnly` script, publint/attw in CI. Application:
`private: true`, `main` or none, a `start` script, a Dockerfile, `.env` handling. Library rules
(explicit return types, no `any` in public types, `readonly` inputs, no dual-emit from one
compilation) apply only to the former. [choosing compiler options]

**Node vs browser.** Node: `node:` imports, `process`, `Buffer`, `lib` without `dom`,
`@types/node`, `engines.node`. Browser/React: `.tsx`, `jsx` in tsconfig, `lib: ["dom"]`,
`@testing-library/*`, a bundler config. Shared packages must avoid both sets of globals.

## 3. Checklist

Each item: the rule, the signal in a diff, why, and the source plus the lint rule when one exists.

### 3.1 Type system and `any`

- **No new `any`, explicit or implicit.** Signal: `: any`, `as any`, `<any>`, `any[]`, untyped
  parameters when `noImplicitAny` is off, `Record<string, any>`, `Function`. Each `any` disables
  checking for everything it touches; downstream `.d.ts` inherit it. Prefer a precise type, then
  `unknown` narrowed by a guard. [Google TS; Do's and Don'ts; Effective TS 5, 43–46;
  `@typescript-eslint/no-explicit-any`, `no-unsafe-assignment`, `no-unsafe-argument`,
  `no-unsafe-call`, `no-unsafe-member-access`, `no-unsafe-return`, `no-unsafe-function-type`]
- **`unknown` at every boundary, with runtime validation.** Signal: `JSON.parse(...)` result
  used without a schema, `req.body as Foo`, `await res.json() as Foo`, `process.env.X` treated as
  `string`. `JSON.parse` and `fetch().json()` return `any`; the cast is a lie. Validate with
  zod/valibot/arktype (`safeParse` returning a discriminated result) and derive the type with
  `z.infer`. [Effective TS 46, 74; zod basics; Node best practices 2.11, 6.10]
- **Type assertions need a reason.** Signal: `as Foo` (other than `as const`), `x!`, `as unknown
  as`. Assertions insert no runtime check; Google requires a comment explaining why each is safe;
  double assertions must go through `unknown`, never `any`/`{}`. Prefer a runtime check
  (`instanceof`, `in`, a predicate) or a type annotation. Hide an unavoidable assertion inside a
  small well-typed function. [Google TS; Effective TS 9, 45; `no-unnecessary-type-assertion`,
  `no-non-null-assertion` (strict), `no-unsafe-type-assertion` (opt-in),
  `consistent-type-assertions`]
- **Annotation, not assertion, on object literals.** Signal: `const cfg = { ... } as Config`.
  `as` suppresses excess-property checks and lets a stale literal compile when `Config` gains a
  field; `const cfg: Config = { ... }` or `{ ... } satisfies Config` catches it. Use `satisfies`
  when the narrow inferred type must survive (config maps, route tables). [Google TS; TS 4.9;
  Total TypeScript satisfies; Effective TS 11]
- **Discriminated unions over optional-field bags.** Signal: an interface with several `?:`
  fields whose validity depends on each other (`error?: E; data?: T`), or a `kind` field typed
  `string`. Unions of interfaces with a literal discriminant make invalid states unrepresentable
  and let `switch` narrow without `!`. [TS narrowing; Effective TS 29, 34, 37, 63]
- **Exhaustiveness via `never`.** Signal: a `switch` over a union or enum with no `default`
  and no `assertNever`/`satisfies never`, or a `default` that silently returns. Adding a union
  member should fail compilation somewhere. Pattern: `default: { const _x: never = v; return _x
  }` or `assertNever(v)`. Note `switch-exhaustiveness-check` by default does not accept a
  `default` as exhaustive. [TS narrowing; Effective TS 59;
  `@typescript-eslint/switch-exhaustiveness-check` (opt-in)]
- **Enums: prefer `as const` objects and literal unions; never `const enum`; never numeric.**
  Signal: new `enum`, `const enum`, numeric or mixed enums, `Boolean(someEnum)`. Numeric enums
  accept any number (`logStatus(0)` compiles) and generate reverse mappings; enums are nominal;
  `const enum` breaks under `isolatedModules` and version skew; under Node type stripping every
  `enum` is a runtime error. If an enum must exist, make it a string enum with explicit values.
  [TS enums; Total TypeScript enums; Google TS; TS 5.8 erasableSyntaxOnly; Node typescript;
  `no-mixed-enums`, `prefer-literal-enum-member`, `prefer-enum-initializers`,
  `no-duplicate-enum-values`, `no-unsafe-enum-comparison`]
- **`interface` vs `type`: follow the repo's convention; use `extends` for inheritance.** Signal:
  a diff that mixes styles, or `A & B & C` chains where `interface X extends A, B` would do.
  Interfaces merge silently on duplicate names (a `no-redeclare` concern) and cache better under
  `extends`; type aliases get an implicit index signature and so assign to `Record<string, T>`.
  Google says interfaces for object types; Total TypeScript says `type` by default. Either is
  fine; inconsistency within a diff is not. [Google TS; Total TypeScript type vs interface;
  Effective TS 13; `consistent-type-definitions` (stylistic)]
- **Property syntax for function-typed members.** Signal: `method(x: Dog): void` in an interface
  or type used as a callback contract. Method shorthand is bivariant even under
  `strictFunctionTypes`, so a narrower implementation compiles and crashes at runtime; `method:
  (x: Dog) => void` is checked contravariantly. [Total TypeScript method shorthand; tsconfig
  strictFunctionTypes; `@typescript-eslint/method-signature-style`]
- **Generics restraint.** Signal: a type parameter used once (`function f<T>(x: T): void`), a
  parameter whose only purpose is to return its input, deep conditional/mapped types where an
  explicit interface would read better, `infer` chains for a one-off. Google: "always use the
  simplest type construct"; Do's and Don'ts: never a type parameter you don't use. Prefer
  conditional types to overloads only when they actually reduce duplication. [Google TS; Do's
  and Don'ts; Effective TS 50–52, 57, 58; `no-unnecessary-type-parameters` (strict),
  `no-unnecessary-type-constraint`, `no-unnecessary-type-arguments`]
- **Overloads: specific first, and unions/optional params instead when the return type is
  the same.** Signal: overloads differing only by one argument's type or by trailing arity;
  a general `unknown` overload listed first. TS picks the first match. [Do's and Don'ts;
  `unified-signatures` (strict), `adjacent-overload-signatures`]
- **`Omit`/`Pick` on unions collapse them.** Signal: `Omit<SomeUnion, 'id'>`. `Omit` is
  `Pick<T, Exclude<keyof T, K>>`; `keyof` of a union is the common keys only, and `K` is not
  constrained to `keyof T` so typos pass. Use a `DistributiveOmit<T, K> = T extends unknown ?
  Omit<T, K> : never` helper. [TS utility types; Total TypeScript distributive omit]
- **`Record<string, T>` means "may be absent".** Signal: `dict[key].foo` with no check when the
  type is an index signature or array; `Object.keys(obj) as (keyof typeof obj)[]`. Without
  `noUncheckedIndexedAccess`, TS pretends every lookup succeeds; with it, every read is `T |
  undefined`. Prefer `Map`, or a `Record<KnownKeys, T>`; iterate objects with `Object.entries`
  and accept `string` keys unless the object's keys are closed. [Google TS; Effective TS 16, 17,
  60, 61; Effective TS iterate-objects; tsconfig; `no-unnecessary-condition` caveat]
- **Branded types for validated primitives at boundaries.** Signal: several `string`
  parameters of the same function (`(userId: string, orgId: string)`), IDs and validated inputs
  passed as bare `string`. A brand (`string & { readonly __brand: 'UserId' }`) makes "validated
  once, trusted thereafter" a compiler fact. Nit unless the function has swapped them before.
  [Total TypeScript four patterns; Effective TS 36, 38, 64]
- **`readonly` by default on data that is not meant to change.** Signal: a function taking
  `T[]` or `{...}` it does not mutate; class fields assigned only in the constructor without
  `readonly`; mutable exported arrays/objects. `readonly T[]` documents and enforces; a private
  field never reassigned should be `readonly`. [Google TS; Effective TS 14; `prefer-readonly`
  (opt-in), `prefer-readonly-parameter-types` (opt-in, noisy)]
- **Wrapper and empty types.** Signal: `String`, `Number`, `Boolean`, `Object`, `Symbol`,
  `{}` as "any object", `Function`. Use primitives, `object`, `unknown`, and a concrete function
  type. [Do's and Don'ts; Google TS; `no-wrapper-object-types`, `no-empty-object-type`,
  `no-unsafe-function-type`]
- **Suppression comments.** Signal: `// @ts-ignore`, `// @ts-nocheck`, `// @ts-expect-error`
  without a description, `eslint-disable` without a rule name. Google bans all three in
  production code; if one is unavoidable, `@ts-expect-error` with a reason, since it fails when
  the error disappears. [Google TS; `@typescript-eslint/ban-ts-comment` (requires
  descriptions), `unicorn/no-abusive-eslint-disable`]

### 3.2 Nullability and optionals

- **Optional `?:` over `| undefined`; never `|null`/`|undefined` inside a type alias.** Signal:
  `type Id = string | null` then `Id` used everywhere; `field: T | undefined` on an interface.
  Put nullability at the use site; push nulls to the perimeter. Under
  `exactOptionalPropertyTypes`, `?:` forbids an explicit `undefined`, so `{ x: undefined }` will
  no longer compile for `x?: T`: check the diff does not rely on that. [Google TS; Effective TS
  32, 33, 37; tsconfig exactOptionalPropertyTypes]
- **Truthiness is not a null check.** Signal: `if (value)` or `value || default` where `value`
  may be `0`, `''`, `false` or `NaN`. Use `value != null`, `??`, `?.`. `== null` is the one
  sanctioned `==`. [TS narrowing; Google TS; eslint `eqeqeq` with `null: ignore`;
  `@typescript-eslint/prefer-nullish-coalescing`, `prefer-optional-chain` (stylistic)]
- **Non-null assertions are assertions.** Signal: `x!.y`, `map.get(k)!`, `arr[0]!`. Each `!` is
  an unchecked promise; prefer a guard, `?? throwIfMissing()`, or `at(0)`. Comment any that
  stay. [Google TS; `no-non-null-assertion` (strict), `no-extra-non-null-assertion`,
  `no-non-null-asserted-optional-chain`, `no-non-null-asserted-nullish-coalescing`]
- **Array and `Record` lookups may be `undefined`.** Signal: `items[0].id`, `parts.at(-1)!`,
  `env[name].trim()`. Without `noUncheckedIndexedAccess` the compiler does not warn; the
  runtime does. Guard or destructure with defaults. [tsconfig noUncheckedIndexedAccess;
  `no-unnecessary-condition` caveat]
- **`typeof x === 'object'` admits `null`.** Signal: that check followed by property access.
  Add `x !== null`. [TS narrowing]
- **Filter with a real predicate, not `Boolean`.** Signal: `.filter(Boolean)` on `(T |
  undefined)[]` expecting `T[]`; `filter(x => !!x)` on numbers. TS 5.5 infers predicates only
  for "if and only if" checks (`x !== undefined`), not truthiness, so `0` and `''` are dropped
  silently and the element type may stay wide. [TS 5.5]
- **`catch (e)` is `unknown`.** Signal: `catch (e) { e.message }`, `catch (e: any)`, `.catch((e)
  => e.code)`. Under `useUnknownInCatchVariables` (part of `strict`) narrow with `instanceof
  Error` or a helper; promise rejection callbacks need the same. [tsconfig; Google TS;
  `use-unknown-in-catch-callback-variable` (strict)]
- **Conditions that cannot change.** Signal: `if (arr)` for a non-nullable array, `str?.length`
  on `string`, `x ?? y` where `x` is non-nullable, `if (/re/)`. TS 5.6 errors on some
  always-truthy expressions; the lint rule catches the rest. Usually means the type is wrong,
  not the check. [TS 5.6; `no-unnecessary-condition` (strict), eslint
  `no-constant-binary-expression`]
- **`Object.groupBy`, `Map.get`, `find` return optionals.** Signal: using their result without
  a check. [TS 5.4; tsconfig strictNullChecks]
- **Default parameters and destructuring defaults hide `null`.** Signal: `function f(x = 5)`
  called with `null`; `const { a = 1 } = obj` where `a` may be `null`. Defaults apply to
  `undefined` only. [TS narrowing]

### 3.3 Idioms and language features

- **`const` by default, `let` when reassigned, never `var`; one declaration per statement.**
  [Google TS; Node best practices 3.7; eslint `prefer-const`, `no-var`]
- **`===`/`!==` always; `== null` is the only exception.** Signal: `==` anywhere else. [Google
  TS; Node best practices 3.10; eslint `eqeqeq`]
- **Coercion is explicit.** Signal: unary `+str`, `parseInt(str)` for base 10, `!!x` on enums,
  `'' + n`, `new Number()`. Use `Number(str)` and check `Number.isNaN`; `String()`/`Boolean()`
  as functions are fine. TS 5.0 made `<`/`>` between string and number an error. [Google TS;
  TS 5.0; `unicorn/prefer-native-coercion-functions`, `unicorn/prefer-number-properties`]
- **Template literals only with strings (or numbers you have formatted).** Signal: `${obj}`,
  `${arr}`, `${maybeNull}`, `${0.1 + 0.2}`. Objects print `[object Object]`, arrays join with
  bare commas, floats print their full expansion. [`restrict-template-expressions`
  (recommended allows number/boolean/nullish/any; strict allows none); `no-base-to-string`]
- **`for..of` over indexed `for` and `forEach`; `for..in` only on dict objects, via
  `Object.keys/entries`.** Signal: `for (k in arr)`, `arr.forEach(async ...)`. `forEach` swallows
  returned promises and cannot `break`/`await`. [Google TS; `no-for-in-array`,
  `prefer-for-of` (stylistic), `unicorn/no-for-each`, `no-misused-promises`]
- **Spread only like onto like; no prototype-bearing objects.** Signal: `{...someClassInstance}`,
  `[...maybeUndefined]`, `{...await getData()}`. Spreading a promise or a primitive is a
  silent bug; instances lose their prototype. [Google TS; `no-misused-spread` (strict),
  `no-misused-promises` checksSpreads]
- **`switch` ends with `default`, last, and no fall-through in non-empty cases.** Signal: a
  case without `break`/`return`/`throw`, a `default` in the middle, a `switch` on a union with
  no default and no `never` check. [Google TS; tsconfig `noFallthroughCasesInSwitch`; eslint
  `default-case`, `no-fallthrough`; `switch-exhaustiveness-check`]
- **Function declarations for named functions; arrows for callbacks; no function
  expressions.** Signal: `const f = function () {}`, `this` inside a non-method non-arrow
  function, `self = this`. [Google TS; `no-this-alias`, `unicorn/consistent-function-scoping`]
- **Unbound methods lose `this`.** Signal: `const fn = obj.method`, `arr.map(obj.method)`,
  `on('x', this.handle)`. Pass an arrow or `bind`; or declare `this: void` for methods that do
  not use `this`. `expect(obj.method)` in tests is the known false positive. [`unbound-method`]
- **Classes: `private`/`protected` TS modifiers (or `#private`, per repo convention), `readonly`
  fields, pure getters, `override` on overrides, no container classes of statics.** Signal: a
  class with only static members; a getter with side effects; a subclass method shadowing a base
  method without `override`; `Object.defineProperty` accessors. Google bans `#private` for
  compatibility and prefers parameter properties, but parameter properties are a type-stripping
  error under Node, so in a `node file.ts` repo prefer explicit fields. [Google TS; tsconfig
  `noImplicitOverride`; TS 5.8; `no-extraneous-class` (strict), `class-literal-property-style`,
  `prefer-readonly`, `parameter-properties`]
- **`namespace`, `module Foo {}`, `/// <reference path>`, `require()`, `export default`,
  `export let`.** Signal: any of these in new code. ES modules, named exports and immutable
  exports only; `namespace` only to type third-party code. [Google TS; `no-namespace`,
  `no-require-imports`, `triple-slash-reference`, `import-x/no-default-export`,
  `import-x/no-mutable-exports`]
- **No `eval`, `new Function(string)`, string `setTimeout`, `debugger`, builtin prototype
  modification.** [Google TS; Node best practices 6.15; `no-implied-eval`, eslint `no-eval`,
  `no-debugger`, `no-extend-native`]
- **Array construction.** Signal: `new Array(n)`, `Array(3)`. Use literals or `Array.from({
  length })`. [Google TS; `no-array-constructor`, `unicorn/no-new-array`]
- **`delete` on arrays or computed keys; `void` misuse.** Signal: `delete arr[i]`, `delete
  obj[userKey]`, `void` as a parameter type, `return void fn()`. [`no-array-delete`,
  `no-dynamic-delete` (strict), `no-invalid-void-type`, `no-meaningless-void-operator`,
  `no-confusing-void-expression` (strict)]
- **Modern features used correctly.** `satisfies` (4.9) for config shapes; `as const` for
  literal tables; `const` type parameters (5.0) only when callers need literal inference;
  `NoInfer` (5.4) to stop a default parameter widening a union; `using`/`await using` (5.2)
  requires `Symbol.dispose` support in the runtime and `lib: esnext.disposable` — check the
  target before accepting; `accessor` (4.9) and TC39 decorators (5.0) are not type-strippable
  and TC39 decorators cannot decorate parameters or emit metadata; iterator helpers (5.6) need
  Node 22+; `import defer` (5.9) needs `module: preserve`/`esnext` and runtime support.
  [TS 4.9, 5.0, 5.2, 5.4, 5.5, 5.6, 5.9; Node typescript]
- **Regex literals.** Signal: a new regex with nested quantifiers (`(a+)+`), user input
  interpolated into `new RegExp`. TS 5.5 checks literal syntax; it does not check for ReDoS.
  [TS 5.5; Node best practices 6.16; `security/detect-unsafe-regex`,
  `security/detect-non-literal-regexp`]

### 3.4 Promises and async

- **No floating promises.** Signal: a call returning a promise used as a statement
  (`doThing();`), `.then(cb)` with no rejection handler, `[...].map(async ...)` left unawaited,
  `void promise` used to silence rather than to mark deliberate fire-and-forget. A rejection
  becomes an unhandled rejection, which crashes Node by default (`--unhandled-rejections=throw`).
  `void` is acceptable only with a comment and a `.catch` inside. [`no-floating-promises`;
  Node process; Node best practices 2.10; `promise/catch-or-return`]
- **No promises where a value or a `void` callback is expected.** Signal: `if (isValid())`
  where `isValid` is async; `arr.forEach(async ...)`; `emitter.on('x', async ...)` /
  `addEventListener('x', async ...)` / `app.get('/', async ...)` on frameworks that do not
  handle returned promises; `{ ...asyncCall() }`; an async override of a `void` method. Wrap in
  a sync handler that calls `.catch`, or `for..of` + `await`, or `Promise.all`.
  [`no-misused-promises`; Node events captureRejections]
- **`await` only thenables; `async` only when awaiting.** Signal: `await syncValue`, `async
  function f() { return x }` with no `await`. The former hides a type confusion; the latter
  changes error timing (sync throws become rejections) and adds a tick. Exception: an `async`
  wrapper kept for a uniform interface. [`await-thenable`, `require-await`,
  `unicorn/no-unnecessary-await`, `promise-function-async` (opt-in)]
- **`return await` inside `try`/`catch`/`finally`/`using`.** Signal: `try { return p() }
  catch`. Without `await`, the rejection skips the `catch` and the `finally` cleanup ordering
  is wrong. Outside error-handling contexts either style is fine; Node best practices want
  `return await` everywhere for full stack traces. [`return-await` (strict); Node best
  practices 2.12]
- **Concurrency on purpose.** Signal: `await` in a loop over independent items; conversely,
  `Promise.all` over hundreds of network calls with no limit. Sequential awaits waste time
  *and* leave later rejections unhandled when an earlier one throws; unbounded parallelism
  exhausts sockets and rate limits. Use `Promise.all`/`allSettled` for small independent sets,
  a bounded pool otherwise, and keep sequential loops where order, retries or rate limits
  require. [eslint `no-await-in-loop` (and its exceptions); Node best practices 7.1]
- **`Promise.all` vs `allSettled`.** Signal: `Promise.all` where one failure should not abort
  the others, or `allSettled` whose rejected entries are never inspected.
- **No `new Promise` around something already promise-shaped; no async executor; executor
  returns nothing.** Signal: `new Promise(async (res, rej) => ...)`, `new Promise(r =>
  r(value))`, `return` of a value inside the executor. Errors in an async executor are lost.
  Use `util.promisify`, `events.once`, `timers/promises`, `stream/promises`. [eslint
  `no-async-promise-executor`, `no-promise-executor-return`; `promise/avoid-new`,
  `promise/no-multiple-resolved`; Node timers; Node events]
- **Read-modify-write across an `await` is a race.** Signal: `this.total += await f()`,
  `cache[k] = await load()` after an `if (!cache[k])` with no re-check. The state may have
  changed during the await. [eslint `require-atomic-updates`]
- **Cancellation and timeouts via `AbortSignal`.** Signal: `fetch(url)` with no `signal`, a
  long operation with no deadline, `setTimeout` used to race a promise by hand, a shared
  long-lived signal with `addEventListener('abort', ...)` lacking `{ once: true }`. Use
  `AbortSignal.timeout(ms)`, `AbortSignal.any([...])`, pass `signal` into `fetch`,
  `pipeline`, `events.once`, `timers/promises`; check `signal.throwIfAborted()` at
  checkpoints. [Node globals; Node timers; Node events; Node stream]
- **Rejections are `Error`s.** Signal: `reject('msg')`, `throw 'msg'`, `Promise.reject(42)`.
  Non-`Error` rejections have no stack and defeat `instanceof` handling.
  [`prefer-promise-reject-errors`, `only-throw-error`; Google TS]
- **Top-level await is fine in ESM apps, a hazard in libraries.** Signal: top-level `await` in
  a module that CJS consumers may `require()` (throws `ERR_REQUIRE_ASYNC_MODULE`), or that is
  the `module-sync` export; a never-settling top-level await exits the process with code 13.
  [Node modules; Node esm; `n/no-top-level-await`, `unicorn/prefer-top-level-await`]
- **Promise chains in new code.** Signal: `.then().then().catch()` where `await` would read
  better; nested `.then` inside `.then`; `return` inside `.finally`. [`promise/no-nesting`,
  `promise/no-return-in-finally`, `promise/prefer-await-to-then` (opt-in)]

### 3.5 Error handling

- **Throw `new Error` (or a subclass) and nothing else.** Signal: `throw 'string'`, `throw {
  code }`, `Error('x')` without `new`, `new Error()` without a message. [Google TS; Node best
  practices 2.2; `only-throw-error`, `unicorn/throw-new-error`, `unicorn/error-message`]
- **Subclass `Error` properly; carry `cause` and a stable `code`.** Signal: a custom error
  class that does not set `this.name`, or that stringifies the inner error into the message
  instead of `new AppError(msg, { cause })`; callers comparing `error.message` text. `cause`
  (ES2022) preserves the chain; Node's own guidance is to identify errors by `code`, since
  messages change. Distinguish operational errors (expected, handled, returned to the client
  as a safe message) from programmer errors (crash and restart). [Node errors; Node best
  practices 2.2, 2.3, 6.20; `unicorn/custom-error-definition` (opt-in)]
- **Catch narrowly; never swallow.** Signal: `catch {}` or `catch (e) { /* ignore */ }` with no
  comment; `catch (e) { console.log(e) }` then continue as if success; a catch that converts
  every error into a generic 500 and drops the original. Google: an empty catch needs a
  justifying comment; do not defensively handle non-`Error` values unless the API is known to
  throw them. [Google TS; `no-empty` (eslint), `unicorn/catch-error-name`]
- **Central handler, not per-route logging.** Signal: `logger.error` plus `res.status(500)`
  duplicated in every handler; decisions about crashing scattered. One error handler owns
  logging, metrics and the crash decision. [Node best practices 2.4]
- **`uncaughtException`/`unhandledRejection` handlers log and exit; they never resume.**
  Signal: `process.on('uncaughtException', log)` with no exit; a handler that swallows
  rejections to "keep the server up". The process is in an undefined state; do synchronous
  cleanup, set `process.exitCode`, let it die, and let the supervisor restart it. Prefer
  `uncaughtExceptionMonitor` for pure observation. [Node process; Node best practices 2.6,
  2.10, 6.23; OWASP Node]
- **`process.exit()` loses output and pending work.** Signal: `process.exit(1)` after a
  `console.error`/logger call in library or server code. Set `process.exitCode` and return, or
  throw. CLIs at the very end of `main` are the exception. [Node process; `n/no-process-exit`,
  `unicorn/no-process-exit`, `n/process-exit-as-throw`]
- **EventEmitters and streams need an `'error'` listener.** Signal: `new EventEmitter()`,
  `createReadStream`, `net.connect`, `child_process.spawn` with no `on('error')`; `async`
  listeners without `captureRejections`. An unlistened `'error'` throws asynchronously and
  `try/catch` cannot see it. [Node errors; Node events; Node best practices 2.13]
- **Error details stay out of responses.** Signal: `res.json({ error: err })`, `err.stack` or
  `err.message` of an unknown error sent to a client. Map operational errors to safe messages
  and codes; log the rest. [Node best practices 6.20; OWASP Node]
- **Result types vs throwing: pick one per boundary.** Signal: a function returning `{ ok,
  error }` in one place and throwing in another for the same failure class; `safeParse` result
  with `.data!`. Either is acceptable; mixing within one API is not. See section 6.
- **Validate at the edge, fail fast.** Signal: handler code reading `req.body.foo.bar` with no
  schema; config read from `process.env` without a parsed, typed object at startup. [Node best
  practices 2.11, 6.10; zod]
- **Test the error paths.** Signal: a PR adding error branches with no test that triggers them;
  `expect(...).rejects` without `await`. [Node best practices 2.8; vitest expect]

### 3.6 Modules, imports and ESM/CJS

- **Relative imports carry an extension under Node resolution.** Signal: `import './util'`
  with `module: nodenext` and ESM; `.js` (compiled) vs `.ts` (type stripping) must match how
  the code actually runs; mixing both in one package. Under `rewriteRelativeImportExtensions`
  `.ts` is rewritten to `.js` on emit; under `allowImportingTsExtensions` nothing is emitted.
  [TS modules reference; TS 5.7; Node esm; Node typescript; `import-x/extensions`,
  `n/file-extension-in-import`]
- **Type-only imports are marked.** Signal: `import { Foo } from './types.ts'` where `Foo` is
  an interface, in a repo without `verbatimModuleSyntax`/`isolatedModules`; `export { Type }`
  re-exports. Under type stripping an unmarked type import is a runtime `SyntaxError`; under
  bundlers it defeats single-file transpilation. Use `import type` or inline `type`, `export
  type`. If the compiler flag is on, skip this item. [Node typescript; TS 5.0; Google TS;
  `consistent-type-imports`, `consistent-type-exports`, `no-import-type-side-effects`]
- **No `require`, `module.exports`, `__dirname`, `__filename` in ESM.** Signal: any of them in
  a `"type": "module"` package or `.mts`. Use `import.meta.dirname`/`filename` (Node 20.11+),
  `new URL('./x', import.meta.url)`, `createRequire(import.meta.url)` when a CJS-only thing
  must be loaded. [Node esm; `no-require-imports`, `unicorn/prefer-module`, `n/no-path-concat`]
- **`node:` prefix for builtins.** Signal: `import fs from 'fs'`. Removes typosquat ambiguity
  and is required for some newer builtins. [Node best practices 6.27; `unicorn/prefer-node-
  protocol`, `n/prefer-node-protocol`]
- **Named exports; no default exports; no mutable exports; no barrel-of-everything.** Signal:
  `export default`, `export let`, a new `index.ts` re-exporting an entire directory. Defaults
  have no canonical name and resist refactoring; mutable exports break the module contract;
  barrels pull every module in unless `sideEffects` is declared and the consumer tree-shakes
  ESM. [Google TS; webpack tree-shaking; `import-x/no-default-export`,
  `import-x/no-mutable-exports`, `import-x/no-unused-modules`]
- **No import cycles; no self-import; no duplicate imports.** Signal: `a.ts` imports `b.ts`
  which imports `a.ts`; CJS cycles see half-initialised exports, ESM cycles see TDZ errors.
  [Node modules; `import-x/no-cycle`, `import-x/no-self-import`, `import-x/no-duplicates`]
- **Side-effect imports are deliberate and resolvable.** Signal: `import './polyfill'` with
  no comment, an unresolvable side-effect import (silently ignored before 5.6). Module-level
  code must not open connections or read the network. [TS 5.6 noUncheckedSideEffectImports;
  Node best practices 3.13]
- **CJS interop assumptions.** Signal: `import { named } from 'cjs-package'` relying on
  Node's best-effort cjs-module-lexer detection; `import x from 'esm-only'` from a CJS file;
  `require('esm-with-tla')`. Node 22.12+ can `require(esm)` only when the graph is synchronous;
  the default export lands on `.default` unless the module exports `'module.exports'`. [Node
  esm; Node modules; TS 5.8]
- **JSON imports use import attributes.** Signal: `import data from './x.json'` with no `with
  { type: 'json' }` under `nodenext`; `assert { type }` (removed). Named exports from JSON are
  gone. [TS 5.3, 5.7, 5.8; Node esm]
- **`paths` aliases are a bundler feature.** Signal: `@/foo` or `~/foo` imports in a package
  that runs under plain Node or is published. Node ignores tsconfig; use `package.json`
  `imports` (`#foo`) or relative paths. [Node typescript; TS modules reference]
- **Dynamic `import()` and `require(variable)`.** Signal: `import(userInput)`, `require(path)`
  with a non-literal. Arbitrary code load. [Node best practices 6.17;
  `security/detect-non-literal-require`]
- **Dependencies imported are declared.** Signal: an import of a package that is only a
  transitive dependency, or that sits in `devDependencies` but is imported from shipped code.
  [`import-x/no-extraneous-dependencies`, `n/no-extraneous-import`,
  `n/no-unpublished-import`]

### 3.7 Node runtime

- **No sync I/O or CPU-heavy work on the request path.** Signal: `fs.readFileSync`,
  `execSync`, `JSON.parse` of a large body, `crypto.pbkdf2Sync`, big sorts, `zlib.*Sync`
  inside a handler. The event loop is shared; one slow request stalls all. Move to
  `fs/promises`, worker threads, or the reverse proxy. Sync at startup is fine. [OWASP Node;
  Node best practices 5.3, 7.1; `n/no-sync` (opt-in), `n/prefer-promises/fs`]
- **Bounded request bodies and timeouts.** Signal: a body parser with no `limit`; an
  `http.createServer` without `requestTimeout`/`headersTimeout`; no per-request deadline on
  outbound calls. [Node security best practices; OWASP Node; Node best practices 6.14]
- **Streams: `pipeline`, not `.pipe()`; honour backpressure.** Signal: `a.pipe(b).pipe(c)`
  with no error wiring; a loop calling `writable.write()` ignoring its boolean; mixing
  `on('data')` with `pipe` or async iteration on one stream. `.pipe()` neither forwards errors
  nor destroys the chain; unchecked writes buffer until the process dies. [Node stream]
- **Listener hygiene.** Signal: `emitter.on(...)` inside a per-request handler with no
  `removeListener`/`once`/`signal`; `setMaxListeners(0)` to hide `MaxListenersExceededWarning`.
  [Node events; Node globals]
- **Timers.** Signal: hand-rolled `new Promise(r => setTimeout(r, ms))` (use
  `timers/promises`); a `setInterval` with no clear path; a background timer keeping a CLI
  alive (`unref()`); string arguments to `setTimeout`. [Node timers]
- **Shutdown.** Signal: a server with no `SIGTERM`/`SIGINT` handler, or a handler calling
  `process.exit()` immediately rather than closing the server and draining. Kubernetes sends
  SIGTERM; in-flight requests are dropped otherwise. [Node process; Node best practices 2.6]
- **Logging.** Signal: `console.log` in server code (no levels, no structure); logging whole
  request/error objects (secrets, PII); logging to a file or remote inside the app rather than
  stdout; no request id. [Node best practices 2.7, 5.14, 5.18; OWASP Node]
- **Configuration.** Signal: `process.env.X!` scattered through modules; secrets in code or
  committed `.env`; `NODE_ENV` unset in production images. Parse env once at startup into a
  typed, validated object. [Node best practices 5.15, 6.3; `n/no-process-env` (opt-in)]
- **Child processes.** Signal: `exec(\`cmd ${input}\`)`, `spawn(cmd, { shell: true })` with
  input, `maxBuffer` left at 1 MiB for large output. Use `execFile`/`spawn` with an args array.
  [Node child_process; Node best practices 6.19; `security/detect-child-process`]
- **Type stripping constraints (when the repo runs `node file.ts`).** Signal: `enum`,
  parameter properties, runtime `namespace`, decorators, `.tsx`, extensionless relative
  imports, `paths`, an unmarked type import, TS in `node_modules`. All fail at runtime.
  `erasableSyntaxOnly` + `verbatimModuleSyntax` in tsconfig make them compile errors. [Node
  typescript; TS 5.8]
- **Experimental and version-gated APIs.** Signal: `--experimental-*` flags in production
  scripts; `import.meta.dirname` on Node < 20.11; `Array.fromAsync`, Set methods, iterator
  helpers without checking `engines.node`. [Node security best practices;
  `n/no-unsupported-features/node-builtins`, `n/no-unsupported-features/es-syntax`,
  `n/no-deprecated-api`]
- **Memory.** Signal: an unbounded in-process `Map` cache; `Buffer.concat` of a whole upload;
  `--max-old-space-size` raised instead of a leak fixed. [Node best practices 5.10, 5.12]
- **Hidden event-loop blockers in utilities.** Signal: `await` inside a hot `for` over a huge
  array with no yielding; synchronous `JSON.stringify` of large graphs for every log line.
  [Node best practices 7.1]

### 3.8 Security

- **Validate every external input against a schema before use.** Signal: `req.body`,
  `req.query`, `req.params`, message payloads, env, file contents used without a parser;
  `as` casts standing in for validation. Allow-list shape and type. [OWASP Node; Node best
  practices 2.11, 6.10; zod]
- **No dynamic code.** Signal: `eval`, `new Function(str)`, `vm.runInContext` as a sandbox,
  string timers. [Node best practices 6.15, 6.18; `no-implied-eval`,
  `security/detect-eval-with-expression`]
- **Command injection.** Signal: `exec` with interpolation; `shell: true`. [Node
  child_process; Node best practices 6.19]
- **Path traversal.** Signal: `path.join(root, userPath)` then `fs.*` without checking the
  result stays under `root` (`path.resolve` + `startsWith(root + sep)`); `fs` calls with
  non-literal paths derived from input. [OWASP Node; Node best practices 6.17;
  `security/detect-non-literal-fs-filename`]
- **Prototype pollution.** Signal: recursive merge/`deepAssign`/`set(obj, path, v)` over
  untrusted JSON; `Object.assign(target, req.body)`; `obj[userKey] = v` on a plain object;
  lodash `merge`/`set` with input. Mitigate with `Map`, `Object.create(null)`,
  `Object.hasOwn`, key allow-lists, schema validation that rejects `__proto__`/`constructor`/
  `prototype`, and `--disable-proto=delete` as defence in depth. [OWASP prototype pollution;
  Node security best practices; `security/detect-object-injection` (noisy)]
- **ReDoS.** Signal: nested quantifiers, overlapping alternation, user input in `new RegExp`,
  regex validation of long untrusted strings on the request path. [Node best practices 6.16;
  OWASP Node; `security/detect-unsafe-regex`]
- **Header and redirect injection.** Signal: `res.setHeader(name, userValue)`, `res.redirect(
  req.query.next)` with no allow-list. [Node best practices 6.24; OWASP Node]
- **Secrets.** Signal: tokens/keys in source, in test fixtures, in logs, in error messages, in
  URLs; `.env` committed; secrets published (no `files` allow-list). [Node best practices 6.3,
  6.25; Node security best practices]
- **Timing-safe comparison of secrets.** Signal: `token === expected`, `hash == stored` for
  passwords/HMACs. Use `crypto.timingSafeEqual`, `scrypt`/`bcrypt` for passwords.
  [Node security best practices; Node best practices 6.8;
  `security/detect-possible-timing-attacks`]
- **Randomness.** Signal: `Math.random()` for tokens/IDs; `pseudoRandomBytes`. Use
  `crypto.randomUUID`/`randomBytes`. [`security/detect-pseudoRandomBytes`]
- **Query injection.** Signal: SQL built by template string or concatenation; Mongo filters
  built from raw objects (operator injection). Parameterise. [Node best practices 6.4]
- **Output escaping and headers.** Signal: user content into HTML templates unescaped;
  `innerHTML`; no `helmet`-style headers; cookies without `httpOnly`/`secure`/`sameSite`.
  [OWASP Node; Node best practices 6.6, 6.9, 6.22]
- **Supply chain.** Signal: a new dependency for a one-liner; `postinstall` scripts; a
  dependency pinned to `latest`/`*`/a git branch; lockfile not updated with `package.json`; a
  typo'd package name; `npm install` instead of `npm ci` in CI; no `npm audit`/Dependabot;
  `--ignore-scripts` absent in CI installs. [Node security best practices; Node best
  practices 5.4, 5.19, 6.7, 6.21, 6.26]
- **Running as root / in the inspector.** Signal: Dockerfile without `USER`; `--inspect` in a
  production start script. [Node best practices 6.13; Node security best practices]

### 3.9 API design and public types (libraries)

- **Explicit types on every export.** Signal: `export function f(x) { return ... }` with an
  inferred return type; `export const x = compute()`. Required by `isolatedDeclarations`;
  stabilises the `.d.ts`; stops an internal change from silently changing the public type.
  [TS 5.5; `explicit-module-boundary-types` (opt-in); Google TS (reviewer may require)]
- **No `any` or unexported types in the public surface.** Signal: a public function whose
  parameter or return type is `any`, or references a type that is not itself exported (consumers
  cannot name it). [Effective TS 67; Do's and Don'ts]
- **Accept `readonly`, return owned values.** Signal: `(items: T[])` on a function that does
  not mutate; returning an internal array/map by reference. Be liberal in what you accept,
  strict in what you produce. [Effective TS 14, 30]
- **Options object over positional parameters past two; no boolean flags.** Signal: `f(a, b,
  true, false)`, repeated parameters of the same type. [Effective TS 38; Google TS]
- **Discriminated result or throw; say which.** Signal: a library mixing thrown errors and
  `{ ok: false }` for the same failure class; thrown non-`Error`s; errors without a `code`.
  Document which errors a call can produce. [Node best practices 2.2, 2.5; Node errors]
- **`satisfies` for config tables; `as const` for literal sets.** Signal: `Record<string,
  Handler>` annotations that lose key autocomplete; a `string[]` where a literal union was
  intended. [TS 4.9; Total TypeScript satisfies]
- **Stable JSON shapes.** Signal: a serialised type that includes `Date`, `Map`, `bigint`,
  `undefined` fields, class instances; a wire type changed without a version. `JSON.stringify`
  drops `undefined`, turns `Date` into a string and throws on `bigint`.
- **Avoid default exports, `namespace`, `export =`, and runtime `enum` in a published API.**
  Signal: any of them. `export =`/`import =` are not erasable and tie consumers to CJS; a
  `const enum` cannot be consumed under `isolatedModules`. [Google TS; TS enums; TS 5.8]
- **Barrel files and `sideEffects`.** Signal: a single `index.ts` re-exporting everything with
  no `"sideEffects": false` (or a glob list) in `package.json`; a side-effectful module
  (polyfill, global registration) not listed. [webpack tree-shaking; publint
  `USE_SIDE_EFFECTS`]
- **Declaration merging and `declare module` augmentations are scoped and documented.**
  Signal: `declare global`, `declare module 'express'` adding fields; two interfaces with one
  name. Fine for typing globals or extending a framework's request type; a hazard when it
  leaks from a library into every consumer. [Total TypeScript four patterns; Effective TS 71;
  `no-unsafe-declaration-merging`]
- **Variance of exported generics.** Signal: a generic type used both as input and output
  without an `in out` annotation when assignability surprises appear; method-shorthand
  callbacks in public interfaces. [TS 4.7; Total TypeScript method shorthand]
- **Deprecate before removing.** Signal: a public symbol removed or re-typed in a minor
  release; no `@deprecated` TSDoc on the old one. [`no-deprecated` (strict); Effective TS 68]
- **Types for `this` and callbacks.** Signal: callbacks with optional parameters; `any`
  return on callbacks whose result is ignored (use `void`); a `this`-dependent callback with no
  `this` type. [Do's and Don'ts; Effective TS 69]

### 3.10 Tests

- **Names say unit, circumstance, expectation.** Signal: `it('works')`, `test('add')`.
  Arrange/Act/Assert visible. [Node best practices 4.2, 4.3]
- **The right equality matcher.** Signal: `toBe` on objects (reference equality fails or, worse,
  passes by accident on a shared reference); `toEqual` where `undefined` keys or class identity
  matter (`toStrictEqual`); `toBe` on floats (`toBeCloseTo`). [vitest expect]
- **Async assertions are awaited.** Signal: `expect(p).rejects.toThrow()` or `.resolves`
  without `await` (vitest marks the test failed at the end; in other runners it can pass
  vacuously); `toThrow` on a call rather than a function. Use `expect.assertions(n)` when an
  assertion lives in a callback. [vitest expect]
- **No `.only`/`.skip`/`.todo` left behind; no `allowOnly: true` in CI.** Signal: `test.only`,
  `describe.skip`, `it.todo` in a PR not marked WIP. Vitest fails on `.only` in CI by default
  (`allowOnly = !CI`); Node's runner needs `--test-only`. [vitest allowOnly; vitest features;
  Node test]
- **Mock what you own at the seam; prefer injection to `vi.mock`.** Signal: `vi.mock` of a
  module to stub one function, when a constructor or parameter could take the dependency;
  mocking the module under test's own internals (does not work: internal calls bypass the
  mock). `vi.mock` is hoisted above imports; factories cannot reference outer variables
  without `vi.hoisted`. [vitest mocking; vitest vi; Node best practices 4.10, 4.11]
- **Mocks reset between tests.** Signal: no `restoreMocks`/`mockReset`/`clearMocks` config
  and no `afterEach(vi.restoreAllMocks)`; `vi.setSystemTime`/`vi.stubEnv`/`vi.stubGlobal`
  with no unstub. `mockClear` keeps implementations; `mockReset` drops them; `mockRestore`
  also puts the original back for spies. Call arguments are stored by reference. [vitest mock;
  vitest mocking]
- **Fake timers, not real waits.** Signal: `await new Promise(r => setTimeout(r, 500))` or
  `setTimeout` in a test; `vi.useFakeTimers()` without `useRealTimers` in `afterEach`;
  `advanceTimersByTime` where promise-scheduled timers need `advanceTimersByTimeAsync`;
  relying on `process.nextTick` being faked (it is not by default). [vitest vi]
- **Flaky waits.** Signal: polling with arbitrary sleeps; `waitFor` with side effects inside
  or an empty callback; `expect.poll` on snapshots. Use `vi.waitFor`/`expect.poll` with a
  condition, or `events.once`. [vitest expect; Kent C. Dodds RTL mistakes]
- **Test isolation.** Signal: module-level mutable state shared across tests; tests that
  depend on order; a fixed port (use `0`); global seeds instead of per-test data;
  `--no-isolate`/`isolate: false` added for speed. [Node best practices 4.5, 4.12; vitest
  features]
- **Snapshots are deliberate.** Signal: large `toMatchSnapshot` on whole objects or DOM;
  snapshots updated wholesale in a PR with no review of the diff. Prefer explicit assertions or
  small inline snapshots. [vitest features]
- **No `any` or `!` to make a test compile; no `@ts-expect-error` typos.** Signal: `as any`
  on fixtures, `@ts-expect-error` on a line that would error for an unrelated reason. Google
  tolerates `@ts-expect-error` in tests only. For type-level behaviour use `expectTypeOf` in
  `*.test-d.ts` with `--typecheck` enabled, else the file never runs. [Google TS; vitest
  testing-types]
- **`expect(obj.method)` and `unbound-method`.** Signal: a lint-disable for `unbound-method` in
  tests. Use the test plugin's variant of the rule or scope the disable to test files.
  [unbound-method]
- **Coverage thresholds exist and are not lowered.** Signal: `coverage.thresholds` reduced,
  `autoUpdate` without review, an `exclude` added for the new code. [vitest coverage; Node
  best practices 4.7]
- **Frontend (RTL) specifics, only when present.** Signal: `getByTestId` where `getByRole`
  works; `container.querySelector`; `fireEvent` where `userEvent` fits; `waitFor(() =>
  getBy...)` instead of `findBy`; `query*` for presence; manual `cleanup`; `act` wrappers.
  [Kent C. Dodds RTL mistakes]
- **Error flows tested, five outcomes considered.** Signal: new error branches untested; tests
  checking only the response and not state, outgoing calls, queued messages or observability.
  [Node best practices 2.8, 4.13]

### 3.11 Build, packaging and dependencies

- **`exports` is correct and complete.** Signal: `exports` added without every previously
  public subpath; `types` not first or `default` not last inside a condition; a `module`
  condition pointing at CJS; `import`/`require` conditions whose files are the wrong format;
  the root `types` field relied on when `exports` exists (ignored); `main` missing for old
  tooling. [Node packages; publint `EXPORTS_*`, `TYPES_NOT_EXPORTED`; TS 4.7]
- **One `.d.ts` per entrypoint and format.** Signal: a single `index.d.ts` shared by `import`
  and `require` conditions; `.d.ts` emitted as ESM but served to CJS. attw reports `FalseCJS`/
  `FalseESM`/`CJSResolvesToESM`. Run `publint` and `attw --pack` in CI. [attw; TS 4.7; choosing
  compiler options]
- **No dual emit from one compilation under `verbatimModuleSyntax`; prefer ESM-only or a
  bundler that checks both.** Signal: a `tsc` run producing both CJS and ESM from the same
  files; `exports` with both `import` and `require` and no dual-package-hazard thought
  (two module instances, two copies of state). [choosing compiler options; Node packages]
- **`files` allow-list and `npm pack --dry-run`.** Signal: no `files`, or `.npmignore` only;
  tests, `.env`, source maps or configs in the tarball; `src/` shipped by accident so consumers
  load `.ts` instead of `.d.ts` (hence `rootDir: src`, `outDir: dist`). [npm package.json; Node
  security best practices; publint `USE_FILES`, `FILE_NOT_PUBLISHED`]
- **Dependency sections.** Signal: `typescript`, `@types/*`, test and build tools in
  `dependencies`; a runtime import from a `devDependency`; an `@types/*` package your `.d.ts`
  exposes left in `devDependencies` (consumers need it: `dependencies` or `peerDependencies`);
  a framework the plugin extends in `dependencies` instead of `peerDependencies`. [Effective TS
  65, 66; TS publishing; npm package.json]
- **Lockfile and install.** Signal: `package.json` changed without the lockfile (or vice
  versa); `npm install` in CI (use `npm ci`, which fails on mismatch and never writes);
  `--legacy-peer-deps` added to make a conflict go away. [npm ci; Node best practices 5.4,
  5.19]
- **Version ranges.** Signal: `*`, `latest`, `>=`, git branches; apps may pin exactly or use
  `^` with a committed lockfile; libraries use ranges wide enough for consumers; `overrides`
  added without a comment saying which advisory or bug it works around and when to remove.
  [npm package.json; Node security best practices]
- **`engines.node` and `.nvmrc` agree with CI and the Dockerfile; LTS only.** Signal: a PR
  using a Node 22 API with `engines` still at `>=18`. `engines` is advisory unless
  `engine-strict`. [Node best practices 4.4, 5.17; npm package.json; publint `USE_ENGINES_NODE`]
- **`"type"` is set explicitly; `sideEffects` declared when bundler-consumed.** Signal: a
  package with no `type` (Node falls back to syntax detection with a cost). [Node packages;
  publint `USE_TYPE`, `USE_SIDE_EFFECTS`]
- **tsconfig `include`/`exclude` cover what ships and only that.** Signal: tests compiled into
  `dist`; `exclude` used instead of a separate tsconfig for tests; `skipLibCheck` turned on to
  hide an error in the repo's own `.d.ts`. [tsconfig; choosing compiler options]
- **Bundler settings match the package contract.** Signal: a bundled library externalising
  nothing (dependencies inlined), or inlining a `peerDependency`; `--dts` turned off so no
  types ship; shims for `__dirname`/`import.meta.url` in a package that claims pure ESM. Note
  tsup is unmaintained and points to tsdown. [tsup README; choosing compiler options]
- **Lifecycle scripts.** Signal: `postinstall` added (runs on every consumer's machine);
  `prepare` doing a full build on install; `prepublishOnly` missing when a build must precede
  publish. [npm package.json; Node security best practices]
- **Provenance and publishing.** Signal: a release workflow without `--provenance` /
  `id-token: write` or trusted publishing; `repository` not matching the source repo. [npm
  provenance]
- **Target and lib for the actual runtime.** Signal: `target: es5`/`es2015` in a Node 22 app
  (needless downlevel, broken `async` stack traces); `lib: ["dom"]` in a server package;
  `target` above the lowest supported Node for a library. [choosing compiler options; tsconfig]

## 4. Severity hints

**Blocking (likely wrong behaviour, security or a broken package).**
Floating or misused promises; `return promise` inside `try`/`finally`; async executor; unhandled
`'error'` on an emitter/stream or `.pipe()` without error wiring; `uncaughtException` handler that
resumes; `process.exit()` in library/server code; read-modify-write across `await`; `exec`
with input; `eval`/`new Function`; path traversal; prototype-pollution-prone merge of input;
secrets in code or logs; SQL/Mongo built from input; `JSON.parse`/`req.body` cast to a type
with no validation at a trust boundary; `as`/`!` that hides a real `undefined`; `switch` over a
union with no exhaustiveness and a silent default when new members were added in the same PR;
`enum`/parameter properties/unmarked type imports in a type-stripping repo; extensionless or
`.ts`/`.js`-mismatched relative imports under Node resolution; `exports` that drops a public
subpath or orders `types`/`default` wrongly; `.d.ts` format mismatch (attw `FalseCJS`/`FalseESM`);
runtime import of a `devDependency`; lockfile out of sync; `.only` in a test; `rejects`/`resolves`
not awaited; thresholds lowered.

**Should fix (correctness debt, maintainability, public-API hygiene).**
New `any`, `no-unsafe-*` sites, uncommented assertions; `Omit` on a union; `Record<string,T>`
indexing without a check; truthiness null checks on `0`/`''`-capable values; numeric or `const`
enums in new code; method-shorthand callbacks in public types; overload ordering; sequential
`await` in a loop over independent items (or unbounded `Promise.all`); missing `AbortSignal`
on outbound I/O; `new Promise` around promise-shaped APIs; non-`Error` throws/rejections; empty
catch without comment; per-request listeners without removal; sync fs on the request path;
`console.log` in server code; env read ad hoc; default exports, `export let`, new barrels
without `sideEffects`; import cycles; missing `node:` prefix; missing explicit types or
unexported types on a library's public surface; mutable inputs to pure functions; `vi.mock`
where injection would do; mocks not restored; real `setTimeout` in tests; snapshot sprawl;
`@types` in the wrong section; `engines` not matching used APIs; `postinstall` added.

**Nit (style, consistency, modern idiom).**
`interface` vs `type` consistency; `T[]` vs `Array<T>`; `for..of` over `forEach`; `??`/`?.`
over `||`/`&&` chains; `at()`; `prefer-const`; `readonly` on never-reassigned private fields;
braces on one-line `if`s; function declaration vs arrow for named functions; `Prettify` on hover
types; naming conventions; `satisfies` over an annotation that loses key inference; import
ordering; test naming; `expect.assertions` in simple async tests; `toStrictEqual` where
`toEqual` is already exact.

## 5. Already enforced by tooling

Skip the item when the repo has the tool on (section 2 tells you). Names are the rules that make
the review item redundant.

**Prettier or Biome formatter.** Everything about whitespace, quotes, semicolons, line length,
brace position, trailing commas, import blank lines (`import-x/newline-after-import`),
`numeric-separators-style`. Never comment on formatting in such a repo. [Biome; Node best
practices 3.1, 3.3, 3.4]

**`tsc --strict` (and friends).** Implicit `any` (`noImplicitAny`), `this: any`, null/undefined
flow (`strictNullChecks`), callback parameter variance for property-syntax functions
(`strictFunctionTypes`), `bind/call/apply` typing, uninitialised class fields, `catch (e)` as
`unknown` (`useUnknownInCatchVariables`), iterator `value` after `done`
(`strictBuiltinIteratorReturn`), `NaN` equality (4.9), always-truthy expressions (5.6),
never-assigned variables (5.7), regex literal syntax (5.5). With the extras: index access
optionals (`noUncheckedIndexedAccess`), `undefined` on `?:` (`exactOptionalPropertyTypes`),
`override` keyword (`noImplicitOverride`), dot access on index signatures
(`noPropertyAccessFromIndexSignature`), case fall-through (`noFallthroughCasesInSwitch`),
missing returns (`noImplicitReturns`), unused locals/params (`noUnused*`), type-only import
marking (`verbatimModuleSyntax`/`isolatedModules`), non-erasable syntax (`erasableSyntaxOnly`),
unresolvable side-effect imports (`noUncheckedSideEffectImports`), explicit export types
(`isolatedDeclarations`), extension-required relative imports and `exports` resolution
(`moduleResolution: nodenext`). [tsconfig; TS release notes]

**typescript-eslint `recommended` / `recommended-type-checked`.** `no-explicit-any`,
`no-unsafe-{assignment,argument,call,member-access,return}`, `no-floating-promises`,
`no-misused-promises`, `await-thenable`, `require-await`, `no-array-delete`,
`no-base-to-string`, `no-duplicate-enum-values`, `no-duplicate-type-constituents`,
`no-redundant-type-constituents`, `no-unsafe-enum-comparison`, `no-unsafe-declaration-merging`,
`no-unsafe-function-type`, `no-wrapper-object-types`, `no-empty-object-type`,
`no-unnecessary-type-assertion`, `no-unnecessary-type-constraint`, `no-non-null-asserted-
optional-chain`, `no-extra-non-null-assertion`, `no-namespace`, `no-require-imports`,
`no-this-alias`, `no-implied-eval`, `no-for-in-array`, `no-array-constructor`, `no-misused-new`,
`no-unused-vars`, `no-unused-expressions`, `only-throw-error`, `prefer-promise-reject-errors`,
`prefer-as-const`, `prefer-namespace-keyword`, `restrict-plus-operands`,
`restrict-template-expressions` (loose), `triple-slash-reference`, `unbound-method`,
`ban-ts-comment`, `no-unsafe-unary-minus`. [typescript-eslint rules table; configs]

**typescript-eslint `strict` / `strict-type-checked` adds.** `no-non-null-assertion`,
`no-unnecessary-condition`, `no-unnecessary-type-parameters`, `no-unnecessary-type-arguments`,
`no-unnecessary-boolean-literal-compare`, `no-unnecessary-template-expression`,
`no-unnecessary-type-conversion`, `no-confusing-void-expression`, `no-deprecated`,
`no-dynamic-delete`, `no-extraneous-class`, `no-invalid-void-type`,
`no-meaningless-void-operator`, `no-mixed-enums`, `no-misused-spread`,
`no-unsafe-enum-assignment`, `no-useless-constructor`, `no-useless-default-assignment`,
`no-non-null-asserted-nullish-coalescing`, `prefer-literal-enum-member`,
`prefer-reduce-type-parameter`, `prefer-return-this-type`, `related-getter-setter-pairs`,
`return-await` (`error-handling-correctness-only`), `unified-signatures`,
`use-unknown-in-catch-callback-variable`, `restrict-template-expressions` (strict allow-list).
[typescript-eslint rules table]

**typescript-eslint `stylistic` / `stylistic-type-checked`.** `array-type`,
`consistent-type-definitions`, `consistent-indexed-object-style`, `consistent-type-assertions`,
`consistent-generic-constructors`, `prefer-for-of`, `prefer-function-type`, `prefer-includes`,
`prefer-string-starts-ends-with`, `prefer-nullish-coalescing`, `prefer-optional-chain`,
`prefer-find`, `prefer-regexp-exec`, `no-inferrable-types`, `no-empty-function`,
`no-confusing-non-null-assertion`, `non-nullable-type-assertion-style`, `dot-notation`,
`adjacent-overload-signatures`, `class-literal-property-style`, `ban-tslint-comment`.

**Not in any typescript-eslint preset (review by hand unless the config enables them).**
`switch-exhaustiveness-check`, `consistent-type-imports`, `consistent-type-exports`,
`no-import-type-side-effects`, `explicit-module-boundary-types`, `explicit-function-return-type`,
`prefer-readonly`, `prefer-readonly-parameter-types`, `method-signature-style`,
`naming-convention`, `promise-function-async`, `no-unsafe-type-assertion`,
`strict-boolean-expressions`, `prefer-enum-initializers`, `require-array-sort-compare`,
`no-unnecessary-qualifier`, `member-ordering`, `parameter-properties`, `max-params`.

**ESLint core `recommended`.** `no-async-promise-executor`, `no-constant-binary-expression`,
`no-debugger`, `no-dupe-*`, `no-empty` (allows a comment), `no-fallthrough`, `no-unused-vars`,
`no-useless-catch`, `no-self-assign`, `no-unsafe-finally`. Not in recommended: `eqeqeq`,
`prefer-const`, `no-var`, `no-param-reassign`, `no-await-in-loop`, `no-promise-executor-return`,
`require-atomic-updates`, `no-eval`, `no-extend-native`, `default-case`, `no-shadow`.

**Plugins.** `import-x/recommended`: `no-unresolved`, `named`, `namespace`, `no-named-as-default*`,
`no-duplicates` (not `no-cycle`, `no-extraneous-dependencies`, `no-default-export`, `extensions`,
`no-unused-modules`, `no-mutable-exports`). `n/recommended`: `no-missing-import`,
`no-extraneous-import`, `no-unpublished-import`, `no-unsupported-features/*`, `no-deprecated-api`,
`no-process-exit`, `process-exit-as-throw`, `hashbang` (not `no-sync`, `prefer-node-protocol`,
`no-path-concat`, `prefer-promises/fs`, `no-top-level-await`). `promise/recommended`:
`catch-or-return`, `always-return`, `no-nesting`, `no-return-wrap`, `no-return-in-finally`,
`param-names`, `valid-params`, `no-new-statics`, `avoid-new`, `no-promise-in-callback`,
`no-callback-in-promise` (not `prefer-await-to-then`, `no-multiple-resolved`).
`unicorn/recommended`: `no-for-each`, `prefer-node-protocol`, `no-null`, `prefer-top-level-await`,
`no-process-exit`, `error-message`, `throw-new-error`, `catch-error-name`, `no-array-reduce`,
`no-useless-undefined`, `no-await-expression-member`, `prefer-module`,
`no-abusive-eslint-disable`, `no-instanceof-builtins`, `no-thenable`, `no-new-array`,
`prefer-native-coercion-functions`, `no-unnecessary-await`, `prefer-structured-clone` and most
others (not `custom-error-definition`). `security/recommended`: all `detect-*` except
`detect-bidi-characters` (warn); every hit needs human triage, so treat it as a prompt, not a
verdict. [plugin READMEs]

**Biome.** `recommended` rules per group (correctness, suspicious, style, complexity, security,
performance); `noFloatingPromises`/`noImportCycles`/`noUnresolvedImports` only when the project
Scanner is enabled. Biome has no type-aware `no-unsafe-*` family, so `any` leakage still needs
a reviewer. [Biome]

**publint / attw / `tsc --noEmit` in CI.** When present, skip the `exports` ordering, format
mismatch, `files`, `type`, `engines`, `sideEffects` and declaration-resolution items. [publint;
attw]

## 6. Open questions and disagreements

- **`interface` vs `type` for object shapes.** Google and the TypeScript team: `interface`.
  Total TypeScript: `type` by default, `interface` for `extends`. Arguments: interface merging
  surprises and implicit index signatures (for `type`) vs caching and `extends` performance
  (for `interface`). Decide per repo and enforce with `consistent-type-definitions`; a reviewer
  should only flag inconsistency.
- **Enums.** Google allows plain `enum` (bans `const enum`); Total TypeScript and the TS
  handbook's "Objects vs Enums" lean to `as const` objects and literal unions; Node type
  stripping makes every `enum` a runtime error. Treat `enum` as "needs justification" in new
  code, string-valued if accepted, and as "blocking" under type stripping.
- **Default exports.** Google bans them; `import-x/prefer-default-export` exists for the
  opposite camp; some frameworks (Next.js pages, Vite plugins) require them. Rule: named exports
  unless the framework dictates otherwise.
- **Throw vs result types.** Node best practices and the TS ecosystem throw `Error` subclasses
  and catch centrally; neverthrow/Effect-style code returns discriminated results and never
  throws for expected failures; zod exposes both (`parse`/`safeParse`). No consensus. Review
  for consistency within a boundary and for `Error` subclasses when throwing.
- **`return await` outside error handling.** typescript-eslint `in-try-catch` says no; Node
  best practices 2.12 says always (stack traces); the strict preset enforces only the
  error-handling cases. Do not flag either style outside `try`/`finally`/`using`.
- **`noUncheckedIndexedAccess`.** typescript-eslint calls it "unwieldy in real projects"; TS
  5.9's `tsc --init` turns it on by default. Expect friction either way; if it is off, review
  indexed access by hand.
- **`#private` vs `private`.** Google bans `#private` (downlevel and tooling reasons); it is the
  only real runtime privacy and works under type stripping, while parameter properties do not.
  Follow the repo; prefer `#private` or explicit fields in type-stripping repos.
- **Explicit return types.** Google leaves it to the author and reviewer; `isolatedDeclarations`
  and library hygiene require them on exports; `explicit-function-return-type` on everything is
  widely considered noise. Rule: exports yes, internals optional.
- **`no-await-in-loop`.** Right for independent I/O, wrong for rate limits, retries, dependent
  steps and bounded resources. Ask which case it is before commenting.
- **`prefer-readonly-parameter-types`.** Sound in principle, very noisy in practice (every
  callback and class instance). Few repos run it; flag `readonly` only on obvious pure
  functions over arrays/objects.
- **Dual CJS/ESM packages.** The TS team says a single `tsc` cannot check both outputs and
  points at the dual package hazard; much of the ecosystem still ships dual via bundlers. Node
  22's `require(esm)` makes ESM-only viable for Node 20.19+/22.12+ consumers. Flag dual
  packaging as a design question, not a defect, and insist on attw.
- **`vi.mock` vs dependency injection.** Vitest documents module mocking fully; the Node
  best-practices and testing-pyramid camp prefer injecting dependencies and mocking only at
  the network edge. Both are defensible; module mocks of the unit's own neighbours are the
  smell.
- **`security/detect-object-injection` and friends.** eslint-plugin-security's own README says
  the output "needs triage by a human"; treat its hits as questions.
- **`using` declarations and decorators.** Standardised in TS 5.0/5.2 but Node support and
  type-stripping support lag (decorators are a parse error under stripping). Whether to allow
  them is a runtime question; check `engines.node` and the loader first.
- **tsup vs tsdown vs plain `tsc`.** tsup is no longer maintained and recommends tsdown; the TS
  team's advice is not to bundle libraries at all. New library PRs choosing a bundler deserve a
  "why" comment.
