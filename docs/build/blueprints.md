---
title: Blueprints
description: Describe workers and the steps between them as data the platform runs — ask, work, for-each, gather, judge, critique and call steps — register it, start runs by hand or on a schedule, follow them, and test with a scripted model.
kind: guide
languages: [scala]
components: [agent, autonomous-agent]
related: [build/agents.md, build/autonomous-agents.md, build/multi-agent-orchestration.md, build/judgments.md, build/timers.md, build/testing.md, reference/limitations.md]
---

# Blueprints

A blueprint describes a reasoning process as data: the workers it needs — each an agent defined by
instructions, tools, a model and a budget — and the steps between them, each reading the run's input or
earlier steps' results and giving a result of a declared shape. A service registers it, and the platform
runs it: starts a run on a call or on a schedule, carries out each step as it becomes ready, keeps every
result, and picks a run up where it stopped after a crash. There is no orchestration code to write and
nothing to deploy when the process changes: a changed blueprint is a new version, and the next run uses
it.

A blueprint is held, never deployed. It has no conditions, branches or loops of its own; every repetition
is inside a step and bounded by it, and what a step does depends on what earlier steps gave, never on a
test written into the blueprint. That is what makes a run readable afterwards as a record, and a
blueprint checkable before it is held. [Multi-agent orchestration](multi-agent-orchestration.md) is the
same work done in a workflow, with code; when the steps are one of the patterns here, a blueprint says
the same thing in less and the platform owns the durability.

Blueprints are Scala only for now; see [Limitations](../reference/limitations.md).

## A blueprint, step by step

Every step is an **action**, how many times it is done (**over**), and optionally what its worker drafts
**until**. The named patterns are the common combinations; each is a builder on `Step`. This blueprint
has one of each of the five that run once:

<!-- include: modules/testkit/src/test/scala/com/thinkmorestupidless/ankka/testkit/blueprint/GuideSamples.scala#blueprint -->
```scala
val text: Shape = Shape.obj("text" -> Shape.string)

val brief: Blueprint = Blueprint("brief")
  .input(Shape.obj("topic" -> Shape.string))
  // Workers are data: instructions, tools by name, a model by name, and a budget of model calls.
  .worker(Worker("writer").instructions("You write briefly.").tools("note").budget(4))
  .worker(
    Worker("researcher")
      .instructions("You research until you have a draft.")
      .tools("note")
      .budget(6)
  )
  .worker(Worker("critic").instructions("You pass a draft only when it is on topic.").budget(2))
  // An ask step: one turn of a worker, its answer checked against the step's shape.
  .step(
    Step("outline")
      .ask("writer")
      .reads("input")
      .result(Shape.obj("points" -> Shape.arr(Shape.string)))
  )
  // A work step: an autonomous task the worker iterates until it completes it, within its budget.
  .step(Step("draft").work("researcher").reads("outline").result(text))
  // A judge step: a judgment's answers to questions about what the step reads.
  .step(Step("check").judge("on-topic").reads("draft").result(Shape.any))
  // A critique step: the writer drafts until the critic's verdict passes, within two rounds.
  .step(
    Step("polish")
      .critique("writer", Verdict.critic("critic"), rounds = 2)
      .reads("draft", "check")
      .result(text)
  )
  // A call step: a handler the service registered, with what the step reads.
  .step(
    Step("file")
      .call("archive")
      .reads("polish")
      .result(Shape.obj("filed" -> Shape.boolean, "run" -> Shape.string))
  )
  .runBudget(20)
```

- An **ask step** is one turn of a worker: its instructions as the system message, what the step reads
  as the user message, its tools; the answer is checked against the step's result shape and, when it
  does not fit, sent back with the problems for the worker to correct, within the worker's budget of
  model calls.
- A **work step** hands the worker's instructions, tools, model and budget to the platform's one
  worker agent as a task of its own, which iterates until the model completes the task with a result of
  the step's shape, gives up, or spends the budget — an [autonomous agent](autonomous-agents.md) whose
  definition is the blueprint's.
- A **judge step** asks a [judgment](judgments.md): typed questions about what the step reads, answered
  by a System One model with the probabilities behind each answer. Its result is the answers; it makes
  no text.
