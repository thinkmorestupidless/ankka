package com.thinkmorestupidless.ankka.agent.javasdk.bridge

import com.thinkmorestupidless.ankka.agent
import com.thinkmorestupidless.ankka.agent.javasdk
import com.thinkmorestupidless.ankka.agent.{autonomous, judgment}
import com.thinkmorestupidless.ankka.agent.autonomous.forAutonomousAgent
import com.thinkmorestupidless.ankka.agent.blueprint as bp
import com.thinkmorestupidless.ankka.agent.{
  Agent,
  AgentCalls,
  AgentContext,
  AgentRuntime,
  CompactionSettings,
  MemoryProvider,
  StreamHandle
}
import com.thinkmorestupidless.ankka.core.{ComponentDescriptor, ComponentId, Metadata, SessionId}
import com.thinkmorestupidless.ankka.javasdk.annotations
import com.thinkmorestupidless.ankka.javasdk.bridge.{
  Clients,
  CodecBridge,
  Declarations,
  Effects,
  Invocable,
  JavaComponentClient,
  JavaModule,
  ModulePlan,
  Refs,
  Services,
  Stores
}
import com.thinkmorestupidless.ankka.javasdk.client.{CallerError, ComponentClient, Invocation}
import com.thinkmorestupidless.ankka.javasdk.codec.{EncodingException, Serializer}
import com.thinkmorestupidless.ankka.javasdk.http.Problem
import com.thinkmorestupidless.ankka.javasdk.problems.DeclarationProblem
import com.thinkmorestupidless.ankka.runtime
import com.thinkmorestupidless.ankka.sdk.{CallTransport, CommandHandle, NoArgHandle}
import com.thinkmorestupidless.ankka.sdk.ComponentClient as ScalaClient
import org.apache.pekko.stream.SystemMaterializer
import org.apache.pekko.stream.scaladsl.JavaFlowSupport

import java.lang.reflect.{Method, Modifier, ParameterizedType}
import java.time.{Duration, Instant}
import java.util.Optional
import java.util.concurrent.{CompletionException, CompletionStage, Flow}
import java.util.function.{Function as JFunction, Supplier}
import scala.collection.mutable
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.FutureConverters.*
import scala.jdk.OptionConverters.*

