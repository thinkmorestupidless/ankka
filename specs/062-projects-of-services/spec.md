# Feature Specification: Projects of several services

**Feature Branch**: `062-projects-of-services`

**Created**: 2026-10-31

**Status**: Draft

**Input**: User description: "Systems of services: guidance and tooling for building a non-trivial
ankka application as several services with independent lifecycles. Today the skills, template and
spec-kit setup stop at one service. Scope: a system step in the design skill and the designing
services page that produces a context map; the rule that services which must be deployed together
are one service; repository model guidance and how a feature that spans services decomposes into
a system spec and service specs; a multi-service build with shared modules; running and checking a
system locally, with an inspect mode for scenarios that cross services. Out of scope: deploying
several services as one unit, a project-level descriptor, or a batch apply."

## Context

A developer who builds on ankka with the skills and spec-kit builds one service. The template, and
`ankka init` in front of it, makes one standalone build in a new, empty directory, whose deploy job
releases that one service on a `v*` tag. The design skill reaches the question of service
boundaries as its tenth step, "one service, or two", and answers it with sound criteria (ownership
and release cadence, scaling, consistency; start with fewer, larger services), but produces nothing
that records the answer. The inspect skill checks one running service against its spec. A feature
spec lives in the repository the feature lands in, so a feature that spans two services has no home
unless both live in one repository.

The platform already holds most of what a project of several services needs. Each service is
applied and rolled out on its own. A declared topic carries a named contract whose schema the
project holds and which each reading or publishing component states. Several services run on one
machine without configuration: each picks its own cluster port, and the service client finds the
others through the local console or a configured address. What is missing is the guidance that
ties these together, a repository that holds several services and still releases each one on its
own, and a way to check a scenario that only passes when two services and a topic work together.

This feature takes a position. A project is the services that deliver what is required of them
when they run together, each deployed on its own. Its services, its context map, its contracts and
its specs live in one repository, because the map and the contracts are easiest to keep true beside
the code they describe. What usually makes one repository tie deployments together is a release
pipeline that builds and ships everything at once; the project template's pipeline releases one
service per tag instead, and tests only what a change touched. ankka deploys each service on its
own and offers no way to roll several out as one. That is a decision, not a gap: services that must
be deployed together are one service, and a rollout that must be coordinated says the boundary
between them is in the wrong place.

## Clarifications

### Session 2026-10-31

- Q: Is a system a word of its own, or a project? → A: A project. A project is a group of services
  that deliver the functionality required of them when they run together, each deployed
  independently. The glossary's definition changes to say so; there is no separate "system".
- Q: Which repository model does the platform recommend? → A: One repository per project, holding
  every service, the context map, the contracts and the specs. Releases are per service, by tag,
  so the repository does not tie deployments together. Moving one service to a repository of its
  own is the documented way out for a service whose owner or toolchain differs, not a second model
  of equal standing.
- Q: How does a developer get a build holding several services? → A: A project template, separate
  from the single-service template, which stays as it is. `ankka init` inside a project made from
  it adds a service, wired in with nothing edited by hand.
- Q: The glossary refuses "map" as a synonym of topology; what is the record of a project's
  services, boundaries and crossings called? → A: A context map. Topology's refused synonyms become
  "graph, diagram, service map", so "map" alone is no longer refused; no living feature used it.
- Q: How does each service of a project get its own database on one machine with nothing
  configured, when a service defaults to the database `ankka`? → A: The project template's run
  recipe sets each service's database name to the service's name, and its compose file creates one
  database per service. The runtime's default stays `ankka`; nothing in the platform or in a single
  service changes.
- Q: Where do the kept cross-service tests live, and when do they run? → A: In a tests module of
  the project, beside the services, which starts the services a test needs. It runs when a service
  it starts changes or the shared module changes. A change to one service still runs no other
  service's own tests.
- Q: Which languages may a service added inside a project be? → A: Any the single-service template
  offers. A JVM service joins the project's build; a service in another language is in a directory
  of its own with its own toolchain. Either way `ankka init` wires its checks and its release on its
  service tag into the project with nothing edited by hand.
- Q: Is the context map prose, or does something mechanical hold it true against the services? →
  A: A structured file in the project's repository, which the project's checks, the design skill
  and the inspect skill all read, rendered for people to read. The checks fail when the file and the
  services' descriptors disagree about the services or the declared topics and contracts that cross
  between them.
- Glossary: the six proposed terms (project template, context map, boundary, crossing, shared
  module, service tag) and the changed repository stand as written; the context map's definition
  says it is a file the checks hold true.