- A **critique step** has one worker draft and another, the critic, pass the draft or return it with
  reasons, up to the step's number of rounds. The verdict can be a judgment question instead of a critic:
  `Verdict.judgment("on-topic")`. A step that runs out of rounds fails, unless it is told to keep the
  last draft (`keepLast = true`), when its result says the draft did not pass and why.
- A **call step** runs a handler the service registered, given which run it serves and what the step
  reads, and keeps what the handler returns. It is the one step that is code: anything the patterns do
  not cover is built here, with the run's durability around it. A handler may run again after a
  restart, as a tool may, so it tolerates a repeat.

A step reads by name: `input` is the run's input, `outline` an earlier step's whole result, and
`select.specialists` one field of it. Steps that read only the input, or only steps that have ended,
run at once; the blueprint's steps are a graph, and the reads are its edges. A step's result is the
JSON its worker answered, in the shape the step declared.

The two patterns that repeat take their list or their workers from the step:

- A **for-each step** does its action once per item of a list an earlier step gave, at most `limit` at
  once (four by default), and its result is the items' results in the list's order. One item failing fails
  the step, unless the step `keepGoing`s, when the failed item's result is `null` and the failure is on the
  item's record.
- A **gather step** does its action once per worker of several, or so many times by one worker, each
  given the same input at once; its result is a list of `{worker, result}`, or `{n, result}`. Which
  workers run can be chosen by an earlier step's result: a list of names among the step's own.

Any action may repeat and any turn may draft until a verdict, so a for-each of work steps, or a critique
whose drafter is a worker with tools, is a combination, not a new pattern:

```scala
Step("dig").work("researcher").each("papers.papers", limit = 4).result(Shape.arr(text))
Step("views").ask("reviewer").times(3).reads("draft").result(Shape.arr(Shape.obj("n" -> Shape.integer, "result" -> text)))
```

The check refuses the combinations that mean nothing — a judgment or a call repeated over workers, a
call drafting until a verdict — naming the step.

### Shapes

A result shape, and the input shape a run's input is checked against, is a small subset of JSON Schema:
`type`, `properties`, `required`, `items`, `enum` and `description`. `Shape.obj("text" -> Shape.string)`
is an object with that one required property; `Shape.obj(fields, required)` makes some optional;
`Shape.arr(item)`, `Shape.enumOf("weather", "activity")`, `Shape.string`, `Shape.integer`, `Shape.number`,
`Shape.boolean`, and `Shape.any` for an object the step does not constrain. The shape is described to the
model, and an answer that does not have it goes back with where it did not — `$.specialists[0]: must be one
of "weather", "activity", "budget", not "astrology"` — so a shape is also how a blueprint says what a
worker may choose.

### Workers

A worker is data, not a component: `Worker("writer").instructions(...).tools("note").model("fast").
guardrails("polite").budget(4)`. Its tools, model and guardrails are names the service registered for
blueprints (below); `model` defaults to the runtime's default model. The budget is model calls per turn
for an ask, and iterations for a work step. Each worker in each step has a session of its own,
`run:<runId>:<step>:<worker>`, which the run names, so what a worker said is readable afterwards and a
turn already recorded there is not made again.

## What a blueprint may name

A blueprint names tools, MCP servers, models, guardrails, judgment questions and handlers; the service
says which exist, when it starts, from a `BlueprintContext` that gives the registry's builder the
component client and the view client, so a tool can call an entity or read a view:

<!-- include: modules/testkit/src/test/scala/com/thinkmorestupidless/ankka/testkit/blueprint/GuideSamples.scala#tools -->
```scala
/** A tool a worker may name; it calls an entity in a real service. */
val note: FunctionTool = FunctionTool
  .named("note")
  .describedAs("Keeps a note for the reader.")
  .param[String]("text", "What to note.")
  .handle { (text: String) =>
    noted.add(text): Unit
    "noted"
  }

/** A handler a call step runs: code with the run's durability, given what the step reads. */
val archive: BlueprintHandler = BlueprintHandler("archive") { (run, input) =>
  Json.obj("filed" -> Json.bool(input("polish").isDefined), "run" -> Json.str(run.runId))
}

/** A judgment question a judge step asks, and a verdict a critique step may draft until. */
val onTopic: YesNoQuestion = Question.yesNo("on-topic", "Is the draft about the topic asked for?")
```