/** What the Java agent library's static factories reach. */
private[ankka] object AgentBridge:

  def module(): javasdk.AgentModule = JavaAgentModule()

  def tool(name: String, description: String, run: Supplier[String]): javasdk.FunctionTool =
    Tools.noArgument(name, description, run)

  def tool[A](
      name: String,
      description: String,
      arguments: Class[A],
      run: JFunction[A, String]
  ): javasdk.FunctionTool = Tools.withArguments(name, description, arguments, run)

  def forbidding(name: String, regex: String): javasdk.Guardrail =
    ScalaGuardrail(agent.Guardrail.forbidding(name, regex.r))

  def maxInputLength(maxChars: Int): javasdk.Guardrail =
    ScalaGuardrail(agent.Guardrail.maxInputLength(maxChars))

  def memoryNone(): javasdk.Memory    = JavaMemory(MemoryProvider.none)
  def memorySession(): javasdk.Memory = JavaMemory(MemoryProvider.limitedWindow)

  def installation(model: String): javasdk.ModelProvider =
    JavaModelProvider(agent.providers.ModelProviders.installation(model))

  def named(
      name: String,
      protocol: Optional[String],
      url: Optional[String],
      model: Optional[String],
      key: Optional[String],
      preset: Optional[String]
  ): javasdk.ModelProvider =
    JavaModelProvider(
      agent.providers.ModelProviders.named(
        name,
        protocol = protocol.toScala,
        url = url.toScala,
        model = model.toScala,
        key = key.toScala,
        preset = preset.toScala
      )
    )

  def mcpNamed(name: String): javasdk.McpServer = JavaMcpServer(agent.mcp.McpServer.named(name))

  def mcpAt(name: String, url: String): javasdk.McpServer =
    JavaMcpServer(agent.mcp.McpServer.at(name, url))

  def mcpService(name: String, service: String, path: String): javasdk.McpServer =
    JavaMcpServer(agent.mcp.McpServer.service(name, service, path))

  /** A Scala approval request as the Java one: what the agent test kit's result lists. */
  def approvalRequest(request: agent.ApprovalRequest): javasdk.ApprovalRequest =
    Outcomes.request(request)

  /** A Java model provider over a Scala one: what the test kit's scripted provider is. */
  def provider(scala: agent.ModelProvider): javasdk.ModelProvider = JavaModelProvider(scala)

  def forAgent(client: ComponentClient, sessionId: String): javasdk.AgentRef =
    val (java, plan) = planOf(client)
    JavaAgentRef(java.transport, plan, sessionId)

  private def javaClient(client: ComponentClient): JavaComponentClient = client match
    case java: JavaComponentClient => java
    case _ =>
      throw IllegalStateException(
        "an agent is called through the component client a service or a test kit gave"
      )

  private def planOf(client: ComponentClient): (JavaComponentClient, AgentsPlan) =
    val java = javaClient(client)
    val plan = java.refs
      .module(classOf[AgentsPlan])
      .getOrElse(
        throw CallerError(
          "this service has no agents: give it withModule(Agents.module()) and register them"
        )
      )
    java -> plan

  private def scalaClient(client: ComponentClient): ScalaClient =
    ScalaClient(javaClient(client).transport)

  // ── Judgments ──

  def choice[T](
      id: String,
      instructions: String,
      options: java.util.List[javasdk.ChoiceOption[T]]
  ): javasdk.ChoiceQuestion[T] = Judgments.choice(id, instructions, options)

  def score(
      id: String,
      instructions: String,
      levels: java.util.List[String]
  ): javasdk.ScoreQuestion =
    JavaScore(judgment.Question.score(id, instructions)(levels.asScala.toSeq*))

  def yesNo(id: String, instructions: String): javasdk.YesNoQuestion =
    JavaYesNo(judgment.Question.yesNo(id, instructions))

  def jev(model: Optional[String]): javasdk.JudgmentProvider =
    JavaJudgmentProvider(
      model.toScala.fold(judgment.JevProvider.fromEnv())(m => judgment.JevProvider.fromEnv(m))
    )

  /** A Java judgment provider over a Scala one: what the test kit's scripted provider is. */
  def judgmentProvider(scala: judgment.JudgmentProvider): javasdk.JudgmentProvider =
    JavaJudgmentProvider(scala)

  /** The Scala question under a Java one: what a test scripts its answers against. */
  def scalaQuestion(question: javasdk.Question[?]): judgment.Question[?] =
    Judgments.scalaQuestion(question)

  def judged(name: String): javasdk.JudgedGuardrail = JavaJudged(agent.Guardrail.judged(name))

  def ifYes(question: javasdk.YesNoQuestion, atLeast: Double): javasdk.Refusal = question match
    case JavaYesNo(s) => JavaRefusal(judgment.Refuse.ifYes(s, atLeast))
    case other        => throw IllegalArgumentException(s"$other is not a question Question made")

  def ifChosen[T](
      question: javasdk.ChoiceQuestion[T],
      minConfidence: Double,
      options: java.util.List[T]
  ): javasdk.Refusal = Judgments.ifChosen(question, minConfidence, options)

  def ifScore(question: javasdk.ScoreQuestion, atLeast: Double): javasdk.Refusal = question match
    case JavaScore(s) => JavaRefusal(judgment.Refuse.ifScore(s, atLeast))
    case other        => throw IllegalArgumentException(s"$other is not a question Question made")

  def when[A](
      question: javasdk.Question[A],
      refuse: java.util.function.Predicate[A]
  ): javasdk.Refusal =
    Judgments.when(question, refuse)

  // ── Autonomous agents and tasks ──

  def definition(description: String): javasdk.AutonomousDefinition = JavaDefinition(description)

  def taskType(name: String, description: String): javasdk.TaskType[String] =
    JavaTaskType(autonomous.Task.named(name).describedAs(description))

  def tasks(client: ComponentClient): javasdk.TasksRef = JavaTasksRef(scalaClient(client))

  def forTask(client: ComponentClient, taskId: String): javasdk.TaskRef =
    JavaTaskRef(scalaClient(client), taskId)

  def forAutonomousAgent(
      client: ComponentClient,
      agentClass: Class[? <: javasdk.AutonomousAgent],
      instanceId: String
  ): javasdk.AutonomousRef =
    val (java, plan) = planOf(client)
    JavaAutonomousRef(
      ScalaClient(java.transport),
      java.transport,
      plan.autonomous(agentClass).componentId,
      instanceId
    )

  def runSingleTask(
      client: ComponentClient,
      agentClass: Class[? <: javasdk.AutonomousAgent],
      taskType: javasdk.TaskType[?],
      instructions: String
  ): String =
    val (java, plan) = planOf(client)
    Outcomes.answering(
      ScalaClient(java.transport)
        .forAutonomousAgent(plan.autonomous(agentClass))
        .runSingleTask(Autonomous.scalaType(taskType), instructions)
    )

  // ── Blueprints ──

  def blueprint(name: String): javasdk.Blueprint             = JavaBlueprint(bp.Blueprint(name))
  def blueprintFromJson(json: String): javasdk.Blueprint     = Blueprints.fromJson(json)
  def blueprintFromResource(path: String): javasdk.Blueprint = Blueprints.fromResource(path)
  def worker(name: String): javasdk.Worker                   = JavaWorker(bp.Worker(name))
  def step(name: String): javasdk.Step.Start                 = JavaStart(bp.Step(name))
  def critic(worker: String): javasdk.Verdict = JavaVerdict(bp.Verdict.critic(worker))
  def verdictJudgment(question: String): javasdk.Verdict = JavaVerdict(
    bp.Verdict.judgment(question)
  )
  def shape(kind: String): javasdk.Shape       = Blueprints.shapeNamed(kind)
  def arr(items: javasdk.Shape): javasdk.Shape = JavaShape(bp.Shape.arr(Blueprints.shape(items)))
  def obj(fields: java.util.Map[String, javasdk.Shape]): javasdk.Shape = Blueprints.obj(fields)
  def enumOf(values: Array[String]): javasdk.Shape = JavaShape(bp.Shape.enumOf(values.toSeq*))
  def shapeFromJson(schema: String): javasdk.Shape = Blueprints.shapeFromJson(schema)

  def weekly(
      day: java.time.DayOfWeek,
      time: java.time.LocalTime,
      zone: java.time.ZoneId
  ): javasdk.Schedule =
    JavaSchedule(bp.Schedule.weekly(day, time, zone))
  def everyHours(hours: Int, zone: java.time.ZoneId): javasdk.Schedule =
    JavaSchedule(bp.Schedule.everyHours(hours, zone))
  def everyDays(days: Int, zone: java.time.ZoneId): javasdk.Schedule =
    JavaSchedule(bp.Schedule.everyDays(days, zone))

  def registry(): javasdk.BlueprintRegistry = JavaRegistry()

  def handler(
      name: String,
      run: java.util.function.BiFunction[javasdk.RunRef, String, String]
  ): javasdk.BlueprintHandler = Blueprints.handler(name, run)

  def blueprints(client: ComponentClient): javasdk.BlueprintsRef =
    JavaBlueprintsRef(Outcomes.answering(planOf(client)._2.hostRuntime.blueprints))

  def runs(client: ComponentClient): javasdk.RunsRef =
    JavaRunsRef(Outcomes.answering(planOf(client)._2.hostRuntime.runs))

