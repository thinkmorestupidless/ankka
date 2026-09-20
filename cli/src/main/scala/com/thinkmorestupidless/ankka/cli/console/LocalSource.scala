package com.thinkmorestupidless.ankka.cli.console

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path, Paths}
import java.time.Duration
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * Services running on this machine, found by reading the registry directory.
 *
 * The only `Source` this feature ships. It discovers nothing by scanning ports or guessing: a
 * service announces itself by writing a file, which mirrors how components reach the runtime —
 * explicitly, never by discovery.
 */
final class LocalSource(directory: Path = LocalSource.defaultDirectory) extends Source:

  private val client = HttpClient
    .newBuilder()
    .connectTimeout(Duration.ofMillis(300))
    .build()

  def services(): Vector[ServiceSummary] =
    entries().sortBy(_.startedAt)

  def service(name: String): Option[String] =
    forName(name).flatMap(e => get(s"${e.observabilityAddress}/observability/service"))

  def traces(name: String): Option[String] =
    forName(name).flatMap(e => get(s"${e.observabilityAddress}/observability/traces"))

  def trace(name: String, traceId: String): Option[String] =
    forName(name).flatMap(e => get(s"${e.observabilityAddress}/observability/traces/$traceId"))

  /**
   * Proxied through this process rather than made from the browser.
   *
   * Not for want of trying to keep it in the page: a service's HTTP port is a different origin from
   * the console's, so a browser will not read the response without CORS headers — and adding CORS
   * to a *production* HTTP server so that a development tool can call it would be letting the tool
   * dictate terms to the thing it observes. The console process makes the call instead.
   *
   * Nothing about the ACL changes: this is still an ordinary HTTP request to the service's own
   * port, carrying no privilege, matched and refused exactly as any other client's would be.
   */
  def invoke(name: String, request: InvokeRequest): Option[InvokeResponse] =
    for
      entry   <- forName(name)
      address <- httpAddressOf(entry)
    yield try
      val builder = HttpRequest
        .newBuilder(URI.create(address + request.path))
        .timeout(Duration.ofSeconds(30))
      request.headers.foreach((k, v) => builder.header(k, v): Unit)
      val publisher = request.body match
        case Some(body) => HttpRequest.BodyPublishers.ofString(body)
        case None       => HttpRequest.BodyPublishers.noBody()
      builder.method(request.method, publisher): Unit

      val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
      val headers = response
        .headers()
        .map()
        .asScala
        .toVector
        .flatMap((k, vs) => vs.asScala.map(v => (k, v)))
      InvokeResponse(response.statusCode(), headers, response.body)
    catch
      // A service that refuses the connection is a fact worth showing, not an error page.
      case failure: Throwable =>
        InvokeResponse(0, Vector.empty, s"could not reach the service: ${failure.getMessage}")

  /**
   * The streaming counterpart, read a line at a time and handed on immediately.
   *
   * `ofInputStream` rather than `ofString`: the point is to not wait for the end. Server-sent
   * events are line-delimited, so a line is the natural unit to forward.
   */
  def invokeStream(name: String, request: InvokeRequest, onChunk: String => Unit): Boolean =
    val target =
      for
        entry   <- forName(name)
        address <- httpAddressOf(entry)
      yield address

    target match
      case None => false
      case Some(address) =>
        try
          val builder = HttpRequest
            .newBuilder(URI.create(address + request.path))
            // No timeout: a stream that is doing its job may stay open for a long time, and
            // cutting it off after an arbitrary interval would look exactly like the service
            // failing.
            .version(java.net.http.HttpClient.Version.HTTP_1_1)
          request.headers.foreach((k, v) => builder.header(k, v): Unit)
          val publisher = request.body match
            case Some(body) => HttpRequest.BodyPublishers.ofString(body)
            case None       => HttpRequest.BodyPublishers.noBody()
          builder.method(request.method, publisher): Unit

          val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
          val reader   = scala.io.Source.fromInputStream(response.body())
          try reader.getLines().foreach(line => onChunk(line + "\n"))
          finally reader.close()
          true
        catch
          case failure: Throwable =>
            onChunk(s"\n(stream ended: ${failure.getMessage})\n")
            true

  /** The service's real HTTP address, asked of the service rather than read from a file. */
  private def httpAddressOf(entry: ServiceSummary): Option[String] =
    get(s"${entry.observabilityAddress}/observability/service")
      .flatMap(json => field(json, "address"))

  def session(name: String, sessionId: String): Option[String] =
    forName(name).flatMap { e =>
      get(s"${e.observabilityAddress}/observability/sessions/$sessionId")
    }

  def query(
      name: String,
      component: String,
      entityId: String,
      method: String
  ): Option[QueryResponse] =
    forName(name).flatMap { e =>
      // Forwarded whole, including a refusal: the console is a window onto the service's answer,
      // not a second opinion about it.
      val url = s"${e.observabilityAddress}/observability/query/$component/$entityId/$method"
      try
        val response = client.send(
          HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(12)).GET().build(),
          HttpResponse.BodyHandlers.ofString()
        )
        Some(QueryResponse(response.statusCode(), response.body))
      catch case _: Throwable => None
    }

  private def forName(name: String): Option[ServiceSummary] =
    services().find(_.name == name)

  /**
   * Reads the directory, dropping entries nothing answers for — and deleting their files.
   *
   * **A stale entry is the normal case, not an error.** `kill -9` is how a developer stops a
   * service far more often than a clean shutdown, so the writer cannot be relied on to clean up and
   * the reader must. A dead row that errors when clicked is worse than no row at all, which is why
   * this probes rather than trusting the file's existence.
   */
  private def entries(): Vector[ServiceSummary] =
    if !Files.isDirectory(directory) then Vector.empty
    else
      val files =
        try Files.list(directory).iterator().asScala.filter(_.toString.endsWith(".json")).toVector
        catch case _: Throwable => Vector.empty

      files.flatMap { file =>
        parse(file) match
          case None =>
            // Unreadable or malformed: not ours to interpret, and not ours to keep.
            discard(file)
            None
          case Some(entry) =>
            if alive(entry) then Some(entry)
            else
              discard(file)
              None
      }

  private def alive(entry: ServiceSummary): Boolean =
    get(s"${entry.observabilityAddress}/observability/service").isDefined

  private def discard(file: Path): Unit =
    try Files.deleteIfExists(file): Unit
    catch case _: Throwable => ()

  private def parse(file: Path): Option[ServiceSummary] =
    Try {
      val json = Files.readString(file)
      ServiceSummary(
        name = field(json, "name").get,
        instanceId = field(json, "instanceId").getOrElse("?"),
        observabilityAddress = field(json, "observabilityAddress").get,
        startedAt = field(json, "startedAt").getOrElse("")
      )
    }.toOption

  /**
   * One string field out of a flat object.
   *
   * The registry entry is written by this same project and has five string fields, so a parser is
   * not worth a dependency in a module whose defining property is carrying almost none. Anything it
   * cannot read is treated as a stale entry and removed, which is the same handling a corrupt file
   * would get from a real parser.
   */
  private def field(json: String, key: String): Option[String] =
    val marker = s""""$key":""""
    json.indexOf(marker) match
      case -1 => None
      case at =>
        val from = at + marker.length
        json.indexOf('"', from) match
          case -1 => None
          case to => Some(json.substring(from, to))

  private def get(url: String): Option[String] =
    try
      val response = client.send(
        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(2)).GET().build(),
        HttpResponse.BodyHandlers.ofString()
      )
      if response.statusCode() == 200 then Some(response.body) else None
    catch case _: Throwable => None

object LocalSource:

  /**
   * `-Dankka.running.dir`, else `~/.ankka/running`.
   *
   * Overridable for the reason `Settings.path` checks `-Dankka.config` first: environment variables
   * cannot be set in-process, so without this no suite could exercise discovery without writing
   * into the developer's own home directory.
   */
  def defaultDirectory: Path =
    sys.props.get("ankka.running.dir") match
      case Some(path) => Paths.get(path)
      case None       => Paths.get(sys.props("user.home"), ".ankka", "running")
