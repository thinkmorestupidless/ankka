package com.thinkmorestupidless.ankka.agent.javasdk.bridge

import com.thinkmorestupidless.ankka.agent.Json
import com.thinkmorestupidless.ankka.agent.blueprint
import com.thinkmorestupidless.ankka.agent.javasdk
import com.thinkmorestupidless.ankka.javasdk.bridge.{
  Clients,
  Declarations,
  Effects,
  Services,
  Stores
}
import com.thinkmorestupidless.ankka.javasdk.client.{ComponentClient, ServiceClients}
import com.thinkmorestupidless.ankka.javasdk.secret.SecretStore
import com.thinkmorestupidless.ankka.javasdk.storage.ObjectStorage

import java.nio.charset.StandardCharsets
import java.time.{Duration, Instant}
import java.util.function.{BiFunction, Function as JFunction}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

private[bridge] final case class JavaBlueprint(scala: blueprint.Blueprint)
    extends javasdk.Blueprint:
  def name(): String = scala.name
  def input(shape: javasdk.Shape): javasdk.Blueprint = JavaBlueprint(
    scala.input(Blueprints.shape(shape))
  )
  def worker(w: javasdk.Worker): javasdk.Blueprint = JavaBlueprint(
    scala.worker(Blueprints.worker(w))
  )
  def step(s: javasdk.Step): javasdk.Blueprint = JavaBlueprint(scala.step(Blueprints.step(s)))
  def schedule(s: javasdk.Schedule): javasdk.Blueprint = JavaBlueprint(
    scala.schedule(Blueprints.schedule(s))
  )
  def runBudget(modelCalls: Int): javasdk.Blueprint = JavaBlueprint(scala.runBudget(modelCalls))
  def timeLimit(limit: Duration): javasdk.Blueprint = JavaBlueprint(
    scala.timeLimit(Effects.finite(limit))
  )
  def json(): String   = scala.json
  def digest(): String = scala.digest

private[bridge] final case class JavaWorker(scala: blueprint.Worker) extends javasdk.Worker:
  def name(): String                                      = scala.name
  def instructions(text: String): javasdk.Worker          = JavaWorker(scala.instructions(text))
  def model(name: String): javasdk.Worker                 = JavaWorker(scala.model(name))
  override def tools(names: String*): javasdk.Worker      = JavaWorker(scala.tools(names*))
  override def guardrails(names: String*): javasdk.Worker = JavaWorker(scala.guardrails(names*))
  def budget(modelCalls: Int): javasdk.Worker             = JavaWorker(scala.budget(modelCalls))

private[bridge] final case class JavaStep(scala: blueprint.Step) extends javasdk.Step:
  def name(): String                               = scala.name
  override def reads(names: String*): javasdk.Step = JavaStep(scala.reads(names*))
  def result(shape: javasdk.Shape): javasdk.Step   = JavaStep(scala.result(Blueprints.shape(shape)))
  def each(read: String): javasdk.Step             = JavaStep(scala.each(read))
  def each(read: String, limit: Int, keepGoing: Boolean): javasdk.Step =
    JavaStep(scala.each(read, limit, keepGoing))
  override def overWorkers(workers: String*): javasdk.Step = JavaStep(scala.overWorkers(workers*))
  def times(n: Int): javasdk.Step                          = JavaStep(scala.times(n))
  def until(verdict: javasdk.Verdict, rounds: Int): javasdk.Step =
    JavaStep(scala.until(Blueprints.verdict(verdict), rounds))

private[bridge] final case class JavaStart(scala: blueprint.Step.Start) extends javasdk.Step.Start:
  def ask(worker: String): javasdk.Step                   = JavaStep(scala.ask(worker))
  def work(worker: String): javasdk.Step                  = JavaStep(scala.work(worker))
  override def judge(questions: String*): javasdk.Step    = JavaStep(scala.judge(questions*))
  def call(handler: String): javasdk.Step                 = JavaStep(scala.call(handler))
  def forEach(worker: String, over: String): javasdk.Step = JavaStep(scala.forEach(worker, over))
  override def gather(workers: String*): javasdk.Step     = JavaStep(scala.gather(workers*))
  def gather(worker: String, times: Int): javasdk.Step    = JavaStep(scala.gather(worker, times))
  def critique(drafter: String, verdict: javasdk.Verdict, rounds: Int): javasdk.Step =
    JavaStep(scala.critique(drafter, Blueprints.verdict(verdict), rounds))

private[bridge] final case class JavaVerdict(scala: blueprint.Verdict) extends javasdk.Verdict

private[bridge] final case class JavaShape(scala: blueprint.Shape) extends javasdk.Shape:
  def json(): String = scala.schema.render

private[bridge] final case class JavaSchedule(scala: blueprint.Schedule) extends javasdk.Schedule:
  def perMissedPeriod(): javasdk.Schedule = JavaSchedule(scala.perMissedPeriod)