// ── The module ─────────────────────────────────────────────────────────────────────────────────

/** The agents module: the default model, compaction, and the reader of the service's agents. */
private[bridge] final case class JavaAgentModule(
    model: Option[(agent.ModelProvider, FiniteDuration)] = None,
    compaction: Option[(Int, Int, Option[agent.ModelProvider])] = None,
    judgments: Option[(judgment.JudgmentProvider, Option[FiniteDuration])] = None,
    blueprints: Option[JFunction[javasdk.BlueprintContext, javasdk.BlueprintRegistry]] = None
) extends javasdk.AgentModule
    with JavaModule:

  def withModelProvider(provider: javasdk.ModelProvider): javasdk.AgentModule =
    copy(model = Some(Values.scalaModel(provider) -> 2.minutes))

  def withModelProvider(provider: javasdk.ModelProvider, timeout: Duration): javasdk.AgentModule =
    copy(model = Some(Values.scalaModel(provider) -> Effects.finite(timeout)))

  def withCompaction(maxHistoryBytes: Int, keepRecentMessages: Int): javasdk.AgentModule =
    copy(compaction = Some((maxHistoryBytes, keepRecentMessages, None)))

  def withCompaction(
      maxHistoryBytes: Int,
      keepRecentMessages: Int,
      summariser: javasdk.ModelProvider
  ): javasdk.AgentModule =
    copy(compaction =
      Some((maxHistoryBytes, keepRecentMessages, Some(Values.scalaModel(summariser))))
    )

  def withJudgments(provider: javasdk.JudgmentProvider): javasdk.AgentModule =
    copy(judgments = Some(Judgments.scalaProvider(provider) -> None))

  def withJudgments(provider: javasdk.JudgmentProvider, timeout: Duration): javasdk.AgentModule =
    copy(judgments = Some(Judgments.scalaProvider(provider) -> Some(Effects.finite(timeout))))

  def withBlueprints(
      registry: JFunction[javasdk.BlueprintContext, javasdk.BlueprintRegistry]
  ): javasdk.AgentModule = copy(blueprints = Some(registry))

  def name: String = "Agents.module()"

  def claims(cls: Class[?]): Boolean =
    classOf[javasdk.Agent].isAssignableFrom(cls) ||
      classOf[javasdk.AutonomousAgent].isAssignableFrom(cls)

  def read(
      classes: Vector[(Class[?], Option[String])],
      serializers: Declarations.Serializers
  ): Either[Vector[DeclarationProblem], ModulePlan] =
    val problems = mutable.ArrayBuffer.empty[DeclarationProblem]
    val (autonomousClasses, agentClasses) =
      classes.partition((cls, _) => classOf[javasdk.AutonomousAgent].isAssignableFrom(cls))
    val plans = agentClasses.flatMap { (cls, id) =>
      AgentReader.read(cls, id, serializers) match
        case Right(plan) => Some(plan)
        case Left(ps)    => problems ++= ps; None
    }
    val refs    = RefsHolder()
    val runtime = agentRuntime(problems, refs, serializers)
    val autonomousCompanions = autonomousClasses.flatMap { (cls, id) =>
      AutonomousReader.read(cls, id) match
        case Left(ps) => problems ++= ps; None
        case Right(plan) =>
          try
            val companion = JavaAutonomousCompanion(plan, refs, serializers)
            companion.descriptor: Unit
            Some(companion)
          catch
            case e: IllegalArgumentException =>
              problems += DeclarationProblem(cls.getSimpleName, "definition", e.getMessage)
              None
    }
    val companions = plans.flatMap { plan =>
      try
        val companion = JavaAgentCompanion(plan, refs, serializers)
        companion.descriptor: Unit
        Some(companion)
      catch
        case e: IllegalArgumentException =>
          problems += DeclarationProblem(plan.cls.getSimpleName, "", e.getMessage)
          None
    }
    if problems.nonEmpty then Left(problems.toVector)
    else Right(AgentsPlan(companions, autonomousCompanions, runtime.get, refs))

  private def agentRuntime(
      problems: mutable.Buffer[DeclarationProblem],
      refs: RefsHolder,
      serializers: Declarations.Serializers
  ): Option[AgentRuntime] =
    val modelled = model match
      case Some((provider, timeout)) => AgentRuntime.withDefaultModel(provider, timeout)
      case None                      => AgentRuntime()
    val judged = judgments match
      case Some((provider, Some(timeout))) => modelled.withJudgments(provider, timeout)
      case Some((provider, None))          => modelled.withJudgments(provider)
      case None                            => modelled
    val base = blueprints match
      case Some(build) => judged.withBlueprints(Blueprints.registry(build, refs, serializers))
      case None        => judged
    compaction match
      case None => Some(base)
      case Some((_, _, None)) if model.isEmpty =>
        problems += DeclarationProblem(
          name,
          "withCompaction",
          "compaction is written by the default model: give the module withModelProvider(...), " +
            "or name a summariser"
        )
        None
      case Some((bytes, keep, summariser)) =>
        try
          Some(
            base.withCompaction(
              CompactionSettings(maxHistoryBytes = bytes, keepRecentMessages = keep),
              summariser.map(agent.ModelSummariser(_))
            )
          )
        catch
          case e: IllegalArgumentException =>
            problems += DeclarationProblem(name, "withCompaction", e.getMessage)
            None

