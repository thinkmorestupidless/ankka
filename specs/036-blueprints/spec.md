# Feature Specification: Blueprints — Agents and Their Interactions as Data, Run by the Platform

**Feature Branch**: `036-blueprints`

**Created**: 2026-10-07

**Status**: Draft

**Input**: User description: "Reasoning patterns (a loop of thinking and acting, fan out and
gather, map and reduce, a draft and a critique) as platform features. A service holds blueprints:
data, never a deployment, never edited (a change is a new version), that choose and parameterise
patterns the platform implements, define the agents taking part (instructions, model, tools,
budget) and the steps between them, with no conditions or loops of their own, checked before they
are held. A run is held and linked to the version it ran, started by a call or on a schedule. The
example is a weekly research digest: a watch that searches literature sources on a cadence and
keeps one entry per paper, and a digest that reads the week's papers and writes a podcast script
in which every statement traces to a paper; the two compose through the service's own records.
Asked for by ankka-reasoning, which will publish runs into its reasoning graph; the platform
feature needs no graph."

## Context

ankka gives a service two kinds of agent and one way to coordinate them. A request agent answers
one message while its caller waits; its effect is built on every call, so its instructions, model
and tools can already be chosen at run time from among those the service has. An autonomous agent
works a task until its model completes it, recording every iteration so that a crash resumes where
it stopped; its instructions, model, tools and budget are fixed on its companion and class, and the
limitations page says so: "no per-instance overrides of a definition". Several agents are
coordinated by a workflow the developer writes, as the multi-agent planner sample does, step by
step in code. A judgment is asked only from an agent's handler.

Every well-known way of making agents reason is a few shapes of control flow: one agent loops on
its own until it is done; several answer the same input and their answers are put together; one
agent is given each item of a list; one drafts and another critiques until the draft passes. Each
team that wants one today writes the workflow, its steps, its timeouts, its fan-out and its
collection of results, and writes it again for the next. Changing the instructions of one agent in
it is a deployment.

Four decisions shape this feature.

- **The patterns are the platform's; a blueprint chooses them.** A blueprint is a value: the
  workers taking part, each with instructions, a model, tools and a budget, and the steps, each
  using one pattern with its parameters, naming the workers it uses and the earlier results it
  reads. It has no conditions and no loops of its own; every repetition is inside a pattern and
  bounded by it. A configuration format that grows conditions becomes a poor programming language,
  and a service that needs one writes a workflow, as it does today.
- **A blueprint is held, not deployed.** A service registers a blueprint with a call, and the
  platform holds it as a platform component holds a task. Nothing is edited: a changed blueprint is
  a new version of it, and every version is kept. Code is deployed once, the service that runs
  blueprints; a blueprint names only the tools, MCP servers, models, guardrails and judgment
  questions that service registers for blueprints.
- **A run is held too.** A run is one carrying out of one version, started by a call or by the
  blueprint's schedule. It keeps the version it ran, its input, each step's result, the sessions of
  the workers that produced them and what they used, and it survives a restart with no completed
  step done again.
- **Blueprints compose through records, not calls.** A blueprint does not start another. One run's
  tools write the service's own records, and another run's tools read them.

The example is a weekly research digest, from a use-case ankka-reasoning is building towards: a
watch that searches literature sources on a cadence and keeps one entry per paper, however many
sources it is found in; and a digest that, every Sunday night, reads the papers found in the past
week and writes a podcast script in which every statement names the paper it comes from. It needs
a schedule, a fan-out, a map, a reduce and a critique, and no person in the loop.

What this feature is not: it is not branching search (several drafts scored and the weaker
pruned), a blueprint starting or waiting on another, conditions or loops in a blueprint, replaying
or forking a run, a console view of runs, or speech from a script.

## Clarifications

### Session 2026-10-07

- Q: Who may register a blueprint? → A: The service holds it; any of its components may register one, and who may from outside is whatever the service's endpoints and their ACLs allow, as for starting a task. The platform adds no access rule of its own.
- Q: After an outage, one run or one run per missed period? → A: The schedule says which. By default one run covers every missed period; a schedule may ask for one run per missed period instead, oldest first, each with its own period.
- Q: Which languages does this feature reach? → A: Scala. Python and TypeScript services, through the sidecar, are a follow-up feature.
- Q: Where do the research digest sample's words and scenarios live? → A: In the sample's own features and glossary, as the shopping cart's are; the root features keep one scenario saying the sample's features pass offline.
- Q: How are the proposed glossary terms settled? → A: All accepted as written except for-each step, work step and input shape, which stay proposed until planning has used them.

