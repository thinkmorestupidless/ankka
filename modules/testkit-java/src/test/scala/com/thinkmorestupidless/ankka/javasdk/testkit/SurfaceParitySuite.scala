package com.thinkmorestupidless.ankka.javasdk.testkit

import com.thinkmorestupidless.ankka.javasdk
import com.thinkmorestupidless.ankka.javasdk.annotations
import com.thinkmorestupidless.ankka.javasdk.client
import com.thinkmorestupidless.ankka.javasdk.component
import com.thinkmorestupidless.ankka.javasdk.effect
import com.thinkmorestupidless.ankka.javasdk.http as jhttp
import com.thinkmorestupidless.ankka.javasdk.timer.Timers

import java.nio.file.{Files, Paths}

/**
 * Every public declaration the Scala SDK reference names, held to its Java form (FR-001: a
 * capability with no Java form is a defect).
 *
 * The list is kept by hand, and cannot go stale silently: each Scala declaration must still exist,
 * found by reflection, and still be named in its section of `docs/reference/scala-sdk.md`; each
 * Java counterpart must exist; a counterpart still to be written names an open task in `tasks.md`,
 * which fails once that task is checked off; and every section of the page outside the agents' has
 * rows. The agents' sections (Agent, Judgments, Autonomous agent) are the agent SDK's, held by its
 * own tasks.
 */