<!-- include: modules/testkit/src/test/scala/com/thinkmorestupidless/ankka/testkit/blueprint/GuideSamples.scala#registry -->
```scala
/**
 * What the service's blueprints may name. A tool that needs the service is built from its
 * context.
 */
val registry: BlueprintRegistry =
  BlueprintRegistry.empty
    .tools(note)
    .handlers(archive)
    .questions(onTopic)
    .carrying(brief) // registered when the service starts; a changed blueprint is a new version
```

<!-- include: modules/testkit/src/test/scala/com/thinkmorestupidless/ankka/testkit/blueprint/GuideSamplesSuite.scala#runtime -->
```scala
private val agents = AgentRuntime
  .withDefaultModel(model)
  .withJudgments(judge)
  .withBlueprints(_ => GuideSamples.registry)
```

Register `AgentRuntime.descriptors` with the service, as for agents: the platform's blueprint, run and
worker components are among them. A blueprint with a schedule needs `TimerRuntime`, and listing a
blueprint's runs needs `ProjectionRuntime()`.

### Registering, and the check

A blueprint carried in the registry (`carrying`) is registered when the service starts; one built or
received later is registered through the runtime's calls, which are available once the service has
started — inject `agents.blueprints` into an endpoint or a workflow, as a `TimerRuntime`'s scheduler is:

```scala
val registered = agents.blueprints.register(brief)      // Registered(name, version, isNew, notes)
agents.blueprints.register(jsonText)                    // the same, from a blueprint written as JSON
agents.blueprints.versions("brief")                     // every version, oldest first
agents.blueprints.version("brief", 2)                   // one, as it was registered
```

Registering checks the blueprint whole first: every name resolves, every read names the input or an
earlier step, every shape is one the platform checks, a schedule's zone and cadence are sound and the
service has timers, a scheduled blueprint's input admits a period, a repeating step gives a list, and
each pattern's own rules (a critic is not the drafter, a gather has at least two workers or is chosen by
a step). A blueprint with problems is refused with every problem at once, as a `CommandError` of
`BadRequest`; `BlueprintRefusal.problemsOf(error)` reads them back and `BlueprintRefusal.describe(error)`
is one per line. A service carrying a blueprint with problems does not start. A note — a worker no step
uses — is answered with the registration, not a refusal.

A blueprint is never changed. Registering one whose canonical form is the current version's answers that
version; a different one is the next number, and a run uses the version current when it starts. The
canonical form is the JSON with keys sorted at every level, so two blueprints written in different orders
are one version.

### As JSON

A blueprint is data, so it can be written as JSON and kept beside the code, loaded with
`Blueprint.fromJson`. The research digest's weekly digest, the whole of it: an ask that reads the week's
papers, a for-each over them, an ask that relates them, and a critique drafting a script until a judgment
says every statement names a paper, on a schedule.

