---
name: pr-review-python
description: The Python rules a code review checks after the general pr-review checklist: unsafe deserialisation, shell and SQL built from strings and the other stdlib security traps; exception handling (bare except, raise without from, assert as validation); asyncio and threading (dropped tasks, blocked loops, swallowed CancelledError); mutable defaults, late-binding closures and mutation during iteration; typing under mypy or pyright strict; logging, pytest and packaging hygiene. Use whenever a pull request, diff, branch or file under review contains Python (.py, .pyi, pyproject.toml, uv.lock, requirements.txt), whether or not pr-review loaded it, and whenever someone asks for feedback on, a check of, or a second opinion about Python code. It is not for writing Python or for fixing the code it reviews.
---

# Reviewing Python

Python reviews most often miss what the interpreter never reports at the line that is wrong: a
default argument shared across calls, a container mutated mid-iteration, a coroutine never
awaited, a dropped `create_task` result, a naive datetime beside an aware one, a patch applied
where the object is defined rather than looked up. Apply the groups in the order below: security
first, idioms last. Two idiom items, mutable defaults and late-binding closures, are blocking
despite where they sit; check them on every changed `def`.

## Before reading the code

Files: `.py`; `.pyi` stubs (only PYI rules, no runtime concerns); `.ipynb` (`print` and top-level
statements tolerated); `pyproject.toml`; lockfiles and `requirements*.txt`; `setup.py`; `py.typed`.

The Python version comes from three places, in order of authority; if they disagree, say so.

1. `[project] requires-python = ">=3.12"`: the floor the code must run on. An upper bound
   (`<3.14`) is itself a finding unless justified.
2. `.python-version`, the Dockerfile base image and the CI matrix: what actually runs and is
   tested. A 3.11 floor that CI never runs is an unverified claim.
3. `[tool.ruff] target-version`, `[tool.mypy] python_version`, `[tool.pyright] pythonVersion`;
   ruff infers `target-version` from `requires-python` when unset.

| Floor | Available, so its absence is reviewable | Not yet available, so do not request it |
|---|---|---|
| 3.10 | `X \| Y`, `match`, `zip(strict=True)`, `ParamSpec`, `TypeGuard`, `dataclass(slots=, kw_only=)`, parenthesised context managers | `Self`, `TaskGroup`, `asyncio.timeout`, `ExceptionGroup`/`except*`, `StrEnum`, `tomllib` |
| 3.11 | `Self`, `Never`, `TaskGroup`, `asyncio.timeout()`, `except*`, `StrEnum`, `tomllib`, `datetime.UTC`, `Required`/`NotRequired`, builtin `TimeoutError` is `asyncio.TimeoutError` | PEP 695 syntax, `@override`, `itertools.batched`, `Path.walk`, tarfile `filter=` |
| 3.12 | `type X = ...`, `def f[T]`, `@override`, PEP 701 f-strings, `batched`, `Path.walk`, tarfile `filter=` (still explicit); `distutils` gone; `utcnow()` deprecated | `@deprecated`, `TypeIs`, `ReadOnly`, type-parameter defaults, `copy.replace` |
| 3.13 | `warnings.deprecated`, `TypeIs`, `ReadOnly`, PEP 696 defaults, `copy.replace`; PEP 594 modules gone; experimental free-threaded build | deferred annotations, t-strings |
| 3.14 | lazy annotations and `annotationlib` (no quoting of forward refs); t-strings; `except A, B:`; `return` in `finally` warns; tarfile default `data`; `forkserver` default on Unix; free-threading supported | |

Tooling configuration to read before flagging anything:

- `[tool.ruff.lint] select`/`extend-select`/`ignore`/`per-file-ignores`/`preview`. `select`
  replaces the small default set (pyflakes plus a few pycodestyle rules); if `B`, `S`, `ASYNC`,
  `DTZ`, `G`, `TRY`, `PT`, `UP` are not named, those items are live. Preview-only rules (B909,
  RUF029, RUF056, RUF069) are live without `preview = true`. A new `# noqa` is itself reviewable.
- `[tool.mypy] strict = true` and `enable_error_code`; an override with `ignore_missing_imports`
  or `follow_imports = skip` means whole packages are `Any`. `[tool.pyright] typeCheckingMode`.
- `[tool.pytest.ini_options]`: `asyncio_mode`, `strict` (pytest 9) or `strict_markers` and
  `xfail_strict`, `filterwarnings = ["error"]`. `[tool.ruff.format]` or `[tool.black]`: never
  comment on layout, quotes, commas or line length.