### Planning 2026-10-07

Found while planning, with the reason in [research.md](research.md):

- What a blueprint may name (tools, MCP servers, models, guardrails, judgment questions) is what the service registers for blueprints; nothing lists the tools a service's agents have, since an agent chooses them inside its handler (R6).
- A cancelled run lets the ask turn in progress finish; nothing starts after it (R17).
- Each item of a for-each or gather step is one ask turn; a work loop per item is not in this feature (R5).
- A gather step may be *chosen by* an earlier step's result: a list of names picking which of the workers the step names run (R22). Without it the planner's selection could not be a blueprint, and SC-001 would not hold.
- An ask turn interrupted by a restart runs again from its start, model calls included; only a work step records each model call (R18).
- A run that ends while it waits for a decision refuses the approval request itself, so the tool never runs for a run that has ended (R16).
- Due times missed in an outage are computed from, and their runs use, the version current when the service is back (R13).
- A worker's budget bounds one turn; the run budget bounds the run (R7).
- A schedule in a service without timers is a problem the check names, whether the blueprint is carried or registered by a call (R6).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Register a blueprint and have it checked (Priority: P1)

A developer writes a blueprint as a value: a name, the shape of a run's input, its workers and its
steps. The service registers it with one call. The platform checks it whole before holding it, and
a blueprint with problems is refused with every problem named, in one answer. Registering the same
blueprint again is the version already held; registering a different one under the same name holds
a new version beside the earlier ones. A reader lists a blueprint's versions and reads any of them
as it was registered. A service may also carry blueprints in its code, registered when it starts.

**Why this priority**: Everything else runs a held version. Checking before holding is what lets a
blueprint be data without being a way to break a running service.