<!-- include: samples/research-digest/src/main/resources/blueprints/digest.json -->
```json
{
  "name": "digest",
  "input": {
    "type": "object",
    "properties": {
      "from": {"type": "string"},
      "to": {"type": "string"},
      "dueTimes": {"type": "array", "items": {"type": "string"}}
    },
    "required": ["from", "to", "dueTimes"]
  },
  "workers": [
    {
      "name": "librarian",
      "instructions": "You find the week's papers. Call papers_found_between with the period's from and to, then answer with the papers it gives, exactly as given.",
      "model": "default",
      "tools": ["papers_found_between"],
      "guardrails": [],
      "budget": 3
    },
    {
      "name": "reader",
      "instructions": "You read one paper and state in one sentence what it reports, naming it by its identifier.",
      "model": "default",
      "tools": [],
      "guardrails": [],
      "budget": 2
    },
    {
      "name": "editor",
      "instructions": "You relate the week's papers: group the statements into themes, each theme naming the papers it draws on by identifier.",
      "model": "default",
      "tools": [],
      "guardrails": [],
      "budget": 2
    },
    {
      "name": "writer",
      "instructions": "You write the week's script for someone to listen to: a statement for each paper, naming the paper by its identifier, ordered by theme. When no papers were found in the period, one statement saying so.",
      "model": "default",
      "tools": [],
      "guardrails": [],
      "budget": 3
    }
  ],
  "steps": [
    {
      "name": "papers",
      "does": {"type": "Ask", "worker": "librarian"},
      "over": {"type": "Once"},
      "until": null,
      "reads": ["input"],
      "result": {
        "type": "object",
        "properties": {
          "papers": {
            "type": "array",
            "items": {
              "type": "object",
              "properties": {"identifier": {"type": "string"}, "title": {"type": "string"}},
              "required": ["identifier", "title"]
            }
          }
        },
        "required": ["papers"]
      }
    },
    {
      "name": "read",
      "does": {"type": "Ask", "worker": "reader"},
      "over": {"type": "Each", "read": "papers.papers", "limit": 4, "keepGoing": false},
      "until": null,
      "reads": [],
      "result": {
        "type": "array",
        "items": {
          "type": "object",
          "properties": {"identifier": {"type": "string"}, "statement": {"type": "string"}},
          "required": ["identifier", "statement"]
        }
      }
    },
    {
      "name": "relate",
      "does": {"type": "Ask", "worker": "editor"},
      "over": {"type": "Once"},
      "until": null,
      "reads": ["read"],
      "result": {
        "type": "object",
        "properties": {
          "themes": {
            "type": "array",
            "items": {
              "type": "object",
              "properties": {"theme": {"type": "string"}, "papers": {"type": "array", "items": {"type": "string"}}},
              "required": ["theme", "papers"]
            }
          }
        },
        "required": ["themes"]
      }
    },
    {
      "name": "script",
      "does": {"type": "Ask", "worker": "writer"},
      "over": {"type": "Once"},
      "until": {"verdict": {"type": "Judgment", "question": "names-a-paper"}, "rounds": 2, "keepLast": false},
      "reads": ["input", "read", "relate"],
      "result": {
        "type": "object",
        "properties": {
          "statements": {
            "type": "array",
            "items": {
              "type": "object",
              "properties": {
                "text": {"type": "string"},
                "paper": {"type": "string", "description": "The identifier of the paper the statement is about."}
              },
              "required": ["text"]
            }
          }
        },
        "required": ["statements"]
      }
    }
  ],
  "schedule": {"cadence": {"type": "Weekly", "day": "SUNDAY", "time": "20:00"}, "zone": "Europe/London", "catchUp": "each"},
  "runBudget": null,
  "timeLimit": null
}
```

<!-- include: samples/research-digest/src/main/scala/digest/application/Blueprints.scala#registry -->
```scala
/**
 * What the service's blueprints may name: the tools over its records, the question, and the two.
 */
def registry(
    context: BlueprintContext,
    sources: Vector[Source],
    clock: Clock
): BlueprintRegistry =
  val tools = Tools(context.componentClient, context.viewClient, clock)
  BlueprintRegistry.empty.tools(tools.all(sources)*).questions(namesPaper).carrying(watch, digest)
```

## Runs

A run is one carrying out of one blueprint version under a run id the caller chooses. It is not a task
and not a workflow instance: it is a record, with the version it ran, its input, each step's result, the
sessions each step used, its usage and its status.

<!-- include: modules/testkit/src/test/scala/com/thinkmorestupidless/ankka/testkit/blueprint/GuideSamplesSuite.scala#run -->
```scala
val started =
  agents.runs.start("brief", Json.obj("topic" -> Json.str("ducks")), runId = "brief-7")
val run = agents.runs.await("brief-7", 60.seconds)
assertEquals(run.status, RunStatus.Completed)
assertEquals(
  run.stepNamed("polish").flatMap(_.result),
  Some(Json.obj("text" -> Json.str("ducks, polished")))
)
```

`start` checks the input against the blueprint's input shape and refuses one that does not fit. It is
idempotent: a run already held under the id with the same blueprint and input is answered as it stands,
and one with another input is a conflict. `get` reads a run; `await` waits for it to end; `cancel` asks it
to stop, which it does at the next step boundary, refusing any approval it was waiting for; `list(name)`
is a blueprint's runs oldest first, with the version each ran. A run's status is `Running`,
`WaitingForDecision`, or ended: `Completed`, `Failed` with a reason naming the step, or `Cancelled`.