- `.pre-commit-config.yaml`, CI workflows, `Justfile`: a ruff config CI does not run enforces nothing.

Already enforced: do not comment

| If the repository runs | then skip |
|---|---|
| ruff `B` | mutable and call defaults, loop closures, raise without from, `zip` strict, cached methods, jumps in `finally`, `raises(Exception)`, ABC shape, `stacklevel`, handler tuples, loop variable overriding the iterable |
| ruff `S` (or bandit directly, which adds B109, B111, B613–B615) | assert, exec/eval, bind-all, hard-coded passwords, `/tmp`, request timeout, debug, tarfile, pickle/marshal/yaml, `random`, xml, weak hashes, `verify=False`, subprocess and shell, SQL strings, templates |
| ruff `BLE`, `E722`, `TRY`, `EM`, `RET`, `G`, `LOG`, `T20`, `PLE1205`/`1206` | blind and bare except, raise/log/else/re-raise shape, inline messages, return consistency; f-strings in logging, root logger, `exc_info` misuse, `print`, format argument mismatch |
| ruff `ASYNC`, `RUF006`, `PLE1142`, `DTZ`, `UP017` | blocking calls in coroutines, timeout parameters, polling sleeps, dangling tasks, `await` outside async; naive datetimes, `utcnow`, `timezone.utc` |
| ruff `FBT`, `ARG`, `A`, `ERA`, `PTH`, `PLC0415`, `TID252`, `F`, `E7xx`, `SIM`, `C4`, `PERF`, `PIE`, `PLR`, `PLW`, `PLE` | boolean trap, unused arguments, builtin shadowing, commented-out code, `os.path`, function-level and relative imports, `is` with literals, `== None`, `type() ==`, `open` without `with`, `in d.keys()`, comprehension forms, complexity thresholds, `global`, `run` without `check`, `__eq__` without `__hash__`, loop rebinding, runtime-error shapes |
| ruff `RUF`, `UP`, `PT`, `N`, `D` with a `convention`, `ISC`, `INP`, `PGH`, `SLF`, `TC`, `C90`, `PYI` | mutable class attributes, implicit `Optional`, `sum(lists, [])`, `__all__` order, typing aliases, PEP 695, `StrEnum`, dead version blocks; pytest style, naming, docstring form, implicit concatenation, missing `__init__.py`, blanket ignores, private access, typing-only imports, complexity, stubs |
| the ruff formatter or black | all layout, line length, quote style, trailing commas, blank lines, parentheses |
| mypy `--strict` | untyped defs and calls, bare generics, subclassing `Any`, untyped decorators, redundant casts, unused ignores, `Any` returns, implicit re-export, non-overlapping comparisons. Still live: `warn_unreachable`, `possibly-undefined`, `truthy-bool`, `ignore-without-code`, `unused-awaitable`, `explicit-override`, `exhaustive-match`, `deprecated`, `explicit-any` |
| pyright `strict` | `reportUnknown*`, missing parameter types and type arguments, private usage, unnecessary isinstance/cast/comparison, non-exhaustive `match`, deprecated, unused imports and variables, untyped decorators; `reportUnusedCoroutine` in every mode. Still live: `reportImplicitOverride`, `reportMissingSuperCall`, `reportUnnecessaryTypeIgnoreComment`, `reportUnusedCallResult`, `reportImportCycles`, `reportUninitializedInstanceVariable`, `reportImplicitStringConcatenation`, `reportUnreachable` |
| pytest `strict`/`strict_markers`/`xfail_strict`; `filterwarnings = error` | unregistered marks, XPASS as failure; never-awaited coroutines and deprecations in tests |

## Checklist

### Security

- **No pickle, yaml.load, eval or exec on untrusted data.** Signal: `pickle.loads(`, `yaml.load(`
  or `Loader=yaml.Loader`, `eval(`, `exec(`, `logging.config.listen` fed from outside. Why: all run
  arbitrary code; use `json`, `yaml.safe_load`, `ast.literal_eval`. [pickle docs; S301, S506, S102, S307]
- **subprocess as a list, shell=False, check=, timeout=.** Signal: `shell=True` with an f-string,
  `os.system`, `Popen` without `communicate(timeout=)`, `run` without `check`. Why: under
  `shell=True` quoting every metacharacter is the caller's job. [subprocess docs; S602–S607, PLW1510]
- **SQL through parameters, never string formatting.** Signal: `execute(f"`, `%`, `.format` or `+`
  building a query. Why: injection; identifiers cannot be parameters, allow-list them. [sqlite3 docs; S608]
