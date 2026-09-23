package com.thinkmorestupidless.ankka.sidecar

import ankka.protocol.v1.discovery.*
import com.thinkmorestupidless.ankka.core.{
  ComponentId,
  ComponentKind,
  ComponentRegistry,
  MethodName
}
import com.thinkmorestupidless.ankka.runtime.remote.*
import com.thinkmorestupidless.ankka.sdk.{RecoverStrategy, WorkflowSettings}
import io.grpc.ManagedChannel
import org.slf4j.LoggerFactory

import scala.concurrent.duration.*
import scala.concurrent.Await
import scala.util.control.NonFatal

/**
 * The first conversation: the process describes what it hosts, the sidecar validates it.
 *
 * Every problem is collected and reported at once — through `ReportError` so it lands in the
 * developer's own log, and in the sidecar's — because a service with four problems should take one
 * restart to fix, not four. That is `ComponentRegistry.from`'s rule applied at the boundary.
 */
object Discovery:

  private val log = LoggerFactory.getLogger(getClass)

  /** The sidecar's own protocol version. Written once here and once in `controlplane-api`. */
  val ProtocolVersion: String = "1.0"

  /** What discovery hands the rest of the sidecar: validated descriptors plus the raw spec. */
  final case class Discovered(
      spec: Spec,
      descriptors: Vector[RemoteDescriptor],
      agents: Vector[Component],
      endpoints: Vector[Endpoint]
  )

  /** Dials until the process answers; refuses with every problem or returns the discovered spec. */
  def discover(
      channel: ManagedChannel,
      settings: Settings,
      runtimeVersion: String
  ): Either[Vector[String], Discovered] =
    val stub = DiscoveryGrpc.stub(channel)
    val spec = awaitSpec(stub, settings, runtimeVersion)
    validate(spec) match
      case Left(problems) =>
        val message = problems.mkString("the sidecar refused the service:\n  - ", "\n  - ", "")
        log.error(message)
        try Await.result(stub.reportError(Problem(message)), 5.seconds)
        catch
          case NonFatal(e) =>
            log.warn("could not report the refusal to the process: {}", e.toString)
        Left(problems)
      case right => right

  private def awaitSpec(
      stub: DiscoveryGrpc.DiscoveryStub,
      settings: Settings,
      runtimeVersion: String
  ): Spec =
    var attempt              = 0
    var backoff              = 500.millis
    var result: Option[Spec] = None
    while result.isEmpty do
      attempt += 1
      try
        result = Some(
          Await.result(
            stub.discover(SidecarInfo(ProtocolVersion, runtimeVersion)),
            settings.discoveryTimeout
          )
        )
      catch
        case NonFatal(e) =>
          log.info(
            "discovery attempt {} at {} failed ({}); retrying in {}",
            attempt,
            settings.processAddress,
            e.getClass.getSimpleName + ": " + e.getMessage,
            backoff
          )
          Thread.sleep(backoff.toMillis)
          backoff = (backoff * 2).min(settings.discoveryBackoffMax)
    result.get

  /** Pure: what is wrong with a spec, all of it. Exposed for the protocol suite. */
  def validate(spec: Spec): Either[Vector[String], Discovered] =
    val problems = Vector.newBuilder[String]

    spec.protocolVersion.split('.').toList match
      case major :: _ if major == ProtocolVersion.split('.').head => ()
      case _ =>
        problems += s"the SDK speaks protocol '${spec.protocolVersion}' and this sidecar speaks " +
          s"'$ProtocolVersion'; the major versions must match"

    val descriptors = Vector.newBuilder[RemoteDescriptor]
    val agents      = Vector.newBuilder[Component]

    spec.components.foreach { c =>
      ComponentId.parse(c.id) match
        case Left(msg) => problems += msg
        case Right(id) =>
          val handlers = c.handlers.flatMap { h =>
            MethodName.parse(h.name) match
              case Left(msg) =>
                problems += s"component '${c.id}': $msg"
                None
              case Right(name) =>
                if h.readOnly && h.streaming then
                  problems += s"component '${c.id}': handler '${h.name}' is both read-only and streaming"
                Some(name -> RemoteHandler(name, h.readOnly, h.streaming))
          }
          handlers.groupBy(_._1).collect { case (n, dup) if dup.sizeIs > 1 => n }.foreach { n =>
            problems += s"component '${c.id}': handler '$n' declared ${handlers.count(_._1 == n)} times"
          }
          val handlerMap = handlers.toMap
          (c.kind, c.detail) match
            case (Kind.EVENT_SOURCED_ENTITY, Component.Detail.EventSourced(d)) =>
              descriptors += RemoteEventSourcedDescriptor(
                id,
                handlerMap,
                Option(d.snapshotEvery).filter(_ > 0)
              )
            case (Kind.KEY_VALUE_ENTITY, Component.Detail.KeyValue(_)) =>
              descriptors += RemoteKeyValueDescriptor(id, handlerMap)
            case (Kind.WORKFLOW, Component.Detail.Workflow(d)) =>
              descriptors += RemoteWorkflowDescriptor(
                id,
                handlerMap,
                d.steps.toSet,
                workflowSettings(c.id, d, problems)
              )
            case (Kind.VIEW, Component.Detail.View(d)) =>
              source(c.id, d.source, problems).foreach { s =>
                descriptors += RemoteViewDescriptor(
                  id,
                  s,
                  d.rowManifest,
                  d.queries.map(MethodName(_)).toSet
                )
              }
            case (Kind.CONSUMER, Component.Detail.Consumer(d)) =>
              source(c.id, d.source, problems).foreach { s =>
                descriptors += RemoteConsumerDescriptor(id, s, d.producesTo)
              }
            case (Kind.TIMED_ACTION, Component.Detail.TimedAction(_)) =>
              descriptors += RemoteTimedActionDescriptor(id, handlerMap)
            case (Kind.AGENT, Component.Detail.Agent(d)) =>
              d.tools.groupBy(_.name).collect { case (n, dup) if dup.sizeIs > 1 => n }.foreach { n =>
                problems += s"agent '${c.id}': tool '$n' is declared ${d.tools.count(_.name == n)} times"
              }
              d.tools.foreach { t =>
                if t.name.isEmpty then problems += s"agent '${c.id}': a tool has no name"
                if t.description.isEmpty then
                  problems += s"agent '${c.id}': tool '${t.name}' has no description; the model decides by it"
                if t.inputSchemaJson.nonEmpty && com.thinkmorestupidless.ankka.agent.Json
                    .parse(t.inputSchemaJson)
                    .isLeft
                then
                  problems += s"agent '${c.id}': tool '${t.name}' has an input schema that is not JSON"
              }
              d.guardrails
                .groupBy(identity)
                .collect { case (n, dup) if dup.sizeIs > 1 => n }
                .foreach { n =>
                  problems += s"agent '${c.id}': guardrail '$n' is declared twice"
                }
              if d.maxToolCallSteps < 0 then
                problems += s"agent '${c.id}': max_tool_call_steps must not be negative"
              agents += c
            case (kind, detail) =>
              problems += s"component '${c.id}': kind $kind does not match its detail " +
                s"(${detail.getClass.getSimpleName}); this sidecar cannot host it"
    }

    val built = descriptors.result()
    // Sources must name a declared component: a view over nothing would never receive an event.
    built.foreach {
      case v: RemoteViewDescriptor =>
        v.source match
          case RemoteSource.Component(kind, id)
              if !built.exists(d => d.kind == kind && d.componentId == id) =>
            problems += s"view '${v.componentId}' subscribes to $kind '$id', which is not declared"
          case _ => ()
      case c: RemoteConsumerDescriptor =>
        c.source match
          case RemoteSource.Component(kind, id)
              if !built.exists(d => d.kind == kind && d.componentId == id) =>
            problems += s"consumer '${c.componentId}' subscribes to $kind '$id', which is not declared"
          case _ => ()
      case _ => ()
    }

    ComponentRegistry.from(built) match
      case Left(more) => problems ++= more
      case Right(_)   => ()

    // Endpoints: the same rules HttpServer.validate applies to a Scala endpoint, at the boundary.
    spec.endpoints.groupBy(_.prefix).foreach { (prefix, sharing) =>
      if sharing.sizeIs > 1 then problems += s"${sharing.size} endpoints share the prefix '$prefix'"
    }
    spec.endpoints.foreach { e =>
      if e.id.isEmpty then problems += s"an endpoint with prefix '${e.prefix}' has no id"
      if !e.prefix.startsWith("/") then
        problems += s"endpoint '${e.id}': prefix '${e.prefix}' must start with '/'"
      e.routes.groupBy(r => (r.method.toUpperCase, r.template)).foreach { (key, dup) =>
        if dup.sizeIs > 1 then
          problems += s"endpoint '${e.id}' declares ${key._1} ${key._2} ${dup.size} times"
      }
      e.routes.groupBy(_.id).foreach { (id, dup) =>
        if dup.sizeIs > 1 then
          problems += s"endpoint '${e.id}' declares route id '$id' ${dup.size} times"
      }
      e.routes.foreach { r =>
        if !Set("GET", "POST", "PUT", "DELETE", "PATCH").contains(r.method.toUpperCase) then
          problems += s"endpoint '${e.id}': route '${r.id}' has an unsupported method '${r.method}'"
        if !r.template.startsWith("/") then
          problems += s"endpoint '${e.id}': route '${r.id}' template '${r.template}' must start with '/'"
        if r.template.count(_ == '{') != r.template.count(_ == '}') then
          problems += s"endpoint '${e.id}': route '${r.id}' template '${r.template}' has unbalanced braces"
      }
    }

    val found = problems.result()
    if found.nonEmpty then Left(found)
    else Right(Discovered(spec, built, agents.result(), spec.endpoints.toVector))

  /**
   * The engine's settings, as the process declared them. A failover target must be a declared step:
   * the engine runs it with no input, and a name that matches nothing would fail the workflow at
   * the exact moment it was meant to recover.
   */
  private def workflowSettings(
      owner: String,
      detail: WorkflowDetail,
      problems: scala.collection.mutable.Builder[String, Vector[String]]
  ): WorkflowSettings =
    detail.settings match
      case None => WorkflowSettings.default
      case Some(declared) =>
        def duration(millis: Long, what: String): Option[FiniteDuration] =
          if millis > 0 then Some(millis.millis)
          else
            problems += s"workflow '$owner': $what must be positive, not ${millis}ms"
            None
        def recovery(r: WorkflowDetail.Recovery, what: String): RecoverStrategy =
          if r.maxRetries < 0 then
            problems += s"workflow '$owner': $what declares ${r.maxRetries} retries"
          r.failoverTo.foreach { target =>
            if !detail.steps.contains(target) then
              problems += s"workflow '$owner': $what fails over to '$target', which is not a declared step"
          }
          RecoverStrategy(math.max(0, r.maxRetries), r.failoverTo)
        val base = WorkflowSettings.default
        WorkflowSettings(
          timeout = declared.timeoutMillis.flatMap(duration(_, "the workflow timeout")),
          defaultStepTimeout = declared.defaultStepTimeoutMillis
            .flatMap(duration(_, "the default step timeout"))
            .getOrElse(base.defaultStepTimeout),
          stepTimeouts = declared.steps.flatMap { st =>
            if !detail.steps.contains(st.step) then
              problems += s"workflow '$owner': settings name step '${st.step}', which is not declared"
            st.timeoutMillis.flatMap(duration(_, s"step '${st.step}' timeout")).map(st.step -> _)
          }.toMap,
          defaultRecovery = declared.defaultRecovery
            .map(recovery(_, "the default recovery"))
            .getOrElse(base.defaultRecovery),
          stepRecovery = declared.steps.flatMap { st =>
            st.recovery.map(r => st.step -> recovery(r, s"step '${st.step}' recovery"))
          }.toMap
        )

  private def source(
      owner: String,
      s: Option[Source],
      problems: scala.collection.mutable.Builder[String, Vector[String]]
  ): Option[RemoteSource] =
    s.map(_.source) match
      case Some(Source.Source.Component(ref)) =>
        ComponentId.parse(ref.id) match
          case Left(msg) =>
            problems += s"component '$owner': source $msg"
            None
          case Right(id) => Some(RemoteSource.Component(kindOf(ref.kind), id))
      case Some(Source.Source.Topic(name)) if name.nonEmpty => Some(RemoteSource.Topic(name))
      case _ =>
        problems += s"component '$owner' declares no source"
        None

  def kindOf(kind: Kind): ComponentKind = kind match
    case Kind.EVENT_SOURCED_ENTITY => ComponentKind.EventSourcedEntity
    case Kind.KEY_VALUE_ENTITY     => ComponentKind.KeyValueEntity
    case Kind.WORKFLOW             => ComponentKind.Workflow
    case Kind.VIEW                 => ComponentKind.View
    case Kind.CONSUMER             => ComponentKind.Consumer
    case Kind.TIMED_ACTION         => ComponentKind.TimedAction
    case _                         => ComponentKind.Agent

  /** Not used by `validate`; the callback service maps the other way. */
  def kindTo(kind: ComponentKind): Kind = kind match
    case ComponentKind.EventSourcedEntity => Kind.EVENT_SOURCED_ENTITY
    case ComponentKind.KeyValueEntity     => Kind.KEY_VALUE_ENTITY
    case ComponentKind.Workflow           => Kind.WORKFLOW
    case ComponentKind.View               => Kind.VIEW
    case ComponentKind.Consumer           => Kind.CONSUMER
    case ComponentKind.TimedAction        => Kind.TIMED_ACTION
    case _                                => Kind.AGENT
