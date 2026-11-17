# Feature Specification: A project started on one machine with one command

**Feature Branch**: `066-local-up`

**Created**: 2026-11-17

**Status**: Draft

**Input**: User description: "`ankka local up`: start every service of a project on a developer's
machine with one command. It looks for the project's definition in the local directory and composes
the containers from it: one Postgres holding a database for each service, one Kafka broker with
the project's topics made, and each service running and finding the others. As light as it can be,
for functional testing of a project of several services on one machine (32 GB, 10 cores) where a
local Kubernetes installation is too heavy. Running a single service stays as it is. A directory
holds one project, so there is no option that names one."

## Context

A project of several services runs on one machine today in two ways, and neither suits testing
what the project does as a whole.

The first is the project template's (spec 062): a compose file with one Postgres and one Kafka, a
script that gives each service a database of its own, and a script that runs one service from its
source in the foreground. Six services are six terminals, six builds held in memory, and a broker
whose topics come into being with the broker's defaults when a service first publishes, not with
the partitions the project means them to have. A project that needs something more beside its
services, an identity provider or a stand-in for a payment provider, starts it by hand.

The second is a local installation: a Kubernetes cluster on the machine with the platform, its
database operator, its broker, its gateway and its telemetry beside the services. It is the
platform itself and proves what only the platform does, but most of what it runs is not the
project. A project of nine services on it took more than the machine's container memory, and a
second workload on the same machine was enough to stop its control plane answering.

A project made outside the template has neither. It is left with a compose file per service, each
with a Postgres of its own, and a run command per service that lists every neighbour's address by
hand.

This feature gives the first way one command and makes it what a project's functional tests run
against. `ankka local up`, run in a project's directory, reads what the project already says about
itself and starts all of it: one database server with a database per service, one broker with the
project's topics made as declared, whatever else the project says it needs, and every service,
each run from its image and finding the others by name. Nothing of Kubernetes or of the platform's
own services runs. `ankka local down` stops it. Running one service from its source stays, and
such a service takes its place among the others.

It does not stand in for an installation, and says so: services are not given identities, callers
are not told apart, topics are not authorised and nothing is rolled out. Those are checked on an
installation.

## Clarifications

### Session 2026-11-17

- Q: Is this the platform's, or a tool of each project that needs it? → A: The platform's (Trevor).
  Any repository that holds a project gets it, and a project made before the template adopts it by
  holding the same files.
- Q: What is the command called? → A: `ankka local up`, with `ankka local down` beside it (Trevor).
  `ankka projects` is already what a member does to projects on an installation, and `ankka local`
  is already what the command line does on this machine.
- Q: How does the command know which project? → A: It is the project whose context map is in the
  directory the command runs in, or the one `--dir` names, as `ankka context-map` does. A
  directory holds one project, so no option names one (Trevor).
- Q: Does this replace running one service? → A: No. One service still runs on its own from its
  source exactly as before, and a service run that way beside a project that is up is one of the
  project's services to every other.
- Q: How light? → A: One database server and one broker for the whole project, each service its own
  database within the server, and nothing of the platform's own. The measure is that a project is
  usable for functional testing on a developer's machine beside their other work; starting it is
  not expected to be quick.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Every service of a project started with one command (Priority: P1)

A developer in a project's directory runs `ankka local up` and, when it returns, every service of
the project is running on their machine: each in a database of its own in one database server,
each connected to one broker, each under its own name and on its own port, each reaching the
others by name. The command says what it started and where each service answers. `ankka local
down` stops everything it started. What the services recorded is still there the next time the
project is brought up, unless the developer asks for it to be removed.

**Why this priority**: It is the feature. Everything else here is what makes the one command
enough for a real project.