private[bridge] final case class JavaHandler(scala: blueprint.BlueprintHandler)
    extends javasdk.BlueprintHandler:
  def name(): String = scala.name

/**
 * A Java registry: what it was given, made Scala when the service starts, since a tool read from a
 * class needs the service's serializers.
 */
private[bridge] final case class JavaRegistry(steps: Vector[RegistryStep] = Vector.empty)
    extends javasdk.BlueprintRegistry:
  private def and(step: RegistryStep) = JavaRegistry(steps :+ step)

  override def tools(tools: AnyRef*): javasdk.BlueprintRegistry =
    and((r, s) => r.tools(Tools.of(tools, s)*))
  override def mcpServers(servers: javasdk.McpServer*): javasdk.BlueprintRegistry =
    and((r, _) => r.mcpServers(servers.map(Values.scalaMcp)*))
  def model(name: String, provider: javasdk.ModelProvider): javasdk.BlueprintRegistry =
    and((r, _) => r.models(name -> Values.scalaModel(provider)))
  def guardrail(name: String, guardrail: javasdk.Guardrail): javasdk.BlueprintRegistry =
    and((r, _) => r.guardrails(name -> Values.scalaGuardrail(guardrail)))
  override def questions(questions: javasdk.Question[?]*): javasdk.BlueprintRegistry =
    and((r, _) => r.questions(questions.map(Judgments.scalaQuestion)*))
  override def handlers(handlers: javasdk.BlueprintHandler*): javasdk.BlueprintRegistry =
    and((r, _) => r.handlers(handlers.map(Blueprints.handlerOf)*))
  override def carrying(blueprints: javasdk.Blueprint*): javasdk.BlueprintRegistry =
    and((r, _) => r.carrying(blueprints.map(Blueprints.scala)*))

  def scala(serializers: Declarations.Serializers): blueprint.BlueprintRegistry =
    steps.foldLeft(blueprint.BlueprintRegistry.empty)((r, step) => step(r, serializers))

private[bridge] type RegistryStep =
  (blueprint.BlueprintRegistry, Declarations.Serializers) => blueprint.BlueprintRegistry

private[bridge] final class JavaBlueprintContext(
    client: ComponentClient,
    serviceClients: ServiceClients,
    secretStore: SecretStore,
    objectStorage: ObjectStorage
) extends javasdk.BlueprintContext:
  def componentClient(): ComponentClient = client
  def services(): ServiceClients         = serviceClients
  def secrets(): SecretStore             = secretStore
  def storage(): ObjectStorage           = objectStorage

