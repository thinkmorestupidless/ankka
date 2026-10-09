package com.thinkmorestupidless.ankka.testkit

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import com.thinkmorestupidless.ankka.runtime.secrets.DerivedIds

import java.net.{InetAddress, InetSocketAddress, URLDecoder}
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.{ConcurrentLinkedQueue, Executors}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/**
 * Google Secret Manager, played on loopback for a test: the REST calls the platform makes, Google's
 * answers and error shapes, and Google Cloud's refusals.
 *
 * Secret Manager has no emulator, so without this the Secret Manager backend would be tested only
 * on GKE. What makes it worth testing against is that it refuses what Google Cloud would refuse:
 * every call is checked against the secret access a caller would have been given (a service: create
 * anything, and keep, read and remove only under its own prefix, read its project's entries; the
 * control plane: add and disable versions of entries, read none; the cloud provider: read and seed
 * entries, never a service secret; nobody lists), before anything changes.
 *
 * The caller is the bearer token: `fake:<project>/<service>`, `fake:controlplane` or
 * `fake:provider` (see `tokenFor`), so a test plays two services by giving each its own token.
 * Plain HTTP on `127.0.0.1` and an ephemeral port; it reaches no network.
 */
final class FakeSecretManager private (server: HttpServer, val account: String):
  import FakeSecretManager.{*, given}

  /** Where it answers, as `ankka.secrets.secret-manager.endpoint` takes it. */
  val endpoint: String = s"http://127.0.0.1:${server.getAddress.getPort}"

  private val lock     = Object()
  private val secrets  = scala.collection.mutable.LinkedHashMap.empty[String, Secret]
  private val received = ConcurrentLinkedQueue[Call]()
  private val down     = AtomicBoolean(false)
  private val failures = AtomicInteger(0)
  private val failWith = AtomicInteger(503)
  private val withheld = java.util.concurrent.ConcurrentHashMap.newKeySet[Identity]()

  /** While on, every call's connection is closed unanswered, as an unreachable endpoint's is. */
  def unreachable(on: Boolean): Unit = down.set(on)

  /** Answers the next call with `status` in Google's error shape, once, and then as before. */
  def failNext(status: Int = 503): Unit =
    failWith.set(status)
    failures.incrementAndGet(): Unit

  /** What a test may withhold: the secret access an identity would have been given. */
  object access:
    /** From now on `identity` is refused everything, create included, as with no access written. */
    def withhold(identity: Identity): Unit = withheld.add(identity): Unit
    def restore(identity: Identity): Unit  = withheld.remove(identity): Unit

  /** Every call received, in order, with the identity that made it and what it was answered. */
  def calls: Vector[Call] = received.asScala.toVector

  /** Forgets the calls received; keeps the secrets. */
  def clearCalls(): Unit = received.clear()

  /** Each secret's id and how many enabled versions it has. */
  def snapshot: Map[String, Int] = lock.synchronized {
    secrets.view.mapValues(_.versions.count(_.state == Enabled)).toMap
  }

  /** The secret's versions, oldest first, as `(number, state)`. */
  def versionsOf(id: String): Vector[(Long, String)] = lock.synchronized {
    secrets.get(id).fold(Vector.empty)(_.versions.map(v => v.number -> v.state))
  }

  /** The secret's annotations, or none when there is no such secret. */
  def annotationsOf(id: String): Option[Map[String, String]] = lock.synchronized {
    secrets.get(id).map(_.annotations)
  }

  /** What `latest` reads for the secret, as text: for a test's assertion, never the platform's. */
  def latestValue(id: String): Option[String] = lock.synchronized {
    secrets.get(id).flatMap(latestEnabled).map(v => String(v.data, StandardCharsets.UTF_8))
  }

  /** Makes `id` hold `value` as a new version, as someone with access in Google Cloud would. */
  def seed(id: String, value: String, annotations: Map[String, String] = Map.empty): Unit =
    lock.synchronized {
      val secret = secrets.getOrElseUpdate(id, Secret(annotations, Vector.empty))
      secrets(id) = secret.add(value.getBytes(StandardCharsets.UTF_8))
    }

  /** Disables version `number`, as a person might by hand in the console. */
  def disableByHand(id: String, number: Long): Unit = lock.synchronized {
    secrets.get(id).foreach(s => secrets(id) = s.withState(number, Disabled))
  }

  def stop(): Unit = server.stop(0)

  private def handle(exchange: HttpExchange): Unit =
    if down.get then exchange.close()
    else
      val identity = bearer(exchange)
      try
        val (status, body) =
          if failures.getAndUpdate(n => if n > 0 then n - 1 else 0) > 0 then
            failWith.get -> error(failWith.get, "failed on purpose by the test")
          else
            identity match
              case None      => 401 -> error(401, "no recognised bearer token")
              case Some(who) => route(who, exchange)
        received.add(Call(identity, exchange.getRequestMethod, operationOf(exchange), status))
        exchange.getResponseHeaders.add("Content-Type", "application/json")
        val bytes = body.getBytes(StandardCharsets.UTF_8)
        exchange.sendResponseHeaders(status, if bytes.isEmpty then -1L else bytes.length.toLong)
        if bytes.nonEmpty then exchange.getResponseBody.write(bytes)
      catch
        case NonFatal(e) =>
          val bytes = error(500, e.toString).getBytes(StandardCharsets.UTF_8)
          exchange.sendResponseHeaders(500, bytes.length.toLong)
          exchange.getResponseBody.write(bytes)
      finally exchange.close()

  private def bearer(exchange: HttpExchange): Option[Identity] =
    Option(exchange.getRequestHeaders.getFirst("Authorization"))
      .map(_.stripPrefix("Bearer "))
      .flatMap(Identity.fromToken)

  /** `create:<id>`, `access:<id>`… — what a call was, for `calls`. */
  private def operationOf(exchange: HttpExchange): String =
    parse(exchange).fold(_ => exchange.getRequestURI.getPath, (op, id, _) => s"$op:$id")

  /** `(operation, id, version)` from the request, or a status and a message. */
  private def parse(exchange: HttpExchange): Either[(Int, String), (Op, String, Option[String])] =
    val method = exchange.getRequestMethod
    val path   = exchange.getRequestURI.getRawPath
    val query  = Option(exchange.getRequestURI.getRawQuery).getOrElse("")
    val prefix = s"/v1/projects/$account/secrets"
    if !path.startsWith(prefix) then Left(403 -> s"no such project, or no access to it: $path")
    else
      val rest = path.stripPrefix(prefix)
      (method, rest) match
        case ("POST", "") =>
          queryParam(query, "secretId")
            .toRight(400 -> "secretId is required")
            .map(id => (Op.Create, id, None))
        case ("GET", "") => Right((Op.ListSecrets, "", None))
        case ("POST", r) if r.endsWith(":addVersion") =>
          Right((Op.Add, r.stripPrefix("/").stripSuffix(":addVersion"), None))
        case (m, r) if r.contains("/versions") =>
          val id    = r.stripPrefix("/").takeWhile(_ != '/')
          val after = r.stripPrefix("/" + id + "/versions")
          (m, after) match
            case ("GET", "") => Right((Op.ListVersions, id, None))
            case ("GET", a) if a.endsWith(":access") =>
              Right((Op.Access, id, Some(a.stripPrefix("/").stripSuffix(":access"))))
            case ("POST", a) if a.endsWith(":destroy") =>
              Right((Op.Destroy, id, Some(a.stripPrefix("/").stripSuffix(":destroy"))))
            case ("POST", a) if a.endsWith(":disable") =>
              Right((Op.Disable, id, Some(a.stripPrefix("/").stripSuffix(":disable"))))
            case _ => Left(404 -> s"no such method: $m $path")
        case ("GET", r)    => Right((Op.Get, r.stripPrefix("/"), None))
        case ("DELETE", r) => Right((Op.Delete, r.stripPrefix("/"), None))
        case _             => Left(404 -> s"no such method: $method $path")

  private def route(who: Identity, exchange: HttpExchange): (Int, String) =
    parse(exchange) match
      case Left((status, message)) => status -> error(status, message)
      case Right((op, id, version)) =>
        if op != Op.ListSecrets && !IdShape.matches(id) then
          400 -> error(400, s"secret id '$id' is not letters, digits, '_' and '-', 1 to 255")
        else if withheld.contains(who) || !allowed(who, op, id) then
          403 -> error(
            403,
            s"Permission '${op.permission}' denied on resource " +
              s"'projects/$account/secrets/$id' (or it may not exist)."
          )
        else
          val body = exchange.getRequestBody.readAllBytes()
          lock.synchronized(perform(op, id, version, body))

  private def perform(
      op: Op,
      id: String,
      version: Option[String],
      body: Array[Byte]
  ): (Int, String) =
    val name = s"projects/$account/secrets/$id"
    op match
      case Op.Create =>
        val request     = readFromArray[CreateRequest](if body.isEmpty then "{}".getBytes else body)
        val annotations = request.annotations.getOrElse(Map.empty)
        annotations.keys.find(k => !AnnotationKey.matches(k)) match
          case Some(bad) => 400 -> error(400, s"annotation key '$bad' is not allowed")
          case None =>
            if secrets.contains(id) then 409 -> error(409, s"Secret [$name] already exists.")
            else
              secrets(id) = Secret(annotations, Vector.empty)
              200 -> s"""{"name":${quote(name)}}"""
      case Op.Add =>
        secrets.get(id) match
          case None => 404 -> error(404, s"Secret [$name] not found or has no versions.")
          case Some(secret) =>
            val data = Base64.getDecoder.decode(readFromArray[AddRequest](body).payload.data)
            if data.length > 65536 then 400 -> error(400, "payload is larger than 64 KiB")
            else
              val added = secret.add(data)
              secrets(id) = added
              200 -> s"""{"name":${quote(
                  s"$name/versions/${added.versions.last.number}"
                )},"state":"ENABLED"}"""
      case Op.Access =>
        val found = secrets.get(id).flatMap { secret =>
          version match
            case Some("latest") => latestEnabled(secret)
            case Some(n) =>
              secret.versions.find(v => v.number.toString == n && v.state == Enabled)
            case None => None
        }
        found match
          case None =>
            404 -> error(
              404,
              s"Secret Version [$name/versions/${version.getOrElse("")}] not found."
            )
          case Some(v) =>
            val data = Base64.getEncoder.encodeToString(v.data)
            200 -> s"""{"name":${quote(s"$name/versions/${v.number}")},"payload":{"data":${quote(
                data
              )}}}"""
      case Op.ListVersions =>
        secrets.get(id) match
          case None => 404 -> error(404, s"Secret [$name] not found.")
          case Some(secret) =>
            val listed = secret.versions.reverse.filter(_.state == Enabled)
            val items = listed.map(v =>
              s"""{"name":${quote(s"$name/versions/${v.number}")},"state":${quote(v.state)}}"""
            )
            200 -> s"""{"versions":[${items.mkString(",")}],"totalSize":${items.size}}"""
      case Op.Destroy | Op.Disable =>
        val state = if op == Op.Destroy then Destroyed else Disabled
        secrets
          .get(id)
          .filter(s => version.exists(n => s.versions.exists(_.number.toString == n))) match
          case None =>
            404 -> error(
              404,
              s"Secret Version [$name/versions/${version.getOrElse("")}] not found."
            )
          case Some(secret) =>
            secrets(id) = secret.withState(version.get.toLong, state)
            200 -> s"""{"name":${quote(s"$name/versions/${version.get}")},"state":${quote(
                state
              )}}"""
      case Op.Get =>
        secrets.get(id) match
          case None    => 404 -> error(404, s"Secret [$name] not found.")
          case Some(_) => 200 -> s"""{"name":${quote(name)}}"""
      case Op.Delete =>
        secrets.remove(id) match
          case None    => 404 -> error(404, s"Secret [$name] not found.")
          case Some(_) => 200 -> "{}"
      case Op.ListSecrets =>
        // Nobody the platform runs is given list; `allowed` refuses it first.
        200 -> s"""{"secrets":[${secrets.keys
            .map(k => s"""{"name":${quote(s"projects/$account/secrets/$k")}}""")
            .mkString(",")}]}"""