**Independent Test**: In a project of two services where one calls a route of the other, `ankka
local up` followed by a request to the first is answered with what the second said, with no other
command run and no address configured; after `ankka local down` neither service answers, and after
a second `ankka local up` what was recorded before is read back.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/projects/local-up.feature`: one command starts every service of a project, each in its own database, and each finds the others
- added `features/projects/local-up.feature`: a service that does not become ready is reported by name, with what it logged, and the command fails
- added `features/projects/local-up.feature`: bringing a project down stops every service and everything started for it
- added `features/projects/local-up.feature`: what the services recorded is kept when a project is brought down and up again
- added `features/projects/local-up.feature`: what the services recorded is removed when the developer asks for it
- added `features/projects/local-up.feature`: bringing up a project that is already up starts only what is not running
- added `features/projects/local-up.feature`: a directory that holds no project is refused, saying what a project's directory holds

---

### User Story 2 - The project's topics made as the project declares them (Priority: P1)

The project's repository says which topics the project has, with the partitions of each and
whether it is compacted, as a member would declare them on an installation. `ankka local up` makes
each on the local broker before any service starts, under the name the services use for it. A
service that publishes to or reads a topic the project does not declare is not given one by
accident: the broker makes no topic of its own accord.

**Why this priority**: A topic made with the broker's defaults has one partition and is never
compacted. A project whose ordering, consumers or erasure depend on what it declared behaves
differently on the developer's machine from anywhere else, and the difference is silent.

**Independent Test**: In a project that declares a topic of six partitions and a compacted topic,
after `ankka local up` the local broker holds both as declared; a service that publishes to an
undeclared topic fails to, and its log names the topic.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/projects/local-topics.feature`: each topic the project declares is made on the local broker with its partitions before any service starts
- added `features/projects/local-topics.feature`: a topic declared as compacted is made compacted
- added `features/projects/local-topics.feature`: a topic that already exists with other partitions is reported, and the command fails
- added `features/projects/local-topics.feature`: the local broker makes no topic that the project does not declare
- added `features/projects/local-topics.feature`: a topic crossing in the context map that names no declared topic is refused before anything starts

---

### User Story 3 - A service given what it needs to run on this machine (Priority: P1)

A service's descriptor states its environment for an installation: some of it literal, some of it
read from secrets, some of it addresses that exist only there. The project's repository says, once
and beside the descriptors, what each of those is on a developer's machine. `ankka local up` gives
each service its descriptor's environment with those values in place, and the settings only the
command can know: the service's database, the broker, its port and where each other service is.
A variable the service needs and nothing supplies stops the command before anything starts, naming
the service and the variable.

**Why this priority**: A real service does not start on defaults alone. Without this the one
command starts the template's example and nothing else.

**Independent Test**: A service whose descriptor reads one variable from a secret and declares one
literal starts with both set when the project's local values name the first, and `ankka local up`
is refused naming the service and the variable when they do not.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/projects/local-environment.feature`: a service is started with the literal environment its descriptor declares
- added `features/projects/local-environment.feature`: a variable the descriptor reads from a secret takes the value the project's local values give it
- added `features/projects/local-environment.feature`: a variable read from a secret with no local value is refused before anything starts, naming the service and the variable
- added `features/projects/local-environment.feature`: a local value replaces a literal the descriptor declares
- added `features/projects/local-environment.feature`: a variable the platform sets is not taken from the descriptor or the local values
- added `features/projects/local-environment.feature`: the project's local values are never printed

---

### User Story 4 - What the project needs beside its services (Priority: P2)

A project says in its repository what else must run for its services to work on a developer's
machine: an identity provider with its realms, an object store, a stand-in for a system outside
the project. `ankka local up` starts each beside the database server and the broker, waits for
each to be ready before the services that need it, and `ankka local down` stops them. One that
needs a database is given one in the project's database server, so the project still runs one.

**Why this priority**: Most real projects have one. It is second because a project with none is
already served by the first three stories.

**Independent Test**: A project that declares an identity provider and a service whose endpoint
admits only tokens of that provider: after `ankka local up` a token signed by the provider is
admitted by the service, and with the provider's declaration removed the command still starts
every service.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/projects/local-companions.feature`: what a project declares beside its services is started before the services
- added `features/projects/local-companions.feature`: a service is started only when what it needs beside it is ready
- added `features/projects/local-companions.feature`: something declared beside the services is given a database in the project's database server when it asks for one
- added `features/projects/local-companions.feature`: bringing a project down stops what was started beside its services

---

### User Story 5 - One service run from its source among the others (Priority: P2)

A developer changing one service brings the project up without it and runs that one service from
its source, as they do today. It takes its place: the services the command started reach it by
name, it reaches them, and it reads and writes the database and the topics it would have had. The
developer restarts it as often as they change it, and nothing else is restarted.

**Why this priority**: Functional testing is done while something is being changed. A project that
can only be run from built images makes every change an image build.

