# Contract: the Scala API

What a Scala service writes and calls. Names are the contract; bodies are the tasks'.

## Registering what blueprints may name

```scala
// Built once at start from a context, because a tool that writes an entity needs the client.
val registry: BlueprintContext => BlueprintRegistry = ctx =>
  BlueprintRegistry.empty
    .tools(searchEuropePmc, searchBiorxiv, keepPaper(ctx.componentClient), papersFoundBetween(ctx.componentClient))
    .mcpServers(McpServer.named("openalex").at("https://…"))                // as an agent lists them
    .models("large" -> AnthropicProvider.fromEnv("claude-opus-5-5"))        // "default" is the runtime's
    .guardrails("no-urls" -> Guardrail.forbidding("no-urls", "https?://".r))
    .questions(namesAPaper)                                                  // Question[?]*
    .carrying(Blueprint.fromResource("blueprints/watch.json"),
              Blueprint.fromResource("blueprints/digest.json"))

Ankka.service("research-digest")
  .registerAll(AgentRuntime.descriptors)          // now includes the blueprint components
  .withExtension(AgentRuntime.withDefaultModel(model).withJudgments(judge).withBlueprints(registry))
  .withExtension(TimerRuntime())                  // for schedules
  .withExtension(ProjectionRuntime())             // for listing runs and following them
```

A blueprint naming something the registry does not hold is refused (FR-004), and so is one with a
schedule in a service without `TimerRuntime`, whether carried (the start fails, naming it) or
registered by a call.

## Writing a blueprint in Scala

```scala
val digest =
  Blueprint("digest")
    .input(Shape.obj("from" -> Shape.string, "to" -> Shape.string))
    .worker(Worker("reader").instructions("Say what this paper finds, in two sentences.").budget(3))
    .worker(Worker("writer").instructions("Write a podcast script …").tools("papers_found_between").budget(6))
    .step(Step("papers").ask("writer").reads("input").result(Shape.arr(Paper.shape)))
    .step(Step("findings").forEach("reader", over = "papers", limit = 8).result(Shape.arr(Finding.shape)))
    // a gather chosen by an earlier step: .gather("weather", "activity", "budget", chosenBy = "select.specialists")
    .step(Step("script").critique("writer", verdict = Verdict.judgment("names-a-paper"), rounds = 3)
            .reads("findings").result(Script.shape))
    .schedule(Schedule.weekly(DayOfWeek.SUNDAY, LocalTime.of(20, 0), ZoneId.of("Europe/London")))
```

The same value is read from JSON (`Blueprint.fromJson`, `Blueprint.fromResource`) and written as
canonical JSON (`blueprint.canonical`).

## Calls (on the `AgentRuntime`, once the service has started)

The check needs what blueprints may name, which only the runtime that built the registry holds, so
the calls hang off it, as a `TimerRuntime`'s scheduler does: inject `agents.blueprints` into
endpoints and workflows.

```scala
val agents = AgentRuntime.withDefaultModel(model).withBlueprints(registry)

agents.blueprints.register(blueprint): Registered      // Registered(name, version, isNew, notes)
                                                        // throws CommandError(BadRequest) carrying every problem
agents.blueprints.register(json: String): Registered   // a pattern the platform lacks is named by its step
agents.blueprints.versions(name): Vector[VersionInfo]  // VersionInfo(number, digest, registeredAt); none when never registered
agents.blueprints.version(name, number): Blueprint
BlueprintRefusal.problemsOf(error): Option[Vector[Problem]]

agents.runs.start(name, input: Json, runId: String, startedBy = "caller"): RunSnapshot
                                                        // Conflict when the id is held with another blueprint or input;
                                                        // BadRequest when the input lacks the shape or the id starts "schedule:"
agents.runs.get(runId): RunSnapshot
agents.runs.await(runId, timeout = 30.minutes): RunSnapshot
agents.runs.cancel(runId, by = "caller"): RunSnapshot
agents.runs.list(name): Vector[RunSummary]             // needs ProjectionRuntime
```

`RunSnapshot`: `runId`, `blueprint`, `version`, `input`, `status: RunStatus`, `step: Option[String]`,
`steps: Vector[StepSnapshot]` (`name`, `status`, `result: Option[Json]`, `sessions`, `usage`,
`judgmentUsage`, `waiting: Vector[ApprovalRef]`), `usage`, `startedBy`, `startedAt`, `endedAt`, `reason`.

`Problem`: `path` (such as `workers[1].tools[0]`), `rule`, `message`. The refusal's message is the
problems as JSON, `{"blueprint": …, "problems": [...]}`, which `BlueprintRefusal.problemsOf` reads
back and `BlueprintRefusal.describe` lists one per line. `Registered.notes` holds the non-refusing
notes (a worker no step uses).

## In a tool

```scala
RunContext.current: Option[RunRef]       // RunRef(runId, step, blueprint, version)
```

## Following

```scala
ChangeSource.eventsOf(BlueprintEntity)   // BlueprintEvent.VersionRegistered, ScheduleAdvanced, ScheduleStopped
ChangeSource.eventsOf(RunEntity)         // RunEvent.Started, StepEnded, Ended, … (data-model.md)
```

## Testkit

```scala
val clock = MovableClock.at(Instant.parse("2026-10-11T18:00:00Z"))
TimerRuntime(pollInterval = 200.millis, clock = clock)
clock.moveTo(Instant.parse("2026-10-18T19:00:01Z"))
kit.awaitRun(runId, within = 30.seconds)
```