**Independent Test**: In the testkit, register a blueprint and read it back; register it again and
find one version; change one worker's instructions, register it and find two versions, the first
unchanged. Register a blueprint with four problems and receive one refusal naming all four, with
nothing held.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/blueprints/registering.feature`: a blueprint is registered and read back as it was registered
- added `features/blueprints/registering.feature`: a blueprint registered twice is one version
- added `features/blueprints/registering.feature`: a changed blueprint is a new version, and the earlier version is kept
- added `features/blueprints/registering.feature`: a blueprint version is never changed
- added `features/blueprints/registering.feature`: a reader lists a blueprint's versions in order
- added `features/blueprints/registering.feature`: a blueprint that comes with a service is registered when it starts
- added `features/blueprints/registering.feature`: a blueprint that comes with a service and is unchanged adds no version when the service starts again
- added `features/blueprints/checking.feature`: a blueprint is refused with every problem named in one answer
- added `features/blueprints/checking.feature`: a worker naming a tool the service does not have is refused
- added `features/blueprints/checking.feature`: a worker naming a model the service does not have is refused
- added `features/blueprints/checking.feature`: a step reading a step that comes after it is refused
- added `features/blueprints/checking.feature`: a step naming a pattern the platform does not have is refused
- added `features/blueprints/checking.feature`: a step whose pattern needs a list and reads something else is refused
- added `features/blueprints/checking.feature`: a worker with no budget is refused
- added `features/blueprints/checking.feature`: a step naming a judgment question the service does not have is refused
- added `features/blueprints/checking.feature`: a blueprint with a schedule is refused in a service without timers
- added `features/blueprints/checking.feature`: a worker no step uses is noted and the blueprint is held

---

### User Story 2 - Start a run and read what it did (Priority: P1)

A caller starts a run of a blueprint with an input of the blueprint's input shape, under an id it
chooses. The platform carries out the steps in order with the version that was current when the
run started, each step given the run's input and the earlier results it reads, and holds each
step's result once the step ends. A reader reads the run at any time: its version, its input, which
step it is on, each finished step's result, the sessions of the workers that produced it, the model
usage of each step and of the whole run, and, once it has ended, whether it completed, failed or was
cancelled, and why. A caller may wait for a run to end. A run survives a restart of the service: a
step that had ended is never done again, and a model call that had been recorded is never made
again. An ask turn records its model calls when it ends, so a turn interrupted part way runs again
from its start; a work step records each iteration, so it resumes exactly.

**Why this priority**: A held run is the second half of the feature: it is what makes a blueprint's
work durable, readable and comparable between versions.

**Independent Test**: In the testkit with a scripted model, start a run of a three-step blueprint,
restart the service during its second step, and wait for it to complete: every step's result is
held, the first step's model calls were made once, and the run names the version it ran. Register a
new version during the run and find that the run kept the old one.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/blueprints/runs.feature`: a run carries out a blueprint's steps in order and holds each step's result
- added `features/blueprints/runs.feature`: a run keeps the version that was current when it started
- added `features/blueprints/runs.feature`: a run started twice under one id is one run
- added `features/blueprints/runs.feature`: a run id used again for a different blueprint or input is refused
- added `features/blueprints/runs.feature`: a run whose input does not have the blueprint's input shape is refused
- added `features/blueprints/runs.feature`: a step is given the run's input and the results it reads
- added `features/blueprints/runs.feature`: a reader reads a run's progress while it runs
- added `features/blueprints/runs.feature`: a run names the sessions of the workers in each step
- added `features/blueprints/runs.feature`: a run gives the model usage of each step and of the whole run
- added `features/blueprints/runs.feature`: a caller waits for a run to end
- added `features/blueprints/runs.feature`: a run resumes after a restart and no ended step is done again
- added `features/blueprints/runs.feature`: a model call recorded before a restart is not made again
- added `features/blueprints/runs.feature`: an ask turn interrupted by a restart runs again from its start
- added `features/blueprints/runs.feature`: a step that fails fails the run, naming the step and why
- added `features/blueprints/runs.feature`: a failed run is not tried again by the platform
- added `features/blueprints/runs.feature`: a run that spends its run budget fails, saying so
- added `features/blueprints/runs.feature`: a cancelled run finishes the turn in progress and starts nothing after it
- added `features/blueprints/runs.feature`: a step's result that does not have its declared shape is returned to the worker to correct
- added `features/blueprints/runs.feature`: a tool that requires approval makes the run wait for a decision
- added `features/blueprints/runs.feature`: a run that ends while it waits for a decision refuses the approval request
- added `features/blueprints/runs.feature`: a reader lists the runs of a blueprint with the version each ran

---

### User Story 3 - Steps that use workers in the well-known ways (Priority: P1)

Each step uses one pattern, and the patterns are the platform's:

- **ask**: one worker answers the step's input once, running its tools as a request agent does.
- **work**: one worker works the step's input on its own, iteration after iteration, until it
  completes with a result, gives up, or spends its budget, recording every iteration as an
  autonomous agent does.
- **for-each**: one worker is given each item of a list, all at once, and the step's result is their
  results in the list's order.
- **gather**: several workers, or one worker several times, are given the same input at once, and
  the step's result is all of their results together. A gather may be chosen by an earlier step's
  result: a list of names picking which of the workers the step names run this time. The step's
  list is the limit; the earlier step chooses within it.
- **judge**: typed questions about the step's input are answered by a judgment, with the
  probabilities behind each answer.
- **critique**: one worker drafts; a verdict, from a judgment's yes or no or from a critic with
  its reasons, passes the draft or returns it with the reasons; the worker drafts again,
  up to a number of rounds the step names. A draft that has not passed after the last round fails
  the step, or is kept marked as not passed, as the step says.

**Why this priority**: The patterns are what a developer no longer writes. Each is the shape of a
well-known way of reasoning: work is a loop of thinking and acting, gather is several answers put
together, for-each and ask are map and reduce, critique is a draft and its critique.

