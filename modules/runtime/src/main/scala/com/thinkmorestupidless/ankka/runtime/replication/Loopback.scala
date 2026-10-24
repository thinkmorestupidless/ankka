package com.thinkmorestupidless.ankka.runtime.replication

import com.sun.net.httpserver.HttpExchange
import com.thinkmorestupidless.ankka.core.{CommandError, ComponentId, EntityId, ErrorCode}
import com.thinkmorestupidless.ankka.runtime.{
  AnkkaSerializable,
  AnkkaService,
  EntityKeys,
  EntityProtocol,
  MetaEntry,
  ServiceRegistration
}
import org.apache.pekko.Done
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.serialization.SerializationExtension
import org.apache.pekko.util.Timeout

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.jdk.FutureConverters.*

/** A forwarded call, as one service on this machine hands it to another. */
final case class LoopbackCall(method: String, payload: Array[Byte], metadata: Vector[MetaEntry])
    extends AnkkaSerializable

/** The home's answer to a forwarded call: its reply, or the refusal it gave. */
final case class LoopbackAnswer(
    succeeded: Boolean,
    payload: Array[Byte],
    metadata: Vector[MetaEntry],
    message: String,
    code: String
) extends AnkkaSerializable

/** A handover's target says how much it holds. */
final case class LoopbackReport(installation: String, position: Long) extends AnkkaSerializable

/**
 * The two routes a replicating service's local console endpoint serves for a sibling installation
 * running beside it on this machine (feature 051): `POST /replication/forward/<component>/<entity>`
 * hands it a forwarded call and answers with the reply, and `POST /replication/report/…` hands a
 * pending handover's target's position to the home. Bodies are the runtime's own serialized forms.
 * Served on loopback in local mode only, as the rest of the endpoint is.
 */
private[ankka] final class LoopbackRoutes(service: AnkkaService):

  private val system: ActorSystem[?] = service.system
  private val serialization          = SerializationExtension(system.classicSystem)
  private val askTimeout             = FiniteDuration(
    system.settings.config.getDuration("ankka.ask-timeout").toMillis,
    MILLISECONDS
  )

  private def target(exchange: HttpExchange, prefix: String): Option[(ComponentId, EntityId)] =
    exchange.getRequestURI.getRawPath.stripPrefix(prefix).stripPrefix("/").split('/') match
      case Array(component, entity) =>
        Some(
          ComponentId(java.net.URLDecoder.decode(component, UTF_8)) ->
            EntityId(java.net.URLDecoder.decode(entity, UTF_8))
        )
      case _ => None

  private def respond(exchange: HttpExchange, status: Int, body: Array[Byte]): Unit =
    exchange.sendResponseHeaders(status, if body.isEmpty then -1 else body.length.toLong)
    if body.nonEmpty then exchange.getResponseBody.write(body)
    exchange.close()

  private def read[A](exchange: HttpExchange, kind: Class[A]): Option[A] =
    scala.util
      .Try(serialization.deserialize(exchange.getRequestBody.readAllBytes(), kind).get)
      .toOption

  def forward(exchange: HttpExchange): Unit =
    (exchange.getRequestMethod, target(exchange, "/replication/forward")) match
      case ("POST", Some((componentId, entityId))) =>
        read(exchange, classOf[LoopbackCall]) match
          case None       => respond(exchange, 400, "not a forwarded call".getBytes(UTF_8))
          case Some(call) =>
            val reply = ClusterSharding(system)
              .entityRefFor(EntityKeys.forComponent(componentId), entityId)
              .ask[EntityProtocol.Reply](replyTo =>
                EntityProtocol.Invoke(call.method, call.payload, call.metadata, replyTo)
              )(using Timeout(askTimeout))
            val answer =
              scala.util.Try(Await.result(reply, askTimeout + 1.second)) match
                case scala.util.Success(EntityProtocol.Succeeded(bytes, metadata)) =>
                  LoopbackAnswer(true, bytes, metadata, "", "")
                case scala.util.Success(rejected: EntityProtocol.Rejected) =>
                  LoopbackAnswer(
                    false,
                    Array.emptyByteArray,
                    Vector.empty,
                    rejected.message,
                    rejected.code
                  )
                case scala.util.Success(other) =>
                  LoopbackAnswer(
                    false,
                    Array.emptyByteArray,
                    Vector.empty,
                    s"unexpected reply $other",
                    ErrorCode.Internal.toString
                  )
                case scala.util.Failure(e) =>
                  LoopbackAnswer(
                    false,
                    Array.emptyByteArray,
                    Vector.empty,
                    e.getMessage,
                    ErrorCode.Timeout.toString
                  )
            respond(exchange, 200, serialization.serialize(answer).get)
      case ("POST", None) => respond(exchange, 404, Array.emptyByteArray)
      case _              => respond(exchange, 405, Array.emptyByteArray)

  def report(exchange: HttpExchange): Unit =
    (exchange.getRequestMethod, target(exchange, "/replication/report")) match
      case ("POST", Some((componentId, entityId))) =>
        read(exchange, classOf[LoopbackReport]) match
          case None         => respond(exchange, 400, "not a report".getBytes(UTF_8))
          case Some(report) =>
            ReplicationPeer
              .reported(system, componentId, entityId, report.installation, report.position)
              .value match
              case Some(scala.util.Failure(e)) =>
                respond(exchange, 404, e.getMessage.getBytes(UTF_8))
              case _ => respond(exchange, 204, Array.emptyByteArray)
      case ("POST", None) => respond(exchange, 404, Array.emptyByteArray)
      case _              => respond(exchange, 405, Array.emptyByteArray)