/** The refs every agent's Java client resolves its calls with, known once the service is read. */
private[bridge] final class RefsHolder:
  @volatile var refs: Refs = Refs(Nil)

/** The service's agents, read: their components, the runtime that hosts them, their handlers. */
private[ankka] final class AgentsPlan(
    companions: Vector[JavaAgentCompanion],
    autonomousAgents: Vector[JavaAutonomousCompanion],
    agents: AgentRuntime,
    refs: RefsHolder
) extends ModulePlan:

  val classes: Set[Class[?]] =
    (companions.map(_.plan.cls) ++ autonomousAgents.map(_.plan.cls)).toSet

  /** The runtime that hosts the agents: where blueprints and runs are reached. */
  def hostRuntime: AgentRuntime = agents

  /** The agent `cls` is, as registered with this service: what the agent test kit drives. */
  def companionOf(cls: Class[?]): Agent.Companion[?] =
    companions
      .find(_.plan.cls == cls)
      .getOrElse(
        throw CallerError(s"${cls.getSimpleName} is not an agent registered with this service")
      )

  /** The autonomous agent `cls` is, as registered with this service. */
  def autonomous(cls: Class[?]): JavaAutonomousCompanion =
    autonomousAgents
      .find(_.plan.cls == cls)
      .getOrElse(
        throw CallerError(
          s"${cls.getSimpleName} is not an autonomous agent registered with this service"
        )
      )

  def descriptors(refs: Refs, serializers: Declarations.Serializers): Vector[ComponentDescriptor] =
    this.refs.refs = refs
    companions.map(_.descriptor) ++ autonomousAgents.map(_.descriptor) ++ agents.descriptors

  def extensions: Vector[runtime.RuntimeExtension] = Vector(agents)

  // Session memory's tasks are cancelled by a consumer, and an approval's time limit is a timer.
  def needsProjections: Boolean = true
  def needsTimers: Boolean      = true

  private val byKey: Map[(String, String, String), AnyRef] = companions.flatMap(_.handles).toMap

  /** The handle a method reference names. */
  def handle(ref: AnyRef): AnyRef =
    val lambda    = Refs.serialized(ref)
    val implClass = lambda.getImplClass.replace('/', '.')
    val method    = lambda.getImplMethodName
    if method.startsWith("lambda$") then
      throw CallerError(
        s"a call in ${lambda.getCapturingClass.replace('/', '.')} names its handler with a lambda; " +
          "name it with a method reference, such as Assistant::ask"
      )
    byKey
      .get((implClass, method, lambda.getImplMethodSignature))
      .orElse(Refs.throughAdapter(lambda, ref.getClass.getClassLoader).flatMap(byKey.get))
      .getOrElse(
        throw CallerError(
          s"$implClass::$method is not a registered agent handler: it has no @Command or @Stream, " +
            s"or ${implClass.split('.').last} is not registered with this service"
        )
      )