- Q (at planning): A descriptor declares no topic or contract; topics are declared on the project
  and a component states its contract in code, visible when the service runs or under its test
  kit. How do the checks hold the context map true? → A: Statically by the command line (every
  service in the map has a directory and the reverse, every topic crossing's contract names a
  schema file the repository holds, the rendering is in step), and at run time by the project's
  tests module, which starts each Scala service under its kit and compares what it states with
  the map, and by the inspect skill, which compares a running service's topology with the map.
- Q (at planning): Which JVM services share the project's build? → A: Scala only. Java is Maven
  and Kotlin is Gradle, so they are services in a directory of their own with their own build
  tool, like Python, and read the contracts' schema files. The shared module is the Scala
  services'.
- Q (at planning): What of spec-kit does the project template carry? → A: The three extensions
  (bdd, review, design-build), their skills and the hooks file. `ankka init` runs spec-kit's own
  installer when it is on PATH and otherwise names it as the first next step; spec-kit's files
  are never copied into the command line.
- Q (at planning): How does a project start and gain a service on the command line? → A:
  `ankka init <name> --project --services <a>,<b>` starts one; `ankka init <service>` run inside a
  project, which the context map file at its root says it is, adds a service and records it in
  the map; outside a project the command is unchanged.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A project designed with its boundaries written down (Priority: P1)

A developer asks a coding agent to design an application whose domain is large enough to split.
The agent, following the design skill, decides first whether it is one service or several. When it
is several, it writes a context map before any component table: each service, what it owns, which
of the three criteria put its boundary there, and everything that crosses each boundary: the
declared topics and their contracts, the HTTP routes another service calls, and who calls whom.
Then it designs each service as the skill already does. When two proposed services would have to
be released together, the skill tells the agent to merge them and say why. The context map is a
file in the project's repository: the project's checks compare it with each service's descriptor,
so a service, a declared topic or a contract that one has and the other lacks fails the checks,
and a rendering for people to read is kept in step with it.

**Why this priority**: Every other story depends on the boundaries being decided and recorded. It
needs only the skill and the documentation to change, and it removes the most expensive mistake:
a boundary drawn where none belongs.

**Independent Test**: Give an agent with only the updated skill a domain description that splits
into three services, one pair of which shares a consistency rule. The design it produces has a
context map with three services merged to two, every crossing named with its contract or route,
and a reason for each boundary from the three criteria.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/projects/designing.feature`: a design of several services starts with a context map that names each service, what it owns and why its boundary is there
- added `features/projects/designing.feature`: every crossing of a boundary in a context map is a declared topic with its contract or an endpoint route, and names who calls whom
- added `features/projects/designing.feature`: a crossing that is neither a declared topic with its contract nor a route is named as a defect
- added `features/projects/designing.feature`: two services that must be released together are designed as one service
- added `features/projects/designing.feature`: a cycle of calls between services is named and one direction becomes a declared topic
- added `features/projects/designing.feature`: a design that needs no boundary stays one service and has no context map
- added `features/projects/context-map.feature`: a context map that names a service the project does not hold, or does not name one it holds, fails the project's checks
- added `features/projects/context-map.feature`: a crossing whose declared topic or contract no service of the project declares fails the project's checks
- added `features/projects/context-map.feature`: a declared topic a service reads or publishes that the context map records no crossing for fails the project's checks
- added `features/projects/context-map.feature`: a context map that agrees with every descriptor passes the project's checks and is rendered for people to read
- added `features/documentation/projects.feature`: the documentation says that services which must be deployed together are one service, and that the platform deploys each service on its own

---

### User Story 2 - A project in one repository, each service released on its own (Priority: P1)

A developer starts a project from the project template and gets one repository holding a build of
two services and a module of shared contract types, the context map of the two, and the spec-kit
setup. Each service has its own directory, descriptor, image, database and tests. Later they add a
third service with `ankka init` inside the project, in any language the single-service template
offers, and it is wired into the build, the continuous integration and the release pipeline with
nothing edited by hand.

A change to one service's code runs that service's tests and nothing else's. A change to the shared
module runs the tests of every service that uses it. A tag naming one service and a version builds
that service's image alone and deploys that service alone; no other service is built, pushed or
rolled out. Each service's versions come from its own tags.

**Why this priority**: The recommendation to keep a project in one repository stands only if the
repository does not couple releases. This is the story that makes it true, and the one a developer
meets first when they start.

**Independent Test**: Start a project with two services, add a third, change one service's code
and confirm only its tests run; tag that service and confirm only its image is built and only it is
deployed, at the tag's version, while the other two stay at theirs.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/projects/template.feature`: a project started from the project template holds two services, a shared module and their context map, each service with its own descriptor, image and database, all named for it
- added `features/projects/template.feature`: the tests of every service of a project started from the project template pass unchanged
- added `features/projects/template.feature`: a service added inside a project is wired into its build, its checks and its releases with nothing edited by hand
- added `features/projects/template.feature`: a service in another language added inside a project is wired into its checks and its releases with nothing edited by hand
- added `features/projects/template.feature`: adding a service in a directory that holds no project is refused and says why
- added `features/projects/releases.feature`: a service tag naming one service builds and deploys that service alone, at the service tag's version
- added `features/projects/releases.feature`: each service's version comes from its own service tags, whatever the other services were last released at
- added `features/projects/releases.feature`: a change to one service runs that service's checks and no other's
- added `features/projects/releases.feature`: a change to the shared module runs the checks of every service that uses it
- added `features/projects/releases.feature`: a service tag naming no service of the project releases nothing and says why
- added `features/projects/releases.feature`: a service tag at a commit where the service's tests fail deploys nothing of any service

