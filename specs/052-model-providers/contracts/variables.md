# Contract: the sidecar's model variables

What a process-hosted service's descriptor may give, what each means, and every refusal. Every
name here sits under a prefix `PlatformVariables` routes to the platform's container only
(`ANTHROPIC_`, `OPENAI_`, `ANKKA_MODEL_`), so none reaches the process and a module's `config`
answers each absent.

## The family

For a provider named `<name>` (lowercase letters and digits, starting with a letter; `<NAME>` is
the name upper-cased):

| Variable | Value | Required |
|---|---|---|
| `ANKKA_MODEL_<NAME>_PROTOCOL` | `anthropic` or `openai` | unless a preset supplies it |
| `ANKKA_MODEL_<NAME>_URL` | an absolute `http` or `https` address | unless a preset supplies it |
| `ANKKA_MODEL_<NAME>_MODEL` | the model's name at that address | unless a preset supplies a default |
| `ANKKA_MODEL_<NAME>_KEY` | the key; empty is unset | when the preset needs one; otherwise optional |
| `ANKKA_MODEL_<NAME>_SHAPE` | `responses` or `chat-completions` (OpenAI protocol only) | no; the preset's, else `chat-completions` |
| `ANKKA_MODEL_<NAME>_PRESET` | a preset's name (contracts/presets.md) | no |

A value given beside a preset overrides what the preset set.

## Shorthands

| Variable | Equivalent to |
|---|---|
| `ANTHROPIC_API_KEY=k` | `ANKKA_MODEL_ANTHROPIC_PRESET=anthropic` and `ANKKA_MODEL_ANTHROPIC_KEY=k` |
| `OPENAI_API_KEY=k` | `ANKKA_MODEL_OPENAI_PRESET=openai` and `ANKKA_MODEL_OPENAI_KEY=k` |
| `ANKKA_MODEL_NAME=m` | `ANKKA_MODEL_ANTHROPIC_MODEL=m` (the same setting) |
| `ANKKA_MODEL_SCRIPT=…` | the provider `scripted` (unchanged: JSON text or a file path) |

A family variable for `anthropic` or `openai` overrides the shorthand's preset value, as beside any
preset. An empty key is unset.

## The default

| Variable | Value |
|---|---|
| `ANKKA_MODEL_DEFAULT` | the name of a configured provider |

- Exactly one real provider configured: it is the default; the variable is unnecessary.
- More than one: the variable names the default. Unset, a plan or definition naming no model is
  refused at call time with the message below, and the service runs.
- `scripted` is never the default beside a real provider; alone, it is.

## Reserved and malformed names

Refused at start: `scripted`, `default`, `name`, `script` (each collides with a shorthand or the
script), and any name that is not `^[a-z][a-z0-9]*$` (`Fast`, `my_model`, `local-fast`, `2fast`).

## Refusals at the sidecar's start

Each is one line in the sidecar's log, the sidecar exits, and the service's detail carries it. The
wording is the contract the tests assert on; `<var>` is the variable's full name, `<v>` its value.

| Situation | Message |
|---|---|
| unknown `ANKKA_MODEL_*` variable | `<var>: not a model setting; a provider's settings are ANKKA_MODEL_<NAME>_{PROTOCOL,URL,MODEL,KEY,SHAPE,PRESET}` |
| reserved name | `<var>: '<name>' is reserved; a model provider may not be named scripted, default, name or script` |
| malformed name | `<var>: '<name>' is not a name a model provider may have: lowercase letters and digits, starting with a letter` |
| unknown protocol | `<var>: '<v>' is not a model protocol; use anthropic or openai` |
| unknown shape | `<var>: '<v>' is not a shape; use responses or chat-completions` |
| shape on the Anthropic protocol | `<var>: a shape applies to the openai protocol only` |
| unknown preset | `<var>: '<v>' is not a preset; the presets are <list>` |
| address not a URL | `<var>: '<v>' is not an http or https address` |
| no protocol and no preset | `model provider '<name>': a model protocol is missing; set ANKKA_MODEL_<NAME>_PROTOCOL or ANKKA_MODEL_<NAME>_PRESET` |
| no address and no preset address | `model provider '<name>': an address is missing; set ANKKA_MODEL_<NAME>_URL` |
| no model and no preset default | `model provider '<name>': a model is missing; set ANKKA_MODEL_<NAME>_MODEL` |
| preset needs a key, none given | `model provider '<name>': the preset '<p>' needs a key; set ANKKA_MODEL_<NAME>_KEY` |
| `ANKKA_MODEL_NAME` and `ANKKA_MODEL_ANTHROPIC_MODEL` differ | `ANKKA_MODEL_NAME and ANKKA_MODEL_ANTHROPIC_MODEL are one setting and differ: '<a>' and '<b>'` |
| `ANKKA_MODEL_DEFAULT` names no configured provider | `ANKKA_MODEL_DEFAULT: '<v>' is not a configured model provider; the providers are <list>` |
| `ANKKA_MODEL_SCRIPT` malformed | unchanged: `ANKKA_MODEL_SCRIPT: turn <i>: …` |

## Refusals at call time (the service runs)

| Situation | Code | Message |
|---|---|---|
| plan names a model that is not configured | Unavailable | unchanged: `the plan names model '<n>', which the sidecar has not configured` |
| plan names none; no provider at all | Unavailable | `the sidecar has no model: set ANTHROPIC_API_KEY or OPENAI_API_KEY, configure one as ANKKA_MODEL_<NAME>_*, or ANKKA_MODEL_SCRIPT for a test` |
| plan names none; several providers, no default | Unavailable | `the sidecar has <n> models and no default: set ANKKA_MODEL_DEFAULT to one of <list>` |
| an autonomous definition names none | the same two messages, as the task's failure | |

## A key's routing

`OPENAI_` joins `ANTHROPIC_` and `ANKKA_MODEL_` in `PlatformVariables.RuntimeOnlyPrefixes`. The
operator renders every such variable on the sidecar's container only; a module's `config` import
answers each absent; the control plane's descriptor reference lists the prefix. No preset adds a
variable outside `ANKKA_MODEL_<NAME>_KEY`.
