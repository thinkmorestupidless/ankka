# Feature Specification: Java and Kotlin SDKs

**Feature Branch**: `058-java-kotlin-sdk`

**Created**: 2026-10-23

**Status**: Draft

**Input**: User description: "Java and Kotlin SDKs for ankka. A developer writes an ankka service
in Java or in Kotlin and gets the same platform as a Scala developer, in the same process: no
sidecar, no protocol, the JVM runtime hosting the components directly. The Java API is a facade
over the Scala SDK (event sourced and key value entities, views and keyed views, consumers,
workflows, timers and timed actions, agents, HTTP endpoints, the component client and service
client, the secret store and object storage, the serializers), shaped for Java: records and sealed
interfaces, no Scala companions, givens, Option or Scala collections on the surface, wire names
declared separately from method names as in every ankka SDK, and a journal whose JSON for a given
shape matches the Scala SDK's so a Java service recovers what a Scala service wrote. Kotlin uses
the Java API and adds what idiomatic Kotlin expects (a template, null-safety, extension functions
where the Java form reads badly). Each language gets a test kit (component-level without a
runtime, and whole-service against a throwaway database), an `ankka init --language java|kotlin`
template with its own build tool, a CI suite that expands and tests the template, a first-service
docs page and SDK reference page, and a Claude Code skill. The shopping cart sample is written in
each language and holds the facade to the conformance expectations the other SDKs meet.
Divergences from Akka's Java SDK are listed on the divergences page."

## Context

ankka hosts a service in Scala in its own process, and a service in Python, TypeScript or Rust
behind the sidecar or as a module: the platform's own program runs beside or around the developer's
code and speaks a protocol to it. Every one of those SDKs is a protocol consumer, and the
conformance suite, the encoding fixtures and the reference services are what keep them compatible.

Java is the gap. Akka's own SDK is a Java SDK, so the people most likely to arrive knowing the
component model already write Java, and today the only in-process surface is Scala 3: companions,
givens, an effects DSL, `Option` and Scala collections, all of which a Java developer can call and
none of which they would want to. A sidecar for Java would be absurd, since the runtime is itself a
JVM. So this feature is the first SDK that is not a protocol consumer: a Java API over the Scala
SDK, hosted by the same runtime in the same process, with Kotlin on top of it. Nothing in the
runtime, the protocol or the platform changes; what a Java service writes to the journal is byte
for byte what a Scala service writes for the same shape, so the two can share a journal, a view
and a project.

Kotlin comes nearly for free once the Java API exists, which is why the two are one feature:
Kotlin calls Java APIs natively, and what it adds is a template, a sample, documentation, a skill
and the small layer that makes the Java shape read as Kotlin (nullability where Java has
`Optional`, extension functions where a Java builder reads badly).