---

### User Story 3 - A feature that spans services, specified once (Priority: P2)

A developer whose project has two services specifies a feature that changes both: a new field the
first publishes and the second acts on. The feature is one spec in the project's repository; its
plan names each service's part and the contract change between them, and its work list orders the
contract first. The contract change is made so that both sides can build against it while the other
is still on its old release: an incompatible change is a new contract name, and each side keeps
reading what the other still publishes until both have moved. Each service is then released on its
own tag when its part is done.

When one service has been moved to a repository of its own, the project's repository still holds
the context map, the contracts and the spec; the moved service's repository holds a spec that names
the project's spec it implements.

**Why this priority**: Without it, the first feature that crosses a boundary either ties two
releases together or is specified twice. It is guidance and a plan template, built on what story 2
gives.

**Independent Test**: A reader of the documentation alone can say where a cross-service feature's
spec lives, in what order its contract change and its two sides are released, and what changes
when one service lives in a repository of its own.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/projects/specifying.feature`: a change that changes several services of a project is one spec whose plan names each service's part and the contracts it changes
- added `features/projects/specifying.feature`: the work list of a change that changes a contract changes the contract before either service depends on it
- added `features/projects/specifying.feature`: an incompatible change to a contract is a new contract, and each side reads both until neither publishes the old one
- added `features/projects/specifying.feature`: a service moved to a repository of its own keeps a spec there that names the project's spec it implements
- added `features/documentation/projects.feature`: the documentation recommends one repository per project, says why it does not tie deployments together, and says when a service moves to a repository of its own

---

### User Story 4 - A project run and checked on one machine (Priority: P3)

A developer runs every service of the project on their own machine, with one database server and
one broker shared by all of them, each service its own database within it, which the project's run
recipe and compose file provide so the developer configures nothing. The local console lists them
all and each finds the others. The inspect skill, given a spec whose scenarios cross services,
reads the context map for the services and crossings each one passes, checks each by driving the
first service's endpoint and asserting on the second's state through its declared queries, and
reports the scenario as met or not with the evidence: the requests, the messages on the topic and
the trace that joins them. Each met scenario is kept as an integration test in the project's tests
module, which starts the services it needs.

**Why this priority**: Most of the running already works; what is missing is that nobody is told
how, and that a cross-service scenario cannot be checked. It is the last story because it checks
what the first three build.

**Independent Test**: With a two-service project whose acceptance scenario crosses a topic, the
inspect skill reports the scenario met with the trace that joins the two services; with the
consuming service stopped, it reports the scenario not met and names the service that did not
answer.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/projects/running-locally.feature`: every service of a project runs on one machine with nothing configured, and each finds the others
- added `features/projects/running-locally.feature`: the local console lists every service of a running project
- added `features/projects/inspecting.feature`: a scenario that crosses services is reported met with the requests, what was published and the trace that joins them
- added `features/projects/inspecting.feature`: a scenario that crosses services is reported not met, naming the service that failed it
- added `features/projects/inspecting.feature`: a met scenario that crosses services is kept as a test that starts the services it needs
- added `features/documentation/projects.feature`: the documentation says how to run every service of a project on one machine and how they find each other

---

### Edge Cases

- A context map with a cycle of synchronous calls between services: the design skill names the
  cycle and asks which direction becomes a topic, since each service in a cycle cannot be released
  without the others answering.
- One service reads another's database or an entity's own events: already a review smell; the
  context map gives it no crossing to name, so it cannot be recorded as designed.
