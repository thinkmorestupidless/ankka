package com.thinkmorestupidless.ankka.sidecar

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import ankka.protocol.v1.discovery.*
import com.google.protobuf.ByteString
import com.thinkmorestupidless.ankka.core.{
  Contract as CoreContract,
  ComponentKind,
  ComponentId,
  EntityId,
  ErrorCode,
  Metadata,
  MethodName
}
import com.thinkmorestupidless.ankka.runtime.Trace
import com.thinkmorestupidless.ankka.runtime.remote.*
import com.thinkmorestupidless.ankka.sdk.{Publication as SdkPublication, StartFrom, TopicOptions}
import com.typesafe.config.ConfigFactory
import io.grpc.ManagedChannelBuilder
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.stream.scaladsl.Sink

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}

/**
 * Every conversation in contracts/protocol.md, both ends in one JVM: the sidecar's
 * `GrpcConversation` and `Discovery` on one side, `ProcessDouble` on the other. No actor system
 * beyond a scheduler, no database, no cluster — the protocol alone.
 */
class ProtocolSuite extends munit.FunSuite with LogCapturing:

  given ExecutionContext = ExecutionContext.global

  private val kit = ActorTestKit(
    ConfigFactory
      .parseString("pekko.actor.provider = local")
      .withFallback(ConfigFactory.load())
  )
  given org.apache.pekko.stream.Materializer =
    org.apache.pekko.stream.Materializer.matFromSystem(using kit.system)

  private def settings(commandTimeout: FiniteDuration = 2.seconds): Settings =
    Settings("127.0.0.1:0", 0, "127.0.0.1", 5.seconds, 1.second, commandTimeout, 2.seconds)

  private def withDouble[A](
      spec: ProcessDouble.DoubleSpec,
      commandTimeout: FiniteDuration = 2.seconds
  )(
      body: (ProcessDouble, GrpcConversation, io.grpc.ManagedChannel) => A
  ): A =
    val double  = new ProcessDouble(spec)
    val port    = double.start()
    val channel = ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build()
    try body(double, GrpcConversation(channel, settings(commandTimeout)), channel)
    finally
      channel.shutdownNow()
      double.stop()

  override def afterAll(): Unit = kit.shutdownTestKit()

  private val recorder = ProcessDouble.recorder("conformance", snapshotEvery = 3)
  private val spec = ProcessDouble.DoubleSpec(
    entities = Vector(recorder),
    endpoints = Vector(
      ProcessDouble.Endpoint(
        "carts",
        "/carts",
        Vector(
          ProcessDouble.Route("get", "GET", "/{id}"),
          ProcessDouble.Route("awkward", "GET", "/awkward"),
          ProcessDouble.Route("add", "POST", "/{id}/items", hasBody = true),
          ProcessDouble.Route(
            "events",
            "GET",
            "/{id}/events",
            streaming = true,
            frames = _ => Vector(" leading space", "two\nlines")
          )
        )
      )
    )
  )

  private def init(id: String, snapshot: Option[Snapshot] = None) =
    Init(ComponentKind.EventSourcedEntity, ComponentId("conformance"), EntityId(id), snapshot)

  private def command(
      id: Long,
      name: String,
      input: String = "",
      snapshotRequested: Boolean = false
  ) =
    Command(
      id,
      MethodName(name),
      Payload(Payload.Text, "string", input.getBytes),
      Metadata.empty,
      snapshotRequested
    )

  private def await[A](f: Future[A]): A = Await.result(f, 5.seconds)

  // ── Discovery ───────────────────────────────────────────────────────────────

  test("discovery: the spec round-trips into remote descriptors and endpoints") {
    withDouble(spec) { (_, _, channel) =>
      val discovered = Discovery.discover(channel, settings(), "test").toOption.get
      val entity = discovered.descriptors.collectFirst { case d: RemoteEventSourcedDescriptor =>
        d
      }.get
      assertEquals(entity.componentId, ComponentId("conformance"))
      assertEquals(entity.snapshotEvery, Some(3))
      assert(entity.handler(MethodName("count")).exists(_.readOnly))
      assert(entity.handler(MethodName("record")).exists(!_.readOnly))
      assertEquals(discovered.endpoints.map(_.id), Vector("carts"))
    }
  }

  test("discovery: the wrong major is refused with both versions, and reported to the process") {
    withDouble(spec.copy(protocolVersion = "99.0")) { (double, _, channel) =>
      val refused = Discovery.discover(channel, settings(), "test")
      assert(refused.isLeft)
      val message = refused.left.toOption.get.mkString("\n")
      assert(message.contains("99.0") && message.contains(Discovery.ProtocolVersion), message)
      assertEquals(double.problems.size, 1)
    }
  }

  test("discovery: an SDK on an earlier minor is admitted, since a minor only adds") {
    // The double declares 1.0; the sidecar speaks 1.12 (1.1 added the caller, 1.2 the autonomous
    // agent, 1.3 a consumer's several messages, 1.4 metadata on the requests a handler's work is
    // sent in, 1.5 a principal's claims, 1.6 the secret store, 1.7 where a topic source starts,
    // 1.8 a call to another service, 1.9 socket routes, 1.10 three imports for a module, 1.11
    // approvals and MCP servers, 1.12 recurring timers).
    // Earlier minors are admitted.
    assertEquals(spec.protocolVersion, "1.0")
    withDouble(spec)((double, _, _) =>
      assert(
        Discovery.validate(double.toSpec, Discovery.ProtocolVersion, authConfigured = true).isRight
      )
    )
    withDouble(spec.copy(protocolVersion = "1.1"))((double, _, _) =>
      assert(
        Discovery.validate(double.toSpec, Discovery.ProtocolVersion, authConfigured = true).isRight
      )
    )
    withDouble(spec.copy(protocolVersion = "1.2"))((double, _, _) =>
      assert(
        Discovery.validate(double.toSpec, Discovery.ProtocolVersion, authConfigured = true).isRight
      )
    )
    withDouble(spec.copy(protocolVersion = "1.11"))((double, _, _) =>
      assert(
        Discovery.validate(double.toSpec, Discovery.ProtocolVersion, authConfigured = true).isRight
      )
    )
    assertEquals(Discovery.ProtocolVersion, "1.14")
  }

  test(
    "discovery: every problem at once — duplicate route, bad method, duplicate handler, unknown source"
  ) {
    val bad = Spec(
      "1.0",
      None,
      Vector(
        Component(
          Kind.EVENT_SOURCED_ENTITY,
          "e",
          Vector(Handler("a", false, false), Handler("a", false, false)),
          Component.Detail.EventSourced(EventSourcedDetail(0))
        ),
        Component(
          Kind.VIEW,
          "v",
          Vector.empty,
          Component.Detail.View(
            ViewDetail(
              Some(
                Source(
                  Source.Source.Component(Source.ComponentRef(Kind.EVENT_SOURCED_ENTITY, "nope"))
                )
              ),
              "row",
              Vector("get")
            )
          )
        ),
        Component(
          Kind.EVENT_SOURCED_ENTITY,
          "s",
          Vector(Handler("x", true, true)),
          Component.Detail.EventSourced(EventSourcedDetail(0))
        )
      ),
      Vector(
        Endpoint(
          "ep",
          "/x",
          Endpoint.Acl.ALLOW_ALL,
          Vector(
            Route("r1", "GET", "/a", false, false),
            Route("r2", "GET", "/a", false, false),
            Route("r3", "FETCH", "/b", false, false)
          )
        )
      )
    )
    val problems =
      Discovery.validate(bad, Discovery.ProtocolVersion, authConfigured = true).left.toOption.get
    assert(problems.exists(_.contains("handler 'a' declared 2 times")), problems)
    assert(problems.exists(_.contains("'nope', which is not declared")), problems)
    assert(problems.exists(_.contains("both read-only and streaming")), problems)
    assert(problems.exists(_.contains("GET /a 2 times")), problems)
    assert(problems.exists(_.contains("unsupported method 'FETCH'")), problems)
    assertEquals(problems.size, 5)
  }

  // ── Approvals and MCP servers (protocol 1.11) ────────────────────────────────

  test("an agent's MCP servers, approvals and result guardrails are checked in discovery") {
    import ankka.protocol.v1.discovery.{AgentDetail, Approval, McpServer as PbMcpServer, Tool}
    val agent = Component(
      Kind.AGENT,
      "helper",
      Vector(Handler("ask", false, false)),
      Component.Detail.Agent(
        AgentDetail(
          tools = Vector(
            Tool("mcp__tickets__create", "squats an MCP tool's name", "{}"),
            Tool("refund", "waits a negative time", "{}", Some(Approval(Some(-1L))))
          ),
          mcpServers = Vector(
            PbMcpServer(name = "Tickets"),
            PbMcpServer(name = "search").withHeaders(
              Vector(PbMcpServer.Header("Authorization", "SEARCH_TOKEN"))
            ),
            PbMcpServer(name = "docs"),
            PbMcpServer(name = "docs")
          ),
          resultGuardrails = Vector("no-injection", "no-injection")
        )
      )
    )
    val problems = Discovery
      .validate(Spec("1.11", None, Vector(agent), Vector.empty), "1.11", authConfigured = true)
      .left
      .toOption
      .getOrElse(fail("discovery accepted what the agent's definition would refuse"))
    Vector(
      "[a-z0-9-]",                      // a server's name
      "ANKKA_MCP_",                     // a header's variable
      "MCP server 'docs' is listed 2",  // one name twice
      "mcp__tickets__create",           // an own tool named as an MCP tool
      "must be positive",               // a time limit
      "result guardrail 'no-injection'" // declared twice
    ).foreach(word => assert(problems.exists(_.contains(word)), s"$word: $problems"))
  }

  test("a tool's approval and an agent's servers reach the descriptor the sidecar hosts") {
    import ankka.protocol.v1.discovery.{AgentDetail, Approval, McpServer as PbMcpServer, Tool}
    val agent = Component(
      Kind.AGENT,
      "helper",
      Vector(Handler("ask", false, false)),
      Component.Detail.Agent(
        AgentDetail(
          tools = Vector(Tool("refund", "refunds", "{}", Some(Approval(Some(60000L))))),
          mcpServers = Vector(PbMcpServer(name = "tickets", approval = Some(Approval()))),
          resultGuardrails = Vector("no-injection")
        )
      )
    )
    val spec = RemoteAgent.spec(agent).toOption.get
    assertEquals(
      spec.tools("refund").approval,
      Some(com.thinkmorestupidless.ankka.agent.Approval(Some(1.minute)))
    )
    assertEquals(spec.mcpServers.map(_.name), Vector("tickets"))
    assert(spec.mcpServers.head.approval.isDefined)
    assertEquals(spec.resultGuardrails, Vector("no-injection"))
  }

  // ── Topic sources (protocol 1.7) ────────────────────────────────────────────

  private def overTopic(start: Option[ankka.protocol.v1.discovery.StartFrom]) =
    Some(Source(Source.Source.Topic("orders"), start))

  private def named(n: ankka.protocol.v1.discovery.StartFrom.Named) =
    Some(
      ankka.protocol.v1.discovery.StartFrom(ankka.protocol.v1.discovery.StartFrom.Position.Named(n))
    )

  private def topicSpec(version: String, components: Component*) =
    Spec(version, None, components.toVector, Vector.empty)

  private def validateSpec(spec: Spec) =
    Discovery.validate(spec, Discovery.ProtocolVersion, authConfigured = true)

  private def consumer(id: String, source: Option[Source]) =
    Component(Kind.CONSUMER, id, Vector.empty, Component.Detail.Consumer(ConsumerDetail(source)))

  private def view(id: String, source: Option[Source]) =
    Component(Kind.VIEW, id, Vector.empty, Component.Detail.View(ViewDetail(source, "row")))

  test("discovery: a declared start position reaches the remote topic source") {
    import ankka.protocol.v1.discovery.StartFrom as P
    val at = java.time.Instant.parse("2026-10-01T12:00:00Z")
    val discovered = validateSpec(
      topicSpec(
        "1.7",
        consumer("early", overTopic(named(P.Named.EARLIEST))),
        consumer("late", overTopic(named(P.Named.LATEST))),
        consumer("at", overTopic(Some(P(P.Position.AtMillis(at.toEpochMilli))))),
        view("summary", overTopic(None))
      )
    ).toOption.get
    val sources = discovered.descriptors.collect {
      case c: RemoteConsumerDescriptor => c.componentId.toString -> c.source
      case v: RemoteViewDescriptor     => v.componentId.toString -> v.source
    }.toMap
    assertEquals(sources("early"), RemoteSource.Topic("orders", Some(StartFrom.Earliest)))
    assertEquals(sources("late"), RemoteSource.Topic("orders", Some(StartFrom.Latest)))
    assertEquals(sources("at"), RemoteSource.Topic("orders", Some(StartFrom.At(at))))
    assertEquals(sources("summary"), RemoteSource.Topic("orders", None))
  }

  test("discovery 1.14: a topic source's contract, broker and parallel reach the remote source") {
    import ankka.protocol.v1.discovery.StartFrom as P
    val stated = Some(Contract("order.v1", "sha256:ab"))
    val src = Source(
      Source.Source.Topic("orders"),
      named(P.Named.EARLIEST),
      contract = stated,
      broker = Some("legacy"),
      parallel = Some(true)
    )
    val detail = ConsumerDetail(
      Some(src),
      producesTo = Some("enriched"),
      produces =
        Some(Publication("enriched", Some(Contract("enriched.v1", "sha256:cd")), Some("legacy")))
    )
    val discovered = validateSpec(
      topicSpec(
        "1.14",
        Component(Kind.CONSUMER, "relay", Vector.empty, Component.Detail.Consumer(detail))
      )
    ).toOption.get
    val relay = discovered.descriptors.collectFirst { case c: RemoteConsumerDescriptor => c }.get
    assertEquals(
      relay.source,
      RemoteSource.Topic(
        "orders",
        Some(StartFrom.Earliest),
        TopicOptions(Some(CoreContract("order.v1", "sha256:ab")), Some("legacy"), parallel = true)
      )
    )
    assertEquals(
      relay.publication,
      Some(
        SdkPublication("enriched", Some(CoreContract("enriched.v1", "sha256:cd")), Some("legacy"))
      )
    )
  }

  test("discovery 1.14: a Spec from an earlier minor states nothing new and is accepted") {
    import ankka.protocol.v1.discovery.StartFrom as P
    val discovered = validateSpec(
      topicSpec("1.13", consumer("early", overTopic(named(P.Named.EARLIEST))))
    ).toOption.get
    val early = discovered.descriptors.collectFirst { case c: RemoteConsumerDescriptor => c }.get
    assertEquals(
      early.source,
      RemoteSource.Topic("orders", Some(StartFrom.Earliest), TopicOptions())
    )
    assertEquals(early.publication, None)
  }

  test("discovery 1.14: produces and produces_to naming different topics are refused") {
    import ankka.protocol.v1.discovery.StartFrom as P
    val detail = ConsumerDetail(
      overTopic(named(P.Named.EARLIEST)),
      producesTo = Some("one"),
      produces = Some(Publication("another"))
    )
    val problems = validateSpec(
      topicSpec(
        "1.14",
        Component(Kind.CONSUMER, "relay", Vector.empty, Component.Detail.Consumer(detail))
      )
    ).left.toOption.get
    assert(
      problems.exists(_.contains("names 'one' in produces_to and 'another' in produces")),
      problems
    )
  }

  test("discovery 1.14: a contract, a broker or parallel on a component source is refused") {
    val src = Source(
      Source.Source.Component(Source.ComponentRef(Kind.EVENT_SOURCED_ENTITY, "cart")),
      None,
      parallel = Some(true)
    )
    val problems =
      validateSpec(topicSpec("1.14", consumer("follower", Some(src)))).left.toOption.get
    assert(problems.exists(_.contains("which apply to a topic")), problems)
  }

  test("discovery: what a process may not declare about a topic source is refused, all at once") {
    import ankka.protocol.v1.discovery.StartFrom as P
    val problems = validateSpec(
      topicSpec(
        "1.7",
        Component(
          Kind.EVENT_SOURCED_ENTITY,
          "order",
          Vector.empty,
          Component.Detail.EventSourced(EventSourcedDetail(0))
        ),
        consumer("notifier", overTopic(None)),
        consumer("nameless", overTopic(Some(P(P.Position.Empty)))),
        consumer(
          "over-entity",
          Some(
            Source(
              Source.Source.Component(Source.ComponentRef(Kind.EVENT_SOURCED_ENTITY, "order")),
              named(P.Named.LATEST)
            )
          )
        ),
        view("unnamed", overTopic(named(P.Named.NAMED_UNSPECIFIED)))
      )
    ).left.toOption.get
    assertEquals(
      problems.toSet,
      Set(
        "consumer 'notifier' reads topic 'orders' and declares no start position; declare " +
          "earliest, latest or a time",
        "consumer 'nameless' declares a start position that names none",
        "consumer 'over-entity' declares a start position, which applies to a topic; it reads " +
          "event sourced entity 'order'",
        "view 'unnamed' declares a start position that names none"
      )
    )
  }

  test("discovery: a keyed view's sources build a keyed view, and D1–D3 are refused") {
    def keyed(id: String, plain: Option[Source], sources: Source*) = Component(
      Kind.VIEW,
      id,
      Vector.empty,
      Component.Detail.View(ViewDetail(plain, "row", Vector.empty, None, sources.toVector))
    )
    def entity(id: String) =
      Component(
        Kind.EVENT_SOURCED_ENTITY,
        id,
        Vector.empty,
        Component.Detail.EventSourced(EventSourcedDetail(0))
      )
    def of(id: String) =
      Source(Source.Source.Component(Source.ComponentRef(Kind.EVENT_SOURCED_ENTITY, id)))
    val built = validateSpec(
      topicSpec(
        "1.8",
        entity("shipment"),
        entity("customer"),
        keyed("joined", None, of("shipment"), of("customer"))
      )
    ).fold(p => fail(p.mkString("; ")), _.descriptors)
    assertEquals(
      built.collect { case v: RemoteKeyedViewDescriptor => v.sources.size },
      Vector(2)
    )
    // D1: a source and sources.
    val both = validateSpec(
      topicSpec("1.8", entity("shipment"), keyed("joined", Some(of("shipment")), of("shipment")))
    ).left.toOption.get
    assert(both.exists(_.contains("declares a source and sources")), both.toString)
    // D3: a topic among a keyed view's sources.
    val topical = validateSpec(
      topicSpec(
        "1.8",
        entity("shipment"),
        keyed("joined", None, of("shipment"), Source(Source.Source.Topic("orders")))
      )
    ).left.toOption.get
    assert(
      topical.exists(_.contains("a topic and an entity may not be sources of one view")),
      topical.toString
    )
  }

  test(
    "discovery: a view's declared queries reach its descriptor, and a refused one is a problem"
  ) {
    val table = "ankka_view_summary"
    def summary(declared: (String, String)*) = Component(
      Kind.VIEW,
      "summary",
      Vector.empty,
      Component.Detail.View(
        ViewDetail(
          overTopic(None),
          "row",
          Vector.empty,
          None,
          Vector.empty,
          declared.map((name, statement) => DeclaredQuery(name, statement)).toVector
        )
      )
    )
    val accepted = validateSpec(
      topicSpec("1.8", summary("by-kind" -> s"SELECT payload FROM $table WHERE payload = :kind"))
    ).fold(p => fail(p.mkString("; ")), _.descriptors)
    assertEquals(
      accepted.collect { case v: RemoteViewDescriptor => v.declaredQueries.map(_.name) },
      Vector(Vector("by-kind"))
    )

    val refused = validateSpec(
      topicSpec("1.8", summary("broken" -> "SELECT payload FROM ankka_view_accounts"))
    ).left.toOption.getOrElse(fail("a view reading another table was accepted"))
    assert(
      refused.exists(p =>
        p.contains("'summary'") && p.contains("'broken'") && p.contains("ankka_view_accounts")
      ),
      refused.mkString("; ")
    )
  }

  test("discovery: a declared version reaches the remote view and consumer, and is checked") {
    import ankka.protocol.v1.discovery.StartFrom as P
    val latest = Some(P(P.Position.Named(P.Named.LATEST)))
    val versioned = validateSpec(
      topicSpec(
        "1.7",
        Component(
          Kind.VIEW,
          "summary",
          Vector.empty,
          Component.Detail.View(ViewDetail(overTopic(None), "row", Vector.empty, Some(2)))
        ),
        Component(
          Kind.CONSUMER,
          "notifier",
          Vector.empty,
          Component.Detail.Consumer(ConsumerDetail(overTopic(latest), None, Some(3)))
        )
      )
    ).toOption.get.descriptors
    assertEquals(
      versioned.collect { case v: RemoteViewDescriptor => v.version },
      Vector(Some(2))
    )
    assertEquals(
      versioned.collect { case c: RemoteConsumerDescriptor => c.version },
      Vector(Some(3))
    )

    val refused = validateSpec(
      topicSpec(
        "1.7",
        Component(
          Kind.EVENT_SOURCED_ENTITY,
          "order",
          Vector.empty,
          Component.Detail.EventSourced(EventSourcedDetail(0))
        ),
        Component(
          Kind.VIEW,
          "zero",
          Vector.empty,
          Component.Detail.View(ViewDetail(overTopic(None), "row", Vector.empty, Some(0)))
        ),
        Component(
          Kind.VIEW,
          "over-entity",
          Vector.empty,
          Component.Detail.View(
            ViewDetail(
              Some(
                Source(
                  Source.Source.Component(Source.ComponentRef(Kind.EVENT_SOURCED_ENTITY, "order"))
                )
              ),
              "row",
              Vector.empty,
              Some(2)
            )
          )
        )
      )
    ).left.toOption.get
    // A view that reads an entity may declare a version (its rebuild reads the journal again);
    // only one below 1 is refused.
    assertEquals(
      refused.toSet,
      Set("view 'zero' declares version 0; a version is a whole number of 1 or more")
    )
  }

  test("discovery: a consumer from an SDK that cannot declare a start position is admitted") {
    // An SDK speaking 1.3 had no way to say one. Refusing it would stop a running service from
    // restarting after the platform was upgraded; it starts at the earliest message, as it did.
    val discovered = validateSpec(topicSpec("1.6", consumer("notifier", overTopic(None))))
    val notifier = discovered.toOption.get.descriptors.collectFirst {
      case c: RemoteConsumerDescriptor => c
    }.get
    assertEquals(notifier.startDeclarable, false)
    assert(Discovery.declaresStartPositions("1.7"))
    assert(!Discovery.declaresStartPositions("1.6"))
    assert(Discovery.declaresStartPositions("2.0"))
  }

  // ── The event sourced conversation ──────────────────────────────────────────

  test("a command persists, replies, and the reply carries the events and manifests") {
    withDouble(spec) { (_, conversation, _) =>
      val session = conversation.open(init("a"))
      val reply   = await(session.command(command(1, "record", "hello"))).toOption.get
      assertEquals(reply.commandId, 1L)
      assertEquals(reply.events.size, 1)
      assertEquals(reply.events.head.manifest, "double-event")
      assert(String(reply.events.head.data).contains("hello"))
      reply.outcome match
        case RemoteOutcome.Reply(payload, _) => assertEquals(String(payload.data), "done")
        case other                           => fail(s"expected a reply, got $other")
      session.close()
    }
  }

  test("a replayed event is folded before the first command") {
    withDouble(spec) { (_, conversation, _) =>
      val session = conversation.open(init("b"))
      session.event(1, Payload(Payload.Json, "double-event", """{"type":"Recorded"}""".getBytes))
      session.event(2, Payload(Payload.Json, "double-event", """{"type":"Recorded"}""".getBytes))
      val reply = await(session.command(command(1, "count"))).toOption.get
      reply.outcome match
        case RemoteOutcome.Reply(payload, _) => assertEquals(String(payload.data), "2")
        case other                           => fail(s"expected a reply, got $other")
      session.close()
    }
  }

  test("a snapshot on Init seeds the state, and a snapshot is sent only when requested") {
    withDouble(spec) { (_, conversation, _) =>
      val snapshot = Snapshot(
        5,
        Payload(
          Payload.Json,
          "double-state",
          """[{"type":"Recorded"},{"type":"Recorded"},{"type":"Recorded"}]""".getBytes
        )
      )
      val session = conversation.open(init("c", Some(snapshot)))
      val counted = await(session.command(command(1, "count"))).toOption.get
      counted.outcome match
        case RemoteOutcome.Reply(payload, _) => assertEquals(String(payload.data), "3")
        case other                           => fail(s"expected a reply, got $other")
      val plain = await(session.command(command(2, "record"))).toOption.get
      assertEquals(plain.snapshot, None)
      val withSnapshot =
        await(session.command(command(3, "record", snapshotRequested = true))).toOption.get
      assert(withSnapshot.snapshot.isDefined)
      assert(String(withSnapshot.snapshot.get.data).count(_ == '{') == 5)
      session.close()
    }
  }

  test("a refusal, a no-reply and a fault each cross as themselves") {
    withDouble(spec) { (_, conversation, _) =>
      val session = conversation.open(init("d"))
      val refused = await(session.command(command(1, "refuse"))).toOption.get
      refused.outcome match
        case RemoteOutcome.Error(e) => assertEquals(e.code, ErrorCode.Conflict)
        case other                  => fail(s"expected a refusal, got $other")
      val silent = await(session.command(command(2, "no-reply"))).toOption.get
      assertEquals(silent.outcome, RemoteOutcome.NoReply)
      assertEquals(silent.events.size, 1)
      val fault = await(session.command(command(3, "misbehave")))
      assert(fault.left.exists(_.error.message == "boom"))
      session.close()
    }
  }

  test(
    "the sidecar's rules: wrong id, unrequested snapshot, read-only persisting — refused by materialise"
  ) {
    withDouble(spec) { (double, conversation, _) =>
      val handler = RemoteHandler(MethodName("record"), readOnly = false, streaming = false)
      val session = conversation.open(init("e"))

      double.knobs.wrongCommandId = true
      val wrong = await(session.command(command(1, "record"))).toOption.get
      assert(
        RemoteEffect
          .materialise(wrong, handler, 1, false)
          .left
          .exists(_.getMessage.contains("command 1001"))
      )
      double.knobs.wrongCommandId = false

      double.knobs.unrequestedSnapshot = true
      val unasked = await(session.command(command(2, "record"))).toOption.get
      assert(
        RemoteEffect
          .materialise(unasked, handler, 2, false)
          .left
          .exists(_.getMessage.contains("nobody asked"))
      )
      double.knobs.unrequestedSnapshot = false

      val query   = RemoteHandler(MethodName("query-persists"), readOnly = true, streaming = false)
      val illegal = await(session.command(command(3, "query-persists"))).toOption.get
      assert(
        RemoteEffect
          .materialise(illegal, query, 3, false)
          .left
          .exists(_.getMessage.contains("read-only"))
      )
      session.close()
    }
  }

  test("a handler that never replies times out, and a late reply is dropped") {
    withDouble(spec, commandTimeout = 300.millis) { (double, conversation, _) =>
      val session = conversation.open(init("f"))
      double.knobs.neverReply = true
      val timedOut = await(session.command(command(1, "record")))
      assert(timedOut.left.exists(_.error.code == ErrorCode.Timeout), timedOut)
      // The session was closed by the timeout; a new one works.
      double.knobs.neverReply = false
      val again = conversation.open(init("f"))
      val ok    = await(again.command(command(1, "record"))).toOption
      assert(ok.isDefined)
      again.close()
    }
  }

  test("a late reply: the sidecar sees the timeout, the process's answer is dropped") {
    withDouble(spec, commandTimeout = 300.millis) { (double, conversation, _) =>
      val session = conversation.open(init("g"))
      double.knobs.replyDelay = 700.millis
      val timedOut = await(session.command(command(1, "record")))
      assert(timedOut.left.exists(_.error.code == ErrorCode.Timeout))
      Thread.sleep(800) // the late reply arrives into a closed session and is logged, not delivered
      double.knobs.replyDelay = Duration.Zero
    }
  }

  test("the process ending the stream fails the command in flight as unavailable") {
    withDouble(spec, commandTimeout = 5.seconds) { (double, conversation, _) =>
      val session = conversation.open(init("h"))
      double.knobs.neverReply = true
      val pending = session.command(command(1, "record"))
      Thread.sleep(200)
      double.restart()
      val result = await(pending)
      assert(result.left.exists(_.error.code == ErrorCode.Unavailable), result)
    }
  }

  test("a second command while one is in flight is a protocol violation on the sidecar's own side") {
    withDouble(spec, commandTimeout = 5.seconds) { (double, conversation, _) =>
      val session = conversation.open(init("i"))
      double.knobs.neverReply = true
      val _ = session.command(command(1, "record"))
      intercept[ProtocolViolation](await(session.command(command(2, "record"))))
      double.knobs.neverReply = false
      session.close()
    }
  }

  // ── HTTP ────────────────────────────────────────────────────────────────────

  test(
    "a forwarded request reaches the route with its arguments, and the response comes back whole"
  ) {
    withDouble(
      spec.copy(endpoints =
        Vector(spec.endpoints.head.copy(routes = spec.endpoints.head.routes.map {
          case r if r.id == "add" =>
            r.copy(handler =
              req =>
                Right(
                  ankka.protocol.v1.endpoint.HttpResponse(
                    201,
                    "application/json",
                    ByteString.copyFromUtf8(
                      s"""{"id":"${req.pathArgs.head}","q":"${req.query
                          .map(p => p.name + "=" + p.value)
                          .mkString(
                            ","
                          )}","ct":"${req.contentType}","body":"${req.body.toStringUtf8}"}"""
                    )
                  )
                )
            )
          case r => r
        }))
      )
    ) { (_, conversation, _) =>
      val forward = HttpForward(
        "carts",
        "add",
        Vector("c1"),
        Vector("a"      -> "1", "a" -> "2"),
        Vector("X-Test" -> "y"),
        "application/json",
        """{"n":1}""".getBytes,
        None,
        Metadata.empty
      )
      val result = await(conversation.handleHttp(forward)).toOption.get
      assertEquals(result.status, 201)
      assertEquals(
        String(result.body),
        """{"id":"c1","q":"a=1,a=2","ct":"application/json","body":"{"n":1}"}"""
      )
    }
  }

  test("a streaming route's frames arrive in order, intact") {
    withDouble(spec) { (_, conversation, _) =>
      val forward = HttpForward(
        "carts",
        "events",
        Vector("c1"),
        Vector.empty,
        Vector.empty,
        "",
        Array.emptyByteArray,
        None,
        Metadata.empty
      )
      val frames = await(conversation.handleHttpStream(forward).runWith(Sink.seq))
      assertEquals(frames.toVector, Vector(" leading space", "two\nlines"))
    }
  }

  test("a handler fault crosses as a failure, an unknown route as not found") {
    withDouble(
      spec.copy(endpoints =
        Vector(spec.endpoints.head.copy(routes = spec.endpoints.head.routes.map {
          case r if r.id == "get" => r.copy(handler = _ => Left(RuntimeException("kaboom")))
          case r                  => r
        }))
      )
    ) { (_, conversation, _) =>
      val fault = await(
        conversation.handleHttp(
          HttpForward(
            "carts",
            "get",
            Vector("c1"),
            Vector.empty,
            Vector.empty,
            "",
            Array.emptyByteArray,
            None,
            Metadata.empty
          )
        )
      )
      assert(fault.left.exists(_.error.message == "kaboom"))
      val missing = await(
        conversation.handleHttp(
          HttpForward(
            "carts",
            "nope",
            Vector.empty,
            Vector.empty,
            Vector.empty,
            "",
            Array.emptyByteArray,
            None,
            Metadata.empty
          )
        )
      )
      assert(missing.left.exists(_.error.code == ErrorCode.NotFound))
    }
  }

  // ── Loopback ────────────────────────────────────────────────────────────────

  test("the callback server binds loopback and nothing else") {
    val double  = new ProcessDouble(spec)
    val port    = double.start()
    val channel = ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build()
    try
      val service = kit.spawn(org.apache.pekko.actor.typed.scaladsl.Behaviors.empty[Nothing])
      val _       = service
      // A ClientService needs a started AnkkaService; binding alone is what this test proves.
      val server = io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
        .forAddress(new java.net.InetSocketAddress(Settings.CallbackBindAddress, 0))
        .build()
        .start()
      val bound = server.getListenSockets.get(0).asInstanceOf[java.net.InetSocketAddress]
      assert(bound.getAddress.isLoopbackAddress)
      server.shutdownNow()
    finally
      channel.shutdownNow()
      double.stop()
  }

  // ── Sockets (protocol 1.9) ────────────────────────────────────────────────

  private def socketSpec(script: ProcessDouble.SocketScript, version: String = "1.9") =
    spec.copy(
      protocolVersion = version,
      endpoints = Vector(
        ProcessDouble.Endpoint(
          "notices",
          "/notices",
          Vector(
            ProcessDouble.Route("stream", "GET", "/{room}", socket = true, onSocket = script)
          )
        )
      )
    )

  private val opening = HttpForward(
    "notices",
    "stream",
    Vector("lobby"),
    Vector("tag"    -> "a"),
    Vector("X-Test" -> "1"),
    "",
    Array.emptyByteArray,
    Some(RemotePrincipal("ada", None, None, emailVerified = false, Set("buyer"))),
    Trace.into(Metadata.empty, 41L, 42L),
    RemoteCaller.Gateway
  )

  private def frames(link: SocketLink, n: Int): Vector[SocketOutput] =
    (1 to n).map(_ => link.next(5.seconds).getOrElse(fail("nothing from the process"))).toVector

  test(
    "a socket's open is first, carrying the request, the principal, the caller and the protocol"
  ) {
    withDouble(socketSpec(ProcessDouble.SocketScript.Echo)) { (double, conversation, _) =>
      val link = conversation.openSocket(opening)
      link.send("hello")
      assertEquals(frames(link, 1), Vector(SocketOutput.Frame("hello")))
      link.close("client")
      val in   = double.messagesOf { case m: ankka.protocol.v1.endpoint.SocketIn => m }
      val open = in.head.message.open.getOrElse(fail(s"the first message was ${in.head}"))
      assertEquals(open.pathArgs.toVector, Vector("lobby"))
      assertEquals(open.query.map(p => p.name -> p.value).toVector, Vector("tag" -> "a"))
      assertEquals(open.principal.map(_.subject), Some("ada"))
      assert(open.caller.exists(_.kind.isGateway), open.caller.toString)
      val metadata = open.metadata.toVector.flatMap(_.entries.map(e => e.key -> e.value))
      assert(metadata.contains(WireProtocol.MetadataKey -> WireProtocol.Version), metadata.toString)
    }
  }

  test("frames cross a socket in order both ways") {
    withDouble(socketSpec(ProcessDouble.SocketScript.Echo)) { (_, conversation, _) =>
      val link = conversation.openSocket(opening)
      (1 to 10).foreach(i => assert(link.send(s"frame $i")))
      assertEquals(frames(link, 10), (1 to 10).map(i => SocketOutput.Frame(s"frame $i")).toVector)
      link.close("client")
      assertEquals(link.next(5.seconds), Some(SocketOutput.Completed))
    }
  }

  test("the client's close reaches the process as closed with its reason, then the half-close") {
    withDouble(socketSpec(ProcessDouble.SocketScript.Echo)) { (double, conversation, _) =>
      val link = conversation.openSocket(opening)
      link.close("going away")
      assertEquals(link.next(5.seconds), Some(SocketOutput.Completed))
      val closed = double
        .messagesOf { case m: ankka.protocol.v1.endpoint.SocketIn => m }
        .flatMap(_.message.closed)
      assertEquals(closed.map(_.reason), Vector("going away"))
    }
  }

  test("a process that completes, and one that fails, end the link with what it said") {
    withDouble(socketSpec(ProcessDouble.SocketScript.SendThenComplete(2))) { (_, conversation, _) =>
      val link = conversation.openSocket(opening)
      assertEquals(
        frames(link, 3),
        Vector(SocketOutput.Frame("frame 1"), SocketOutput.Frame("frame 2"), SocketOutput.Completed)
      )
      assertEquals(link.next(1.second), Some(SocketOutput.Completed), "and goes on saying so")
      assert(!link.send("late"), "a link that has ended sends nothing")
    }
    withDouble(socketSpec(ProcessDouble.SocketScript.FailOnFrame("the handler broke"))) {
      (_, conversation, _) =>
        val link = conversation.openSocket(opening)
        link.send("go")
        assertEquals(link.next(5.seconds), Some(SocketOutput.Failed("the handler broke")))
    }
  }

  test("a message with no case set ends the socket as failed, never skipped") {
    withDouble(socketSpec(ProcessDouble.SocketScript.SendEmpty)) { (_, conversation, _) =>
      val link = conversation.openSocket(opening)
      link.next(5.seconds) match
        case Some(SocketOutput.Failed(message)) => assert(message.contains("no case set"), message)
        case other                              => fail(s"expected a failure, got $other")
    }
  }

  test("a process that stops while a socket is open ends the link as failed") {
    withDouble(socketSpec(ProcessDouble.SocketScript.Echo)) { (double, conversation, _) =>
      val link = conversation.openSocket(opening)
      link.send("hello")
      assertEquals(frames(link, 1), Vector(SocketOutput.Frame("hello")))
      double.stop()
      link.next(5.seconds) match
        case Some(SocketOutput.Failed(_)) => ()
        case other                        => fail(s"expected a failure, got $other")
    }
  }

  test("discovery: a socket route is declared under 1.9, and refused under an earlier minor") {
    withDouble(socketSpec(ProcessDouble.SocketScript.Echo)) { (double, _, _) =>
      assert(
        Discovery.validate(double.toSpec, Discovery.ProtocolVersion, authConfigured = true).isRight
      )
    }
    withDouble(socketSpec(ProcessDouble.SocketScript.Echo, version = "1.8")) { (double, _, _) =>
      val refused =
        Discovery.validate(double.toSpec, Discovery.ProtocolVersion, authConfigured = true)
      assertEquals(
        refused.left.toOption.getOrElse(Vector.empty),
        Vector(
          "endpoint 'notices': route 'stream' is a socket route, which needs protocol 1.9; " +
            "the SDK speaks 1.8"
        )
      )
    }
  }

  test("discovery: a socket route is a GET with no body that does not also stream") {
    val bad = socketSpec(ProcessDouble.SocketScript.Echo).copy(endpoints =
      Vector(
        ProcessDouble.Endpoint(
          "notices",
          "/notices",
          Vector(
            ProcessDouble.Route("post", "POST", "/a", socket = true),
            ProcessDouble.Route("body", "GET", "/b", hasBody = true, socket = true),
            ProcessDouble.Route("both", "GET", "/c", streaming = true, socket = true)
          )
        )
      )
    )
    withDouble(bad) { (double, _, _) =>
      val problems = Discovery
        .validate(double.toSpec, Discovery.ProtocolVersion, authConfigured = true)
        .left
        .toOption
        .getOrElse(Vector.empty)
      assert(
        problems.exists(_.contains("route 'post' is a socket route, which is opened with GET")),
        problems.toString
      )
      assert(
        problems.exists(_.contains("route 'body' is a socket route, which takes no body")),
        problems.toString
      )
      assert(
        problems.exists(_.contains("route 'both' is a socket route and streaming")),
        problems.toString
      )
    }
  }
