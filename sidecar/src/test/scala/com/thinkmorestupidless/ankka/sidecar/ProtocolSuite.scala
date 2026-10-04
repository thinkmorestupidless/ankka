package com.thinkmorestupidless.ankka.sidecar

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import ankka.protocol.v1.discovery.*
import com.google.protobuf.ByteString
import com.thinkmorestupidless.ankka.core.{
  ComponentKind,
  ComponentId,
  EntityId,
  ErrorCode,
  Metadata,
  MethodName
}
import com.thinkmorestupidless.ankka.runtime.remote.*
import com.thinkmorestupidless.ankka.sdk.StartFrom
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
    // The double declares 1.0; the sidecar speaks 1.6 (1.1 added the caller, 1.2 the autonomous
    // agent, 1.3 a consumer's several messages, 1.4 metadata on the requests a handler's work is
    // sent in, 1.5 a principal's claims, 1.6 the secret store). Earlier minors are admitted.
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
    assertEquals(Discovery.ProtocolVersion, "1.7")
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
    assertEquals(
      refused.toSet,
      Set(
        "view 'zero' declares version 0; a version is a whole number of 1 or more",
        "view 'over-entity' declares a version, which applies to a topic; it reads " +
          "EventSourcedEntity(order)"
      )
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