**Independent Test**: In the two-service project, `ankka local up` leaving out the called service,
then that service run from its source: the request to the first service is answered with what the
second said; the second stopped and started again, the request is answered again.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/projects/local-up.feature`: a service the developer leaves out is not started, and the command says how to run it
- added `features/projects/local-up.feature`: a service run from its source is reached by name by the services the command started
- added `features/projects/local-up.feature`: a service run from its source reaches the services the command started by name
- added `features/projects/local-up.feature`: only the services the developer names are started, with what they need
- added `features/projects/running-locally.feature`: one service of a project still runs on its own with nothing configured

---

### User Story 6 - A developer who can see what is running (Priority: P3)

The developer asks what of the project is running and is told, for each service and each thing
started beside them, whether it is running and ready and where it answers. The local console lists
every service the command started beside any run from source, with the calls between them, and a
service's log is read by its name.

**Why this priority**: The command is usable without it; it is what makes a failure quick to find.

**Independent Test**: With the two-service project up and one service stopped by hand, the status
names that service as not running and the other as ready, and the local console lists the one
that is running.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/projects/local-up.feature`: the developer is told which services of a project are running and ready
- added `features/projects/local-up.feature`: the developer reads a service's log by the service's name
- added `features/local-console/services.feature`: the local console lists the services a project was brought up with

---

### User Story 7 - A project that starts from the template, and one that did not (Priority: P3)

A project made from the project template is brought up with `ankka local up` from the moment it is
made, and its documentation says so in place of the compose file and the two scripts it carried. A
project made before the template, or outside it, is brought up once it holds what the command
reads, and the documentation says what that is and how to check it.

**Why this priority**: It is how the feature reaches projects, and it changes nothing a developer
could not do by hand once the earlier stories exist.