- A service written in a language other than the build's: `ankka init` adds it to the project's
  repository in a directory of its own with its own toolchain, wires its checks and its release on
  its service tag in as for any other service, and the context map treats it as any other service.
  It does not build against the shared module; it reads the contract's schema, which the project
  holds.
- A change to the shared module that only one service needs yet: the other services' checks run,
  and they are not released until their owners tag them; nothing deploys because a shared module
  changed.
- A tag pushed for a service at a commit where its checks fail: the release fails for that service
  and no other service is affected.
- A project whose services call a service of another project: the crossing is in the context map
  like any other, through cross-project access; the other project's service is not in the
  repository.
- Inspecting a cross-service scenario when one service is running at an older release than its
  contract expects: reported not met with the contract each side stated.

## Requirements *(mandatory)*

### Functional Requirements

**Designing a project**

- **FR-001**: The design skill MUST decide one service or several before any component table, and
  for several MUST produce a context map: each service, what it owns, the criterion from ownership
  and release cadence, scaling or consistency that puts its boundary there, and every crossing. The
  context map is one file at a fixed place in the project's repository.
- **FR-002**: Every crossing in a context map MUST be a declared topic with its contract, or an
  endpoint route with the service that calls it. A crossing of any other kind (a shared database, a
  read of another service's events) MUST be named as a defect, not recorded.
- **FR-003**: The design skill and the designing services page MUST state that services which must
  be deployed together are one service, that the platform deploys each service on its own and
  offers no way to deploy several as one, and that this is deliberate.
- **FR-004**: The design skill MUST carry the topics page and the calling services page among the
  pages it reads, and its review smells MUST include a synchronous call cycle between services and
  a crossing without a contract.
- **FR-005**: The glossary's project MUST be a group of services that deliver the functionality
  required of them when they run together, each deployed on its own, which a member may operate. It
  MUST no longer say the services are deployed together.

**The project template and its releases**

- **FR-006**: There MUST be a project template, separate from the single-service template, that
  makes one repository holding a build of two named services in a language the single-service
  template offers, a shared module of contract types, the context map of the two services, a tests
  module for scenarios that cross services, the skills, and the spec-kit extensions the family
  uses, with spec-kit itself installed by `ankka init` when its installer is on PATH and named as
  the first next step otherwise.
- **FR-007**: Each service in a project MUST have its own directory, descriptor, image, database and
  tests, named for the service.
- **FR-008**: `ankka init` run inside a project MUST add a service to it in any language the
  single-service template offers, wired into the checks and the releases with nothing edited by
  hand: a Scala service into the project's build, a service in any other language, Java and Kotlin
  included, in a directory of its own with its own build tool. Run in a directory that is not a
  project it MUST keep its current behaviour, and asked to add a service where there is no project
  it MUST refuse and say why.
- **FR-009**: A project's checks MUST run, for a change, the tests of each service whose directory
  changed, the tests of each service that uses a shared module that changed, and the project's
  cross-service tests that start one of those services, and no others.
- **FR-010**: A tag naming one service and a version MUST build that service's image alone and
  deploy that service alone at that version. A tag naming no service of the project MUST release
  nothing and say why.
- **FR-011**: Each service's version MUST come from its own tags, independent of the other services'.
- **FR-012**: Starting a single service from the single-service template MUST keep working exactly
  as today, release pipeline included.

**Specifying across services**

- **FR-013**: The documentation MUST recommend one repository per project, say why per-service
  releases keep it from tying deployments together, and say when a service moves to a repository of
  its own (a different owner or toolchain) and what moves with it.
- **FR-014**: A feature that changes several services of a project MUST be one spec, whose plan
  names each service's part and the contracts it changes, and whose work list changes each
  contract before either side depends on it.
- **FR-015**: The documentation MUST say that an incompatible contract change is a new contract
  name, and that each side keeps reading what the other still publishes until both have moved.
- **FR-016**: For a service in a repository of its own, the documentation MUST say that the context
  map, the contracts and the cross-service spec stay in the project's repository, and that the
  service's repository holds a spec naming the one it implements.

**Running and checking a project**

- **FR-017**: The documentation MUST say how to run every service of a project on one machine and
  how they find each other: their own cluster ports, the local console, and a configured address
  where the console is not running. The project template's run recipe MUST give each service a
  database of its own, named for the service, in the one database server its compose file runs, and
  the runtime's default database name MUST NOT change.
- **FR-018**: The inspect skill MUST check each scenario that crosses services through the services'
  real endpoints and declared queries, reporting each as met or not with the requests, the messages
  on any topic and the trace that joins the services.
- **FR-019**: A scenario that crosses services MUST be reported not met, naming the service, when
  a service it needs is not running or states a different contract.
- **FR-020**: Each met scenario that crosses services MUST be kept as an integration test in the
  project's tests module, which starts the services it needs.

**The context map as a file**

- **FR-022**: The project's checks MUST hold the context map true in two places. Statically, a
  command of the command line MUST fail when a service in the map has no directory or a service
  directory is not in the map, when a topic crossing's contract names no schema file the
  repository holds, when a crossing is neither a declared topic with a contract nor a route, or
  when the rendering is out of step. At run time, the project's tests module MUST start each Scala
  service under its kit and fail when the service publishes to or reads a declared topic the map
  records no crossing for, or states a contract other than the crossing's; the inspect skill MUST
  make the same comparison against a running service of any language.
- **FR-023**: The context map MUST be rendered for people to read, in the project's repository, and
  the checks MUST fail when the rendering is out of step with the file.
- **FR-024**: The inspect skill MUST read the context map for the services and the crossing each
  scenario that crosses services passes, and its report MUST name that crossing.

**Out of scope**

- **FR-021**: Nothing in this feature deploys several services as one unit, adds a descriptor for a
  set of services, or applies several descriptors in one command.

### Key Entities *(include if feature involves data; each a term in the project glossary)*

- **Project**: A group of services that deliver the functionality required of them when they run
  together, each deployed on its own (a changed definition).
- **Context map**: The record of a project's services, what each owns, why each boundary is where
  it is, and every crossing: one file in the project's repository, held true against the services'
  descriptors by the project's checks and rendered for people to read.
- **Crossing**: What passes a boundary between two services: a declared topic with its contract, or
  an endpoint route with its caller.
- **Project template**: What the platform gives a developer to start a project of several services
  from, beside the template for one service.
- **Shared module**: The code a project's Scala services build against together, holding the types
  of the contracts that cross their boundaries; never a component. A service in any other language
  reads the contract's schema instead.
- **Service tag**: A tag that names one service of a project and a version, and releases that
  service alone.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Given three domain descriptions that each split into services, one of which contains a
  pair that must be released together, an agent with only the updated skills produces a context
  map for each in which every crossing names its contract or route and the coupled pair is one
  service: three of three.
- **SC-002**: A project of three services is started, run on one machine and has every service's
  tests passing in under ten minutes from an empty directory, with nothing hand-edited in a build
  or pipeline file.
- **SC-003**: Releasing one service of a three-service project builds one image and changes one
  deployed service; the other two keep their running version and image, every time.
- **SC-004**: A change to one service's code runs that service's checks and the project's
  cross-service tests that start it, and no other service's own checks; a change to the shared
  module runs the checks of exactly the services that use it.
- **SC-005**: A reader with only the documentation answers where a cross-service feature's spec,
  the context map and the contracts live, and in what order a contract change is released, with no
  wrong answer, both for a project in one repository and for one with a service moved out.
- **SC-006**: Breaking the consuming side of a cross-service scenario (stopping the service or
  changing its contract) turns the inspect report and the kept test for that scenario red, every
  time.
- **SC-007**: Adding a declared topic to a service without recording its crossing in the context
  map, or the reverse, fails the project's checks, every time.

## Assumptions

- The rule "deployed together means one service" covers release coupling only. Services run
  together to deliver what is required of them are a project and deploy independently; a project
  also groups services for operating, access and secrets, which does not change.
- One project repository corresponds to one project on the platform; services of another project
  are reached through cross-project access and are not in the repository.
- Contracts that cross services are the project-held contracts of declared topics; the shared
  module carries their types for the Scala services. HTTP routes that cross services are described in
  the context map and their caller's spec; no new contract format for them.
- The project build is one Scala build holding the Scala services, the shared module and the tests
  module; a service in any other language, Java and Kotlin included since each has a build tool
  of its own, lives in the project's repository in a directory of its own with its own toolchain,
  and `ankka init` wires its checks and its release in as it does a Scala service's.
- Locally the project's compose file runs one database server and one broker; the run recipe names
  each service's database for the service, as the platform does when deployed.
- The context map's format, its fixed place and what renders it are the plan's. It is one file,
  written by the design skill and by hand, read by the checks and the inspect skill; routes between
  services are recorded in it but are not compared with the code, since a descriptor does not
  declare the routes a service calls.
- The release pipeline is the template's, on the same CI the single-service template uses today; the
  deploy action needs no change beyond documentation, since it already acts on one service.
- The inspect skill checks services the developer has running; it does not start them. The kept
  integration tests start what they need.
- The skills changed here are rendered from `tools/docs/skill/` into the templates and the
  marketplace as every skill is; nothing about how skills ship changes.
