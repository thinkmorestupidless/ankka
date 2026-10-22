# Quickstart: proving command line plugins

How to see the feature work and how to know it is finished. Paths are from the repository root.
Every command here isolates the CLI's files with `ANKKA_CONFIG`, never the developer's `~/.ankka`.

## Prerequisites

- JDK 21 and sbt (`sbt cli/stage` builds `cli/target/universal/stage/bin/ankka`).
- A shell. The stubs below are `/bin/sh` scripts.
- Optional, for the native check: a GraalVM with `native-image` (`GRAALVM_HOME`).

## 1. A plugin runs with the rest of the command line

```bash
sbt cli/stage
work=$(mktemp -d); mkdir "$work/bin"
cat > "$work/bin/ankka-echo" <<'SH'
#!/bin/sh
echo "args: $*"
env | grep '^ANKKA_' | sort
exit "${ECHO_EXIT:-0}"
SH
chmod +x "$work/bin/ankka-echo"
export PATH="$work/bin:$PATH" ANKKA_CONFIG="$work/config.json"
ankka=cli/target/universal/stage/bin/ankka

$ankka echo a b -o json --help      # prints "args: a b -o json --help" and ANKKA_URL, ANKKA_CONFIG
ECHO_EXIT=3 $ankka echo; echo "exit $?"     # exit 3
```

Expected: the arguments arrive untouched, `ANKKA_URL=http://localhost:9000` and `ANKKA_CONFIG` are
set, `ANKKA_TOKEN` and `ANKKA_PROJECT` are absent, and the exit code is the stub's.

## 2. The settings reach it

```bash
$ankka config set url https://cp.example.test
$ankka config set project p-1
$ankka echo                        # ANKKA_URL=https://cp.example.test, ANKKA_PROJECT=p-1, no token
ANKKA_TOKEN=t-1 $ankka echo        # ANKKA_TOKEN=t-1, and `ankka echo` prints it only because the stub does
```

A saved login and its refresh are exercised by `PluginSuite` against the scripted issuer; see step 5.

## 3. What is not run

```bash
$ankka reports; echo "exit $?"         # 2, "'reports' is not an ankka command, and no program 'ankka-reports' is on the path"
$ankka ../echo; echo "exit $?"         # 2, "a plugin is named by a word, not a path"
$ankka Echo; echo "exit $?"            # 2, even on macOS: names are exact
cp "$work/bin/ankka-echo" "$work/bin/ankka-services"
$ankka services list; echo "exit $?"   # the built-in answers (fails to reach a control plane: exit 1), never the stub
```

The empty-PATH message cannot be shown with the staged launcher, which is a shell script that needs
`java` on the PATH itself; with the native binary (step 7) it is
`PATH= cli/target/graalvm-native-image/ankka echo; echo "exit $?"`, exit 2 and `PATH is empty`.
`PluginSuite` covers it in-process either way.

## 4. Seeing what would run

```bash
$ankka plugin list
$ankka plugin list -o json
$ankka --help 2>&1 | tail -3           # ends with "Plugins ..." and "echo"; "services" is not listed
```

The help, and the plugin lines after it, are on standard error with exit code 2, as the CLI's help
has always been; hence the `2>&1`.

Expected: `echo` runs, `services` is shadowed by the built-in command. Make a second
`ankka-echo` in another directory later on the PATH and it is listed as hidden by the first; remove
the executable bit from one and it is listed as not executable.

## 5. Ctrl-c and signals

```bash
cat > "$work/bin/ankka-sleep" <<'SH'
#!/bin/sh
trap 'echo "plugin: interrupted"; exit 7' INT
sleep 30
SH
chmod +x "$work/bin/ankka-sleep"
$ankka sleep        # press ctrl-c: "plugin: interrupted", exit 7, and the prompt returns after it, not before
```

## 6. The tests that must be green

```bash
sbt 'cli/testOnly *PluginSuite'          # every scenario of features/command-line/plugins.feature, by name
sbt 'cli/testOnly *CliReferenceSuite'    # the reference carries `ankka plugin list` and no plugin names
sbt cli/test                             # nothing else in the CLI changed its behaviour
just features                            # the glossary, the feature and the spec agree
just docs                                # the reference page checks and builds
```

After adding the `plugin` command, regenerate the reference and commit the page:

```bash
sbt -Dankka.docs.update=true 'cli/testOnly *CliReferenceSuite'    # or: just docs-reference
just docs-sync
```

## 7. The native build

```bash
GRAALVM_HOME=... sbt cli/GraalVMNativeImage/packageBin
cli/native-smoke.sh cli/target/graalvm-native-image/ankka      # includes the new plugin step
```

The smoke script puts a stub `ankka-smoke` on a temporary PATH, runs `ankka smoke hello`, compares
the output and exit code, and asks `ankka plugin list` for it.

## Done when

- Steps 1 to 5 behave as written on macOS and Linux.
- `PluginSuite` has a test named for each scenario in `features/command-line/plugins.feature`, and
  each fails with the dispatch removed.
- `docs/reference/cli.md` has the Plugins section and the regenerated block;
  `docs/reference/limitations.md` says `ankka mcp` serves no plugin.
- `cli/native-smoke.sh` runs the plugin step.