- **secrets, not random, for tokens; compare_digest for secrets.** Signal: `random.` producing a
  token, salt or nonce; `token == expected`. Why: predictable, and `==` leaks timing. [secrets docs; S311]
- **Passwords with Argon2id, scrypt or bcrypt, never sha256 or md5.** Signal: `hashlib.sha256(pass`;
  `md5(` without `usedforsecurity=False`. Why: fast hashes are unsuitable for passwords. [OWASP; S324]
- **tarfile.extractall(filter="data") below 3.14; zip-slip checks.** Signal: `extractall(` with no
  `filter=`; `zipfile` names not checked against the destination. Why: `..` is honoured. [tarfile; S202]
- **requests with timeout, with verification, without user URLs.** Signal: `requests.get(url)` with
  no `timeout=`; `verify=False`; a URL from input with no host allow-list. Why: requests never time
  out by default; MitM; SSRF. [requests docs; OWASP SSRF; S113, S501]
- **No secrets in code, defaults or logs; no mktemp or hard-coded /tmp.** Signal: `password="`,
  `api_key = "sk-`, a credential default argument, a connection string logged; `tempfile.mktemp`.
  Why: leaks; `mktemp` races. [OWASP Logging; tempfile docs; S105–S108, S306]
- **Resolve and check user-supplied paths.** Signal: `open(base / user_input)` without
  `.resolve()` and `.is_relative_to(base)`. Why: `..` escapes the base. [tarfile docs]

### Error handling and resources

- **No bare except; no except Exception: pass.** Signal: `except:`, `except Exception:` then `pass`,
  `except BaseException`. Why: bare except catches `SystemExit`, `KeyboardInterrupt`, `CancelledError`;
  fine only when logging the traceback at a boundary. [PEP 8; EP3 85, 86; E722, BLE001]
- **raise ... from err, or from None, inside except.** Signal: `raise X(` in an `except` body without
  `from`. Why: the implicit `__context__` prints a misleading "During handling of". [PEP 3134; B904]
- **Narrowest exception, smallest try, success path in else.** Signal: a `try` wrapping many lines
  so an unrelated `KeyError` lands in an I/O handler; code after the risky call inside the `try`.
  Why: the handler fires for failures it was not written for. [PEP 8; EP3 80, 83; TRY300, TRY301]
- **assert is not validation or access control.** Signal: `assert x is not None`, `assert isinstance(`
  on input, `assert user.is_admin`. Why: stripped under `-O`; raise. Tests excepted. [Google 2.4; S101]
- **with for every resource.** Signal: `f = open(`, a socket, `requests.Session()`, a cursor, a `Popen`
  or a tempfile without `with`; `open()` in a generator whose consumer may stop early. [PEP 8; SIM115]
- **No return, break or continue leaving a finally.** Signal: those keywords inside `finally:`.
  Why: the in-flight exception is silently discarded; a `SyntaxWarning` on 3.14. [PEP 765; B012]
- **A hierarchy, not raise Exception with long inline messages.** Signal: `raise Exception(`; the same
  f-string message repeated across a module; `class Timeout(Exception)`. Why: callers cannot
  distinguish; one `...Error` root per package. [EP3 121; TRY002, TRY003, EM101, N818]
- **No log-and-reraise at every level; no except E: raise.** Signal: `logger.error(e); raise` per
  frame; `except E:` followed only by `raise`. Why: one failure logged three times. [TRY201, TRY203, TRY400]
- **No sys.exit or print in library code; raise rather than return None.** Signal: `sys.exit(`
  outside the entry point; `print(` outside a CLI; a bare `return` beside `return value`; `None`
  meaning "not found" when `None` is also valid. [logging HOWTO; EP3 32; T201, RET501–RET503]

### Concurrency and async

- **Keep a reference to every create_task result.** Signal: `asyncio.create_task(` as a bare
  statement. Why: the loop keeps weak references only, so the task can be collected before it
  finishes; prefer `TaskGroup`, else a set with a done callback. [asyncio-task; RUF006]
- **TaskGroup (3.11) over bare gather; catch its ExceptionGroup with except\*.** Signal:
  `asyncio.gather(*` without `return_exceptions`, or with it and results never checked; `except
  ValueError:` around a `TaskGroup`. Why: siblings keep running orphaned; a group raises
  `ExceptionGroup`, so a plain handler never fires. [asyncio-task; anyio tasks]
- **Every coroutine awaited.** Signal: `some_async()` as a statement or assigned without `await`
  or `create_task`. Why: the runtime only warns, at GC. [asyncio-dev docs; pyright reportUnusedCoroutine]
