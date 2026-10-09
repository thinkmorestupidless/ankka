package com.thinkmorestupidless.ankka.sidecar

import ankka.protocol.v1.discovery.*
import ankka.protocol.v1.discovery.StartFrom as ProtoStartFrom
import com.thinkmorestupidless.ankka.auth.oidc.OidcConfig
import com.thinkmorestupidless.ankka.core.{
  ComponentId,
  ComponentKind,
  ComponentRegistry,
  Contract as CoreContract,
  MethodName
}
import com.thinkmorestupidless.ankka.runtime.remote.*
import com.thinkmorestupidless.ankka.runtime.{KeyedViewRules, QueryCheck, TopicSourceRules}
import com.thinkmorestupidless.ankka.sdk.{
  DeclaredQuery,
  Publication,
  RecoverStrategy,
  StartFrom,
  TopicOptions,
  WorkflowSettings
}
import io.grpc.ManagedChannel
import org.slf4j.LoggerFactory

import scala.concurrent.duration.*
import scala.concurrent.Await
import scala.util.control.NonFatal

/**
 * The first conversation: the process describes what it hosts, the sidecar validates it. A module
 * describes itself through its `ankka1_discover` export instead (`wasm.WasmDiscovery`), and is held
 * to the same `validate`.
 *
 * Every problem is collected and reported at once — through `ReportError` so it lands in the
 * developer's own log, and in the sidecar's — because a service with four problems should take one
 * restart to fix, not four. That is `ComponentRegistry.from`'s rule applied at the boundary.
 */
