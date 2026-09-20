package com.thinkmorestupidless.ankka.runtime

import com.sun.net.httpserver.{HttpExchange, HttpServer as JdkHttpServer}
import com.thinkmorestupidless.ankka.core.{
  ComponentId,
  ComponentKind,
  EntityId,
  Metadata,
  MethodName
}

import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * What a locally-running service exposes for the console to read.
 *
 * ==Why this exists at all, rather than a management route==
 *
 * The obvious home for this is Pekko Management, beside `/ready` and `/ankka/version`. It cannot
 * be: `ClusterFormation` starts management **only** in the Kubernetes path, and says why — it binds
 * a fixed port, which two services on one laptop would fight over. Local mode therefore has no
 * management server at all, which is precisely where the console needs one.
 *
 * So observability is exposed twice, chosen by where the process runs, exactly as cluster formation
 * already is: this endpoint locally, and a `ManagementRouteProvider` in Kubernetes. One recorder,
 * two exposures.
 *
 * ==Why the JDK's server and not pekko-http==
 *
 * `runtime` must not depend on `http` — that is the `RuntimeExtension` seam's whole point, and a
 * service may legitimately serve no HTTP at all. `com.sun.net.httpserver` is part of the JDK, so
 * using it adds nothing to the dependency graph of a published artifact.
 *
 * Bound to **loopback on an ephemeral port**. Loopback is the entirety of the access control, and
 * is the reason the console is local-only: this endpoint shows entity state and agent memory, which
 * is whatever the application put there.
 */
final class ObservabilityEndpoint private (
    server: JdkHttpServer,
    registration: Option[Path]
):

  def address: String = s"http://127.0.0.1:${server.getAddress.getPort}"

  def stop(): Unit =
    registration.foreach(ServiceRegistration.withdraw)
    server.stop(0)