- **No blocking calls inside async def.** Signal: `time.sleep`, `requests.`, `open().read()`,
  `subprocess.run`, a sync DB driver, a CPU-bound loop. Why: one second blocks every task; use
  `asyncio.to_thread` or async clients. [asyncio-dev; ASYNC210, ASYNC220–251]
- **Re-raise CancelledError.** Signal: `except asyncio.CancelledError:` that returns; `except
  BaseException:` or a bare except in a coroutine. Why: `TaskGroup` and `timeout()` misbehave when
  it is swallowed; a `BaseException` since 3.8. [asyncio-exceptions]
- **asyncio.timeout() (3.11) around the await; catch TimeoutError outside.** Signal: an
  `async def f(timeout: float)` re-implementing a deadline; `asyncio.TimeoutError`. Why: `wait_for`
  can overrun; the alias is deprecated. [asyncio-task docs; ASYNC109, UP041]
- **asyncio primitives stay on the loop thread.** Signal: an `asyncio.Lock`, `Future.set_result` or
  `asyncio.Queue` touched from a `threading.Thread`; `queue.Queue.get()` in a coroutine. Why: not
  thread-safe; use `call_soon_threadsafe` or `run_coroutine_threadsafe`. [asyncio-dev]
- **No polling loops; close async generators.** Signal: `while not done: await asyncio.sleep(0)`;
  `async for` with `break` and no `aclosing()`; one async generator shared by two tasks. Why: use
  an `Event` or `Queue`; shared generators raise. [asyncio-task; ASYNC110, ASYNC115, ASYNC119]
- **Threads lock explicitly; builtins are not atomic (3.13t, 3.14t).** Signal: `counter += 1`,
  check-then-act on a dict, `list.remove` from threads without a `threading.Lock`; `cached_property`
  assumed to run once (lock removed in 3.12); reliance on `fork` after threads (`forkserver` is the
  Unix default on 3.14). Why: per-object locks are weaker than the GIL. [PEP 703; free-threading HOWTO]

### Collections and iteration

- **Never mutate a container while iterating it.** Signal: `for k in d: del d[k]`, `xs.remove(x)`
  inside `for x in xs`. Why: `RuntimeError` for dicts and sets, skipped elements for lists. [EP3 22; B909]