object FakeSecretManager:

  /** Starts one on loopback and an ephemeral port, holding the secrets of `account`. */
  def start(account: String = "fake-account"): FakeSecretManager =
    val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress, 0), 0)
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor())
    val fake = new FakeSecretManager(server, account)
    server.createContext("/", exchange => fake.handle(exchange))
    server.start()
    fake

  /** Who a call is from, as the bearer token says. */
  enum Identity:
    case Service(project: String, service: String)
    case ControlPlane
    case Provider

    def token: String = this match
      case Service(p, s) => s"fake:$p/$s"
      case ControlPlane  => "fake:controlplane"
      case Provider      => "fake:provider"

  object Identity:
    def fromToken(token: String): Option[Identity] = token match
      case "fake:controlplane" => Some(ControlPlane)
      case "fake:provider"     => Some(Provider)
      case t if t.startsWith("fake:") =>
        t.stripPrefix("fake:").split('/') match
          case Array(p, s) if p.nonEmpty && s.nonEmpty => Some(Service(p, s))
          case _                                       => None
      case _ => None

  /** The token a service with this identity is given. */
  def tokenFor(project: String, service: String): String = Identity.Service(project, service).token

  /** One call: from whom (none for an unrecognised token), the method, what it was, the status. */
  final case class Call(identity: Option[Identity], method: String, operation: String, status: Int)

  private enum Op(val permission: String):
    case Create       extends Op("secretmanager.secrets.create")
    case Add          extends Op("secretmanager.versions.add")
    case Access       extends Op("secretmanager.versions.access")
    case ListVersions extends Op("secretmanager.versions.list")
    case Destroy      extends Op("secretmanager.versions.destroy")
    case Disable      extends Op("secretmanager.versions.disable")
    case Get          extends Op("secretmanager.secrets.get")
    case Delete       extends Op("secretmanager.secrets.delete")
    case ListSecrets  extends Op("secretmanager.secrets.list")

  /**
   * The secret access of research R3: a create cannot be conditioned on a name, so it is anyone's;
   * everything else is conditioned on a prefix; nobody lists.
   */
  private def allowed(who: Identity, op: Op, id: String): Boolean = who match
    case Identity.Service(project, service) =>
      val own     = id.startsWith(DerivedIds.servicePrefix(project, service))
      val entries = id.startsWith(DerivedIds.projectPrefix(project))
      op match
        case Op.Create                                                  => true
        case Op.Access                                                  => own || entries
        case Op.Add | Op.ListVersions | Op.Destroy | Op.Get | Op.Delete => own
        case Op.Disable | Op.ListSecrets                                => false
    case Identity.ControlPlane =>
      op match
        case Op.Create                             => true
        case Op.Add | Op.ListVersions | Op.Disable => id.startsWith("p_")
        case _                                     => false
    case Identity.Provider =>
      op match
        case Op.Create                                     => true
        case Op.Access | Op.ListVersions | Op.Add | Op.Get => id.startsWith("p_")
        case _                                             => false

  private val Enabled   = "ENABLED"
  private val Disabled  = "DISABLED"
  private val Destroyed = "DESTROYED"

  private val IdShape       = "[A-Za-z0-9_-]{1,255}".r
  private val AnnotationKey = "[A-Za-z0-9]([A-Za-z0-9._-]{0,61}[A-Za-z0-9])?".r

  private final case class Version(number: Long, data: Array[Byte], state: String)

  private final case class Secret(annotations: Map[String, String], versions: Vector[Version]):
    def add(data: Array[Byte]): Secret =
      copy(versions =
        versions :+ Version(versions.lastOption.fold(1L)(_.number + 1), data, Enabled)
      )
    def withState(number: Long, state: String): Secret =
      copy(versions = versions.map { v =>
        if v.number != number then v
        else if state == Destroyed then v.copy(data = Array.emptyByteArray, state = state)
        else v.copy(state = state)
      })

  private def latestEnabled(secret: Secret): Option[Version] =
    secret.versions.reverse.find(_.state == Enabled)

  private final case class Payload(data: String)
  private final case class AddRequest(payload: Payload)
  private final case class CreateRequest(annotations: Option[Map[String, String]] = None)
  private given JsonValueCodec[AddRequest]    = JsonCodecMaker.make
  private given JsonValueCodec[CreateRequest] = JsonCodecMaker.make

  private def queryParam(query: String, name: String): Option[String] =
    query
      .split('&')
      .collectFirst { case kv if kv.startsWith(name + "=") => kv.drop(name.length + 1) }
      .map(URLDecoder.decode(_, StandardCharsets.UTF_8))

  private val statusWords = Map(
    400 -> "INVALID_ARGUMENT",
    401 -> "UNAUTHENTICATED",
    403 -> "PERMISSION_DENIED",
    404 -> "NOT_FOUND",
    409 -> "ALREADY_EXISTS",
    429 -> "RESOURCE_EXHAUSTED",
    500 -> "INTERNAL",
    503 -> "UNAVAILABLE"
  )

  private def error(status: Int, message: String): String =
    val word = statusWords.getOrElse(status, "UNKNOWN")
    s"""{"error":{"code":$status,"message":${quote(message)},"status":"$word"}}"""

  private def quote(text: String): String =
    "\"" + text.flatMap {
      case '"'          => "\\\""
      case '\\'         => "\\\\"
      case c if c < ' ' => f"\\u${c.toInt}%04x"
      case c            => c.toString
    } + "\""