The cost of a language was written down when the TypeScript SDK was added: every later change to
the component model touches one more SDK, one more test kit, one more template, one more skill. For
Java the cost is lower than for a protocol SDK, since the facade delegates and the runtime is
shared, and the audience on record (`ankka-brain/audiences.md`: "people who used Akka and hit the
licence") is the largest the family names. That is the reason on record.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - An entity and an endpoint in Java, in the same process (Priority: P1)

A Java developer writes an event sourced entity and an HTTP endpoint in Java, using the Java SDK.
They declare the entity's state and events as Java records and sealed interfaces, its command and
query handlers with their wire names declared separately from the method names, and how an event
folds into state. They register the components explicitly, start the service with the Java they
already have, and command the entity over HTTP. The service is one process: the runtime hosts the
entity directly, as it hosts a Scala one. The entity's state survives a restart, and its journal
records are the ones a Scala service would have written for the same state and events, so a Java
service recovers what a Scala service recorded and the other way round. A query handler cannot
persist: the compiler refuses it. Nothing on the surface the developer touches is a Scala type.

**Why this priority**: It is the whole feature in miniature. If one entity and one endpoint can be
written in Java with nothing Scala showing, commanded, recovered and read from the Scala journal,
the facade's shape is proven and every other kind follows the pattern.

**Independent Test**: Write the shopping cart's entity and endpoint in Java; start the service;
add items over HTTP; restart; read the cart back. Then start the Java cart on a journal the Scala
cart wrote and read the same cart.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/java-kotlin/embedded.feature`: a service written in Java or Kotlin is embedded, as a service written in Scala is
- changed `features/polyglot/entities.feature`: a refused command records nothing and its caller is told the refusal
- changed `features/polyglot/entities.feature`: a handler that fails records nothing and the service goes on serving
- changed `features/polyglot/entities.feature`: a restarted entity is recovered through the developer's code before its next command
- changed `features/polyglot/entities.feature`: a snapshot is the developer's code's own, and recovery reads only the events after it
- changed `features/polyglot/entities.feature`: a service written in one language recovers what a service written in another recorded
- changed `features/polyglot/entities.feature`: a query that would record an event is refused before the service is built
- changed `features/polyglot/components.feature`: a key value entity's state is kept and recovered after a restart
- added `features/java-kotlin/embedded.feature`: a handler's name is declared apart from the function that handles it
- added `features/java-kotlin/embedded.feature`: two handlers declared with one name fail the service at start
- added `features/java-kotlin/embedded.feature`: a component the service does not register fails at start, not at its first request
- added `features/java-kotlin/embedded.feature`: every type a Java developer writes, reads or is given is a Java type
- changed `features/polyglot/encoding.feature`: every fixture is read and written again exactly by an SDK
- added `features/java-kotlin/embedded.feature`: a state declared in Java and the same state declared in Scala are written the same
- added `features/java-kotlin/embedded.feature`: a field holding nothing is written as absent, and a field holding null is refused

---

### User Story 2 - Starting from the template, tested at both levels (Priority: P1)

A Java developer runs `ankka init cart --language java` and gets a project with its own build tool,
an entity, a view, an endpoint, tests at both levels, the compose file that starts Postgres, the
descriptor and the GitHub workflows, as the Python, TypeScript and Rust templates give. The
project builds and its tests pass with the version of ankka the CLI was built with. The tests use
the Java component test kit, which drives one component with no runtime and shows each effect as
values, and the Java test kit, which starts the whole service against a throwaway database and
can restart it to prove durability. Neither test kit requires a particular test framework, and the
template's tests use the one Java developers expect. Continuous integration expands the template
and runs its tests, as it does for the other languages' templates.

**Why this priority**: The first contact is the template or the first-service page. A facade with
no template is a library nobody can start from, and a template with no test kit teaches nothing.

**Independent Test**: Run `ankka init` for Java into an empty directory on a machine with a JDK and
Docker; build; run the tests; run the service; call it. Break one effect in the template's entity
and watch the component test fail.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- changed `features/polyglot/template.feature`: a service started from the template passes its tests with none left out
- added `features/java-kotlin/embedded.feature`: a service started from the template builds with a JDK and no build tool installed
- added `features/java-kotlin/embedded.feature`: a change to the template is proven on the template before it is merged
- changed `features/polyglot/testing.feature`: the component test kit shows a command's effect as values
- changed `features/polyglot/testing.feature`: a value the encoding cannot write fails in the component test kit
- changed `features/polyglot/testing.feature`: the component test kit follows a workflow to where it waits for a command
- changed `features/polyglot/testing.feature`: the test kit is ready before the test begins
- changed `features/polyglot/testing.feature`: the test kit restarts the platform's own program on the same database

---

### User Story 3 - Every component kind the Scala SDK offers (Priority: P2)

Beyond entities and endpoints, the Java developer writes views and keyed views with declared
queries, consumers that react to changes and publish to topics, workflows with steps, pauses,
timeouts, retries and compensation, timers and timed actions, and calls other components through
the component client and other services through the service client, all in Java with nothing Scala
on the surface. Every effect the Scala SDK offers for a kind is expressible in Java. Which kinds
this feature carries, and which wait for a later one, is settled by the first clarification below.

**Why this priority**: A platform that hosts entities in Java but workflows only in Scala forces a
Java team to keep a Scala module, which is the failure the sidecar SDKs were measured against.

**Independent Test**: Write the shopping cart sample in Java, every component of it, and run the
sample's own living features against it.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- changed `features/polyglot/components.feature`: a view's rows follow its source's events and are removed with its source
- changed `features/polyglot/components.feature`: a consumer is given each event at least once and in the order its entity recorded them
- changed `features/polyglot/components.feature`: a consumer publishes the messages its handler produces
- changed `features/polyglot/components.feature`: a timer set by a handler runs its timed action, and again after a failure
- changed `features/polyglot/components.feature`: a workflow's steps run in the developer's code and each step is recorded
- changed `features/polyglot/components.feature`: a step that fails is retried and then failed over as the workflow declared
- changed `features/polyglot/components.feature`: a command to a workflow during a step is answered from the state before the step
- changed `features/polyglot/components.feature`: a call through the SDK's client reaches its component wherever it runs
- changed `features/polyglot/components.feature`: a call made by a handler is nested under that handler in the trace
- added `features/java-kotlin/embedded.feature`: the shopping cart written in Java or Kotlin passes the sample's own features
- changed `features/polyglot/conformance.feature`: the conformance suite passes against the reference service in every language
- changed `features/polyglot/conformance.feature`: a behaviour broken in an SDK is named and no other

---

### User Story 4 - The same in Kotlin (Priority: P2)

A Kotlin developer runs `ankka init cart --language kotlin` and gets the same project shape with a
Kotlin build, then writes the same components in Kotlin against the Java API, with what Kotlin
expects on top: a value Java gives as `Optional` is nullable, a builder that reads badly from
Kotlin has an extension function, a data class is a state or an event as a Java record is. Nothing
a Kotlin developer must write is Java-flavoured in a way that Kotlin idiom would not. The
shopping cart sample is written in Kotlin too and passes the sample's features.

**Why this priority**: Kotlin is nearly free once Java exists, and leaving it out would hand
Kotlin developers a Java-shaped API with no word on how to hold it.

**Independent Test**: Run `ankka init` for Kotlin; build; test; run. Write the shopping cart in
Kotlin and run the sample's features against it.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/java-kotlin/kotlin.feature`: a value the Java SDK may give as nothing is nullable in Kotlin
- added `features/java-kotlin/kotlin.feature`: a null given where the Java SDK takes a value is refused before the service is built
- added `features/java-kotlin/kotlin.feature`: a data class is a state or an event as a Java record is
- added `features/java-kotlin/kotlin.feature`: a data class's default is taken for a field that is absent, and a field with no default that is absent fails recovery
- `features/polyglot/template.feature`: a service started from the template passes its tests with none left out
- `features/java-kotlin/embedded.feature`: the shopping cart written in Java or Kotlin passes the sample's own features

---

### User Story 5 - Agents in Java and Kotlin (Priority: P3)

A Java developer writes an agent with a system message, tools whose schema and argument decoder
come from one declaration, and a model from the installation's or a provider of their own; and an
autonomous agent with tasks, guardrails and result checks. The agent loop is the runtime's, as it
is for every language; the Java developer writes the decisions. A Kotlin developer does the same.

**Why this priority**: Agents are the platform's purpose, but the facade for them follows the one
for entities, and a team can prove the model with entities first.

**Independent Test**: Write the research digest sample's agent in Java against the scripted model
provider; run one turn with one tool.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- changed `features/polyglot/agents.feature`: a tool runs in the developer's code with the model's arguments
- changed `features/polyglot/agents.feature`: a guardrail is consulted in the developer's code
- changed `features/polyglot/agents.feature`: an agent's reply reaches its caller as a stream while the model produces it
- changed `features/polyglot/agents.feature`: a session survives a restart of the developer's code
- changed `features/polyglot/agents.feature`: a tool that fails is told to the model and the loop goes on

---

### User Story 6 - The documentation, the skills and the honest list (Priority: P2)

A Java developer's first contact is a first-service page or a coding agent holding an ankka skill.
The documentation gains a first-service page and an SDK reference page for Java and for Kotlin,
with samples included from tested code; every page, skill and tool description that names the
supported languages names the two new ones; a Java skill and a Kotlin skill exist beside the
Python, TypeScript and Rust ones, and every shared skill that has a differences section gains
one for Java and Kotlin. The divergences page says where ankka's Java differs from Akka's Java SDK
and why. The limitations page names what the Java and Kotlin SDKs do not yet do.

**Why this priority**: Nothing public may claim what the product cannot do, and a page out of
step with the code fails the docs build.

**Independent Test**: Build the docs; follow the first-service page from an empty directory; ask a
coding agent holding the Java skill to write an entity and check what it writes compiles.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/java-kotlin/embedded.feature`: the first service in Java or Kotlin needs a JDK and Docker and nothing else
- changed `features/polyglot/documentation.feature`: the documentation of a language states every kind of component with a tested sample
- changed `features/polyglot/documentation.feature`: a coding agent writing a component in a language is told how the language differs
- added `features/java-kotlin/embedded.feature`: every place that names the languages a service may be written in names Java and Kotlin
- added `features/java-kotlin/embedded.feature`: the documentation says where ankka's Java differs from Akka's
- added `features/java-kotlin/embedded.feature`: the limitations name what the Java and Kotlin SDKs do not offer

---

### Edge Cases

- A Java record field of `Optional` type: written as absent when empty, as the Scala `Option`
  is, so the fixtures pass; a `null` in a record field is refused when the value is written, naming
  the field, since the encoding has no form for it.
- A Java `List`, `Map` or `Set` in a state or event: written as the Scala collection of the same
  shape is; the fixtures hold both.
- A handler that throws a checked exception: the command fails as a thrown unchecked one does, and
  the developer is steered to returning an error effect, as in Scala.
- Two handlers on one entity declaring the same wire name: the service fails at start, naming
  both.
- A Java service and a Scala service in one project calling each other's entities: works unchanged,
  since both are the same runtime speaking to the same journal.
- A Kotlin data class with default arguments: its defaults apply when a field is absent in the
  journal, as a Scala default does; a non-nullable field absent in the journal fails recovery,
  naming the field.
- The template on a machine without the build tool installed: the template carries the build
  tool's wrapper and needs only a JDK; a JDK older than the platform's refuses the build, naming
  the version.
- A Kotlin developer passing `null` where the Java API takes an object: refused at compile time
  by the nullability the Kotlin layer declares, not at runtime.
- A Java developer calling a Scala SDK type directly: nothing stops them, and the Scala type is
  not documented for Java; the facade is the supported surface.
- A protocol or encoding change later: the Java and Kotlin SDKs are in-process, so there is no
  protocol version to refuse; the fixture suite is what catches an encoding drift, and it runs for
  Java as for every SDK.

## Requirements *(mandatory)*

### Functional Requirements

**The Java API**

- **FR-001**: The Java SDK MUST let a developer declare components of these kinds in Java with
  nothing on the surface that is a Scala type: event sourced entity, key value entity, HTTP
  endpoint, and [NEEDS CLARIFICATION: which further kinds this feature carries: (A) every kind the
  Scala SDK has, agents, gRPC, MCP, sockets, graph consumers, storage and secrets included, in one
  feature; (B) views, keyed views, consumers, workflows, timers and timed actions, the component
  and service clients, the secret store and object storage now, with agents, autonomous agents,
  gRPC endpoints, MCP endpoints, socket routes and graph consumers in a follow-on spec; (C) entities,
  endpoints, the component client and the test kits only, everything else later].
- **FR-002**: A handler's wire name MUST be declared separately from its method name, and a
  query handler MUST be unable to persist: the Java compiler refuses it.
- **FR-003**: Registration MUST be explicit, and the SDK MUST collect every problem with a
  service's declarations (an unregistered component, two handlers with one wire name, a route
  with no access rule) and fail at start naming each.
- **FR-004**: Every effect the Scala SDK offers for a kind the Java SDK carries MUST be
  expressible in Java, and MUST be reduced by the same function the Scala SDK's is, so the two
  cannot disagree about semantics.
- **FR-005**: The Java SDK MUST provide a component client through which a handler calls entities,
  workflows and views by their handles, and a service client for other services, with the same
  rules about which components may call what as the Scala SDK applies.
- **FR-006**: The Java SDK MUST expose the runtime's contexts (command, event, endpoint, step,
  change, timed action) in Java form, with the same things readable in the same places and
  refused in the same places.
- **FR-007**: A handler that throws MUST fail its request as a Scala handler's exception does, and
  the documentation MUST steer towards returning an error effect.

**The encoding**

- **FR-008**: The Java SDK's default serializer MUST derive from a Java record or sealed
  interface, MUST produce and accept exactly the encoding the platform publishes, and MUST write a
  Java record and the Scala case class of the same shape identically, so that a journal written
  by one is recovered by the other.
- **FR-009**: Every encoding fixture published with the protocol MUST pass through the Java
  default serializer unchanged, and a value the encoding cannot write (a `null` field, a type with
  no form) MUST be refused naming the field, in the component test kit as in the service.
- **FR-010**: A developer MUST be able to supply a serializer of their own for a type;
  portability with Scala is then theirs to keep.

**Test kits, template and samples**

- **FR-011**: The Java SDK MUST provide a component test kit that drives one component of any
  kind it carries with no runtime, database or network, showing each effect as values and
  round-tripping inputs and replies through the component's own serializers.
- **FR-012**: The Java SDK MUST provide a test kit that starts the whole service against a
  throwaway database, is ready before the first test runs, and can restart the service on the same
  database to prove durability.
- **FR-013**: Neither test kit MAY require a particular test framework.
- **FR-014**: `ankka init <name> --language java` and `--language kotlin` MUST write a project
  with an entity, a view, an endpoint, tests at both levels, the compose file, the descriptor, a
  Dockerfile or image build, the GitHub workflows and the Claude Code skills, as the other
  templates do, pinned to the CLI's own version; the project MUST build and pass its tests with
  only a JDK and Docker installed, carrying its build tool's wrapper, using [NEEDS CLARIFICATION:
  which build tool each template uses: (A) Maven for Java and Gradle with the Kotlin DSL for
  Kotlin, each what its community expects; (B) Gradle for both, one template engine and one CI
  shape; (C) Maven for both].
- **FR-015**: Continuous integration MUST expand each template and run its tests, as it does for
  the Scala, Python, TypeScript and Rust templates, and only when a pull request touches what the
  template reads.
- **FR-016**: The shopping cart sample MUST be written in Java and in Kotlin, every component of
  it that the feature's kinds cover, and MUST pass the sample's own living features.
- **FR-017**: A Java reference service MUST declare the same components, wire names and routes as
  the Scala reference service, and the conformance suite MUST pass against it in process, as it
  does against the Scala one.
- **FR-018**: A test MUST prove that a journal written by the Scala shopping cart is recovered by
  the Java one, and the reverse.

**Kotlin**

- **FR-019**: The Kotlin support MUST consist of a template, a sample, a first-service page, a
  reference page and a skill, plus [NEEDS CLARIFICATION: whether a Kotlin library is published:
  (A) no library, Kotlin calls the Java API directly and the documentation shows the idiom; (B) a
  small published Kotlin library of extension functions and nullable-returning forms, nothing a
  Java developer sees; (C) a Kotlin library with its own component DSL and suspending client
  calls].
- **FR-020**: Where the Java API gives `Optional`, Kotlin MUST see a nullable type; where the Java
  API takes an object, Kotlin MUST see a non-nullable parameter, so a `null` is refused at compile
  time.
- **FR-021**: A Kotlin data class MUST serve as a state or an event exactly as a Java record does,
  under the same encoding.

**Publishing, documentation, skills**

- **FR-022**: The Java SDK, its test kits and any Kotlin library MUST be published to Maven
  Central from a release tag of this repository, with the platform's own artifacts, under the
  same version, with no hand step.
- **FR-023**: The documentation MUST gain a first-service page and an SDK reference page for Java
  and for Kotlin, with samples included from tested code, and every page, skill and tool
  description that names the supported languages MUST name Java and Kotlin.
- **FR-024**: A Java skill and a Kotlin skill MUST exist beside the Python, TypeScript and Rust
  ones, and every shared skill that has a per-language differences section MUST gain one for each.
- **FR-025**: The divergences page MUST say where ankka's Java differs from Akka's Java SDK and
  why, and the limitations page MUST name what the Java and Kotlin SDKs do not do.
- **FR-026**: No file in the runtime, the protocol, the sidecar, the operator, the control plane
  or the CRD MAY change for this feature, except where a Scala SDK type must gain a Java-callable
  form, and every existing test MUST pass unchanged.

### Key Entities

- **Java SDK**: the library a Java developer writes a service with; an SDK in the glossary's
  sense, the first that is hosted in the platform's own process rather than speaking the protocol.
- **Kotlin SDK**: what a Kotlin developer writes a service with: the Java SDK, a template, a
  sample, documentation and a skill, and whatever the third clarification settles.
- **Facade**: the Java-shaped form of a Scala SDK type: it delegates to the Scala type and shows
  nothing Scala to its caller.
- **Component test kit**, **test kit**, **encoding**, **fixture**, **reference service**,
  **conformance suite**, **wire name**: as in the glossary, now with a Java and a Kotlin form each.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Every encoding fixture published with the protocol passes through the Java default
  serializer with 0 differences, and a Java record and a Scala case class of the same shape
  produce byte-identical journal records for the sample's whole feature run.
- **SC-002**: The conformance suite passes against the Java reference service with 0 failures and
  0 skips, in process, and the shopping cart written in Java and in Kotlin passes every one of the
  sample's living features.
- **SC-003**: A journal written by the Scala shopping cart is recovered by the Java cart with
  every cart readable, and the reverse.
- **SC-004**: A developer with a JDK and Docker installed and no other tool goes from an empty
  directory to a tested, running service by following the Java or the Kotlin first-service page,
  or by running `ankka init`, with no step that needs Scala or sbt.
- **SC-005**: A persisting query, an unstated access rule and a duplicate wire name are each
  caught before the first request: the first at compile time, the other two at start.
- **SC-006**: Nothing a Java or Kotlin developer must write, import or read in the documented
  surface is a Scala type; a check over the public signatures of the Java SDK finds 0.
- **SC-007**: Every existing test passes unchanged, and no artifact the platform already publishes
  changes its public surface.

## Assumptions

- The Java SDK is a facade in this repository, published beside the Scala SDK under the same
  version, not a port: it delegates to the Scala SDK and the runtime, and reduces effects through
  the same functions, so there is one semantics. A behaviour the Scala SDK gains later reaches Java
  by adding its facade, which is the per-language cost on record.
- The Java version is the platform's, JDK 21, so records, sealed interfaces and virtual threads
  are available and the facade is shaped for them.
- The default serializer derives from the record's shape and matches the Scala encoding under a
  specified mapping proven by the fixture suite, as every SDK's must since feature 009; the
  mapping for `Optional`, collections, enumerations and sealed hierarchies is written in the
  encoding document.
- The template's tests use JUnit, since that is what Java and Kotlin developers expect; the test
  kits depend on no test framework.
- A Java or Kotlin service is deployed exactly as a Scala one: one image, the JVM runtime inside
  it; the descriptor, the operator and the control plane see no difference, so the deploying
  scenarios of feature 009 need no Java form.
- The audience is developers who already run services on the JVM in Java or Kotlin, including
  those who know Akka's SDK; the first-service pages explain the model from scratch all the same.
- Agents, where this feature carries them, are the runtime's loop with Java-declared tools, system
  messages and guardrails, against the same scripted model provider the Scala tests use; no model
  key is needed to test them.
- Windows is out of scope for the CLI and the template, as it is today.