object Discovery:

  private val log = LoggerFactory.getLogger(getClass)

  /**
   * The sidecar's own protocol version: the runtime's, which is also written once in
   * `controlplane-api`.
   *
   * 1.1: the caller on every forwarded request and caller-naming ACLs in discovery (feature 014).
   * 1.3: a consumer may answer with several messages, each under its own record key (feature 019).
   * 1.4: metadata on a workflow step, a tool call, a guardrail check, a result check and a view
   * query, so a call made from any of them is attributed to its handler. 1.5: a route's principal
   * carries the token's other claims and its issuer's name (feature 022). 1.6: the secret store,
   * three calls on `Client` and three module imports (feature 023). 1.7: a topic source declares
   * where it starts, and a view or consumer over a topic its version (feature 024). 1.8: a call to
   * another service, `Request` on `Client`, made by the runtime as the service (feature 025). 1.9:
   * socket routes, `Route.socket` and `Http.HandleSocket` (feature 028). 1.10: three imports for a
   * module, `request`, `now` and `random`, and no message changed (feature 030). 1.11: a tool's
   * approval, an agent's MCP servers and result guardrails, the RESULT guardrail stage, the
   * approval-request reply and token, and `Decide` (feature 029). 1.12: recurring timers, one call
   * on `Client` and one module import, and the due time in a timed action's metadata (feature 032).
   * 1.13: a view's declared queries, the keyed view, and a version on a view that reads entities
   * (feature 031). 1.14: a topic source's contract, broker and parallel flag (feature 037). 1.15:
   * the granted-caller matcher, the machine caller, and another project's topic (feature 040).
   */
  val ProtocolVersion: String = WireProtocol.Version

  /**
   * What discovery hands the rest of the sidecar: validated descriptors plus the raw spec. `shapes`
   * is a module's per-component guest shape; a process has none, and a component absent from it is
   * stateless.
   */
  final case class Discovered(
      spec: Spec,
      descriptors: Vector[RemoteDescriptor],
      agents: Vector[Component],
      endpoints: Vector[Endpoint],
      autonomousAgents: Vector[Component] = Vector.empty,
      shapes: Map[ComponentId, Shape] = Map.empty
  ):
    def shapeOf(id: ComponentId): Shape = shapes.getOrElse(id, Shape.Stateless)

  /**
   * How a module's guest keeps a stateful component's state: handed it on every call, or once per
   * loaded instance and kept until closed. The runtime holds the state in both.
   */
  enum Shape:
    case Stateless, Stateful

  /**
   * Dials the process until it answers; refuses with every problem or returns the discovered spec.
   */
  def discover(
      channel: ManagedChannel,
      settings: Settings,
      runtimeVersion: String,
      protocolVersion: String = ProtocolVersion
  ): Either[Vector[String], Discovered] =
    val stub = DiscoveryGrpc.stub(channel)
    val spec = awaitSpec(stub, settings, runtimeVersion, protocolVersion)
    validate(spec, protocolVersion, authConfigured = !settings.auth.isEmpty) match
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
      runtimeVersion: String,
      protocolVersion: String
  ): Spec =
    var attempt              = 0
    var backoff              = 500.millis
    var result: Option[Spec] = None
    while result.isEmpty do
      attempt += 1
      try
        result = Some(
          Await.result(
            stub.discover(SidecarInfo(protocolVersion, runtimeVersion)),
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

  /** The minor that introduced socket routes. */
  private val SocketsSince = 9

  /** The first minor whose SDKs may declare granted callers and another project's topic. */
  private val GrantsSince = 15

  /**
   * Whether an SDK speaking `version` predates grants, so cannot have meant another project's
   * topic.
   */
  private def beforeGrants(version: String): Boolean = minorOf(version).exists(_ < GrantsSince)

  private def minorOf(version: String): Option[Int] =
    version.split('.').toList match
      case _ :: minor :: Nil => minor.toIntOption
      case _                 => None

  /**
   * Pure: what is wrong with a spec, all of it. The rules every spec is held to, whether a process
   * or a module declared it; a module's own rules are added by `wasm.WasmDiscovery`.
   *
   * `authConfigured` says whether the sidecar has issuers to verify tokens against. Without them,
   * an `AUTHENTICATED` endpoint or route is a problem: it could only ever answer 503 (feature 022).
   * Deliberately no default, so no caller can forget to say.
   */
  def validate(
      spec: Spec,
      protocolVersion: String,
      authConfigured: Boolean
  ): Either[Vector[String], Discovered] =
    val problems = Vector.newBuilder[String]

    spec.protocolVersion.split('.').toList match
      case major :: _ if major == protocolVersion.split('.').head => ()
      case _ =>
        problems += s"the SDK speaks protocol '${spec.protocolVersion}' and this sidecar speaks " +
          s"'$protocolVersion'; the major versions must match"

    val descriptors = Vector.newBuilder[RemoteDescriptor]
    val agents      = Vector.newBuilder[Component]
    val autonomous  = Vector.newBuilder[Component]

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
            case (Kind.VIEW, Component.Detail.View(d)) if d.sources.nonEmpty =>
              // A keyed view (1.13): its sources, each a component, and never a single source.
              if d.source.isDefined then
                problems += s"view '${c.id}' declares a source and sources; a plain view declares " +
                  "one source, a keyed view its sources"
              else
                val read =
                  d.sources.flatMap(s => source(s"view '${c.id}'", c.id, Some(s), problems))
                if read.size == d.sources.size then
                  descriptors += RemoteKeyedViewDescriptor(
                    id,
                    read.toVector,
                    d.rowManifest,
                    d.version,
                    d.declaredQueries.map(q => DeclaredQuery(id, q.name, q.statement)).toVector
                  )
            case (Kind.VIEW, Component.Detail.View(d)) =>
              source(s"view '${c.id}'", c.id, d.source, problems).foreach { s =>
                descriptors += RemoteViewDescriptor(
                  id,
                  s,
                  d.rowManifest,
                  d.queries.map(MethodName(_)).toSet,
                  d.version,
                  d.declaredQueries.map(q => DeclaredQuery(id, q.name, q.statement)).toVector
                )
              }
            case (Kind.CONSUMER, Component.Detail.Consumer(d)) =>
              source(s"consumer '${c.id}'", c.id, d.source, problems).foreach { s =>
                val produces = d.produces.map(p =>
                  Publication(
                    p.topic,
                    p.contract.map(contract),
                    p.broker.filter(_.nonEmpty),
                    p.project.filter(_.nonEmpty)
                  )
                )
                if produces.exists(p => d.producesTo.exists(_ != p.topic)) then
                  problems += s"consumer '${c.id}' names '${d.producesTo.get}' in produces_to and " +
                    s"'${produces.get.topic}' in produces; a consumer publishes to one topic"
                if produces.exists(_.project.isDefined) && beforeGrants(spec.protocolVersion) then
                  problems += s"consumer '${c.id}' publishes to another project's topic, which " +
                    s"needs protocol 1.$GrantsSince; the SDK speaks ${spec.protocolVersion}"
                descriptors += RemoteConsumerDescriptor(
                  id,
                  s,
                  // As the runtime carries it: `<project>/<name>` for another project's topic.
                  produces.map(_.address).orElse(d.producesTo),
                  startDeclarable = declaresStartPositions(spec.protocolVersion),
                  version = d.version,
                  produces = produces
                )
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
              problems ++= RemoteMcp.problems(
                s"agent '${c.id}'",
                d.mcpServers,
                d.tools,
                d.resultGuardrails
              )
              agents += c
            case (Kind.AUTONOMOUS_AGENT, Component.Detail.AutonomousAgent(d)) =>
              problems ++= RemoteAutonomousAgent.problems(c.id, d)
              autonomous += c
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

    // The rules a Scala service is held to, for what a process declared about the topics it reads.
    problems ++= TopicSourceRules.problems(built)

    // What a keyed view may read, and a view's declared statements, checked as a Scala view's are.
    problems ++= KeyedViewRules.problems(built)
    problems ++= QueryCheck.problems(built)

    // Endpoints: the same rules HttpServer.validate applies to a Scala endpoint, at the boundary.
    spec.endpoints.groupBy(_.prefix).foreach { (prefix, sharing) =>
      if sharing.sizeIs > 1 then problems += s"${sharing.size} endpoints share the prefix '$prefix'"
    }
    spec.endpoints.foreach { e =>
      if e.id.isEmpty then problems += s"an endpoint with prefix '${e.prefix}' has no id"
      if !e.prefix.startsWith("/") then
        problems += s"endpoint '${e.id}': prefix '${e.prefix}' must start with '/'"
      if e.acl.isCallers && e.allowCallers.isEmpty then
        problems += s"endpoint '${e.id}': a CALLERS acl must name at least one caller"
      if !authConfigured then
        if e.acl.isAuthenticated then
          problems += s"endpoint '${e.id}' is AUTHENTICATED but no issuer is configured; " +
            s"set ${OidcConfig.IssuersVariable}"
        e.routes.filter(_.acl.exists(_.isAuthenticated)).foreach { r =>
          problems += s"endpoint '${e.id}': route '${r.method.toUpperCase} ${r.template}' is " +
            s"AUTHENTICATED but no issuer is configured; set ${OidcConfig.IssuersVariable}"
        }
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
        if r.acl.exists(_.isCallers) && r.allowCallers.isEmpty then
          problems += s"endpoint '${e.id}': route '${r.id}' has a CALLERS acl naming no caller"
        if r.template.count(_ == '{') != r.template.count(_ == '}') then
          problems += s"endpoint '${e.id}': route '${r.id}' template '${r.template}' has unbalanced braces"
        if r.socket then
          if r.method.toUpperCase != "GET" then
            problems += s"endpoint '${e.id}': route '${r.id}' is a socket route, which is opened " +
              s"with GET, not ${r.method}"
          if r.hasBody then
            problems += s"endpoint '${e.id}': route '${r.id}' is a socket route, which takes no body"
          if r.streaming then
            problems += s"endpoint '${e.id}': route '${r.id}' is a socket route and streaming; " +
              "declare one or the other"
          // The first minor the sidecar gates on: an older SDK cannot have meant a socket, and a
          // runtime that predates the field would have served the route as a plain GET.
          if minorOf(spec.protocolVersion).exists(_ < SocketsSince) then
            problems += s"endpoint '${e.id}': route '${r.id}' is a socket route, which needs " +
              s"protocol 1.$SocketsSince; the SDK speaks ${spec.protocolVersion}"
      }
      // Feature 040: an SDK before 1.15 cannot have meant granted callers, and a runtime before it
      // would read the matcher as nobody — refused here, as a socket route is, so the mismatch is
      // named at start rather than found as a 403.
      val grantsBefore = minorOf(spec.protocolVersion).exists(_ < GrantsSince)
      if grantsBefore && e.allowCallers.exists(_.kind.isGranted) then
        problems += s"endpoint '${e.id}' admits granted callers, which needs protocol " +
          s"1.$GrantsSince; the SDK speaks ${spec.protocolVersion}"
      e.routes.filter(r => grantsBefore && r.allowCallers.exists(_.kind.isGranted)).foreach { r =>
        problems += s"endpoint '${e.id}': route '${r.id}' admits granted callers, which needs " +
          s"protocol 1.$GrantsSince; the SDK speaks ${spec.protocolVersion}"
      }
    }
    // Feature 040: a runtime before 1.15 would read another project's topic as this project's.
    if beforeGrants(spec.protocolVersion) then
      spec.components.foreach { c =>
        val sources = c.detail match
          case Component.Detail.View(d)     => d.source.toVector ++ d.sources
          case Component.Detail.Consumer(d) => d.source.toVector
          case _                            => Vector.empty
        if sources.exists(_.project.exists(_.nonEmpty)) then
          problems += s"component '${c.id}' reads another project's topic, which needs " +
            s"protocol 1.$GrantsSince; the SDK speaks ${spec.protocolVersion}"
      }

    val found = problems.result()
    if found.nonEmpty then Left(found)
    else
      Right(Discovered(spec, built, agents.result(), spec.endpoints.toVector, autonomous.result()))

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

  /**
   * `named` is how a problem names the component — `view 'summary'`. A start position is refused
   * where it means nothing: on a source that reads an entity, and when it names no position, which
   * a Scala declaration cannot say and a process can.
   */
  private def source(
      named: String,
      owner: String,
      s: Option[Source],
      problems: scala.collection.mutable.Builder[String, Vector[String]]
  ): Option[RemoteSource] =
    val declaredStart = s.flatMap(_.startFrom)
    // 1.14: what a project must know about a topic source; nothing on an earlier Spec.
    val options = s.fold(TopicOptions())(src =>
      TopicOptions(
        src.contract.map(contract),
        src.broker.filter(_.nonEmpty),
        src.parallel.getOrElse(false),
        // 1.15: another project's topic; refused below on an earlier Spec.
        src.project.filter(_.nonEmpty)
      )
    )
    s.map(_.source) match
      case Some(Source.Source.Component(ref)) =>
        ComponentId.parse(ref.id) match
          case Left(msg) =>
            problems += s"component '$owner': source $msg"
            None
          case Right(id) =>
            val kind = kindOf(ref.kind)
            if declaredStart.isDefined then
              problems += s"$named declares a start position, which applies to a topic; it " +
                s"reads ${describe(kind)} '$id'"
            if options != TopicOptions() then
              problems += s"$named declares a contract, a broker or parallel, which apply to a " +
                s"topic; it reads ${describe(kind)} '$id'"
            Some(RemoteSource.Component(kind, id))
      case Some(Source.Source.Topic(name)) if name.nonEmpty =>
        declaredStart match
          case None => Some(RemoteSource.Topic(name, None, options))
          // A start position that names nothing is refused once, here, and the source is not
          // built: read as "none declared" it would be refused a second time for that.
          case Some(declared) =>
            startFrom(named, declared, problems).map(start =>
              RemoteSource.Topic(name, Some(start), options)
            )
      case _ =>
        problems += s"component '$owner' declares no source"
        None

  private def startFrom(
      named: String,
      declared: ProtoStartFrom,
      problems: scala.collection.mutable.Builder[String, Vector[String]]
  ): Option[StartFrom] =
    declared.position match
      case ProtoStartFrom.Position.Named(ProtoStartFrom.Named.EARLIEST) => Some(StartFrom.Earliest)
      case ProtoStartFrom.Position.Named(ProtoStartFrom.Named.LATEST)   => Some(StartFrom.Latest)
      case ProtoStartFrom.Position.AtMillis(millis) =>
        Some(StartFrom.At(java.time.Instant.ofEpochMilli(millis)))
      case _ =>
        problems += s"$named declares a start position that names none"
        None

  /** A word for a kind, as a person would say it. */
  /** 1.14: a contract as the process stated it. */
  private def contract(c: Contract): CoreContract = CoreContract(c.name, c.fingerprint)

  private def describe(kind: ComponentKind): String = kind match
    case ComponentKind.EventSourcedEntity => "event sourced entity"
    case ComponentKind.KeyValueEntity     => "key value entity"
    case other                            => other.toString

  /**
   * Whether an SDK speaking `protocolVersion` could have declared where a topic source starts. One
   * that could not is not refused for declaring nowhere: refusing it would leave a running service
   * unable to restart after the platform beneath it was upgraded.
   */
  def declaresStartPositions(protocolVersion: String): Boolean =
    protocolVersion.split('.').toList.map(_.toIntOption) match
      case Some(major) :: Some(minor) :: _ => major > 1 || (major == 1 && minor >= 7)
      case _                               => true

  def kindOf(kind: Kind): ComponentKind = kind match
    case Kind.EVENT_SOURCED_ENTITY => ComponentKind.EventSourcedEntity
    case Kind.KEY_VALUE_ENTITY     => ComponentKind.KeyValueEntity
    case Kind.WORKFLOW             => ComponentKind.Workflow
    case Kind.VIEW                 => ComponentKind.View
    case Kind.CONSUMER             => ComponentKind.Consumer
    case Kind.TIMED_ACTION         => ComponentKind.TimedAction
    case Kind.AUTONOMOUS_AGENT     => ComponentKind.AutonomousAgent
    case _                         => ComponentKind.Agent

  /** Not used by `validate`; the callback service maps the other way. */
  def kindTo(kind: ComponentKind): Kind = kind match
    case ComponentKind.EventSourcedEntity => Kind.EVENT_SOURCED_ENTITY
    case ComponentKind.KeyValueEntity     => Kind.KEY_VALUE_ENTITY
    case ComponentKind.Workflow           => Kind.WORKFLOW
    case ComponentKind.View               => Kind.VIEW
    case ComponentKind.Consumer           => Kind.CONSUMER
    case ComponentKind.TimedAction        => Kind.TIMED_ACTION
    case ComponentKind.AutonomousAgent    => Kind.AUTONOMOUS_AGENT
    case _                                => Kind.AGENT