class SurfaceParitySuite extends munit.FunSuite:

  // ── The Java side ─────────────────────────────────────────────────────────

  private enum Java:
    /** A Java type, and optionally a member of it: a method, a field, an enum constant, a type. */
    case Member(cls: Class[?], member: Option[String])

    /** Not written yet: the open task that writes it. */
    case Pending(task: String)

    /** The Scala form has no Java one by design, and why. */
    case ByDesign(why: String)

  private def j(cls: Class[?], member: String): Java = Java.Member(cls, Some(member))
  private def j(cls: Class[?]): Java                 = Java.Member(cls, None)
  private def byDesign(why: String): Java            = Java.ByDesign(why)

  /** One declaration the page names: its section, the Scala type and member, the Java form. */
  private final case class Row(
      section: String,
      scala: String,
      member: String,
      java: Java,
      named: Option[String] = None
  ):
    override def toString = s"$section: $scala.$member"

    /** The word the page names it by. */
    def word: String = named.getOrElse(member)

  private val S = "com.thinkmorestupidless.ankka."

  private def rows(section: String, scala: String)(members: (String, Java)*): Seq[Row] =
    members.map((m, java) => Row(section, S + scala, m, java))

  // ── The list ──────────────────────────────────────────────────────────────

  private val ES  = classOf[component.EventSourcedEntity[?, ?]]
  private val KV  = classOf[component.KeyValueEntity[?]]
  private val ESE = classOf[effect.EventSourcedEffects[?, ?]]
  private val PB  = classOf[effect.PersistBuilder[?, ?]]
  private val KVE = classOf[effect.KeyValueEffects[?]]
  private val UB  = classOf[effect.UpdateBuilder[?]]
  private val Ctx = classOf[component.EntityContext]
  private val Chg = classOf[component.ChangeContext]

  private val table: Seq[Row] =
    // Event sourced entity
    rows("Event sourced entity", "sdk.EventSourcedEntity")(
      "emptyState"     -> j(ES, "emptyState"),
      "applyEvent"     -> j(ES, "applyEvent"),
      "currentState"   -> j(ES, "currentState"),
      "commandContext" -> j(ES, "context"),
      "eventContext"   -> j(ES, "eventContext")
    ) ++ rows("Event sourced entity", "sdk.EventSourcedEntity$Companion")(
      "command"       -> j(classOf[annotations.Command]),
      "query"         -> j(classOf[annotations.Query]),
      "snapshotEvery" -> j(classOf[annotations.SnapshotEvery]),
      "replicated"    -> j(classOf[annotations.Replicated])
    ) ++ rows("Event sourced entity", "sdk.CommandContext")(
      "entityId"       -> j(Ctx, "entityId"),
      "metadata"       -> j(Ctx, "metadata"),
      "sequenceNumber" -> j(Ctx, "sequenceNumber"),
      "installation"   -> j(Ctx, "installation"),
      "isHome"         -> j(Ctx, "isHome")
    ) ++ rows("Event sourced entity", "sdk.EventContext")(
      "installation"   -> j(classOf[component.EventContext], "installation"),
      "isLocal"        -> j(classOf[component.EventContext], "isLocal"),
      "sequenceNumber" -> j(classOf[component.EventContext], "sequenceNumber")
    ) ++ rows("Event sourced entity", "core.effect.EventSourcedEffects")(
      "persist"           -> j(ESE, "persist"),
      "persistAll"        -> j(ESE, "persistAll"),
      "deleteEntity"      -> j(ESE, "deleteEntity"),
      "reply"             -> j(ESE, "reply"),
      "error"             -> j(ESE, "error"),
      "noReply"           -> j(ESE, "noReply"),
      "handOverTo"        -> j(ESE, "handOverTo"),
      "replicateTo"       -> j(ESE, "replicateTo"),
      "stopReplicatingTo" -> j(ESE, "stopReplicatingTo")
    ) ++ rows("Event sourced entity", "core.effect.PersistBuilder")(
      "deleteEntity"      -> j(PB, "thenDelete"),
      "thenReply"         -> j(PB, "thenReply"),
      "thenReplyState"    -> j(PB, "thenReplyState"),
      "thenNoReply"       -> j(PB, "thenNoReply"),
      "expireAfter"       -> j(PB, "expireAfter"),
      "handOverTo"        -> j(PB, "handOverTo"),
      "replicateTo"       -> j(PB, "replicateTo"),
      "stopReplicatingTo" -> j(PB, "stopReplicatingTo")
    ) ++ rows("Event sourced entity", "sdk.EventSourcedEntityCalls")(
      "replication"   -> j(classOf[client.EntityRef], "replication"),
      "awaitHandover" -> j(classOf[client.EntityRef], "awaitHandover")
    ) ++ rows("Event sourced entity", "sdk.ReplicationStatus")(
      "ReplicationStatus" -> j(classOf[client.ReplicationStatus])
    ) ++
      // Key value entity
      rows("Key value entity", "sdk.KeyValueEntity")(
        "emptyState"     -> j(KV, "emptyState"),
        "currentState"   -> j(KV, "currentState"),
        "commandContext" -> j(KV, "context")
      ) ++ rows("Key value entity", "core.effect.KeyValueEffects")(
        "updateState"  -> j(KVE, "updateState"),
        "deleteEntity" -> j(KVE, "deleteEntity"),
        "reply"        -> j(KVE, "reply"),
        "error"        -> j(KVE, "error"),
        "noReply"      -> j(KVE, "noReply")
      ) ++ rows("Key value entity", "core.effect.UpdateBuilder")(
        "thenReply"      -> j(UB, "thenReply"),
        "thenReplyState" -> j(UB, "thenReplyState"),
        "thenNoReply"    -> j(UB, "thenNoReply"),
        "expireAfter"    -> j(UB, "expireAfter")
      ) ++
      // View
      rows("View", "sdk.View")(
        "onChange"      -> j(classOf[component.View[?, ?]], "onChange"),
        "onDelete"      -> j(classOf[component.View[?, ?]], "onDelete"),
        "parallelism"   -> j(classOf[component.View[?, ?]], "parallelism"),
        "rowState"      -> j(classOf[component.View[?, ?]], "rowState"),
        "updateContext" -> j(classOf[component.View[?, ?]], "updateContext")
      ) ++ rows("View", "sdk.ChangeSource")(
        "eventsOf"  -> j(classOf[component.ChangeSource[?]], "of"),
        "stateOf"   -> j(classOf[component.ChangeSource[?]], "of"),
        "fromTopic" -> j(classOf[component.ChangeSource[?]], "ofTopic")
      ) ++ rows("View", "sdk.TopicOptions")(
        "contract" -> j(classOf[component.ChangeSource[?]], "withContract"),
        "broker"   -> j(classOf[component.ChangeSource[?]], "onBroker"),
        "parallel" -> j(classOf[component.ChangeSource[?]], "parallel")
      ) ++ rows("View", "sdk.ChangeContext")(
        "subject"        -> j(Chg, "subject"),
        "sequenceNumber" -> j(Chg, "sequenceNumber"),
        "localOrigin"    -> j(Chg, "localOrigin")
      ) ++ rows("View", "core.effect.ViewEffects")(
        "updateRow" -> j(classOf[effect.ViewEffects[?]], "updateRow"),
        "deleteRow" -> j(classOf[effect.ViewEffects[?]], "deleteRow"),
        "ignore"    -> j(classOf[effect.ViewEffects[?]], "ignore")
      ) ++ rows("View", "runtime.ViewClient")(
        "forView" -> j(classOf[client.ViewClient], "forView")
      ) ++ rows("View", "runtime.ViewQueries")(
        "get"   -> j(classOf[client.ViewQueries[?]], "get"),
        "all"   -> j(classOf[client.ViewQueries[?]], "all"),
        "count" -> j(classOf[client.ViewQueries[?]], "count"),
        // A Java view is queried by the statements it declares, as a process's is.
        "where"   -> j(classOf[client.ViewQueries[?]], "ask"),
        "ordered" -> j(classOf[client.ViewQueries[?]], "ask")
      ) ++ Seq(
        // The page names them together: "each with an …Async form".
        "getAsync"   -> j(classOf[client.ViewQueries[?]], "getAsync"),
        "allAsync"   -> j(classOf[client.ViewQueries[?]], "allAsync"),
        "countAsync" -> j(classOf[client.ViewQueries[?]], "countAsync"),
        "whereAsync" -> j(classOf[client.ViewQueries[?]], "askAsync")
      ).map((m, java) => Row("View", S + "runtime.ViewQueries", m, java, Some("Async"))) ++
      // Consumer
      rows("Consumer", "sdk.Consumer")(
        "onMessage"        -> j(classOf[component.Consumer[?, ?]], "onChange"),
        "onDelete"         -> j(classOf[component.Consumer[?, ?]], "onDelete"),
        "produceTo"        -> j(classOf[component.Consumer[?, ?]], "publishes"),
        "produces"         -> j(classOf[component.Consumer[?, ?]], "publishes"),
        "parallelism"      -> j(classOf[component.Consumer[?, ?]], "parallelism"),
        "outputSerializer" -> byDesign(
          "the output's serializer is the one for its type: derived, or given withSerializer"
        ),
        "messageContext"  -> j(classOf[component.Consumer[?, ?]], "messageContext"),
        "mirroredChanges" -> j(classOf[component.Consumer[?, ?]], "mirroredChanges")
      ) ++ rows("Consumer", "sdk.Publication")(
        "contract" -> j(classOf[component.Publication], "withContract"),
        "broker"   -> j(classOf[component.Publication], "onBroker")
      ) ++ rows("Consumer", "sdk.ChangeContext")(
        "subject"        -> j(Chg, "subject"),
        "sequenceNumber" -> j(Chg, "sequenceNumber"),
        "localOrigin"    -> j(Chg, "localOrigin"),
        "installation"   -> j(Chg, "installation")
      ) ++ rows("Consumer", "core.effect.ConsumerEffects")(
        "produce"    -> j(classOf[effect.ConsumerEffects[?]], "publish"),
        "produceAll" -> j(classOf[effect.ConsumerEffects[?]], "publishAll"),
        "done"       -> j(classOf[effect.ConsumerEffects[?]], "done"),
        "ignore"     -> j(classOf[effect.ConsumerEffects[?]], "ignore"),
        "message"    -> j(classOf[effect.Outgoing[?]], "of")
      ) ++ rows("Consumer", "core.effect.Outgoing")(
        "withKey"      -> j(classOf[effect.Outgoing[?]], "withKey"),
        "withMetadata" -> j(classOf[effect.Outgoing[?]], "withMetadata")
      ) ++
      // Graph consumer
      rows("Graph consumer", "sdk.graph.GraphConsumer")(
        "onMessage"      -> j(classOf[component.GraphConsumer[?]], "onChange"),
        "onDelete"       -> j(classOf[component.GraphConsumer[?]], "onDelete"),
        "parallelism"    -> j(classOf[component.GraphConsumer[?]], "parallelism"),
        "messageContext" -> j(classOf[component.GraphConsumer[?]], "messageContext"),
        "graph"          -> j(classOf[component.GraphConsumer[?]], "graph"),
        "effects"        -> j(classOf[component.GraphConsumer[?]], "effects")
      ) ++ rows("Graph consumer", "sdk.graph.GraphElements")(
        "node"          -> j(classOf[effect.GraphElements], "node"),
        "edge"          -> j(classOf[effect.GraphElements], "edge"),
        "tombstoneNode" -> j(classOf[effect.GraphElements], "tombstoneNode"),
        "tombstoneEdge" -> j(classOf[effect.GraphElements], "tombstoneEdge")
      ) ++ rows("Graph consumer", "sdk.graph.GraphEffects")(
        "publish" -> j(classOf[effect.GraphEffects], "publish"),
        "done"    -> j(classOf[effect.GraphEffects], "done"),
        "ignore"  -> j(classOf[effect.GraphEffects], "ignore")
      ) ++ rows("Graph consumer", "sdk.graph.GraphElement")(
        "at" -> j(classOf[effect.GraphElement], "at")
      ) ++ rows("Graph consumer", "core.graph.GraphElementRefused")(
        "why" -> byDesign("a refused element is an IllegalArgumentException whose message is why")
      ) ++ rows("Graph consumer", "core.graph.GraphDelta")(
        "read"        -> j(classOf[component.GraphDelta], "read"),
        "serializer"  -> j(classOf[component.GraphDelta], "serializer"),
        "nodeKey"     -> j(classOf[component.GraphDelta], "nodeKey"),
        "edgeKey"     -> j(classOf[component.GraphDelta], "edgeKey"),
        "isTombstone" -> j(classOf[component.GraphDelta], "isTombstone")
      ) ++
      // Workflow
      rows("Workflow", "sdk.Workflow")(
        "emptyState"     -> j(classOf[component.Workflow[?]], "emptyState"),
        "settings"       -> j(classOf[component.Workflow[?]], "settings"),
        "currentState"   -> j(classOf[component.Workflow[?]], "currentState"),
        "commandContext" -> j(classOf[component.Workflow[?]], "context")
      ) ++ rows("Workflow", "sdk.Workflow$Companion")(
        "step" -> j(classOf[annotations.Step])
      ) ++ rows("Workflow", "sdk.WorkflowSettings")(
        "builder" -> j(classOf[component.WorkflowSettings], "builder")
      ) ++ rows("Workflow", "sdk.WorkflowSettingsBuilder")(
        "timeout"            -> j(classOf[component.WorkflowSettings], "timeout"),
        "defaultStepTimeout" -> j(classOf[component.WorkflowSettings], "defaultStepTimeout"),
        "stepTimeout"        -> j(classOf[component.WorkflowSettings], "stepTimeout"),
        "defaultRecovery"    -> j(classOf[component.WorkflowSettings], "defaultRecovery"),
        "stepRecovery"       -> j(classOf[component.WorkflowSettings], "stepRecovery"),
        "build"              -> j(classOf[component.WorkflowSettings], "build")
      ) ++ rows("Workflow", "sdk.RecoverStrategy")(
        "fail"       -> j(classOf[component.RecoverStrategy], "fail"),
        "maxRetries" -> j(classOf[component.RecoverStrategy], "maxRetries"),
        "failoverTo" -> j(classOf[component.RecoverStrategy], "failoverTo")
      ) ++ rows("Workflow", "core.effect.WorkflowEffects")(
        "updateState"  -> j(classOf[effect.WorkflowEffects[?]], "updateState"),
        "transitionTo" -> j(classOf[effect.WorkflowEffects[?]], "transitionTo"),
        "delete"       -> j(classOf[effect.WorkflowEffects[?]], "delete"),
        "reply"        -> j(classOf[effect.WorkflowEffects[?]], "reply"),
        "error"        -> j(classOf[effect.WorkflowEffects[?]], "error")
      ) ++ rows("Workflow", "core.effect.WorkflowCommandBuilder")(
        "transitionTo"   -> j(classOf[effect.WorkflowBuilder[?]], "transitionTo"),
        "thenReply"      -> j(classOf[effect.WorkflowBuilder[?]], "thenReply"),
        "thenReplyState" -> j(classOf[effect.WorkflowBuilder[?]], "thenReplyState"),
        "thenNoReply"    -> j(classOf[effect.WorkflowBuilder[?]], "thenNoReply")
      ) ++ rows("Workflow", "core.effect.WorkflowStepEffects")(
        "updateState"      -> j(classOf[effect.StepEffects[?]], "updateState"),
        "thenTransitionTo" -> j(classOf[effect.StepEffects[?]], "thenTransitionTo"),
        "thenPause"        -> j(classOf[effect.StepEffects[?]], "thenPause"),
        "thenEnd"          -> j(classOf[effect.StepEffects[?]], "thenEnd"),
        "thenFail"         -> j(classOf[effect.StepEffects[?]], "thenFail")
      ) ++ rows("Workflow", "sdk.StepHandle")(
        // A Java step is named by a method reference: one with input, or one without.
        "withInput" -> j(classOf[client.StepRef[?, ?]])
      ) ++ rows("Workflow", "sdk.NoInputStepHandle")(
        "ref" -> j(classOf[client.NoInputStepRef[?]])
      ) ++ rows("Workflow", "sdk.WorkflowCalls")(
        "lifecycle" -> j(classOf[client.WorkflowRef], "lifecycle"),
        "awaitEnd"  -> j(classOf[client.WorkflowRef], "awaitEnd")
      ) ++ rows("Workflow", "sdk.WorkflowInvocation")(
        // Java cannot add type parameters to the inherited call, so the two are one call.
        "thenAwaitEnd" -> j(classOf[client.WorkflowRef], "callThenAwaitEnd")
      ) ++ rows("Workflow", "http.EndpointClients")(
        "awaitEnd" -> j(classOf[client.WorkflowRef], "awaitEndEvents")
      ) ++ rows("Workflow", "core.WorkflowEnd$")(
        "failure" -> j(classOf[client.CommandException], "workflowFailure")
      ) ++
      // Timed action and timers
      rows("Timed action and timers", "sdk.TimedAction")(
        "timerContext" -> j(classOf[component.TimedAction], "context")
      ) ++ rows("Timed action and timers", "sdk.TimedAction$Companion")(
        "handler" -> j(classOf[annotations.Action])
      ) ++ rows("Timed action and timers", "sdk.TimedActionContext")(
        "timerName"        -> j(classOf[component.TimerContext], "timerName"),
        "previousAttempts" -> j(classOf[component.TimerContext], "previousAttempts"),
        "dueTime"          -> j(classOf[component.TimerContext], "dueTime"),
        "componentClient"  -> j(classOf[component.TimerContext], "componentClient")
      ) ++ rows("Timed action and timers", "core.effect.TimedActionEffects")(
        "done"  -> j(classOf[effect.TimedActionEffects], "done"),
        "error" -> j(classOf[effect.TimedActionEffects], "error")
      ) ++ rows("Timed action and timers", "sdk.TimerScheduler")(
        "createSingleTimer"    -> j(classOf[Timers], "schedule"),
        "createRecurringTimer" -> j(classOf[Timers], "scheduleRecurring"),
        "delete"               -> j(classOf[Timers], "cancel"),
        "exists"               -> j(classOf[Timers], "exists")
      ) ++ rows("Timed action and timers", "testkit.TimerProbe")(
        "fired"     -> j(classOf[TimerRuns], "fired"),
        "dueTimes"  -> j(classOf[TimerRuns], "dueTimes"),
        "scheduled" -> j(classOf[TimerRuns], "scheduled"),
        "bind"      -> byDesign("the kit's timer runs are bound to the kit that made them")
      ) ++
      // HTTP endpoint
      rows("HTTP endpoint", "http.HttpEndpoint")(
        "acl"       -> j(classOf[jhttp.HttpEndpoint], "acl"),
        "get"       -> j(classOf[annotations.Get]),
        "post"      -> j(classOf[annotations.Post]),
        "put"       -> j(classOf[annotations.Put]),
        "patch"     -> j(classOf[annotations.Patch]),
        "delete"    -> j(classOf[annotations.Delete]),
        "postBody"  -> j(classOf[annotations.Body]),
        "sse"       -> j(classOf[annotations.Sse]),
        "socket"    -> j(classOf[annotations.Socket]),
        "request"   -> j(classOf[jhttp.HttpEndpoint], "request"),
        "principal" -> j(classOf[jhttp.HttpEndpoint], "principal"),
        "caller"    -> j(classOf[jhttp.HttpEndpoint], "caller")
      ) ++ rows("HTTP endpoint", "http.Socket")(
        "receive" -> j(classOf[jhttp.WebSocket], "receive"),
        "send"    -> j(classOf[jhttp.WebSocket], "send")
      ) ++ rows("HTTP endpoint", "http.SocketClosed")(
        "reason" -> j(classOf[jhttp.SocketClosed], "reason")
      ) ++ rows("HTTP endpoint", "http.CloseReason")(
        "code" -> j(classOf[jhttp.CloseReason], "code")
      ) ++ rows("HTTP endpoint", "testkit.AnkkaTestKit")(
        "socket"   -> j(classOf[AnkkaTestKit], "socket"),
        "asCaller" -> j(classOf[AnkkaTestKit], "asCaller")
      ) ++ rows("HTTP endpoint", "testkit.TestSocket")(
        "send"        -> j(classOf[TestSocket], "send"),
        "receive"     -> j(classOf[TestSocket], "receive"),
        "close"       -> j(classOf[TestSocket], "close"),
        "closed"      -> j(classOf[TestSocket], "closed"),
        "subprotocol" -> j(classOf[TestSocket], "subprotocol")
      ) ++ rows("HTTP endpoint", "http.RequestContext")(
        "header" -> j(classOf[jhttp.Request], "header"),
        "query"  -> j(classOf[jhttp.Request], "query"),
        "caller" -> j(classOf[jhttp.Request], "caller")
      ) ++ rows("HTTP endpoint", "http.QueryParams")(
        "required" -> j(classOf[jhttp.Request], "requiredQuery"),
        "optional" -> j(classOf[jhttp.Request], "query"),
        "all"      -> j(classOf[jhttp.Request], "queryAll"),
        "flag"     -> j(classOf[jhttp.Request], "flag")
      ) ++ rows("HTTP endpoint", "http.Acl$")(
        "DenyAll"      -> j(classOf[jhttp.Acl], "denyAll"),
        "AllowAll"     -> j(classOf[jhttp.Acl], "allowAll"),
        "AllowIf"      -> j(classOf[jhttp.Acl], "allowIf"),
        "Authenticate" -> j(classOf[jhttp.Acl], "authenticate"),
        "allowCallers" -> j(classOf[jhttp.Acl], "allowCallers")
      ) ++ rows("HTTP endpoint", "http.Callers")(
        "internet"     -> j(classOf[jhttp.Callers], "internet"),
        "service"      -> j(classOf[jhttp.Callers], "service"),
        "anyInProject" -> j(classOf[jhttp.Callers], "anyInProject"),
        "self"         -> j(classOf[jhttp.Callers], "self"),
        "granted"      -> j(classOf[jhttp.Callers], "granted")
      ) ++ rows("HTTP endpoint", "http.Caller")(
        "Gateway" -> j(classOf[jhttp.Caller], "Gateway"),
        "Service" -> j(classOf[jhttp.Caller], "Service"),
        "Machine" -> j(classOf[jhttp.Caller], "Machine"),
        "Local"   -> j(classOf[jhttp.Caller], "Local")
      ) ++ rows("HTTP endpoint", "sdk.ServiceClient")(
        "get"     -> j(classOf[client.ServiceClient], "get"),
        "getText" -> j(classOf[client.ServiceClient], "getText"),
        "post"    -> j(classOf[client.ServiceClient], "post"),
        "put"     -> j(classOf[client.ServiceClient], "put"),
        "delete"  -> j(classOf[client.ServiceClient], "delete"),
        "request" -> j(classOf[client.ServiceClient], "request")
      ) ++ rows("HTTP endpoint", "http.EndpointClients")(
        "services" -> j(classOf[jhttp.HttpEndpoint], "services")
      ) ++ rows("HTTP endpoint", "sdk.ServiceUnresolvable")(
        "service" -> j(classOf[client.ServiceUnreachable], "service")
      ) ++ rows("HTTP endpoint", "sdk.ServiceIdentityMismatch")(
        "service" -> j(classOf[client.ServiceUnreachable], "service")
      ) ++ rows("HTTP endpoint", "sdk.ServiceUnanswered")(
        "service" -> j(classOf[client.ServiceUnreachable], "service")
      ) ++ rows("HTTP endpoint", "sdk.ServiceCallFailed")(
        "status" -> j(classOf[client.ServiceCallFailed], "status")
      ) ++ rows("HTTP endpoint", "http.HttpProblem$")(
        "badRequest"   -> j(classOf[jhttp.Problem], "badRequest"),
        "unauthorized" -> j(classOf[jhttp.Problem], "unauthorized"),
        "forbidden"    -> j(classOf[jhttp.Problem], "forbidden"),
        "notFound"     -> j(classOf[jhttp.Problem], "notFound"),
        "conflict"     -> j(classOf[jhttp.Problem], "conflict")
      ) ++ rows("HTTP endpoint", "http.HttpServer$")(
        "of" -> j(classOf[javasdk.ServiceBuilder], "withEndpoint"),
        "at" -> j(classOf[javasdk.ServiceBuilder], "withHttp")
      ) ++
      // Calling components
      rows("Calling components", "sdk.ComponentClient")(
        "forEventSourcedEntity" -> j(classOf[client.ComponentClient], "forEventSourcedEntity"),
        "forKeyValueEntity"     -> j(classOf[client.ComponentClient], "forKeyValueEntity"),
        "forWorkflow"           -> j(classOf[client.ComponentClient], "forWorkflow")
      ) ++ rows("Calling components", "agent.AgentRuntime$package$")(
        "forAgent" -> j(
          classOf[com.thinkmorestupidless.ankka.agent.javasdk.AgentClients],
          "forAgent"
        )
      ) ++ rows("Calling components", "sdk.Invocation")(
        "invoke"       -> j(classOf[client.Invocation[?, ?]], "invoke"),
        "invokeAsync"  -> j(classOf[client.Invocation[?, ?]], "invokeAsync"),
        "withMetadata" -> j(classOf[client.Invocation[?, ?]], "withMetadata")
      ) ++ rows("Calling components", "core.CommandError")(
        "CommandError" -> j(classOf[client.CommandException])
      ) ++
      // Running a service
      rows("Running a service", "runtime.Ankka$")(
        "service" -> j(classOf[javasdk.Ankka], "service")
      ) ++ rows("Running a service", "runtime.ServiceBuilder")(
        "register"      -> j(classOf[javasdk.ServiceBuilder], "register"),
        "start"         -> j(classOf[javasdk.ServiceBuilder], "start"),
        "withExtension" -> byDesign(
          "a Java service names no extension: the platform's are added for what it registers"
        )
      ) ++ rows("Running a service", "runtime.ProjectionRuntime$")(
        "withPublisher" -> byDesign(
          "the broker is the environment's, as a deployed service is told"
        ),
        "withBroker" -> byDesign("the test kit holds a broker in memory, read by published"),
        "withKafka"  -> byDesign("ANKKA_KAFKA_BOOTSTRAP_SERVERS names the broker")
      ) ++ rows("Running a service", "runtime.AnkkaService")(
        "whenTerminated" -> j(classOf[javasdk.AnkkaService], "whenTerminated"),
        "terminate"      -> j(classOf[javasdk.AnkkaService], "terminate")
      ) ++
      // Testing
      rows("Testing", "testkit.EventSourcedTestKit$")(
        "of" -> j(classOf[EventSourcedTestKit[?, ?]], "of")
      ) ++ rows("Testing", "testkit.EventSourcedTestKit")(
        "call"   -> j(classOf[EventSourcedTestKit[?, ?]], "call"),
        "replay" -> j(classOf[EventSourcedTestKit[?, ?]], "replay")
      ) ++ rows("Testing", "testkit.EventSourcedTestKit$Mode")(
        "Home"     -> j(classOf[EventSourcedTestKit[?, ?]], "home"),
        "Follower" -> j(classOf[EventSourcedTestKit[?, ?]], "follower")
      ) ++ rows("Testing", "testkit.KeyValueEntityTestKit$")(
        "of" -> j(classOf[KeyValueEntityTestKit[?]], "of")
      ) ++ rows("Testing", "testkit.WorkflowTestKit$")(
        "of" -> j(classOf[WorkflowTestKit[?]], "of")
      ) ++ rows("Testing", "testkit.WorkflowTestKit")(
        "call"    -> j(classOf[WorkflowTestKit[?]], "call"),
        "timeOut" -> j(classOf[WorkflowTestKit[?]], "timeOut")
      ) ++ rows("Testing", "testkit.StepResult")(
        "steps"        -> j(classOf[StepResult[?, ?]], "steps"),
        "state"        -> j(classOf[StepResult[?, ?]], "state"),
        "lifecycle"    -> j(classOf[StepResult[?, ?]], "lifecycle"),
        "pauseTimeout" -> j(classOf[StepResult[?, ?]], "pausedFor")
      ) ++ Seq(
        Row(
          "Testing",
          S + "testkit.ViewTestKit$",
          "apply",
          j(classOf[ViewTestKit[?, ?]], "of"),
          Some("ViewTestKit")
        ),
        Row(
          "Testing",
          S + "testkit.KeyedViewTestKit$",
          "apply",
          j(classOf[KeyedViewTestKit[?]], "of"),
          Some("KeyedViewTestKit")
        )
      ) ++ rows("Testing", "testkit.ViewTestKit")(
        "change"  -> j(classOf[ViewTestKit[?, ?]], "change"),
        "deleted" -> j(classOf[ViewTestKit[?, ?]], "deleted"),
        "rows"    -> j(classOf[ViewTestKit[?, ?]], "rows"),
        "row"     -> j(classOf[ViewTestKit[?, ?]], "row")
      ) ++ rows("Testing", "testkit.KeyedViewTestKit")(
        "change"    -> j(classOf[KeyedViewTestKit[?]], "change"),
        "deleted"   -> j(classOf[KeyedViewTestKit[?]], "deleted"),
        "answering" -> j(classOf[KeyedViewTestKit[?]], "answering")
      ) ++ rows("Testing", "testkit.ConsumerTestKit$")(
        "of"    -> j(classOf[ConsumerTestKit[?, ?]], "of"),
        "graph" -> j(classOf[ConsumerTestKit[?, ?]], "graph")
      ) ++ rows("Testing", "testkit.TestTransport")(
        "client" -> j(classOf[TestClient], "client")
      ) ++ rows("Testing", "testkit.AnkkaTestKit$")(
        "start"         -> j(classOf[AnkkaTestKit], "start"),
        "installations" -> j(classOf[AnkkaTestKit], "installations")
      ) ++ rows("Testing", "testkit.AnkkaTestKit")(
        "restartService" -> j(classOf[AnkkaTestKit], "restartService")
      ) ++ rows("Testing", "testkit.AnkkaTestKit$Installations")(
        "start"     -> j(classOf[Installations], "start"),
        "transport" -> j(classOf[Installations], "pause")
      ) ++ rows("Testing", "agent.TestModelProvider")(
        "TestModelProvider" -> j(classOf[TestModelProvider])
      )

  // ── The checks ────────────────────────────────────────────────────────────

  private val page: String =
    val root = Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(p => Files.exists(p.resolve("docs/reference/scala-sdk.md")))
      .getOrElse(fail("no docs/reference/scala-sdk.md above the working directory"))
    Files.readString(root.resolve("docs/reference/scala-sdk.md"))

  private val tasks: String =
    val root = Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(p => Files.exists(p.resolve("specs/058-java-kotlin-sdk/tasks.md")))
      .getOrElse(fail("no specs/058-java-kotlin-sdk/tasks.md above the working directory"))
    Files.readString(root.resolve("specs/058-java-kotlin-sdk/tasks.md"))

  /** The text of each `## ` section of the page, by its heading. */
  private val sections: Map[String, String] =
    page
      .split("\n## ")
      .drop(1)
      .map { s =>
        val (heading, body) = s.span(_ != '\n')
        heading.trim -> body
      }
      .toMap

  private val AgentSections = Set("Agent", "Judgments", "Autonomous agent")

  /**
   * The agent module, which this kit's tests cannot see: its rows wait on the agent SDK's tasks.
   */
  private def inAgentModule(r: Row) = r.scala.startsWith(S + "agent.")
  private val NotSurface            = Set("Artifacts", "The shape every component shares")

  /** The class, its companion module, and every type nested in either, to any depth. */
  private def family(cls: Class[?]): Seq[Class[?]] =
    def nested(c: Class[?]): Seq[Class[?]] = c +: c.getDeclaredClasses.toSeq.flatMap(nested)
    val module = scala.util.Try(Class.forName(cls.getName + "$")).toOption
    (Seq(cls) ++ module).flatMap(nested) ++ ancestors(cls)

  private def ancestors(cls: Class[?]): Seq[Class[?]] =
    Iterator
      .iterate[Class[?] | Null](cls.getSuperclass)(c => if c == null then null else c.getSuperclass)
      .takeWhile(_ != null)
      .map(_.nn)
      .toSeq ++ cls.getInterfaces.toSeq

  private def declares(cls: Class[?], member: String): Boolean =
    family(cls).exists { c =>
      c.getDeclaredMethods.exists(_.getName == member) ||
      c.getDeclaredFields.exists(_.getName == member) ||
      c.getSimpleName == member ||
      c.getName.endsWith("$" + member) ||
      c.getName.endsWith("$" + member + "$")
    }

  private def scalaClass(name: String): Option[Class[?]] =
    scala.util
      .Try(Class.forName(name))
      .orElse(scala.util.Try(Class.forName(name.stripSuffix("$") + "$")))
      .toOption

  test("every Scala declaration the list names still exists") {
    val missing = table.filterNot(r =>
      scalaClass(r.scala).exists(declares(_, r.member)) ||
        (inAgentModule(r) && r.java.isInstanceOf[Java.Pending])
    )
    assert(missing.isEmpty, missing.mkString("no such Scala declaration:\n  ", "\n  ", ""))
  }

  test("every Scala declaration the list names is still on the reference page, in its section") {
    val gone = table.filterNot(r => sections.get(r.section).exists(_.contains(r.word)))
    assert(gone.isEmpty, gone.mkString("not named in its section:\n  ", "\n  ", ""))
  }

  test("every section of the page outside the agents' is in the list") {
    val listed   = table.map(_.section).toSet
    val unlisted = sections.keySet -- AgentSections -- NotSurface -- listed
    assert(unlisted.isEmpty, unlisted.mkString("sections with no rows: ", ", ", ""))
    assertEquals(listed -- sections.keySet, Set.empty[String], "rows naming no section")
  }

  test("every declaration has its Java form") {
    val missing = table.collect {
      case r @ Row(_, _, _, Java.Member(cls, Some(m)), _) if !declares(cls, m) =>
        s"$r: ${cls.getName} has no '$m'"
    }
    assert(missing.isEmpty, missing.mkString("\n  ", "\n  ", ""))
  }

  test("a Java form still to be written names a task that is still open") {
    val closed = table.collect {
      case r @ Row(_, _, _, Java.Pending(task), _) if !tasks.contains(s"- [ ] $task ") =>
        s"$r: $task is not an open task; write its Java form and name it here"
    }
    assert(closed.isEmpty, closed.mkString("\n  ", "\n  ", ""))
  }

  test("the Java close reasons are the Scala ones, code for code and word for word") {
    val scala = com.thinkmorestupidless.ankka.http.CloseReason.values.toVector
      .map(r => (r.toString.toUpperCase, r.code, r.word))
    val java = jhttp.CloseReason.values.toVector.map(r => (r.name.replace("_", ""), r.code, r.word))
    assertEquals(java, scala.map((n, c, w) => (n.replace("_", ""), c, w)))
  }
