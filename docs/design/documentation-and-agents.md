# Documentation and agents: a design treatment

**Status**: the documentation system and `ankka mcp` are built on branch `010-documentation`; a
service's own MCP endpoint is a treatment, to be fed to `speckit-specify` as the next feature.
**Date**: 2026-09-24

## The problem

ankka's user-facing documentation was a 1,000-line README and two walkthroughs, written for the people
building ankka as much as for the people using it. The audience that matters now is developers who
build, deploy and operate services on it — and the reader most likely to consume any given page is a
model: a coding agent writing a component, or an assistant answering a question about the platform.

Three things follow. The documentation must be public-facing and complete for that audience. It must
be consumable by people and models alike, without two sources that drift. And an agent should be able
to *act* on ankka — the platform, and the services running on it — not only read about it.

## One source, many renderings

**The source is plain Markdown with YAML frontmatter, in `docs/`, versioned with the code.** Plain,
because a page must read correctly with no renderer at all: on GitHub, in an editor, pasted into a
context window. No MDX, no tabs, no admonitions, no include syntax a renderer has to resolve. A page's
frontmatter carries a one-sentence `description`, a `kind` (tutorial, concept, guide, reference,
contributing), and the languages and component kinds it applies to — which is what a retriever needs
to choose pages before reading them.

**Every consumer is a renderer over that tree**, in `tools/docs` (a small `uv` project):

| Rendering | Consumer |
|---|---|
| MkDocs Material site | people |
| `<page>.md` beside every page | a model reading one page |
| `llms.txt` | a model choosing pages (the llms.txt convention) |
| `llms-full.txt` | a model that can take everything |
| `docs-index.json` | a retriever |
| Agent Skill (`SKILL.md` + `references/`) | Claude Code plugin; every project made from the template |
| MCP resources and `search_docs`/`read_doc` | any MCP client, through `ankka mcp` |

A future standard is one more renderer, not a migration. MkDocs is the choice for people because the
pipeline is Python and `uv` is already in the repository; the constraint that matters is only that the
source stays plain, so replacing the generator later is a change to `mkdocs.yml` and nothing else.

**Pages are written for retrieval.** A page stands alone: no positional references ("see above"), a
term used as the glossary defines it, each section opening with its answer, one topic per page. These
rules happen to make pages better for people too, which is why there is one set of them. `docs check`
enforces what can be enforced: frontmatter, the H1 matching the title, a language on every fence,
links and anchors, positional phrases, internal history (feature numbers, spec references) leaking
into public pages, and navigation coverage.

**What the code knows, the page does not restate by hand.** Samples are copied from sources the build
compiles and tests, marked with `docs:start`/`docs:end` regions and kept in step by `docs sync`; a
drifted copy fails `docs check`. The copy lives in the page, not resolved at render time, because a
model reading the raw Markdown must see the code. Reference tables are generated from the code between
`<!-- generated:start name -->` comments — configuration from HOCON, the protocol from `.proto`
files, and, because only the JVM can enumerate them, the CLI's commands and the control plane's routes
from Scala suites that fail on a stale page. Each generated table has hand-written prose beside it and
a coverage check that the prose mentions every fact, so a new variable or route fails the build until
someone has said what it does. Every `service.json` block in the documentation is decoded and
validated with the platform's own rules by `DocumentationDescriptorsSuite`.

**Where it lives.** In this repository, released with the tag, so the documentation of a runtime
version is that version's. The README became a landing page; `docs/polyglot.md`, `docs/orchestration.md`
and `sdks/python/README.md` were merged into the tree. `CLAUDE.md` stays contributor-facing, and its
user-facing traps were rewritten into the troubleshooting page. `docs/design/` (this file among them)
is excluded from every rendering: treatments name features and specs and are written for the people
building ankka.

## `ankka mcp`: the platform, for an agent

A Model Context Protocol server over stdio, as a CLI subcommand. It is one more thin client over the
same `ControlPlaneClient` the commands use — the argument that made the CLI thin makes this cheap —
and so it needs no new authentication: every tool call resolves settings and the saved login afresh,
exactly as a command would, and the control plane authorizes the token as it always does.

- **Control plane tools** map one to one onto the CLI's service verbs: list, get, history, logs,
  apply, expose, unexpose, pause, resume, restart, delete; plus whoami and the tenancy listings.
  Tenancy *administration* is deliberately absent — it is rare, it is a person's decision, and a tool
  that exists is a tool a model may choose. Each tool carries the protocol's annotations (read-only,
  destructive, idempotent) so a client knows what to ask a person about. `apply_service` validates a
  descriptor with the shared rules before sending anything.
- **Local tools** expose what the local console reads: the services running on this machine, their
  components and routes, traces, declared queries, agent sessions, and a call to an endpoint on the
  service's own port — as an ordinary client, so the endpoint's ACL applies. This is the
  run-and-observe half of the development loop, for an agent.