**Independent Test**: In the testkit with a scripted model and a scripted judgment provider, run a
blueprint with one step of each pattern and read each step's result; for for-each, give a list of five
and find five results in order; for critique, script a check that fails twice and passes once and
find three drafts in the step's sessions and the third as the result.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/blueprints/patterns.feature`: an ask step is one worker's answer
- added `features/blueprints/patterns.feature`: a work step iterates until its worker completes
- added `features/blueprints/patterns.feature`: a work step that spends its worker's budget fails
- added `features/blueprints/patterns.feature`: a for-each step gives each item to the worker and keeps the results in order
- added `features/blueprints/patterns.feature`: a for-each step over an empty list has an empty result
- added `features/blueprints/patterns.feature`: a for-each step's items run at once, up to the step's limit
- added `features/blueprints/patterns.feature`: one failed item fails a for-each step unless the step keeps going
- added `features/blueprints/patterns.feature`: a gather step gives every worker the same input and keeps every result
- added `features/blueprints/patterns.feature`: a gather step chosen by an earlier step runs only the workers it names
- added `features/blueprints/patterns.feature`: a judge step's result is the judgment's answers with their probabilities
- added `features/blueprints/patterns.feature`: a critique step drafts again with the check's reasons until the draft passes
- added `features/blueprints/patterns.feature`: a critique step's check may be a judgment
- added `features/blueprints/patterns.feature`: a draft that has not passed after the last round fails the step
- added `features/blueprints/patterns.feature`: a draft that has not passed after the last round is kept when the step says so
- added `features/blueprints/patterns.feature`: each worker in a step has a session of its own

---

### User Story 4 - Runs on a schedule (Priority: P2)

A blueprint may carry a schedule: a cadence, every so many hours or days, or weekly on a day at a
time, in a named time zone. On each due time the platform starts a run whose input is the period it
covers: from the previous due time to this one. Periods follow one another with no gap and no
overlap. When the service was down over one or more due times, one run starts when it is back,
covering everything since the last run's period, unless the schedule asks for one run per missed
period, as a weekly digest whose runs are episodes would. A scheduled run uses the version current at its
due time, so a new version takes effect at the next run with no deployment; runs that catch up
after an outage use the version current when the service is back, whose schedule says which due
times were missed. The first period of a schedule starts one cadence before its first due time. A schedule is stopped by
registering a version without one.

**Why this priority**: A watch and a digest are both schedules. Everything in this story could be
done by a timer calling the start of a run; the platform owning the period is what makes "the week
since the last digest" exact.

**Independent Test**: In the testkit with a moved clock, register a weekly blueprint for Sunday at
20:00 in a named time zone and move through three Sundays: three runs, whose periods meet end to
start. Stop the service over two Sundays and start it: one run, covering both weeks; with a schedule
that asks for one run per missed period, two runs, a week each, the older first. Register a
version without a schedule: no further run starts.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/blueprints/schedules.feature`: a scheduled blueprint starts a run at each due time
- added `features/blueprints/schedules.feature`: a scheduled run's input is the period since the previous due time
- added `features/blueprints/schedules.feature`: periods meet with no gap and no overlap
- added `features/blueprints/schedules.feature`: due times missed while the service was down start one run covering them all
- added `features/blueprints/schedules.feature`: a schedule that asks for one run per missed period starts one for each, oldest first
- added `features/blueprints/schedules.feature`: a weekly cadence keeps its time of day across a change of the clocks
- added `features/blueprints/schedules.feature`: a scheduled run uses the version current at its due time
- added `features/blueprints/schedules.feature`: runs that catch up after an outage use the version current when the service is back
- added `features/blueprints/schedules.feature`: a version without a schedule stops the schedule
- added `features/blueprints/schedules.feature`: a scheduled run is started once however many instances the service has

---

### User Story 5 - Tools know which run they serve, and others can follow runs (Priority: P2)

A tool a worker calls during a run is told the run, the step and the blueprint version, so that what
it writes can say which run wrote it. A service's consumers can subscribe to its blueprint versions
and its runs as to any entity's changes, so that another component, or another system, can keep its
own record of every run. ankka-reasoning asks for both: it will link every record a run writes to the
run, and publish runs and versions into its graph.

**Why this priority**: It is the seam the asking application needs, and the reason runs are records
rather than workflow internals; without it a run is readable only through its own calls.

**Independent Test**: In the testkit, run a blueprint whose worker calls a tool that records the run
and step it was told, and find both; subscribe a consumer to the service's runs and find one change
for each step that ended and one for the run's end, in order.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/blueprints/following.feature`: a tool called in a run is told the run, the step and the version
- added `features/blueprints/following.feature`: a tool called outside a run is told it is in none
- added `features/blueprints/following.feature`: a consumer subscribes to a service's runs
- added `features/blueprints/following.feature`: a consumer subscribes to a service's blueprint versions
- added `features/blueprints/following.feature`: a consumer is told of each step's end and of the run's end, in order

