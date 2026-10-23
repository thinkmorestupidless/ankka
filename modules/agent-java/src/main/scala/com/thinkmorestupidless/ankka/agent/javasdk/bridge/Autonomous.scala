package com.thinkmorestupidless.ankka.agent.javasdk.bridge

import com.thinkmorestupidless.ankka.agent
import com.thinkmorestupidless.ankka.agent.autonomous
import com.thinkmorestupidless.ankka.agent.autonomous.{
  forAutonomousAgent,
  forTask,
  tasks,
  AutonomousAgentContext,
  AutonomousAgentDefinition,
  AutonomousAgentSettings,
  JsonSchema,
  TaskAcceptance,
  TaskRule
}
import com.thinkmorestupidless.ankka.agent.javasdk
import com.thinkmorestupidless.ankka.agent.{FunctionTool, Json}
import com.thinkmorestupidless.ankka.core.{ComponentId, Done}
import com.thinkmorestupidless.ankka.javasdk.annotations
import com.thinkmorestupidless.ankka.javasdk.bridge.{
  Clients,
  Declarations,
  Effects,
  Invocable,
  Services,
  Stores
}
import com.thinkmorestupidless.ankka.javasdk.codec.Serializer
import com.thinkmorestupidless.ankka.javasdk.problems.DeclarationProblem
import com.thinkmorestupidless.ankka.runtime
import com.thinkmorestupidless.ankka.sdk.{CallTransport, ComponentClient as ScalaClient}
import org.apache.pekko.stream.SystemMaterializer
import org.apache.pekko.stream.scaladsl.JavaFlowSupport

import java.lang.reflect.{Method, Modifier}
import java.time.Duration
import java.util.concurrent.Flow
import java.util.function.Function as JFunction
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

// ── Task types ─────────────────────────────────────────────────────────────────────────────────

/** A Java task type over the Scala one, its result read and written by the Java serializer. */
private[bridge] final case class JavaTaskType[R](scala: autonomous.TaskType[R])
    extends javasdk.TaskType[R]:
  def name(): String        = scala.name
  def description(): String = scala.description

  def resultConformsTo[S](`type`: Class[S]): javasdk.TaskType[S] =
    if !`type`.isRecord then
      throw IllegalArgumentException(
        s"task type '${scala.name}': ${`type`.getSimpleName} is not a record, so it has no shape"
      )
    if scala.hasResultShape then
      throw IllegalArgumentException(s"task type '${scala.name}' has a result type already")
    val serializer = Values.serializer(`type`, Map.empty).asInstanceOf[Serializer[S]]
    val shape = new JsonSchema[S]:
      val schema: Json = Tools.recordSchema(`type`)
    JavaTaskType(
      scala
        .asInstanceOf[autonomous.TaskType[String]]
        .resultConformsTo[S](using SerializerCodec(serializer), shape)
    )

  def rule(name: String, check: JFunction[R, javasdk.RuleResult]): javasdk.TaskType[R] =
    JavaTaskType(scala.rule(name) { result =>
      check.apply(result) match
        case r: javasdk.RuleResult.Rejected => TaskRule.Rejected(r.reason)
        case _: javasdk.RuleResult.Accepted => TaskRule.Accepted
        case null => throw IllegalStateException(s"rule '$name' answered null, not a RuleResult")
    })

// ── The definition ─────────────────────────────────────────────────────────────────────────────

private[bridge] final case class JavaDefinition(
    description: String,
    instructionsText: Option[String] = None,
    guardrailList: Vector[javasdk.Guardrail] = Vector.empty,
    modelProvider: Option[javasdk.ModelProvider] = None,
    servers: Vector[javasdk.McpServer] = Vector.empty,
    resultGuardrailList: Vector[javasdk.Guardrail] = Vector.empty,
    acceptances: Vector[(javasdk.TaskType[?], Int)] = Vector.empty
) extends javasdk.AutonomousDefinition:

  def instructions(text: String): javasdk.AutonomousDefinition = copy(instructionsText = Some(text))
  override def guardrails(guardrails: javasdk.Guardrail*): javasdk.AutonomousDefinition =
    copy(guardrailList = guardrailList ++ guardrails)
  def model(provider: javasdk.ModelProvider): javasdk.AutonomousDefinition =
    copy(modelProvider = Some(provider))
  override def mcpServers(more: javasdk.McpServer*): javasdk.AutonomousDefinition =
    copy(servers = servers ++ more)
  override def resultGuardrails(guardrails: javasdk.Guardrail*): javasdk.AutonomousDefinition =
    copy(resultGuardrailList = resultGuardrailList ++ guardrails)
  def accepts(`type`: javasdk.TaskType[?]): javasdk.AutonomousDefinition = accepts(`type`, 10)
  def accepts(
      `type`: javasdk.TaskType[?],
      maxIterationsPerTask: Int
  ): javasdk.AutonomousDefinition =
    copy(acceptances = acceptances :+ (`type` -> maxIterationsPerTask))

  def scala: AutonomousAgentDefinition =
    AutonomousAgentDefinition
      .remote(
        description,
        instructionsText,
        guardrailList.map(Values.scalaGuardrail),
        modelProvider.map(Values.scalaModel),
        acceptances.map((t, budget) =>
          TaskAcceptance.of(Autonomous.scalaType(t)).maxIterationsPerTask(budget)
        ),
        AutonomousAgentSettings()
      )
      .mcpServers(servers.map(Values.scalaMcp)*)
      .resultGuardrails(resultGuardrailList.map(Values.scalaGuardrail)*)