A run has a budget of model calls, when the blueprint sets one (`runBudget`), and a time limit
(`timeLimit`); passing either fails the run. Usage is counted in tokens, per step and for the run, with a
judgment's tokens counted apart.

### Approvals

A tool that requires a person's approval stops the step where it is: the run's status becomes
`WaitingForDecision`, the step's record names the request (`waiting`, each with its session and approval
id), and the budget does not advance while it waits. Decide it as any agent's approval, on the worker's
session; the step goes on from where it stopped. A run cancelled or past its limit while waiting refuses
the requests itself.

### What resumes, and what may run again

Every step's result is written when the step ends, and a run's host is remembered by the cluster, so a
run working when its node stopped is started again on another, from its record, with nothing sent to it.
A step that had ended is not done again, nor an item or a round that had. An ask turn's messages are
written when the turn finishes, so a turn cut off part way runs again from its start — a model call
recorded before the restart is not made again, one inside the turn that was cut off is — and a work step
resumes its task at the iteration it had reached, its tools running at least once, as an autonomous
agent's do. A handler may run again, as a tool may.

## A choice read from data

The multi-agent planner's three steps as a blueprint, beside [the workflow](multi-agent-orchestration.md)
that does the same by hand: select is an ask whose result shape lists the specialists it may name, consult
is a gather *chosen by* what select named, summarise reads the contributions.

<!-- include: samples/multi-agent-planner/src/main/scala/planner/application/PlannerBlueprint.scala#blueprint -->
```scala
val blueprint: Blueprint =
  Blueprint("planner")
    .input(Shape.obj("userId" -> Shape.string, "destination" -> Shape.string))
    .worker(
      Worker("selector")
        .instructions(
          s"You route planning requests to specialists. Available specialists: ${Specialist.All.mkString(", ")}. " +
            "Choose only the specialists the request genuinely needs."
        )
        // Two model calls: one to choose, one more should the shape refuse the choice.
        .budget(2)
    )
    .worker(
      Worker("weather")
        .instructions(
          "You are a concise weather specialist. Use your tool, then answer in one sentence."
        )
        .tools("get_forecast")
        .budget(2)
    )
    .worker(
      Worker("activity")
        .instructions(
          "You are a concise activity specialist. Read the traveller's preferences with your tool, then suggest two activities in one sentence."
        )
        .tools("get_preferences")
        .budget(2)
    )
    .worker(
      Worker("budget")
        .instructions("You are a concise budget specialist. Answer in one sentence.")
        .budget(1)
    )
    .worker(
      Worker("summary")
        .instructions(
          "You combine the specialists' contributions into one short brief for the traveller."
        )
        .budget(1)
    )
    .step(
      Step("select")
        .ask("selector")
        .reads("input")
        .result(
          Shape.obj(
            "specialists" -> Shape.arr(Shape.enumOf(Specialist.All*)),
            "reason"      -> Shape.string
          )
        )
    )
    .step(
      Step("consult")
        .gather(Specialist.All, chosenBy = "select.specialists")
        .reads("input")
        .result(Shape.arr(Shape.obj("worker" -> Shape.string, "result" -> Shape.string)))
    )
    .step(Step("summarise").ask("summary").reads("input", "consult").result(Shape.string))
```

The selector cannot name a specialist that does not exist, because the shape refuses it and asks again.
What the workflow does with a selection that names nothing usable — fall back to one specialist — is a
condition, and a blueprint has none: the selector is asked to choose again, within its budget.

## Schedules

A blueprint may carry a schedule: every so many hours, every so many days, or weekly on a day at a time,
in a named time zone. At each due time the platform starts one run, with the period from the previous due
time to this one as its input — `{"from": …, "to": …, "dueTimes": […]}`, ISO-8601 instants — however many
instances the service has, under a run id of its own (`schedule:<blueprint>:<due>`). Periods meet with no
gap and no overlap; the first starts one cadence before the first due time. Due times are local to the
zone, so a weekly digest at 20:00 stays at 20:00 when the clocks change, and that week's period is an hour
longer or shorter.