// ── Reading an agent class ─────────────────────────────────────────────────────────────────────

private[bridge] final case class AgentHandler(
    wireName: String,
    method: Method,
    stream: Boolean,
    input: Option[Serializer[Any]],
    output: Option[Serializer[Any]]
):
  def javaKey: (String, String, String) =
    (method.getDeclaringClass.getName, method.getName, Declarations.descriptor(method))

  def invoke(instance: AnyRef, input: Any): Any =
    if Invocable.parameterCount(method) == 0 then Invocable.invoke(method, instance)
    else Invocable.invoke(method, instance, input.asInstanceOf[AnyRef])

private[bridge] final case class AgentClassPlan(
    cls: Class[?],
    componentId: String,
    construct: () => AnyRef,
    handlers: Vector[AgentHandler],
    settings: javasdk.AgentSettings
)

private[bridge] object AgentReader:

  def read(
      cls: Class[?],
      idOverride: Option[String],
      serializers: Declarations.Serializers
  ): Either[Vector[DeclarationProblem], AgentClassPlan] =
    val name     = cls.getSimpleName
    val problems = mutable.ArrayBuffer.empty[DeclarationProblem]
    def problem(member: String, message: String): Unit =
      problems += DeclarationProblem(name, member, message)

    val componentId = idOverride
      .orElse(Option(cls.getAnnotation(classOf[annotations.ComponentId])).map(_.value))
      .filter(_.nonEmpty)
    if componentId.isEmpty then
      problem("", "has no component id: annotate it @ComponentId(\"...\") or register(cls, id)")
    if Modifier.isAbstract(cls.getModifiers) then
      problem("", "is abstract; register a concrete class")

    val construct: Option[() => AnyRef] =
      try
        val c = cls.getDeclaredConstructor()
        c.setAccessible(true)
        Some(() =>
          try c.newInstance().asInstanceOf[AnyRef]
          catch case e: java.lang.reflect.InvocationTargetException => throw e.getCause
        )
      catch
        case _: NoSuchMethodException =>
          problem("", "has no constructor taking nothing"); None

    val bindings = Declarations.typeBindings(cls)
    def serializer(tpe: java.lang.reflect.Type, member: String, what: String) =
      try Some(Values.serializer(Declarations.resolve(tpe, bindings), serializers))
      catch
        case e: EncodingException =>
          problem(member, s"its $what has no serializer: ${e.getMessage}"); None

    val handlers = methods(cls).flatMap { m =>
      val command = Option(m.getAnnotation(classOf[annotations.Command])).map(_.value)
      val stream  = Option(m.getAnnotation(classOf[annotations.Stream])).map(_.value)
      val member  = m.getName
      val count   = Invocable.parameterCount(m)
      if command.isDefined || stream.isDefined then Invocable.problem(m).foreach(problem(member, _))
      def input =
        if count == 1 then serializer(m.getGenericParameterTypes()(0), member, "input")
        else None
      (command, stream) match
        case (None, None) => None
        case (Some(_), Some(_)) =>
          problem(member, "is both @Command and @Stream; a handler is one or the other"); None
        case (Some(wire), None) =>
          if count > 1 then problem(member, "takes more than one parameter; take one record")
          if Invocable.returnType(m) != classOf[javasdk.AgentEffect[?]] then
            problem(member, "is a @Command, so it returns AgentEffect<R>")
            None
          else
            val reply = Invocable.genericReturnType(m) match
              case p: ParameterizedType =>
                serializer(p.getActualTypeArguments()(0), member, "reply")
              case _ => problem(member, "returns a raw AgentEffect; say what it replies with"); None
            if wire.isEmpty then problem(member, "has an empty wire name")
            Some(AgentHandler(wire, m, stream = false, input, reply))
        case (None, Some(wire)) =>
          if count != 1 then problem(member, "is a @Stream, so it takes exactly one parameter")
          if Invocable.returnType(m) != classOf[javasdk.StreamEffect] then
            problem(member, "is a @Stream, so it returns StreamEffect")
          if wire.isEmpty then problem(member, "has an empty wire name")
          Some(AgentHandler(wire, m, stream = true, input, None))
    }
    if handlers.isEmpty then problem("", "has no @Command or @Stream handler")
    handlers.groupBy(_.wireName).collect {
      case (wire, hs) if hs.sizeIs > 1 =>
        problem("", s"declares the wire name '$wire' on ${hs.map(_.method.getName).mkString(", ")}")
    }

    // The settings are the instance's: read once, from one built for it with nothing to call.
    val settings = construct.flatMap { build =>
      try
        val instance = build()
        val read     = classOf[javasdk.Agent].getDeclaredMethod("settings")
        read.setAccessible(true)
        Option(Invocable.invoke(read, instance).asInstanceOf[javasdk.AgentSettings])
          .orElse { problem("settings", "returns null"); None }
      catch
        case e: Exception =>
          problem("", s"could not be built to read its settings: ${e.getMessage}"); None
    }

    if problems.nonEmpty then Left(problems.toVector)
    else Right(AgentClassPlan(cls, componentId.get, construct.get, handlers, settings.get))

  /** Every handler method from `cls` up to the SDK's `Agent`, a subclass's first. */
  private def methods(cls: Class[?]): Vector[Method] =
    Iterator
      .iterate[Class[?]](cls)(_.getSuperclass)
      .takeWhile(c => c != null && c != classOf[javasdk.Agent])
      .flatMap(_.getDeclaredMethods.sortBy(_.getName))
      .filter(Declarations.declaredByHand)
      .map { m =>
        m.setAccessible(true); m
      }
      .toVector