---

### User Story 6 - The research digest sample (Priority: P3)

A sample service registers two blueprints. The watch runs daily: a gather step searches each
literature source for the service's search criteria with its tools, and an ask step keeps one entry
per paper in the service's own records, a paper found in several sources being one entry by its
identifier, with the time it was found. The digest runs weekly on Sunday evening: an ask step reads
the entries found in its period, a for-each step has a reader note what each paper finds, an ask step
groups and relates them, and a critique step writes a podcast script whose every statement names a
paper, checked by a judgment. A developer runs the whole sample offline, with scripted sources, a
scripted model and a scripted judgment provider, and online with a model key and real sources.

**Why this priority**: The sample is what the documentation shows and what proves the patterns fit
a real use. It depends on every other story.

**Independent Test**: Run the sample's suite offline: three scripted sources return forty papers of
which ten are the same paper twice; the watch keeps thirty entries; the digest for that week reads
thirty, its script names only those papers, and each run names the version it ran. What the
watch and the digest do is said by the sample's own features and glossary, under
`samples/research-digest/`, as the shopping cart's are: one entry per paper however many sources
find it; the digest reads the papers found in its period and no others; every statement in the
script names a paper the digest read; a paper found after its digest's period is in the next
digest; a week with no papers has a digest that says so; and a source that cannot be reached is
named in the watch's run while the others are kept.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/blueprints/research-digest.feature`: the research digest sample's features pass offline
- added `features/documentation/blueprints.feature`: the blueprints guide documents every pattern with samples from tested code

---

### Edge Cases

- A blueprint names a tool, model or judgment question the service does not have: refused, naming
  each (*a worker naming a tool the service does not have is refused* and its siblings).
- A blueprint is registered again unchanged, by two instances of the service starting at once: one
  version (*a blueprint registered twice is one version*).
- A new version is registered while a run is in progress: the run keeps its own (*a run keeps the
  version that was current when it started*).
- The service restarts mid-step: the step resumes by its pattern's rules; ended steps are not done
  again and recorded model calls are not made again; a tool may be run again, as an autonomous
  agent's is (*a run resumes after a restart and no ended step is done again*).
- A worker returns a result that does not have the step's declared shape: it is told why and tries
  again within its budget (*a step's result that does not have its declared shape is returned to
  the worker to correct*).
- A for-each step is given an empty list: an empty result, and the steps after it run (*a for-each step over an
  empty list has an empty result*).
- A for-each step is given a thousand items: they run at most the step's limit at once (*a for-each step's items
  run at once, up to the step's limit*).
- A critique never passes: the step fails or keeps the last draft marked as not passed, as the step
  says (*a draft that has not passed after the last round fails the step*).
- A tool in a step requires a person's approval: the run waits, its budget does not advance while
  it waits, and the run's record shows the approval request (*a tool that requires approval makes
  the run wait for a decision*). Its time limit still runs. A run that ends while waiting refuses
  the request, so a later decision runs nothing (*a run that ends while it waits for a decision
  refuses the approval request*). Several items of a for-each may wait at once.
- The service restarts in the middle of an ask turn: the turn runs again from its start, model
  calls included (*an ask turn interrupted by a restart runs again from its start*).
- A run id is used again for another blueprint, or the same blueprint with another input: refused
  (*a run id used again for a different blueprint or input is refused*).
- A gather chosen by an earlier step is given an empty list: no worker runs and the result is
  empty; a name the step does not list cannot arrive, since the choosing step's shape names the
  workers.
- The service was down over two due times: one run covering both periods, or two runs, one per
  period, when the schedule asks for that (*due times missed while the service was down start one
  run covering them all*; *a schedule that asks for one run per missed period starts one for each,
  oldest first*).