```scala
Blueprint("digest")
  .input(Period.shape)
  .schedule(Schedule.weekly(DayOfWeek.SUNDAY, LocalTime.of(20, 0), ZoneId.of("Europe/London")).perMissedPeriod)
```

Due times missed while the service was down are those of the schedule of the version current when it is
back, and their runs use that version. By default one run starts, covering from the end of the last
period to the latest due time passed; a schedule that asks for one run per missed period
(`perMissedPeriod`, `"catchUp": "each"`) starts one for each, oldest first, as a digest whose runs are
episodes would. A scheduled run uses the version current at its due time, so a new version takes effect
at the next run. Registering a version without a schedule stops the schedule; registering one with a
schedule again starts it from its next due time, the first period starting where the last run's ended.

A schedule needs the service's [`TimerRuntime`](timers.md): each due time is a timer, and a blueprint with
a schedule is refused in a service without timers.

## Following runs

A tool called during a run can read which run it serves, so what it writes can say so:

<!-- include: modules/testkit/src/test/scala/com/thinkmorestupidless/ankka/testkit/blueprint/FollowingFeatures.scala#run-context -->
```scala
/** A tool that records which run it was told it serves: `None` outside any run. */
object Told:
  val seen = CopyOnWriteArrayList[Option[RunRef]]()
  val tell: FunctionTool = FunctionTool
    .named("tell")
    .describedAs("Tells the test which run called it.")
    .handle { () =>
      seen.add(RunContext.current): Unit
      "told"
    }
```

A service's consumers and views subscribe to the platform's runs and blueprint versions as to any entity's
changes — `ChangeSource.eventsOf(RunEntity)` and `ChangeSource.eventsOf(BlueprintEntity)` — and see a run's
`Started`, each step's `StepEnded` with its result, and `Ended`, in order; a blueprint's
`VersionRegistered` carries the version's canonical text. This is how another component, or another
system, keeps its own record of every run.

<!-- include: modules/testkit/src/test/scala/com/thinkmorestupidless/ankka/testkit/blueprint/FollowingFeatures.scala#follow -->
```scala
/** A service's consumer of the platform's runs: every event of every run, by run id. */
final class RunFollower extends Consumer[RunEvent, Nothing]:
  def onMessage(event: RunEvent): Effect =
    RunFollower.seen.add(messageContext.subject -> event)
    effects.ignore()

object RunFollower:
  val seen = CopyOnWriteArrayList[(String, RunEvent)]()
  val descriptor: ConsumerDescriptor[RunFollower, RunEvent, Nothing] =
    ConsumerDescriptor(
      componentId = ComponentId("run-follower"),
      source = ChangeSource.eventsOf(RunEntity),
      outputSerializer = None,
      produceTo = None,
      create = _ => new RunFollower,
      parallelism = 1
    )
```

## Testing

A blueprint's suite is an integration test with a [scripted model](testing.md#testing-agents-with-a-scripted-model)
and, for judge and critique steps, a [scripted judgment provider](testing.md#scripting-judgments). Steps
of one run, and items of one step, run at once, so script by what the request is for rather than in a
queue: `Scripted.forStep(request, "draft")` reads the step's name from the message, `Scripted.afterTool`
says whether the request follows a tool result, `Scripted.itemOf` gives a for-each step's item, and a work
step's task is told apart by its `complete_task` tool. `Scripted.answer(json)`, `Scripted.call(tool,
arguments)` and `Scripted.completeTask(result)` are the replies. The research digest's features and the
planner's blueprint suite are the worked examples.

## What a blueprint does not do

- **No conditions, branches or loops of its own.** A choice is a shape, or a gather chosen by an earlier
  step; a repetition is inside a step and bounded by its limit, rounds or budget. Coordination with
  conditions in it is a [workflow](workflows.md).
- **No branching search.** Several drafts scored and the weaker pruned is not a pattern here.
- **No person as a step of its own.** A person is reached through a tool that requires approval, and
  the run waits.
- **No blueprint starts, waits on or reads another's run.** Blueprints compose through a service's
  records: the research digest's watch keeps entries, and its digest reads the ones its period holds.
- **No replaying or forking a run**, and no starting a run from another run's step.
- **Scala only.** Python and TypeScript services, through the sidecar, are a later feature.

See [Limitations](../reference/limitations.md) for the rest.