object ObservabilityEndpoint:

  /**
   * Starts the endpoint and announces the service, in local mode only.
   *
   * Returns `None` when there is nothing to do — in Kubernetes, or if the port cannot be bound. A
   * service whose console endpoint fails to start is a service that works and cannot be browsed;
   * that is not a reason to fail startup.
   */
  def start(service: AnkkaService, serviceName: String): Option[ObservabilityEndpoint] =
    try
      val server = JdkHttpServer.create(
        InetSocketAddress(InetAddress.getLoopbackAddress, 0),
        0
      )
      val observability = Observability(service.system)
      val handler       = Handler(service, serviceName, observability)

      server.createContext("/observability/service", exchange => handler.service(exchange))
      server.createContext("/observability/traces", exchange => handler.traces(exchange))
      server.createContext("/observability/sessions", exchange => handler.sessions(exchange))
      server.setExecutor(null) // the JDK's default: a small pool, which is ample for one reader
      server.start()

      val address      = s"http://127.0.0.1:${server.getAddress.getPort}"
      val registration = ServiceRegistration.announce(serviceName, address)
      service.system.log.info("ankka local console endpoint on {}", address)
      Some(new ObservabilityEndpoint(server, registration))
    catch
      case failure: Throwable =>
        service.system.log.debug("observability endpoint did not start: {}", failure.getMessage)
        None

  private final class Handler(
      service: AnkkaService,
      serviceName: String,
      observability: Observability
  ):

    /** Identity and inventory: what the Services and Components panels are built from. */
    def service(exchange: HttpExchange): Unit =
      val instances = service.boundAddresses match
        case addresses if addresses.nonEmpty =>
          addresses
            .map(a =>
              s"""{"id":${Json.str(instanceId)},"startedAt":${Json.str(startedAt)},""" +
                s""""http":{"address":${Json.str(a)}}}"""
            )
            .mkString("[", ",", "]")
        // A service with `"http": false` serves nothing addressable. Say so, rather than offer an
        // invoke panel that cannot work.
        case _ =>
          s"""[{"id":${Json.str(instanceId)},"startedAt":${Json.str(startedAt)}}]"""

      val components = service.registry.components
        .map { descriptor =>
          s"""{"kind":${Json.str(descriptor.kind.toString)},""" +
            s""""id":${Json.str(descriptor.componentId.toString)},""" +
            s""""sharded":${descriptor.kind.sharded}}"""
        }
        .mkString("[", ",", "]")

      // The routes the console turns into a form. Reported by the extensions that serve them,
      // because `runtime` knows nothing about HTTP and should not start now.
      val routes = service.routes
        .map(r =>
          s"""{"method":${Json.str(r.method)},"path":${Json.str(r.path)},""" +
            s""""streaming":${r.streaming}}"""
        )
        .mkString("[", ",", "]")

      respond(
        exchange,
        s"""{"name":${Json.str(serviceName)},""" +
          s""""runtime":${Json.str(com.thinkmorestupidless.ankka.core.BuildInfo.version)},""" +
          s""""instances":$instances,"components":$components,"routes":$routes}"""
      )

    /** The recent window, newest first, or one trace in full when asked for by id. */
    def traces(exchange: HttpExchange): Unit =
      val path =
        exchange.getRequestURI.getPath.stripPrefix("/observability/traces").stripPrefix("/")
      if path.isEmpty then respond(exchange, summaries())
      else
        java.lang.Long.parseUnsignedLong(path, 16) match
          case traceId => respond(exchange, detail(traceId))

    private def summaries(): String =
      val recorder = observability.recorder
      val spans    = recorder.snapshot()
      val traces = spans
        .groupBy(_.traceId)
        .toVector
        .map((traceId, group) => (traceId, group.minBy(_.startedNanos), group))
        .sortBy(-_._2.startedNanos)
        .take(100)
        .map { (traceId, first, group) =>
          val assembled =
            Trace.assemble(traceId, group.sortBy(_.startedNanos), recorder.oldestOverwritten)
          s"""{"traceId":${Json.str(java.lang.Long.toHexString(traceId))},""" +
            s""""entry":${Json.str(nameOf(first))},""" +
            s""""durationMillis":${assembled.durationNanos / 1000000},""" +
            s""""outcome":${Json.str(first.outcome.toString)},""" +
            s""""spans":${group.size},"partial":${assembled.partial}}"""
        }
        .mkString("[", ",", "]")

      s"""{"capacity":${recorder.capacity},"held":${spans.size},""" +
        s""""oldestOverwritten":${recorder.oldestOverwritten},"traces":$traces}"""

    private def detail(traceId: Long): String =
      val recorder  = observability.recorder
      val assembled = Trace.assemble(traceId, recorder.spansOf(traceId), recorder.oldestOverwritten)
      s"""{"traceId":${Json.str(java.lang.Long.toHexString(traceId))},""" +
        s""""durationMillis":${assembled.durationNanos / 1000000},""" +
        s""""unattributedMillis":${assembled.unattributedNanos / 1000000},""" +
        s""""partial":${assembled.partial},""" +
        s""""spans":${assembled.roots.map(span).mkString("[", ",", "]")}}"""

    private def span(node: TraceSpan): String =
      s"""{"spanId":${Json.str(java.lang.Long.toHexString(node.spanId))},""" +
        s""""component":${Json.str(
            observability.names.nameOf(node.componentRef).getOrElse("?")
          )},""" +
        s""""handler":${Json.str(observability.names.nameOf(node.handlerRef).getOrElse("?"))},""" +
        s""""durationMillis":${node.durationNanos / 1000000},""" +
        s""""durationMicros":${node.durationNanos / 1000},""" +
        s""""outcome":${Json.str(node.outcome.toString)},""" +
        s""""parentUnknown":${node.parentUnknown},""" +
        s""""children":${node.children.map(span).mkString("[", ",", "]")}}"""

    /**
     * An agent session's stored memory and the tokens it has cost.
     *
     * Read from the session entity itself, not from the recorder — the conversation is event
     * sourced, so the entity is the durable record while the ring is a window that evicts. Cost
     * that vanished because a service got busy would be worse than no cost at all.
     *
     * The reply is passed through as bytes. A handler's reply is already JSON on the wire, so the
     * endpoint never has to name a type from `agent` — which it could not do anyway, since
     * `runtime` must not depend on it. The coupling that remains is the component's *name*, which
     * is a platform constant rather than a type, and is the narrowest form this can take.
     */
    def sessions(exchange: HttpExchange): Unit =
      val id =
        exchange.getRequestURI.getPath.stripPrefix("/observability/sessions").stripPrefix("/")
      if id.isEmpty then respond(exchange, """{"error":"name a session"}""")
      else
        val reply =
          try
            Some(
              scala.concurrent.Await.result(
                service.componentClient.transportRef.ask(
                  ComponentId(SessionMemoryComponent),
                  EntityId(id),
                  MethodName("history"),
                  Array.emptyByteArray,
                  Metadata.empty
                ),
                scala.concurrent.duration.Duration(10, java.util.concurrent.TimeUnit.SECONDS)
              )
            )
          catch case _: Throwable => None

        reply match
          // A session nobody has spoken to has no memory. Say so, rather than invent an empty
          // conversation that reads as though it happened.
          case None        => respondNotFound(exchange)
          case Some(bytes) => respondBytes(exchange, bytes)

    private def nameOf(span: RecordedSpan): String =
      val component = observability.names.nameOf(span.componentRef).getOrElse("?")
      val handler   = observability.names.nameOf(span.handlerRef).getOrElse("?")
      s"$component#$handler"

    private val instanceId = ProcessHandle.current().pid().toString
    private val startedAt  = java.time.Instant.now().toString

  /** The platform's own session-memory component. Coupled by name, never by type. */
  private val SessionMemoryComponent = "ankka-session-memory"

  private def respondBytes(exchange: HttpExchange, bytes: Array[Byte]): Unit =
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.getResponseHeaders.add("Access-Control-Allow-Origin", "*")
    exchange.sendResponseHeaders(200, bytes.length.toLong)
    val out = exchange.getResponseBody
    try out.write(bytes)
    finally out.close()

  private def respondNotFound(exchange: HttpExchange): Unit =
    val bytes = """{"error":"no such session"}""".getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.getResponseHeaders.add("Access-Control-Allow-Origin", "*")
    exchange.sendResponseHeaders(404, bytes.length.toLong)
    val out = exchange.getResponseBody
    try out.write(bytes)
    finally out.close()

  private def respond(exchange: HttpExchange, body: String): Unit =
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    // The console is served from a different origin (its own port), so it needs this to read us.
    // Safe precisely because the endpoint is loopback-only: nothing off this machine can reach it.
    exchange.getResponseHeaders.add("Access-Control-Allow-Origin", "*")
    exchange.sendResponseHeaders(200, bytes.length.toLong)
    val out = exchange.getResponseBody
    try out.write(bytes)
    finally out.close()

/** Just enough JSON to emit a string safely. A codec would be a dependency for six call sites. */
private object Json:
  def str(value: String): String =
    val escaped = value.flatMap {
      case '"'                 => "\\\""
      case '\\'                => "\\\\"
      case '\n'                => "\\n"
      case '\r'                => "\\r"
      case '\t'                => "\\t"
      case c if c.toInt < 0x20 => f"\\u${c.toInt}%04x"
      case c                   => c.toString
    }
    s""""$escaped""""