- The clocks change between two weekly due times: the run still starts at the named local time, and
  the period is that much shorter or longer (*a weekly cadence keeps its time of day across a change
  of the clocks*).
- Several instances of the service are running at a due time: one run (*a scheduled run is started
  once however many instances the service has*).
- A model provider is down during a step: retried as an autonomous agent's model calls are, and a
  run that cannot proceed within its time limit fails, saying so.

## Requirements *(mandatory)*

### Functional Requirements

**Blueprints**

- **FR-001**: A service MUST be able to register a blueprint by a call: a name, the shape of a
  run's input, its workers, its steps, and optionally a schedule, a run budget and a time limit. A
  Scala service MUST be able to, and a service MAY carry blueprints in its code, registered when it
  starts; a carried blueprint with problems MUST fail the start, naming them. Any component of the service MAY register a blueprint and start a
  run; the platform adds no access rule of its own, so who may from outside the service is what
  its endpoints and their ACLs allow.
- **FR-002**: A worker MUST be defined by data: a name, instructions, one of the models the service
  registers for blueprints, a list of the tools it registers for blueprints (its own functions and
  MCP servers' tools), guardrails it registers for blueprints, and a budget of model calls for one turn of the worker (an
  item, a round, or the task of a work step).
- **FR-003**: A step MUST name one pattern (ask, work, for-each, gather, judge, critique), the workers or
  judgment questions it uses, the run's input or the earlier steps it reads, the shape of its
  result, and the pattern's parameters. A blueprint MUST have no other conditions, branches or
  loops.
- **FR-004**: A blueprint MUST be checked whole before it is held: every tool, model, guardrail and
  judgment question it names is registered by the service for blueprints; every step reads only the run's input or
  steps before it; every pattern exists and is given what it needs (a list for a for-each step, a check to
  critique with); every worker has a budget; worker and step names are unique and no step is named `input`; a
  judgment verdict names a yes-or-no question; a gather chosen by a read reads a list of strings; the
  schedule, when given, is a cadence the platform has in a zone it knows, and the service has
  timers. A refusal MUST name every problem in one answer, and nothing is held.
- **FR-005**: A blueprint version MUST never change. Registering a blueprint identical to the
  current version MUST be that version; registering a different one under the same name MUST hold
  a new version, numbered after the last, and keep every earlier version readable.
- **FR-006**: A reader MUST be able to list a blueprint's versions and read any as it was
  registered.

**Runs**

- **FR-007**: A caller MUST be able to start a run of a blueprint with an input of its input shape,
  under a run id it chooses; a run started again under the same id with the same blueprint and input
  MUST be the run already held, and with a different blueprint or input MUST be refused. Run ids
  starting `schedule:` are the platform's. The run MUST use the version current when it started, to
  its end.
- **FR-008**: A run MUST carry out its steps in the order the blueprint gives, each given the run's
  input and the results it reads, and MUST hold each step's result when the step ends.
- **FR-009**: A reader MUST be able to read a run at any time: its blueprint and version, its input,
  the step it is on, each ended step's result, the session of each worker in each step, the model
  and judgment usage of each step and of the run, its status, who or which schedule started it, when
  it started and ended, and for an ended run whether it completed, failed or was cancelled, and why. A reader MUST be able to list a blueprint's runs and
  wait for a run to end.
- **FR-010**: A run MUST survive a restart of its service and of any instance of it: an ended step
  MUST NOT be done again, and a model call recorded before the restart MUST NOT be made again. A
  tool whose result was not recorded MAY be run again, as an autonomous agent's may. A work step
  records each model call; an ask turn records its model calls when the turn ends, so a turn
  interrupted part way is run again from its start.
- **FR-011**: A step that fails MUST fail the run, naming the step and the reason; the platform MUST
  NOT start a failed run again, and trying again is a new run.
- **FR-012**: A run MUST stop and fail, saying so, when it spends its run budget of model calls or
  passes its time limit, where the blueprint gives them; the time limit is of wall-clock time and
  runs while the run waits for a decision. A caller MUST be able to cancel a run:
  a turn in progress runs to its end, and no turn, item, round, iteration or step starts after it.
- **FR-013**: A worker's result that does not have the step's declared shape MUST be returned to the
  worker with the reason, within its budget, as an autonomous agent's rejected result is.