// ── Hosting ────────────────────────────────────────────────────────────────────────────────────

/** The Scala agent a Java one runs as: it holds the Java instance and lends it its effects. */
private[bridge] final class Shell extends Agent:
  @volatile var java: AnyRef           = null
  def scalaEffects: agent.AgentEffects = effects

/** The Scala declaration of one Java agent. */
private[bridge] final class JavaAgentCompanion(
    val plan: AgentClassPlan,
    refs: RefsHolder,
    serializers: Declarations.Serializers
) extends Agent.Companion[Shell](ComponentId(plan.componentId)):

  override def role: String =
    if plan.settings.role.isEmpty then componentId else plan.settings.role
  override def maxToolCallSteps: Int = plan.settings.maxToolCallSteps
  override def mcpServers: Vector[agent.mcp.McpServer] =
    plan.settings.mcpServers.asScala.toVector.map(Values.scalaMcp)
  override def resultGuardrails: Vector[agent.Guardrail] =
    plan.settings.resultGuardrails.asScala.toVector.map(Values.scalaGuardrail)

  /** Each handler's handle, by what a method reference to it names. */
  val handles: Vector[((String, String, String), AnyRef)] =
    plan.handlers.map(h => h.javaKey -> bind(h))

  private def bind(h: AgentHandler): AnyRef =
    if h.stream then
      stream[Any](h.wireName)((shell: Shell) => (i: Any) => streamOf(h, h.invoke(shell.java, i)))(
        using CodecBridge.toScala(h.input.get)
      )
    else
      h.input match
        case Some(in) =>
          command[Any, Any](h.wireName)((shell: Shell) =>
            (i: Any) => effectOf(h, h.invoke(shell.java, i))
          )(using
            CodecBridge.toScala(in),
            CodecBridge.toScala(h.output.get)
          )
        case None =>
          command[Any](h.wireName)((shell: Shell) => effectOf(h, h.invoke(shell.java, null)))(using
            CodecBridge.toScala(h.output.get)
          )

  private def effectOf(h: AgentHandler, result: Any): agent.AgentEffect[Any] = result match
    case Held(scala) => scala.asInstanceOf[agent.AgentEffect[Any]]
    case other => throw IllegalStateException(s"${h.method.getName} returned $other, not an effect")

  private def streamOf(h: AgentHandler, result: Any): agent.AgentStreamEffect = result match
    case HeldStream(scala) => scala
    case other => throw IllegalStateException(s"${h.method.getName} returned $other, not an effect")

  def create(ctx: AgentContext): Shell =
    val shell = Shell()
    val cell = AgentCell(
      () => JavaEffects(() => shell.scalaEffects, serializers),
      ctx.sessionId,
      Clients.of(ctx.componentClient, refs.refs),
      Services.of(ctx.services, serializers),
      Stores.secrets(ctx.secrets),
      Stores.storage(ctx.storage)
    )
    shell.java = AgentCell.constructing(cell)(plan.construct())
    shell

// ── Effects ────────────────────────────────────────────────────────────────────────────────────

private[bridge] final case class Held[R](scala: agent.AgentEffect[?]) extends javasdk.AgentEffect[R]
private[bridge] final case class HeldStream(scala: agent.AgentStreamEffect)
    extends javasdk.StreamEffect