/**
 * Reaches a sibling running beside this service on this machine rather than in its JVM: through the
 * routes its local console endpoint serves.
 */
trait LoopbackReplication:
  def forward(
      installation: String,
      componentId: ComponentId,
      entityId: EntityId,
      call: ForwardedCall
  ): Future[ForwardedReply]

  def report(
      installation: String,
      componentId: ComponentId,
      entityId: EntityId,
      from: String,
      position: Long
  ): Future[Done]

object LoopbackReplication:
  /** No sibling outside this JVM can be reached. */
  val none: LoopbackReplication = new LoopbackReplication:
    private def unreachable(installation: String) =
      Future.failed(
        IllegalStateException(s"installation '$installation' is not running in this JVM")
      )
    def forward(
        installation: String,
        componentId: ComponentId,
        entityId: EntityId,
        call: ForwardedCall
    ): Future[ForwardedReply] = unreachable(installation)
    def report(
        installation: String,
        componentId: ComponentId,
        entityId: EntityId,
        from: String,
        position: Long
    ): Future[Done] = unreachable(installation)

  /**
   * Reaches a sibling running beside this service on this machine through the routes its local
   * console endpoint serves, found where every locally run service announces itself.
   */
  def local(system: ActorSystem[?]): LoopbackReplication = new LoopbackReplication:
    private given ExecutionContext = system.executionContext
    private val serialization      = SerializationExtension(system.classicSystem)
    private val http               = HttpClient.newHttpClient()
    private val askTimeout         = FiniteDuration(
      system.settings.config.getDuration("ankka.ask-timeout").toMillis,
      MILLISECONDS
    )

    private def post(
        installation: String,
        route: String,
        componentId: ComponentId,
        entityId: EntityId,
        body: AnyRef
    ): Future[HttpResponse[Array[Byte]]] =
      ServiceRegistration.observabilityAddressOfInstallation(installation) match
        case None =>
          Future.failed(
            IllegalStateException(s"installation '$installation' is not running on this machine")
          )
        case Some(address) =>
          def enc(s: String) = java.net.URLEncoder.encode(s, UTF_8)
          val request        = HttpRequest
            .newBuilder(
              URI(
                s"$address/replication/$route/${enc(componentId.toString)}/${enc(entityId.toString)}"
              )
            )
            .timeout(java.time.Duration.ofMillis((askTimeout + 2.seconds).toMillis))
            .POST(HttpRequest.BodyPublishers.ofByteArray(serialization.serialize(body).get))
            .build()
          http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).asScala

    def forward(
        installation: String,
        componentId: ComponentId,
        entityId: EntityId,
        call: ForwardedCall
    ): Future[ForwardedReply] =
      post(
        installation,
        "forward",
        componentId,
        entityId,
        LoopbackCall(call.method, call.payload, MetaEntry.from(call.metadata))
      ).flatMap { response =>
        if response.statusCode != 200 then
          Future.failed(IllegalStateException(s"'$installation' answered ${response.statusCode}"))
        else
          val answer = serialization.deserialize(response.body, classOf[LoopbackAnswer]).get
          if answer.succeeded then
            Future.successful(
              ForwardedReply.Succeeded(answer.payload, MetaEntry.toMetadata(answer.metadata))
            )
          else if answer.code == ErrorCode.Timeout.toString then
            Future.failed(java.util.concurrent.TimeoutException(answer.message))
          else
            Future.successful(
              ForwardedReply.Rejected(
                CommandError(
                  answer.message,
                  ErrorCode.values.find(_.toString == answer.code).getOrElse(ErrorCode.Internal)
                )
              )
            )
      }

    def report(
        installation: String,
        componentId: ComponentId,
        entityId: EntityId,
        from: String,
        position: Long
    ): Future[Done] =
      post(installation, "report", componentId, entityId, LoopbackReport(from, position))
        .map(_ => Done)