- **FR-014**: A tool that requires approval, called in a step, MUST make the run wait for the
  decision, with its budget not advancing, and the run's record MUST show every approval request
  awaiting a decision. A run that ends while waiting MUST refuse each such request itself, with a
  note saying the run ended.

**Patterns**

- **FR-015**: ask MUST be one worker's answer to the step's input, running its tools.
- **FR-016**: work MUST iterate one worker until it completes with a result, gives up, or spends its
  budget, recording each iteration so that a restart resumes it as an autonomous agent's task
  resumes.
- **FR-017**: for-each MUST give each item of a list to the worker, with at most the step's limit at
  once, and its result MUST be the items' results in the list's order; an empty list MUST give an
  empty result. A failed item MUST fail the step unless the step says to keep going, when the
  result marks each failed item.
- **FR-018**: gather MUST give the same input to each named worker, or to one worker a named number
  of times, at once, and its result MUST be every result, each with the worker that gave it. A
  gather MAY be chosen by a read of an earlier step's result, a list of strings; then only the named
  workers among the step's run, in the step's order, and an empty list gives an empty result.
- **FR-019**: judge MUST ask the named judgment questions about the step's input and its result
  MUST be the judgment's answers with their probabilities.
- **FR-020**: critique MUST have one worker draft, check the draft with a judgment's yes or no or
  another worker's verdict and reasons, and return a draft that does not pass to the drafting worker
  with the reasons, up to the step's number of rounds. After the last round a draft that has not
  passed MUST fail the step, or be kept marked as not passed when the step says so.
- **FR-021**: Each worker in each step MUST have a session of its own, and the run MUST name it.

**Schedules**

- **FR-022**: A blueprint MAY carry a schedule: every so many hours or days, or weekly on a day at a
  time, in a named time zone. At each due time the platform MUST start one run of the version
  current then, whatever the number of instances, with the period from the previous due time to
  this one as its input.
- **FR-023**: Periods MUST meet with no gap and no overlap. The first period of a schedule starts one
  cadence before its first due time. Due times missed while the service was down are those of the
  schedule of the version current when it is back, and their runs use that version; they MUST start one run, covering from the end of the last run's period to the
  latest due time passed; or, when the schedule asks for one run per missed period, one run for
  each missed due time, oldest first, each covering its own period.
- **FR-024**: Registering a version without a schedule MUST stop the schedule; registering one with
  a schedule again MUST start it from its next due time, the first period starting where the last
  scheduled run's ended.

**Following runs**

- **FR-025**: A tool called during a run MUST be able to read the run's id, the step's name and the
  blueprint's name and version; called outside a run, it MUST be told it is in none.
- **FR-026**: A service's consumers MUST be able to subscribe to its blueprint versions and its
  runs, receiving each version when held and each change of a run (a step ended, the run ended) in
  the order it happened.

**The sample and documentation**

- **FR-027**: A sample service MUST show the research digest: a scheduled watch keeping one entry per
  paper by identifier, and a scheduled digest whose script names the papers it uses, checked by a
  judgment. Its features and glossary MUST be its own, beside its code, and its suite MUST run them
  offline with scripted sources, a scripted model and a scripted judgment provider.
- **FR-028**: A blueprints guide MUST document blueprints, every pattern, runs and schedules with
  samples from tested code, and the limitations page MUST say what blueprints do not cover.

### Key Entities *(include if feature involves data; each a term in the project glossary)*

- **Blueprint**: a named description of agents working together, held by the service that runs it:
  its input's shape, its workers, its steps, and optionally a schedule and a run budget. It has
  versions.
- **Blueprint version**: one registration of a blueprint, numbered, never changed.
- **Worker**: an agent a blueprint defines by data: instructions, a model, tools, guardrails and a
  budget. It is not a component.
- **Pattern**: one of the ways the platform has for a step to use workers: ask, work, for-each, gather
  (optionally chosen by an earlier step), judge, critique.
- **Run**: one carrying out of one blueprint version, under an id: its input, each step's result,
  the workers' sessions, usage, and its status.
- **Run status**: where a run stands: running, waiting for a decision, or ended completed, failed
  or cancelled.