**Independent Test**: A project made with `ankka init --project` answers a request to its first
service after `ankka local up` and no other command; a directory of services with descriptors and
no context map is refused by `ankka local up`, and is brought up after the context map and the
topic declarations the refusal asked for are added.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/projects/template.feature`: a project made from the template is brought up with one command and nothing else
- added `features/documentation/projects.feature`: the documentation says how to bring a project up on one machine, what the command reads and what it does not stand in for

### Edge Cases

- A port a service's descriptor names is taken on the machine: the command fails before anything
  starts, naming the service and the port.
- Two services of the project name the same port: refused before anything starts, naming both.
- A service's image is not on the machine and cannot be fetched: the command names the service and
  the image and starts nothing; it does not start the rest.
- A service's descriptor names an ankka runtime other than the one whose schema the command would
  apply: each service's database is given the schema of that service's own runtime.
- A service is moved to a newer runtime of the same major version, whose schema only adds to
  what its database holds: the command applies what is new and says so, naming the service.
- A service is moved to a runtime whose schema its database cannot be brought to by adding to
  it (an older runtime, or another major version): the command says so, naming the service, and
  what removes that service's database; it does not remove it itself.
- The project is up and a service is added to the context map: the next `ankka local up` gives it
  a database and starts it, and restarts nothing else.
- The project is up and a service is removed from the context map: the next `ankka local up` stops
  it and says its database is kept.
- A service hosted as a process, as a module or as a web-hosted service: started as that hosting
  is, with its sidecar, runtime or proxy, from the same command.
- Two projects on one machine: each is brought up from its own directory; their database servers,
  brokers and services do not meet, and a port both want is the refusal above.
- A service of another project that a service calls through cross-project access is not in the
  directory: the call fails as it does when that service is not running, and the command says at
  start which services called by the context map's crossings it does not hold.
- The machine has no container runtime running: refused, saying so, before anything is read.
- `ankka local down` with nothing up: says so and succeeds.

## Requirements *(mandatory)*

### Functional Requirements

**Bringing a project up and down**

- **FR-001**: `ankka local up` MUST start every service the project's context map names, in the
  directory it is run in or the one `--dir` names, and MUST be refused, starting nothing, when that
  directory holds no context map or a service the map names has no descriptor.
- **FR-002**: The command MUST run one database server and one broker for the project, and nothing
  else that the project does not declare: no part of Kubernetes, of the control plane, of the
  operator, of the gateway or of the platform's telemetry.
- **FR-003**: Each service MUST have a database of its own in the one database server, named for
  the service, holding the schema of the ankka runtime that service's descriptor names. A database
  that exists is kept.
- **FR-004**: Each service MUST be started under its own name, on the port its descriptor names,
  from the image its descriptor names, as the hosting its descriptor names.
- **FR-005**: Each service MUST reach every other service of the project by name with nothing
  configured by the developer, whether the other was started by the command or run from its source.
- **FR-006**: The command MUST return when every service it started is ready, having printed each
  service's name and where it answers; when a service is not ready within a stated time it MUST
  fail, naming the service and printing the end of its log, and leave what did start running.
- **FR-007**: `ankka local up` on a project that is up MUST start what is not running and restart
  nothing that is; a service whose image or environment has changed since it was started MUST be
  started again from the new one, and the command MUST say which services it restarted.
- **FR-008**: `ankka local down` MUST stop every service and everything else the command started
  for the project, and keep what was recorded. With `--volumes` it MUST also remove what was
  recorded: every database and every topic's messages.
- **FR-009**: `ankka local up <service>...` MUST start only the services named and what they need
  beside them; `--without <service>` MUST start every service but those named, and print for each
  left out how to run it from its source so that it takes its place.
- **FR-010**: Everything the command checks before starting (the context map, the descriptors, the
  ports, the images, the topics, the environment) MUST be checked before anything is started, and
  every problem found MUST be reported together.

**Topics**

- **FR-011**: The project's repository MUST hold the declaration of each of the project's topics:
  its name, its partitions, whether it is compacted, and the contract it carries when it has one.
  The declaration MUST be the same one a member applies to an installation, so the two cannot
  differ.
- **FR-012**: `ankka local up` MUST make each declared topic on the local broker, under the name a
  service of the project uses for it, before any service starts.
- **FR-013**: The local broker MUST NOT make a topic of its own accord.
- **FR-014**: A topic that exists on the local broker with other partitions or another compaction
  than declared MUST fail the command, naming the topic and both, and saying what removes it.
- **FR-015**: `ankka context-map check` MUST fail when a topic crossing names a topic the project
  does not declare, and `ankka local up` MUST refuse the same before anything starts.

**Environment**

- **FR-016**: Each service MUST be started with the environment its descriptor declares.
- **FR-017**: The project's repository MAY hold local values: for a service, or for every service,
  the value a named variable takes on a developer's machine. A local value MUST replace the
  descriptor's value for that variable.
- **FR-018**: A variable a descriptor reads from a secret MUST take its local value, and the
  command MUST be refused before anything starts, naming the service and the variable, when there
  is none.
- **FR-019**: The command MUST set, and take from neither the descriptor nor the local values, the
  variables that say where the service's database, the broker and the other services are and
  which port it serves; a local value for one of them MUST be refused, naming it.
- **FR-020**: Local values MUST NOT be printed by any command of this feature, and the
  documentation MUST say they are for a developer's machine, are committed with the project, and
  hold nothing that is a secret anywhere else.

**What runs beside the services**

- **FR-021**: The project's repository MAY declare what else runs beside its services on a
  developer's machine: for each, the image, its environment, the ports it serves, what says it is
  ready, and the services that need it.
- **FR-022**: `ankka local up` MUST start each such thing after the database server and the broker
  and before the services that need it, and wait for it to be ready; `ankka local down` MUST stop
  it.
- **FR-023**: Something declared beside the services that asks for a database MUST be given one in
  the project's database server, named for it, and be told where it is.
- **FR-024**: A service's local values MUST be able to name the address of something declared
  beside the services without stating a host or a port.

**One service from its source**

- **FR-025**: Running one service of a project from its source MUST keep working as it does, with
  nothing of this feature required.
- **FR-026**: A service run from its source while its project is up MUST use the database and the
  broker the command made for it, be reached by name by the services the command started, and
  reach them by name.
- **FR-027**: What the command prints for a service left out (FR-009) MUST be enough to run it so:
  one command, with nothing for the developer to work out.

**Seeing what is running**

- **FR-028**: `ankka local status` MUST say, for each service of the project and each thing
  declared beside them, whether it is running, whether it is ready, where it answers, and whether
  the command started it or it is run from its source.
- **FR-029**: `ankka local logs <service>` MUST print the log of a service the command started,
  and follow it when asked.
- **FR-030**: The local console MUST list every service the command started, with its components,
  routes and traces, as it lists a service run from its source.

**The template and the documentation**

- **FR-031**: The project template MUST be brought up by `ankka local up` as made, and MUST no
  longer carry a compose file or scripts that do what the command does. Its script that runs one
  service from its source stays, or the command line takes its place; either way FR-025 holds for
  a project made from the earlier template.
- **FR-032**: The documentation MUST say how to bring a project up and down on one machine, what
  the command reads, how a project made outside the template adopts it, and how to run one service
  from its source beside the rest.
- **FR-033**: The documentation and the limitations page MUST say what the command does not stand
  in for: a service has no identity and no certificate, every caller is the local machine, a topic
  is not authorised, nothing is exposed through a gateway, there is one instance of each service,
  and nothing is rolled out, rolled back or scaled.

**Out of scope**

- **FR-034**: Nothing in this feature applies a project to an installation, builds a service's
  image, fills a project with data, or changes how a service is deployed. A project is still
  deployed one service at a time (spec 062 FR-021).

### Key Entities *(include if feature involves data; each a term in the project glossary)*

- **Local project**: A project's services, their databases, their topics and what the project
  declares beside them, running on one developer's machine from one command.
- **Topic declaration**: What a project's repository says of one of its topics: its name, its
  partitions, whether it is compacted and its contract. Applied to an installation by a member and
  to a developer's machine by `ankka local up`.
- **Local values**: What a project's repository says a service's environment is on a developer's
  machine where it differs from, or is missing from, its descriptor.
- **Companion**: Something a project declares that runs beside its services on a developer's
  machine and is not a service of the project: an identity provider, an object store, a stand-in
  for a system outside the project.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A project of two services made from the project template answers a request that
  crosses both after one command, with nothing configured and no other command run, every time.
- **SC-002**: A project of six Scala services with a database server, a broker and one companion
  runs within 8 GiB of memory in all, measured ten minutes after it is up and idle.
- **SC-003**: That project is up, every service ready, within five minutes of the command on a
  machine of 10 cores and 32 GB, its images already present; a second `ankka local up` with
  nothing changed returns within thirty seconds.
- **SC-004**: With that project up, a second workload that takes four cores for ten minutes on the
  same machine stops no service and loses nothing recorded.
- **SC-005**: Every topic a project declares is on the local broker with its declared partitions
  and compaction after the command, and no other topic of the project's is: ten of ten runs.
- **SC-006**: A project with a variable read from a secret and no local value, a port taken, and a
  topic crossing that names no declared topic is refused with all three named in one run, and
  nothing is started.
- **SC-007**: A developer changes one service of the six, runs it from its source beside the other
  five, and has a request that crosses it answered by the changed code without an image built and
  without another service restarted.
- **SC-008**: A project of twelve services made outside the template, with an identity provider
  beside them, is brought up by the command after its repository gains a context map, its topic
  declarations and its local values, with no change to any service's code.

## Assumptions

- The project's definition is what spec 062 made it: `context-map.json` at the root of the
  project's directory and a descriptor in each service's directory. This feature adds to what the
  repository holds (the topic declarations, the local values, what runs beside the services); where
  each is written, in the context map or in a file beside it, is the plan's.
- Services run as containers from their images, so that what is tested is what is deployed and no
  build tool is held in memory per service. Building an image stays the service's own build's.
- The command drives the machine's container runtime; Docker is assumed, as the template and every
  test kit already assume it.
- One instance of each service. A second node of a service on one machine stays what
  `docs/deploy/run-locally.md` describes.
- A service's size in its descriptor bounds its memory on a developer's machine as it does
  deployed; the plan says how, and SC-002 is the measure.
- Outside a cluster every caller is the local machine and no service holds a certificate
  (`docs/build/calling-services.md`). This feature keeps that and does not add identities.
- A topic's name on the local broker is the name the services use for it on an installation's
  broker, the project's prefix included, so a consumer outside the project is written once.
- Telemetry is the local console's: traces and calls as it shows them today. No collector, store
  or dashboard is started.
- Filling a local project with data, and keeping or restoring what it recorded as one file, is a
  later feature. One database server for the project is what makes it one thing to keep.
- The figures in SC-002 to SC-004 are targets set from one project on one machine and are
  confirmed or changed at planning, by measurement.
