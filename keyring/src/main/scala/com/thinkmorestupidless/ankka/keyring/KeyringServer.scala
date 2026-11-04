package com.thinkmorestupidless.ankka.keyring

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromString, writeToString}
import com.thinkmorestupidless.ankka.core.keyring.*
import com.thinkmorestupidless.ankka.core.keyring.KeyringWire.given
import com.thinkmorestupidless.ankka.http.{Caller, MachineTokens}
import com.thinkmorestupidless.ankka.runtime.RotatingTls
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.{ConnectionContext, Http}
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.model.headers.{
  Authorization,
  OAuth2BearerToken,
  `Tls-Session-Info`
}
import org.apache.pekko.http.scaladsl.model.ws.{Message, TextMessage}
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.{Directive1, Route}
import org.apache.pekko.http.scaladsl.settings.ServerSettings
import org.apache.pekko.stream.scaladsl.{Flow, Sink, Source}
import org.slf4j.LoggerFactory

import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.util.{Failure, Success}

/**
 * The keyring's listeners (feature 042, `contracts/keyring-and-protocol.md`): the routes and the
 * notices WebSocket over mutual TLS as `ankka://platform/keyring`, and a plain probe port with
 * `/ready` (held until the erasure log is replayed) and `/status` (what the replay found).
 */