- **Schedule**: a blueprint's cadence and time zone, from which its due times follow, and whether
  missed due times start one run or one run per missed period.
- **Period**: the span a scheduled run covers, from the previous due time to its own.
- **Draft**, **critic**, **verdict**: in a critique, what the drafting worker produces, the worker
  that judges it, and its decision: the draft passes, or goes back with reasons.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A pattern a developer used to write as a workflow (the multi-agent planner's select,
  consult and summarise, consulting only the specialists the selector chose) is a blueprint of three
  steps and no code beyond its tools, and its suite passes with the same scripted model, except the
  case that falls back to a default specialist when the selector names none: a blueprint has no
  default, and that case becomes a shape refusal the model corrects.
- **SC-002**: A run whose service is restarted once in each of its steps completes, in the testkit,
  with every model call recorded before a restart made exactly once.
- **SC-003**: A blueprint with N problems, for every N from one to the number of checks, is refused
  with exactly N problems named, and nothing is held.
- **SC-004**: Changing a worker's instructions takes no deployment: a version registered while the
  service runs is used by the next run started, and the run before it names the earlier version.
- **SC-005**: Over five weekly due times with a moved clock, every scheduled run starts within the
  timer sweeper's poll interval of its due time, the periods meet with no gap or overlap, and two
  due times missed in an outage start exactly one run, or exactly two when the schedule asks for
  one run per missed period.
- **SC-006**: The research digest sample, offline, keeps thirty entries from forty scripted papers
  of which ten are found twice, and its script names only papers it read, every statement naming
  one.

## Assumptions

- A blueprint's patterns are carried out by the platform's own components, as an autonomous agent's
  tasks are: a run is a platform component with a durable record, and a work step is an autonomous
  agent's task given the worker's definition for that task. Per-task definitions are added for
  blueprints and need not be offered to autonomous agents directly.
- A blueprint is held by the service that runs it, not by the control plane or a project: it names
  that service's tools and models, and registering one needs whatever the service's own endpoint
  allows. Any component of the service may register and start.
- A model is named: the service gives each model it may use a name, and a blueprint names models by
  them. A service with one model has one name, `default`.
- The shapes of a run's input and of a step's result are written as JSON Schema, in the subset the
  platform checks.
- A tool may be run again after a restart, as an autonomous agent's may; the sample's tools are
  written to tolerate it.
- A schedule's due times are kept by the platform's timers, one timer per due time, computed with
  the time zone database the JVM carries; a service with a scheduled blueprint has timers.
- Listing a blueprint's runs, and following runs and versions, use the service's projections; a
  service without them is refused a listing with the reason, and everything else works.
- The sample's online sources are reached by its own tools over each source's public interface;
  arXiv is not among them, as it carries little of the life sciences the sample searches.

## Dependencies

- 015 autonomous agents: tasks, iterations and resumption, which a work step reuses.
- 018 judgments: questions and the scripted judgment provider, for judge and critique steps; this
  feature is how a judgment is asked outside an agent's handler.
- 029 approvals and MCP: tools that require approval, and MCP servers' tools, which a worker may
  name.
- 032 recurring timers: not needed. A schedule keeps one timer per due time on the existing timers
  (research R12), so it does not wait for 032, which covers intervals and not time zones.
- Asked for by ankka-reasoning (its request 13), which will publish blueprint versions and runs into
  its reasoning graph through FR-025 and FR-026.

## Not in this feature

- Branching search: several drafts scored and the weaker pruned.
- A blueprint starting, waiting on or reading another's run directly; blueprints compose through a
  service's records.
- Conditions, branches or loops in a blueprint.
- Replaying or forking a run, or starting a run from another run's step.
- A console view of blueprints and runs.
- A step that waits for a person as its own pattern; a person is reached through a tool that
  requires approval.
- Models named by a blueprint that the service does not register for blueprints, and tools defined
  in a blueprint.
- A work loop for each item of a for-each or gather step; each item is one ask turn.
- Cost in money; usage is in tokens, as elsewhere.
- Speech from a script.
- Python and TypeScript services, and the sidecar protocol calls they would need: a follow-up
  feature, once the calls have settled in Scala.
- A service built as a WebAssembly module.