- **Documentation**: every public page is on the CLI's classpath (copied by a resource generator, with
  an index, since a jar directory cannot be listed), served as `ankka://docs/<path>` resources and
  through `search_docs` and `read_doc`. A model therefore reads the documentation matching the CLI it
  drives, offline.

The Claude Code plugin in `plugins/ankka` registers `ankka mcp` beside the skill, so installing the
plugin gives an agent both the knowledge and the hands.

**Later: a remote endpoint on the control plane.** A hosted agent with no CLI needs the same tools over
Streamable HTTP. That is the control plane serving `/mcp` behind `Acl.Authenticate`, and it waits on
one thing: the protocol's authorization expects the server to be an OAuth 2.1 resource server with
protected-resource metadata pointing at the authorization server. Keycloak can be that server; the
work is metadata, audience and dynamic or pre-registered clients, not tools.

## A service's own MCP endpoint (the next feature)

A running service should be able to offer its own functionality to agents — "add an item to cart c1",
"what is in cart c1" — without every team writing an MCP server by hand. Most of what that needs
already exists: the registry knows every component, every handler's wire name and whether it is
read-only; `/observability/service` renders exactly that inventory; the Python discovery `Spec`
carries it too, plus each agent tool's description and JSON Schema.

### Shape

**A `RuntimeExtension`, like `HttpServer`**, serving MCP over Streamable HTTP at `/mcp` on the service's
HTTP port — so it inherits the port, readiness, the route, TLS and exposure. Nothing new is deployed and
nothing new is exposed: an unexposed service's MCP endpoint is reachable in-cluster only, like its HTTP.

**Exposure is explicit and per handler.** Nothing becomes a tool by being registered: a handler is a
tool because its author said so, with a description written for a model. That is ankka's existing rule
(no scanning; registration is explicit) applied once more, and it is also the security boundary — not
every command should be reachable by a model, and the author is the one who knows which.

```scala
McpServer.of(
  McpTool.query(ShoppingCartEntity.getCart)
    .named("get_cart")
    .describedAs("What is in a shopping cart, by cart id."),
  McpTool.command(ShoppingCartEntity.addItem)
    .named("add_item")
    .describedAs("Add a quantity of a product to a cart.")
    .input[LineItem]                       // schema for the handler's argument
)
```

**Input schemas are the one real gap.** Scala handlers carry jsoniter codecs, not JSON Schema; agent
tools carry `SchemaType`, which emits a schema *and* decodes. The treatment is to reuse `SchemaType`
(moved, or its schema half, to a module both `agent` and the new extension can see) and derive a
`SchemaType` for case classes, so a command's input schema and its decoder come from one instance —
the same argument that made `FunctionTool` take one. Every tool's input is `{ "id": ..., "input": ... }`
for an entity handler, `{ "input": ... }` for a view query or an endpoint-free action. Python gets
schemas from its dataclasses, as its agent tools already do.

**Annotations come from the handler kind.** A `query` is `readOnlyHint: true` by construction — the
compiler already guarantees it cannot persist. A command is not read-only; whether it is destructive
is the author's declaration. An agent is exposed as a tool whose call is one turn in a session the
caller names, or as an MCP prompt.

**Authorization is the endpoint's `acl`**, exactly as for HTTP, because a service's endpoints already
declare who may call them and the MCP endpoint is one more endpoint. When services gain per-project
identity (the limitation already recorded: identity is for operating the platform, not yet for the
services it hosts), the MCP endpoint gains OAuth resource-server metadata with it; building a second,
MCP-only identity path first would be the wrong order.

**The operator and control plane learn one bit.** `services get` shows that a service serves MCP, the
same way it shows `exposed`, because an operator should see every door a service has. The descriptor
does not change: whether a service serves MCP is a fact of its image, reported like readiness.

### Polyglot

The sidecar serves `/mcp` for a process-hosted service. Discovery's `Spec` gains the exposure
declarations (tool name, description, input schema, handler reference); the sidecar translates a tool
call into the same `Invoke` it sends for an HTTP route. A protocol minor version, conformance cases for
the new discovery fields, and the Python SDK's `@mcp_tool` decorator on handlers.

### Open questions for clarify

1. Where `SchemaType` lives once two modules need it: `core` (no Pekko, already has jsoniter) is the
   obvious home; the question is whether `agent.SchemaType` stays as an alias for source compatibility.
2. Whether a new published module (`ankka-mcp`, a seventh library) or the `http` module hosts the
   extension. `http` avoids a new artifact but pulls schema derivation into it.
3. Session handling: MCP's `Mcp-Session-Id` is optional; a stateless server is simpler and fits a
   sharded service where any instance may answer. The treatment assumes stateless.
4. Whether an exposed agent should be a tool, a prompt, or both.
5. The reverse direction — an agent *inside* a service consuming tools from an external MCP server — is
   a separate feature and belongs beside this one on the roadmap.