// ── Reading an autonomous agent class ──────────────────────────────────────────────────────────

private[bridge] final case class AutonomousClassPlan(
    cls: Class[?],
    componentId: String,
    construct: () => AnyRef,
    definition: JavaDefinition
)

private[bridge] object AutonomousReader:

  private val ToolsMethod: Method =
    val m = classOf[javasdk.AutonomousAgent].getDeclaredMethod("tools")
    m.setAccessible(true)
    m

  private val DefinitionMethod: Method =
    val m = classOf[javasdk.AutonomousAgent].getDeclaredMethod("definition")
    m.setAccessible(true)
    m

  def tools(instance: AnyRef): Seq[AnyRef] =
    Option(Invocable.invoke(ToolsMethod, instance).asInstanceOf[java.util.List[AnyRef]])
      .map(_.asScala.toSeq)
      .getOrElse(Seq.empty)

  def read(
      cls: Class[?],
      idOverride: Option[String]
  ): Either[Vector[DeclarationProblem], AutonomousClassPlan] =
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

    // The definition is the instance's: read once, from one built for it with nothing to call.
    val definition = construct.flatMap { build =>
      try
        Invocable.invoke(DefinitionMethod, build()) match
          case d: JavaDefinition => Some(d)
          case null              => problem("definition", "returns null"); None
          case other =>
            problem("definition", s"returns $other, not one AutonomousDefinition made"); None
      catch
        case e: Exception =>
          problem("", s"could not be built to read its definition: ${e.getMessage}"); None
    }

    if problems.nonEmpty then Left(problems.toVector)
    else Right(AutonomousClassPlan(cls, componentId.get, construct.get, definition.get))

// ── Hosting ────────────────────────────────────────────────────────────────────────────────────

/** The Scala autonomous agent a Java one runs as: its tools are the Java instance's. */
private[bridge] final class AutonomousShell(
    context: AutonomousAgentContext,
    java: AnyRef,
    serializers: Declarations.Serializers
) extends autonomous.AutonomousAgent(context):
  override def tools: Seq[FunctionTool] = Tools.of(AutonomousReader.tools(java), serializers)

private[bridge] final class JavaAutonomousCompanion(
    val plan: AutonomousClassPlan,
    refs: RefsHolder,
    serializers: Declarations.Serializers
) extends autonomous.AutonomousAgent.Companion[AutonomousShell](ComponentId(plan.componentId)):

  def definition: AutonomousAgentDefinition = plan.definition.scala

  def create(context: AutonomousAgentContext): AutonomousShell =
    val cell = AutonomousCell(
      context.instanceId,
      Clients.of(context.componentClient, refs.refs),
      Services.of(context.services, serializers),
      Stores.secrets(context.secrets),
      Stores.storage(context.storage)
    )
    AutonomousShell(context, AutonomousCell.constructing(cell)(plan.construct()), serializers)

// ── Calls ──────────────────────────────────────────────────────────────────────────────────────