private[bridge] final class JavaEffects(
    scala: () => agent.AgentEffects,
    serializers: Declarations.Serializers
) extends javasdk.AgentEffects:
  private def builder(effect: agent.AgentEffect[String]) = JavaBuilder(effect, serializers)

  def systemMessage(text: String): javasdk.AgentBuilder = builder(scala().systemMessage(text))
  def userMessage(text: String): javasdk.AgentBuilder   = builder(scala().userMessage(text))
  def model(provider: javasdk.ModelProvider): javasdk.AgentBuilder =
    builder(scala().model(Values.scalaModel(provider)))
  def judgment(): javasdk.JudgmentBuilder = JavaJudgmentBuilder(scala().judgment, serializers)
  def error[R](message: String): javasdk.AgentEffect[R] = Held(scala().error[R](message))
  def error[R](problem: Problem): javasdk.AgentEffect[R] =
    val error = Effects.toCommandError(problem)
    Held(scala().error[R](error.message, error.code))

private[bridge] final class JavaBuilder(
    effect: agent.AgentEffect[String],
    serializers: Declarations.Serializers
) extends javasdk.AgentBuilder:
  private def next(e: agent.AgentEffect[String]) = JavaBuilder(e, serializers)

  def systemMessage(text: String): javasdk.AgentBuilder = next(effect.systemMessage(text))
  def userMessage(text: String): javasdk.AgentBuilder   = next(effect.userMessage(text))
  def withContext(text: String): javasdk.AgentBuilder   = next(effect.withContext(text))
  def model(provider: javasdk.ModelProvider): javasdk.AgentBuilder =
    next(effect.model(Values.scalaModel(provider)))
  def memory(memory: javasdk.Memory): javasdk.AgentBuilder =
    next(effect.memory(Values.scalaMemory(memory)))
  override def tools(tools: AnyRef*): javasdk.AgentBuilder =
    next(effect.tools(Tools.of(tools, serializers)*))
  override def guardrails(guardrails: javasdk.Guardrail*): javasdk.AgentBuilder =
    next(effect.guardrails(guardrails.map(Values.scalaGuardrail)*))
  def maxToolCallSteps(steps: Int): javasdk.AgentBuilder = next(effect.maxToolCallSteps(steps))

  def thenReply(): javasdk.AgentEffect[String] = Held(effect.thenReply())
  def thenReply[R](`type`: Class[R]): javasdk.AgentEffect[R] =
    if `type` == classOf[String] then Held(effect.thenReply())
    else Held(effect.thenReplyAs[R](using Values.codec(`type`, serializers)))
  def thenStream(): javasdk.StreamEffect = HeldStream(effect.thenStream())

// ── Calls ──────────────────────────────────────────────────────────────────────────────────────