private[bridge] object Blueprints:

  def scala(b: javasdk.Blueprint): blueprint.Blueprint = b match
    case JavaBlueprint(s) => s
    case other => throw IllegalArgumentException(s"$other is not a blueprint Blueprint made")

  def shape(s: javasdk.Shape): blueprint.Shape = s match
    case JavaShape(scala) => scala
    case other            => throw IllegalArgumentException(s"$other is not a shape Shape made")

  def worker(w: javasdk.Worker): blueprint.Worker = w match
    case JavaWorker(scala) => scala
    case other             => throw IllegalArgumentException(s"$other is not a worker Worker made")

  def step(s: javasdk.Step): blueprint.Step = s match
    case JavaStep(scala) => scala
    case other           => throw IllegalArgumentException(s"$other is not a step Step made")

  def verdict(v: javasdk.Verdict): blueprint.Verdict = v match
    case JavaVerdict(scala) => scala
    case other => throw IllegalArgumentException(s"$other is not a verdict Verdict made")

  def schedule(s: javasdk.Schedule): blueprint.Schedule = s match
    case JavaSchedule(scala) => scala
    case other => throw IllegalArgumentException(s"$other is not a schedule Schedule made")

  def handlerOf(h: javasdk.BlueprintHandler): blueprint.BlueprintHandler = h match
    case JavaHandler(scala) => scala
    case other => throw IllegalArgumentException(s"$other is not a handler BlueprintHandler made")

  def handler(
      name: String,
      run: BiFunction[javasdk.RunRef, String, String]
  ): javasdk.BlueprintHandler =
    JavaHandler(blueprint.BlueprintHandler(name) { (ref, input) =>
      val answer =
        run.apply(javasdk.RunRef(ref.runId, ref.step, ref.blueprint, ref.version), input.render)
      Json
        .parse(answer)
        .fold(e => throw IllegalStateException(s"handler '$name' answered $e"), identity)
    })

  def fromJson(text: String): javasdk.Blueprint =
    JavaBlueprint(
      blueprint.Blueprint.fromJson(text).fold(e => throw IllegalArgumentException(e), identity)
    )

  /** Read through the caller's class loader: the resource is the service's, not the SDK's. */
  def fromResource(path: String): javasdk.Blueprint =
    val loader =
      Option(Thread.currentThread.getContextClassLoader).getOrElse(getClass.getClassLoader)
    val stream = Option(loader.getResourceAsStream(path))
      .orElse(Option(getClass.getClassLoader.getResourceAsStream(path)))
      .getOrElse(throw IllegalArgumentException(s"no resource '$path' on the class path"))
    val text =
      try String(stream.readAllBytes(), StandardCharsets.UTF_8)
      finally stream.close()
    JavaBlueprint(
      blueprint.Blueprint
        .fromJson(text)
        .fold(e => throw IllegalArgumentException(s"resource '$path' is $e"), identity)
    )

  def shapeNamed(kind: String): javasdk.Shape = JavaShape(kind match
    case "string"  => blueprint.Shape.string
    case "number"  => blueprint.Shape.number
    case "integer" => blueprint.Shape.integer
    case "boolean" => blueprint.Shape.boolean
    case _         => blueprint.Shape.any)

  def obj(fields: java.util.Map[String, javasdk.Shape]): javasdk.Shape =
    JavaShape(blueprint.Shape.obj(fields.asScala.toSeq.map((n, s) => n -> shape(s))*))

  def shapeFromJson(schema: String): javasdk.Shape =
    JavaShape(
      blueprint.Shape
        .fromText(schema)
        .fold(
          ps => throw IllegalArgumentException(ps.mkString("not a shape: ", "; ", "")),
          identity
        )
    )

  /** The registry the module was given, as the Scala runtime builds it when the service starts. */
  def registry(
      build: JFunction[javasdk.BlueprintContext, javasdk.BlueprintRegistry],
      refs: RefsHolder,
      serializers: Declarations.Serializers
  ): blueprint.BlueprintContext => blueprint.BlueprintRegistry = ctx =>
    val java = JavaBlueprintContext(
      Clients.of(ctx.componentClient, refs.refs),
      Services.of(ctx.services, serializers),
      Stores.secrets(ctx.secrets),
      Stores.storage(ctx.storage)
    )
    build.apply(java) match
      case r: JavaRegistry => r.scala(serializers)
      case null            => throw IllegalStateException("withBlueprints built no registry")
      case other => throw IllegalStateException(s"$other is not a registry BlueprintRegistry made")

  def registered(r: blueprint.Registered): javasdk.BlueprintsRef.Registered =
    javasdk.BlueprintsRef.Registered(r.name, r.version, r.isNew, r.notes.map(_.toString).asJava)

  def run(s: blueprint.RunSnapshot): javasdk.RunsRef.RunSnapshot =
    javasdk.RunsRef.RunSnapshot(
      s.runId,
      s.blueprint,
      s.version,
      s.input.render,
      s.status.wire,
      s.step.toJava,
      s.steps
        .map(st =>
          javasdk.RunsRef
            .StepSnapshot(st.name, st.ended, st.result.map(_.render).toJava, st.modelCalls)
        )
        .asJava,
      s.modelCalls,
      s.reason.toJava
    )

private[bridge] final class JavaBlueprintsRef(calls: blueprint.BlueprintCalls)
    extends javasdk.BlueprintsRef:
  def register(b: javasdk.Blueprint): javasdk.BlueprintsRef.Registered =
    Outcomes.answering(Blueprints.registered(calls.register(Blueprints.scala(b))))
  def register(json: String): javasdk.BlueprintsRef.Registered =
    Outcomes.answering(Blueprints.registered(calls.register(json)))
  def versions(name: String): java.util.List[javasdk.BlueprintsRef.Version] =
    Outcomes
      .answering(calls.versions(name))
      .map(v =>
        javasdk.BlueprintsRef.Version(v.number, v.digest, Instant.ofEpochMilli(v.registeredAt))
      )
      .asJava
  def current(name: String): java.util.Optional[javasdk.Blueprint] =
    Outcomes.answering(calls.current(name)).map(b => JavaBlueprint(b): javasdk.Blueprint).toJava

private[bridge] final class JavaRunsRef(calls: blueprint.RunCalls) extends javasdk.RunsRef:
  def start(name: String, inputJson: String, runId: String): javasdk.RunsRef.RunSnapshot =
    val input =
      Json
        .parse(inputJson)
        .fold(e => throw IllegalArgumentException(s"the input is not JSON: $e"), identity)
    Outcomes.answering(Blueprints.run(calls.start(name, input, runId)))
  def get(runId: String): javasdk.RunsRef.RunSnapshot =
    Outcomes.answering(Blueprints.run(calls.get(runId)))
  def await(runId: String, timeout: Duration): javasdk.RunsRef.RunSnapshot =
    Outcomes.answering(Blueprints.run(calls.await(runId, Effects.finite(timeout))))
  def cancel(runId: String): javasdk.RunsRef.RunSnapshot =
    Outcomes.answering(Blueprints.run(calls.cancel(runId)))