private[bridge] object Autonomous:

  def scalaType[R](t: javasdk.TaskType[R]): autonomous.TaskType[R] = t match
    case JavaTaskType(scala) => scala
    case other => throw IllegalArgumentException(s"$other is not a task type Task made")

  def snapshot(record: autonomous.TaskRecord): javasdk.TaskSnapshot[String] =
    javasdk.TaskSnapshot(
      record.id,
      record.typeName,
      record.status.wire,
      record.reason.toJava,
      record.assignee.map(_.instanceId).toJava,
      record.iterations,
      record.result.toJava
    )

  def typed[R](snapshot: autonomous.TypedTaskSnapshot[R]): javasdk.TaskSnapshot[R] =
    javasdk.TaskSnapshot(
      snapshot.record.id,
      snapshot.record.typeName,
      snapshot.status.wire,
      snapshot.reason.toJava,
      snapshot.assignee.map(_.instanceId).toJava,
      snapshot.record.iterations,
      snapshot.result.toJava
    )

  def notification(n: autonomous.Notification): javasdk.Notification =
    val fields = n.productElementNames.zip(n.productIterator).toMap
    javasdk.Notification(
      n.productPrefix,
      fields.get("instanceId").map(_.toString).getOrElse(""),
      fields.get("taskId").map(_.toString).toJava,
      fields.get("at").collect { case at: Long => at }.getOrElse(0L),
      String(autonomous.Notification.serializer.toBytes(n), "UTF-8")
    )

private[bridge] final class JavaTasksRef(client: ScalaClient) extends javasdk.TasksRef:
  def create[R](`type`: javasdk.TaskType[R], instructions: String): javasdk.TaskBuilder[R] =
    JavaTaskBuilder(client.tasks.create(Autonomous.scalaType(`type`), instructions))

private[bridge] final case class JavaTaskBuilder[R](scala: autonomous.TaskBuilder[R])
    extends javasdk.TaskBuilder[R]:
  def withId(taskId: String): javasdk.TaskBuilder[R] = JavaTaskBuilder(scala.withId(taskId))
  def attach(name: String, contentType: String, content: String): javasdk.TaskBuilder[R] =
    JavaTaskBuilder(scala.attach(name, contentType, content))
  def attachReference(name: String, contentType: String, uri: String): javasdk.TaskBuilder[R] =
    JavaTaskBuilder(scala.attachReference(name, contentType, uri))
  override def dependsOn(taskIds: String*): javasdk.TaskBuilder[R] =
    JavaTaskBuilder(scala.dependsOn(taskIds*))
  def create(): String = Outcomes.answering(scala.create())

private[bridge] final class JavaTaskRef(client: ScalaClient, taskId: String)
    extends javasdk.TaskRef:
  private def calls = client.forTask(taskId)

  def get(): javasdk.TaskSnapshot[String] = Outcomes.answering(Autonomous.snapshot(calls.get()))
  def get[R](`type`: javasdk.TaskType[R]): javasdk.TaskSnapshot[R] =
    Outcomes.answering(Autonomous.typed(calls.get(Autonomous.scalaType(`type`))))
  def await[R](`type`: javasdk.TaskType[R], timeout: Duration): javasdk.TaskSnapshot[R] =
    Outcomes.answering(
      Autonomous.typed(calls.await(Autonomous.scalaType(`type`), Effects.finite(timeout)))
    )
  def cancel(reason: String): Unit = Outcomes.answering(calls.cancel(reason): Unit)

private[bridge] final class JavaAutonomousRef(
    client: ScalaClient,
    transport: CallTransport,
    componentId: ComponentId,
    instanceId: String
) extends javasdk.AutonomousRef:
  private val calls = Outcomes.answering(client.forAutonomousAgent(componentId, instanceId))

  override def assign(taskIds: String*): javasdk.AssignResult =
    val result = Outcomes.answering(calls.assign(taskIds*))
    javasdk.AssignResult(
      result.accepted.asJava,
      result.refused.map((id, refusal) => id -> refusal.message).asJava
    )
  def suspend(): Unit   = Outcomes.answering(calls.suspend(): Unit)
  def resume(): Unit    = Outcomes.answering(calls.resume(): Unit)
  def terminate(): Unit = Outcomes.answering(calls.terminate(): Unit)

  def state(): javasdk.InstanceState =
    val s = Outcomes.answering(calls.state())
    javasdk.InstanceState(
      s.instanceId,
      s.phase.toString,
      s.suspended,
      s.terminated,
      s.currentTask.map(_.taskId).toJava,
      s.currentTask.map(_.iteration).getOrElse(0),
      s.queued.asJava,
      s.awaiting.map(Outcomes.request).asJava
    )

  def notifications(): Flow.Publisher[javasdk.Notification] =
    val system = transport match
      case sharded: runtime.ShardingTransport => sharded.actorSystem
      case other =>
        throw IllegalStateException(s"notifications are read through a running service, not $other")
    calls
      .notifications()
      .map(Autonomous.notification)
      .runWith(JavaFlowSupport.Sink.asPublisher[javasdk.Notification](fanout = false))(using
        SystemMaterializer(system).materializer
      )

  def decide(decision: javasdk.Decision): Unit =
    Outcomes.answering(calls.decide(Outcomes.scala(decision)): Done): Unit