- **[[]] * n and b = a alias.** Signal: `* n` on a list of mutables; `copy.copy` of a nested
  structure then mutation of the inner part. Why: one inner object shared. [Python FAQ; Hitchhiker's]
- **Generators are consumed once.** Signal: a generator, `map`/`zip`/`filter` result or file iterated
  twice, passed to `len()`, or a `groupby` group kept past the outer loop. Why: the second pass is
  empty. [EP3 21; B031]
- **.sort(), .reverse(), .append() and .update() return None.** Signal: `xs = xs.sort()`,
  `return items.sort()`. Why: `sorted()` returns the list; the methods do not. [EP3 101]
- **zip(strict=True) (3.10); enumerate over range(len()).** Signal: `zip(a, b)` where lengths must
  match; `for i in range(len(xs))`. Why: `zip` silently truncates. [EP3 17, 18; B905]
- **Right structure for membership; generators to any/all.** Signal: `x in big_list` inside a
  loop; `k in d.keys()`; `all([f(x) for x in xs])`. Why: O(n) lookups; the list form evaluates
  everything first. [Hitchhiker's; SIM118, C419, PLR6201]
- **No sum(lists, []), list(xs)[0], or hand-rolled pairwise and batched.** Signal: those shapes;
  `zip(xs, xs[1:])`; manual chunking. Why: quadratic or wasteful; `next(iter(xs))`, `pairwise`
  (3.10), `batched` (3.12). [RUF017, RUF015, RUF007]

### Nulls, truthiness and equality

- **is None, never == None; is only for singletons; no == True.** Signal: `== None`, `not x is None`,
  `x is 1`, `s is "abc"`, `== True`. Why: interning is an implementation accident. [PEP 8; E711, E712, F632]
- **Truthiness for emptiness; is None when None is distinct.** Signal: `len(xs) == 0`, `xs == []`;
  `if not x` where `x: int | None` and `0` is valid. Why: conflates `0`, `""`, `[]` with `None`. [PEP 8]
- **isinstance, not type(x) == T; isclose or approx for floats.** Signal: `type(x) ==`; `==` on
  floats, `assert result == 0.3`. Why: subclasses break; rounding. [PEP 8; pytest assert docs; E721]
- **__eq__ comes with __hash__.** Signal: a class, or `@dataclass(eq=True)` without `frozen`, used
  in a set or as a key. Why: defining `__eq__` sets `__hash__ = None`. [dataclasses docs; PLW1641]
- **Naive and aware datetimes never mix; no utcnow().** Signal: `datetime.now()` or `datetime(2024,
  1, 1)` beside aware values; `utcnow()`. Why: ordering raises, equality is silently `False`;
  `utcnow` is deprecated since 3.12, use `now(UTC)`. [datetime docs; DTZ001, DTZ005]
- **dict.get(k) or default when a falsy value is valid.** Signal: `cfg.get("retries") or 3`. Why:
  a stored `0` becomes `3`; use `get(k, 3)`. [RUF056 (preview); SIM910]
- **NotImplemented vs NotImplementedError; truthiness of plain objects and Iterables.** Signal:
  `raise NotImplemented`; `if some_obj:` on a class without `__bool__`/`__len__`; `if not items`
  where `items: Iterable`. Why: a `TypeError`, always true, always false. [F901; mypy truthy-bool]

### Classes, dataclasses and API design

- **Mutable class attributes are shared; dataclass mutable defaults need default_factory.**
  Signal: `items: list[int] = []` in a class body outside `ClassVar`; `field(default=[])`. Why:
  one object for every instance; a dataclass raises `ValueError`. [dataclasses docs; RUF012, RUF008]
- **Dataclass hygiene: frozen for value objects, slots on 3.10+, kw_only when wide.** Signal: a
  value-object dataclass without `frozen=True`; a defaulted field before an undefaulted one (also
  through inheritance); `init=False` fields expecting `__post_init__`. [dataclasses docs; EP3 51, 56]
- **super().__init__() in subclasses, from __post_init__ in dataclasses.** Signal: a subclass
  `__init__` without `super().__init__(`; a dataclass whose base has its own `__init__`. Why: the
  generated `__init__` skips the base; pyright's check is off even in strict. [EP3 53; PLE0101]
- **Properties cheap and side-effect free.** Signal: `@property` doing I/O, a network call or a
  mutation; a getter/setter pair wrapping a plain attribute. Why: attribute syntax promises cheap
  access. [PEP 8; Google 2.13; EP3 58, 59]
- **No lru_cache or cache on instance methods; cache is unbounded.** Signal: `@functools.cache` or
  `@lru_cache` above `def m(self`; `cache` on an impure function. Why: every `self` stays alive;
  use `cached_property` or a module function. [functools docs; B019]
- **Public surface deliberate: _private, __all__, no reaching through imports.** Signal: new public
  names without docstrings; `obj._x` from another module; a name imported via a module that merely
  imported it; `__all__` stale. Why: "if in doubt, choose non-public". [PEP 8; EP3 55, 119; SLF001]
- **StrEnum (3.11) and Enum over string constants; no duplicate values.** Signal: `STATUS_OPEN =
  "open"` used as a set; `class S(str, Enum)` on 3.11+; two members with one value (a silent
  alias). [UP042; PIE796]
- **__repr__ on domain classes.** Signal: a class with no `__repr__` that appears in logs or test
  failures. Why: `<Foo object at 0x...>` in every failure. [PEP 8; EP3 12]
- **Deprecate before removing: warnings.warn(..., stacklevel=2) or @deprecated (3.13).** Signal: a
  renamed public function with no shim or changelog entry; a changed default; `logger.warning(
  "deprecated")`. Why: `warnings` points at the caller and type checkers see it. [warnings docs; B028]

### Typing

- **Modern syntax on a 3.10+ floor; PEP 695 on 3.12+.** Signal: `List[`, `Dict[`, `Optional[`,
  `Union[`, `typing.Callable` in new code; `TypeVar(` plus `Generic[` or `TypeAlias` on a 3.12
  floor. Why: deprecated aliases; mixing old and new in one class is an error. [typing docs; UP006, UP040]
- **No bare generics; no Any where TypeVar or object fits.** Signal: `x: list`, `-> dict`, an
  unparametrised `Sequence`, `def f(x: Any)` for a pass-through value. Why: `Sequence[Any]` checks
  nothing. [Google typing; mypy type-arg]
- **Every type: ignore and cast carries a code or a reason.** Signal: `# type: ignore` without
  `[code]`; `# pyright: ignore` without a rule; `cast(` with no comment. Why: a blanket ignore
  hides every future error; `cast` checks nothing at runtime. [mypy ignore-without-code; PGH003]
- **Optional explicit, None last, None branch handled.** Signal: `def f(x: str = None)`;
  `X | None` whose `None` path is never handled; `None | X`. [RUF013, RUF036]
- **@override on overriding methods (3.12); @final where subclassing stops.** Signal: a subclass
  method replacing a base method without `@override`. Why: a base rename silently orphans it;
  neither checker requires it by default. [PEP 698; mypy explicit-override]
- **Self for fluent returns; TypeIs over TypeGuard (3.13) when both branches narrow.** Signal:
  `-> "Foo"` on a subclassable class; a new `-> TypeGuard[` whose `False` branch should also
  narrow. Why: `TypeGuard` narrows only the `True` branch. [typing docs Self, TypeIs]
- **match over a union or enum ends in case _: assert_never(x).** Signal: a `match` with no
  catch-all on a closed type. Why: no exhaustiveness check at runtime or in mypy by default. [typing docs]
- **TYPE_CHECKING imports never reach runtime; TypedDict uses NotRequired and ReadOnly (3.13).**
  Signal: a name imported under `if TYPE_CHECKING:` used in `get_type_hints`, a pydantic model or
  `cast`; `total=False` for one optional key. Why: `NameError` at runtime; `TypedDict` validates
  nothing. [typing docs; TC001–TC004]

### Logging and observability

- **Lazy %s formatting at the call site.** Signal: `logger.info(f"`, `"..." % x`, `"..." + x` inside
  a log call. Why: the merge happens only if the record passes the level; an f-string breaks
  aggregation by template. [logging docs; Google 3.10.1; G001–G004]
- **getLogger(__name__); never the root logger or print.** Signal: `logging.info(`,
  `logging.getLogger()` unnamed, `logging.Logger("x")`, `print(` in a library. [logging HOWTO; LOG001]
- **Libraries add only a NullHandler.** Signal: `basicConfig(`, a `StreamHandler` or `setLevel`
  in library code. Why: handler configuration is the application's prerogative. [logging HOWTO]
- **logger.exception in handlers; exc_info only inside except.** Signal: `logger.error(str(e))` in
  an `except`; `exc_info=True` outside one; `logger.exception(e)`. Why: the traceback is dropped,
  empty, or the message redundant. [TRY400, LOG004, TRY401]
- **No secrets or PII in log calls; structure through extra=.** Signal: a token, password,
  `Authorization` header, card number or full body interpolated; untrusted CR/LF reaching an
  unstructured log; `extra` keys clashing with `LogRecord` attributes. [OWASP Logging; G101]
- **Format arguments match; warn is warning.** Signal: `logger.info("%s %s", a)`; `logger.warn(`.
  Why: the mismatch raises at emission, on the error path in production. [PLE1205, PLE1206, G010]
- **stacklevel=2 in wrappers; no blocking handlers on the loop.** Signal: a helper wrapping
  `logger.*` without `stacklevel`; a `SocketHandler`/`HTTPHandler` in async code. Why: every
  record points at the wrapper; use `QueueHandler`. [logging docs; asyncio-dev docs]

### Tests

- **Plain assert; pytest.raises as a narrow context with match=.** Signal: `self.assertEqual`,
  `pytest.raises(Exc, fn, ...)`, `pytest.raises(Exception)`, no `match=` when the message is the
  contract, several statements inside the `with` (an earlier one may raise). [pytest; PT011, PT012]
- **Patch where the object is looked up.** Signal: `patch("requests.get")` when the module did
  `from requests import get`. Why: the test passes while the code is untested. [unittest.mock docs]
- **autospec=True or spec_set; assert_ prefixes spelt right.** Signal: `Mock()` without a spec;
  `mock.called_once_with(` without `assert_`; mocks three collaborators deep. Why: a renamed method
  keeps the test green; mocking everything tests the mocks. [unittest.mock docs; EP3 109; PGH005]
- **Higher-scope fixtures hold no mutable state; one state change per fixture.** Signal:
  `scope="session"` returning an object tests mutate; `autouse=True` with side effects; two
  changes behind one teardown. [pytest fixtures docs; PT003, PT019–PT022]
- **Async tests under a declared mode.** Signal: `async def test_` without `@pytest.mark.asyncio`
  in strict mode; an async fixture under `@pytest.fixture` instead of `@pytest_asyncio.fixture`.
  Why: silently skipped or never awaited; moot under `asyncio_mode = "auto"`. [pytest-asyncio; PT024]
- **xfail with reason= and strict=True; skip with reason=.** Signal: a bare `@pytest.mark.xfail`.
  Why: XPASS is not a failure unless strict. [pytest skipping docs]
- **Time, randomness and network controlled.** Signal: `datetime.now()` or `time.time()` compared
  in a test; unseeded `random`; a real HTTP call; hypothesis without `max_examples`. [EP3 112]
- **Assertions that can fail; tests the collector finds.** Signal: `assert True`, `assert "msg"`,
  `assert (a, b)`, `assert a and b`; a function not named `test_*`; a `Test*` class with `__init__`;
  an unregistered mark without `strict_markers`. [PT015–PT018; PLW0129; pytest goodpractices]
- **DeprecationWarnings visible in tests.** Signal: no `filterwarnings = ["error"]` while the diff
  adds `utcnow()`, an unfiltered `extractall()` or new coroutines. Why: ignored by default outside
  `__main__`. [warnings docs; pytest goodpractices]

### Packaging and dependencies

- **pyproject.toml with [build-system]; no new setup.py logic.** Signal: install-time logic in
  `setup.py`; metadata split across files; `distutils` (gone in 3.12). [packaging guide]
- **requires-python is a floor the code honours; CI, Dockerfile and .python-version agree.**
  Signal: missing; an upper bound `<3.13`; a 3.9 floor with `match` at runtime; a floor raised with
  no changelog note; a CI matrix without the floor. [packaging guide; UP036]
- **Libraries declare ranges and ship py.typed; applications commit a lockfile.** Signal:
  `requests==2.31.0` in a library's `dependencies`; a typed library with no `py.typed` in its
  package data; an application with no `uv.lock`, `poetry.lock` or `pylock.toml`; a lockfile edited
  by hand. [install-requires-vs-requirements; uv layout docs; PEP 561]
- **Lockfile and manifest change together.** Signal: `pyproject.toml` dependency edits with no
  `uv.lock` change, or the reverse; a regenerated `requirements.txt` full of unrelated churn. Why:
  `uv lock --check` in CI catches it. [uv layout docs]
- **Dev tools in [dependency-groups] or an extra; extras reference extras.** Signal: `pytest`,
  `ruff`, `mypy` in `dependencies`; an `all` extra copying lists. Why: drift. [packaging guide]
- **src layout for a new package; one source for the version; [project.scripts] for commands.**
  Signal: a flat layout whose tests import the working copy; `__version__` and `version =` both
  hand-edited; `python -m pkg.cli` instructions. [src-layout discussion; single-source-version]
- **No private indexes or credentials in metadata; no removed stdlib.** Signal: `--index-url` with
  credentials; `[[tool.uv.index]]` on an internal host in a public repo; a git+ssh dependency in a
  published library; new imports of `cgi`, `telnetlib`, `imp`, `pkg_resources`. [packaging guide; WN 3.12]

### Idioms and language features

- **No mutable default arguments.** Signal: `def f(x, items=[])`, `={}`, `=set()`, or a call
  `=SomeClass()`/`=time.time()`. Why: evaluated once and shared by every call; use `None` or `()`;
  blocking when mutated. [Python FAQ; Google 2.12; B006, B008]
- **Closures in loops bind late.** Signal: a `lambda` or nested `def` in a `for` reading the loop
  variable and called after the loop. Why: every callback sees the final value; bind with a default
  or `functools.partial`; blocking. [Python FAQ; B023]
- **Loop variables stay in the loop.** Signal: `i` read after `for i in items:`; `for x in x`; the
  loop name rebound in its body. [EP3 20; B020, PLW2901]
- **Keyword-only booleans; positional-only for implementation names.** Signal: `flag: bool = False`
  not behind `*`; a call `f(data, True, False)`. Why: readers cannot tell what `True` means; a
  reordering silently changes meaning. [EP3 37; Google 2.12; FBT001–FBT003]
- **No global for mutable state; no builtin or stdlib shadowing.** Signal: `global counter`; a
  module `_cache = {}` mutated from several functions; names `id`, `type`, `list`, `input`; a file
  `logging.py`, `types.py`, `test.py`. Why: the shadow bites far away. [Google 2.5; PLW0603, A001–A006]
- **No import *; imports at the top; an empty __init__.py.** Signal: `from x import *`; `import x`
  inside a function with no comment naming the cycle; an `__init__.py` reading config or opening
  connections. Why: a function-level import is the cycle's symptom. [Google 2.2; F403, PLC0415]
- **Work behind if __name__ == "__main__": main(); lambda never named.** Signal: top-level statements
  that do work; `f = lambda x:`; `map(lambda`. Why: modules must import cleanly; a named lambda
  loses its name in tracebacks. [Google 3.17, 2.10; E731, C417]
- **Watch the missing comma in adjacent literals.** Signal: `["a" "b", "c"]`. Why: silently joins;
  the formatter does not fix it. [EP3 13; ISC001]
- **On a 3.14 floor, from __future__ import annotations is a choice; t-strings are not str.**
  Signal: the import added or removed where code reads annotations at runtime; `t"..."` passed as
  a `str`; an f-string handed to a SQL, HTML or shell sink that offers a t-string API. [What's New 3.14]

## Severity

**Blocking.** Every Security item. Mutable default arguments (when mutated); closures in loops bind
late; never mutate a container while iterating; mutable class attributes shared. Keep a reference
to every create_task; every coroutine awaited; re-raise CancelledError; no blocking calls in async
def (hot path); TaskGroup over bare gather (exceptions not inspected). No bare except or
except-pass; raise from err (when the cause is needed); no return in finally; assert is not
validation. `__eq__` with `__hash__` on a key type; naive and aware never mix, no utcnow(). No
lru_cache on instance methods; cache on an impure function. Patch where looked up (a target that
does not affect the code); assertions that can fail; async tests under a declared mode. No sys.exit
or print in library code; libraries add only a NullHandler. Lockfile and manifest together;
libraries declare ranges; requires-python honoured.

**Should fix.** Broad `except Exception` away from a boundary; narrowest exception, smallest try,
else; no log-and-reraise; a hierarchy not raise Exception. is None not == None; isinstance not
type(); isclose for floats; truthiness vs is None. @override; type: ignore with a code; no Any or
bare generics; Optional explicit; py.typed; TypeIs over TypeGuard; modern syntax when the floor
allows. Lazy %s formatting; getLogger(__name__); logger.exception in handlers; deprecate through
warnings. Keyword-only booleans; properties cheap; dataclass hygiene; super().__init__(); __repr__.
Higher-scope fixtures immutable; xfail strict; pytest.raises narrow with match; autospec; time and
network controlled. zip strict; enumerate; [[]] * n; generators consumed once; .sort() returns
None; no sum(lists, []). Threads lock explicitly. src layout; dev tools out of dependencies; one
source for the version; no upper bound on requires-python.

**Nit.** A readable two-`for` comprehension; lambda vs `operator`; walrus taste; match vs if for a
small literal set; Literal vs Enum. Docstring mood when ruff `D` is off; `TODO` without a link;
`__all__` order; acronym case; enumerate in a three-line loop; pathlib vs os.path in consistent
os.path code; `from __future__ import annotations` on 3.14; relative imports used consistently.

## Where the sources disagree

- Ruff defaults, pyflakes only or "correctness and style categories": follow the repository's
  `select`; if none, assume nothing beyond pyflakes is enforced.
- Broad `except Exception` at a boundary or never: follow the repository's `BLE001` and `noqa`
  markers; if none, accept one that logs with `exception()` at a documented boundary.
- `gather(return_exceptions=True)` or `TaskGroup`: follow the repository; if none, prefer
  `TaskGroup` because siblings are cancelled, and accept `gather` when every result is inspected.
- Relative or absolute imports: follow the repository; if none, prefer absolute (`TID252` is opt-in).
- `os.path` or `pathlib`: follow the surrounding code; flag only a function that mixes both.
- Pydantic everywhere or at the boundary: follow the repository; flag only a hot internal path with
  no untrusted input, because re-validating trusted data costs.
- `@staticmethod` or a module function: a nit. Upper bounds on dependencies: ask why; do not block.
- Free-threading: thread-safety findings are correctness findings regardless of the build.
- tarfile on 3.12 and 3.13, warning or `fully_trusted`: the fix is the same, pass `filter="data"`.
- mypy or pyright exhaustiveness: a repo on mypy alone should still be asked for `assert_never`.
- No OWASP Python sheet exists: the security items rest on the stdlib security pages, bandit and
  the generic OWASP sheets; Semgrep's `python` ruleset is the usual stronger alternative.

## Reference

Open `references/python-review-research.md` for an item's full signal list, every source URL and
what was read from it, the complete tooling table with every ruff code by group, the full version
table, and the items left out here (XML, template autoescaping, attrs vs pydantic, decorator
typing, walrus and match style, docstrings, parametrize, the pytest fixtures). Its sections:
1. Sources; 2. Detecting the language and version; 3. Checklist (3.1 Idioms, 3.2 Typing, 3.3 Nulls,
truthiness and equality, 3.4 Collections and iteration, 3.5 Error handling and resources, 3.6
Concurrency and async, 3.7 Security, 3.8 Classes, dataclasses and API design, 3.9 Logging and
observability, 3.10 Tests, 3.11 Packaging and dependencies); 4. Severity hints; 5. Already enforced
by tooling; 6. Open questions / disagreements.