private[bridge] final class JavaAgentRef(
    transport: CallTransport,
    plan: AgentsPlan,
    session: String
) extends javasdk.AgentRef:

  private val calls = AgentCalls(transport, SessionId(session))

  private def commandOf(ref: AnyRef): CommandHandle[Shell, Any, Any] = plan.handle(ref) match
    case h: CommandHandle[?, ?, ?] => h.asInstanceOf[CommandHandle[Shell, Any, Any]]
    case _ =>
      throw CallerError("that handler takes no input, or streams; call it as it is declared")

  private def noArgOf(ref: AnyRef): NoArgHandle[Shell, Any] = plan.handle(ref) match
    case h: NoArgHandle[?, ?] => h.asInstanceOf[NoArgHandle[Shell, Any]]
    case _                    => throw CallerError("that handler takes an input; call it with one")

  private def streamOf(ref: AnyRef): StreamHandle[Shell, Any] = plan.handle(ref) match
    case h: StreamHandle[?, ?] => h.asInstanceOf[StreamHandle[Shell, Any]]
    case _ => throw CallerError("that handler is not a @Stream; call it for its answer")

  def call[A <: javasdk.Agent, I, O](handler: javasdk.AgentCommandRef[A, I, O]): Invocation[I, O] =
    val h = commandOf(handler)
    AgentInvocation((i, md) => calls.call(h).withMetadata(md).invokeAsync(i), transport.askTimeout)

  def call[A <: javasdk.Agent, O](handler: javasdk.AgentNoArgRef[A, O]): Invocation[Void, O] =
    val h = noArgOf(handler)
    AgentInvocation((_, md) => calls.call(h).withMetadata(md).invokeAsync(), transport.askTimeout)

  def ask[A <: javasdk.Agent, I, O](
      handler: javasdk.AgentCommandRef[A, I, O]
  ): Invocation[I, javasdk.AgentOutcome[O]] =
    val h = commandOf(handler)
    AgentInvocation(
      (i, md) =>
        calls
          .ask(h)
          .withMetadata(md)
          .invokeAsync(i)
          .map(Outcomes.java)(using ExecutionContext.parasitic),
      transport.askTimeout
    )

  def ask[A <: javasdk.Agent, O](
      handler: javasdk.AgentNoArgRef[A, O]
  ): Invocation[Void, javasdk.AgentOutcome[O]] =
    val h = noArgOf(handler)
    AgentInvocation(
      (_, md) =>
        calls
          .ask(h)
          .withMetadata(md)
          .invokeAsync()
          .map(Outcomes.java)(using ExecutionContext.parasitic),
      transport.askTimeout
    )

  def stream[A <: javasdk.Agent, I](
      handler: javasdk.AgentStreamRef[A, I],
      input: I
  ): Flow.Publisher[String] =
    val h = streamOf(handler)
    val system = transport match
      case sharded: runtime.ShardingTransport => sharded.actorSystem
      case other =>
        throw IllegalStateException(s"a stream is read through a running service, not $other")
    calls
      .stream(h)(input)
      .mapError { case failure => Outcomes.failure(failure) }
      .runWith(JavaFlowSupport.Sink.asPublisher[String](fanout = false))(using
        SystemMaterializer(system).materializer
      )

  def decide[A <: javasdk.Agent, I, O](
      handler: javasdk.AgentCommandRef[A, I, O],
      decision: javasdk.Decision
  ): javasdk.AgentOutcome[O] =
    val h = commandOf(handler)
    Outcomes.java(Outcomes.answering(calls.decide(h)(Outcomes.scala(decision))))

  def decide[A <: javasdk.Agent, O](
      handler: javasdk.AgentNoArgRef[A, O],
      decision: javasdk.Decision
  ): javasdk.AgentOutcome[O] =
    val h = noArgOf(handler)
    Outcomes.java(Outcomes.answering(calls.decide(h)(Outcomes.scala(decision))))

  def decideStream[A <: javasdk.Agent, I](
      handler: javasdk.AgentStreamRef[A, I],
      decision: javasdk.Decision
  ): javasdk.AgentOutcome[String] =
    val h = streamOf(handler)
    Outcomes.java(Outcomes.answering(calls.decide(h)(Outcomes.scala(decision))))

  def approvals(): java.util.List[javasdk.ApprovalRequest] =
    Outcomes.answering(calls.approvals()).map(Outcomes.request).asJava

/** A call to an agent as a Java invocation: the same timeout, metadata and failures as any. */
private[bridge] final case class AgentInvocation[I, O](
    send: (I, Metadata) => Future[Any],
    timeout: FiniteDuration,
    metadata: Metadata = Metadata.empty
) extends Invocation[I, O]:

  def invoke(input: I): O =
    Outcomes.answering(ScalaClient.await(send(input, metadata), timeout)).asInstanceOf[O]

  def invokeAsync(input: I): CompletionStage[O] =
    send(input, metadata)
      .transform(identity, Outcomes.failure)(using ExecutionContext.parasitic)
      .asInstanceOf[Future[O]]
      .asJava

  def withTimeout(timeout: Duration): Invocation[I, O] = copy(timeout = Effects.finite(timeout))

  def withMetadata(key: String, value: String): Invocation[I, O] =
    copy(metadata = metadata.add(key, value))

private[bridge] object Outcomes:

  /** What `run` answers, or its failure as a Java caller sees it. */
  def answering[A](run: => A): A =
    try run
    catch case t: Throwable => throw failure(t)

  def failure(t: Throwable): Throwable = t match
    case e: CompletionException if e.getCause != null => failure(e.getCause)
    case a: agent.ApprovalAwaited => javasdk.ApprovalAwaited(a.requests.map(request).asJava)
    case other                    => Clients.javaFailure(other)

  def java[O](outcome: agent.AgentOutcome[?]): javasdk.AgentOutcome[O] = outcome match
    case agent.AgentOutcome.Answered(value) =>
      javasdk.AgentOutcome.Answered[O](value.asInstanceOf[O])
    case agent.AgentOutcome.AwaitingApproval(requests) =>
      javasdk.AgentOutcome.AwaitingApproval[O](requests.map(request).asJava)

  def request(r: agent.ApprovalRequest): javasdk.ApprovalRequest =
    javasdk.ApprovalRequest(
      r.id,
      r.tool,
      r.arguments.render,
      Instant.ofEpochMilli(r.requestedAt),
      r.expiresAt.map(Instant.ofEpochMilli).toJava
    )

  def scala(d: javasdk.Decision): agent.Decision =
    if d.approved then agent.Decision.approved(d.approvalId, d.by)
    else agent.Decision.refused(d.approvalId, d.by, Option(d.note).getOrElse(""))