final class KeyringServer(
    service: KeyringService,
    replay: ErasureLogReplay,
    tls: RotatingTls,
    port: Int,
    probePort: Int,
    machines: Option[MachineTokens] = None,
    host: String = "0.0.0.0"
)(using system: ActorSystem[?]):

  import KeyringServer.*

  private given ExecutionContext = system.executionContext
  private val log                = LoggerFactory.getLogger(classOf[KeyringServer])
  private val bound              = AtomicReference[Vector[Http.ServerBinding]](Vector.empty)
  private val timers = AtomicReference[Vector[org.apache.pekko.actor.Cancellable]](Vector.empty)

  def start(): Unit =
    val settings = ServerSettings(system)
    val main     = Http()
      .newServerAt(host, port)
      .withSettings(
        settings.withParserSettings(settings.parserSettings.withIncludeTlsSessionInfoHeader(true))
      )
      .enableHttps(ConnectionContext.httpsServer(() => tls.serverEngine()))
      .bind(routes)
    val probe = Http().newServerAt(host, probePort).bind(probeRoutes)
    bound.set(Vector(Await.result(main, 30.seconds), Await.result(probe, 30.seconds)))
    timers.set(
      Vector(
        system.scheduler.scheduleAtFixedRate(Notices.PollEvery, Notices.PollEvery)(() =>
          service.notices
            .poll()
            .failed
            .foreach(e => log.warn(s"the outbox was not read: ${e.getMessage}"))
        ),
        system.scheduler.scheduleAtFixedRate(Notices.HeartbeatEvery, Notices.HeartbeatEvery)(() =>
          service.notices
            .heartbeat()
            .failed
            .foreach(e => log.warn(s"a heartbeat was not recorded: ${e.getMessage}"))
        )
      )
    )
    log.info(s"the keyring is on port ${ports.main}, its probe on ${ports.probe}")

  def ports: Ports = bound.get match
    case Vector(main, probe) => Ports(main.localAddress.getPort, probe.localAddress.getPort)
    case _                   => throw IllegalStateException("the keyring is not listening")

  def stop(): Unit =
    timers.getAndSet(Vector.empty).foreach(_.cancel(): Unit)
    bound.getAndSet(Vector.empty).foreach(b => Await.result(b.unbind(), 10.seconds): Unit)

  /** The caller: a machine's bearer token through the gateway, otherwise the certificate's. */
  private val who: Directive1[Admission.Who] =
    (optionalHeaderValueByType(`Tls-Session-Info`) & optionalHeaderValueByType(Authorization))
      .tmap { (session, authorization) =>
        val certificate = session.toVector
          .flatMap(_.peerCertificates)
          .collectFirst { case c: X509Certificate => c }
        val bearer = authorization.collect { case Authorization(OAuth2BearerToken(t)) => t }
        certificate.map(Admission.Who.fromCertificate) match
          case None => Admission.Who.Unknown("no client certificate")
          case Some(Admission.Who.Service(Caller.Gateway)) =>
            (machines, bearer) match
              case (Some(tokens), Some(token)) => Admission.Who.fromBearer(tokens, token)
              case _ => Admission.Who.Unknown("the gateway, with no machine's token")
          case Some(other) => other
      }

  private def body[A: JsonValueCodec]: Directive1[A] =
    entity(as[String]).flatMap(text =>
      Wire.read[A](text) match
        case Right(a)  => provide(a)
        case Left(why) => complete(json(StatusCodes.BadRequest, s"""{"error":${quote(why)}}"""))
    )

  private def answer[A: JsonValueCodec](f: Future[KeyringService.Answer[A]]): Route =
    onComplete(f) {
      case Success(KeyringService.Answer.Ok(a)) => complete(json(StatusCodes.OK, writeToString(a)))
      case Success(KeyringService.Answer.Accepted)     => complete(StatusCodes.Accepted)
      case Success(KeyringService.Answer.Refused(why)) =>
        complete(
          json(
            StatusCodes.Forbidden,
            writeToString(KeyReply(refused = Some(why)))
          )
        )
      case Success(KeyringService.Answer.Invalid(why)) =>
        complete(json(StatusCodes.BadRequest, s"""{"error":${quote(why)}}"""))
      case Success(KeyringService.Answer.Missing(why)) =>
        complete(json(StatusCodes.NotFound, s"""{"error":${quote(why)}}"""))
      case Success(KeyringService.Answer.Unavailable(why)) =>
        complete(json(StatusCodes.ServiceUnavailable, s"""{"error":${quote(why)}}"""))
      case Failure(e) =>
        log.error("a keyring request failed", e)
        complete(
          json(StatusCodes.InternalServerError, """{"error":"the keyring failed to answer"}""")
        )
    }

  /** An answer with no body: a resend. */
  private val unitCodec: JsonValueCodec[Unit] = new JsonValueCodec[Unit]:
    def nullValue: Unit                                                                        = ()
    def encodeValue(x: Unit, out: com.github.plokhotnyuk.jsoniter_scala.core.JsonWriter): Unit =
      out.writeObjectStart()
      out.writeObjectEnd()
    def decodeValue(
        in: com.github.plokhotnyuk.jsoniter_scala.core.JsonReader,
        default: Unit
    ): Unit =
      in.skip()

  val routes: Route =
    who { caller =>
      pathPrefix("v1") {
        concat(
          (post & path("keys"))(
            body[KeyRequest](r => answer(service.key(caller, r)))
          ),
          (post & path("lookup-keys"))(
            body[LookupKeyRequest](r => answer(service.lookupKey(caller, r)))
          ),
          (get & path("notices"))(handleWebSocketMessages(noticesOf(caller))),
          (post & path("erasures"))(body[ErasureOrder](o => answer(service.destroy(caller, o)))),
          (post & path("erasures" / LongNumber / "resend"))(seq =>
            answer(service.resend(caller, seq))(using unitCodec)
          ),
          (get & path("erasures" / LongNumber))(seq => answer(service.status(caller, seq))),
          (post & path("decrypt"))(body[DecryptRequest](r => answer(service.decrypt(caller, r))))
        )
      }
    }

  /**
   * One instance's notices: its first frame is its hello, admitted for its project as the service
   * its certificate names; then every notice is sent as text and every ack read. The socket's end
   * is the channel's end.
   */
  private def noticesOf(caller: Admission.Who): Flow[Message, Message, Any] =
    val (queue, out)                   = Source.queue[Message](256).preMaterialize()
    val connection                     = AtomicReference[Option[Notices#Connection]](None)
    def send(frame: NoticeFrame): Unit =
      queue.offer(TextMessage.Strict(Wire.write(frame))): Unit
    val in = Flow[Message]
      .mapAsync(1) {
        case t: TextMessage => t.toStrict(10.seconds).map(_.text)
        case b: org.apache.pekko.http.scaladsl.model.ws.BinaryMessage =>
          b.dataStream.runWith(Sink.ignore).map(_ => "")
      }
      .mapAsync(1) { text =>
        (Wire.frame(text), connection.get) match
          case (Right(hello: NoticeFrame.Hello), None) =>
            service.mayListen(caller, hello).flatMap {
              case Left(why) =>
                log.info(s"the keyring refused ${caller.name}'s notices: $why")
                queue.complete()
                Future.unit
              case Right(()) =>
                service.notices.connect(hello, send).map(c => connection.set(Some(c)))
            }
          case (Right(frame), Some(c)) =>
            c.received(frame).recover { case e =>
              log.warn(s"a frame from ${c.hello.instance} was refused: ${e.getMessage}")
            }
          case (Right(_), None) =>
            log.info(s"${caller.name} sent a frame before its hello")
            queue.complete()
            Future.unit
          case (Left(why), _) =>
            log.info(s"${caller.name} sent what is not a frame: $why")
            Future.unit
      }
      .to(Sink.onComplete { _ =>
        connection.get.foreach(_.close())
        queue.complete()
      })
    Flow.fromSinkAndSourceCoupled(in, out)

  val probeRoutes: Route =
    concat(
      path("ready")(
        if replay.done then complete(StatusCodes.OK)
        else
          complete(
            json(
              StatusCodes.ServiceUnavailable,
              """{"error":"the erasure log is still being replayed"}"""
            )
          )
      ),
      path("status")(complete(json(StatusCodes.OK, writeToString(replay.report))))
    )

object KeyringServer:

  final case class Ports(main: Int, probe: Int)

  private def json(status: StatusCode, body: String): HttpResponse =
    HttpResponse(status, entity = HttpEntity(ContentTypes.`application/json`, body))

  private def quote(text: String): String = writeToString(text)(using
    com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker.make[String]
  )

  /** `text` as an `A`, for a test that reads an answer. */
  def read[A: JsonValueCodec](text: String): A = readFromString[A](text)
