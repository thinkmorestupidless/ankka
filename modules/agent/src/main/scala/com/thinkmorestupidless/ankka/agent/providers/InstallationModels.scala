package com.thinkmorestupidless.ankka.agent.providers

import com.thinkmorestupidless.ankka.agent.openai.OpenAiProvider
import com.thinkmorestupidless.ankka.agent.{
  Json,
  ModelCallFailed,
  ModelChunk,
  ModelProvider,
  ModelRequest,
  ModelResponse
}
import com.thinkmorestupidless.ankka.runtime.RotatingTls
import com.typesafe.config.Config
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.Paths
import java.security.SecureRandom
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.FutureConverters.*
import scala.util.control.NonFatal

/**
 * Models the installation's model server serves, reached as a service of the installation is.
 *
 * The door is mutual TLS: a call presents the service's own certificate, and accepts a server only
 * when its certificate carries the model server's identity, so nothing else listening at the
 * address is ever sent a prompt. No key is sent. Before a model is called the server is asked what
 * it can do, once per model, and a call that needs what it cannot do fails there, naming the model
 * and the capability, as a provider's failure.
 */
object InstallationModels:

  /** The identity the model server's certificate carries. */
  val ServerIdentity: String = "ankka://platform/models"

  private given ExecutionContext = ExecutionContext.parasitic

  private val ServiceDirectoryKey = "ankka.tls.service-directory"
  private val ReloadIntervalKey   = "ankka.tls.reload-interval"

  /**
   * The service's certificate from a loaded configuration, or why there is none. Only a service on
   * the platform has one.
   */
  def serviceTls(config: Config): Either[String, RotatingTls] =
    val directory =
      if config.hasPath(ServiceDirectoryKey) then config.getString(ServiceDirectoryKey) else ""
    if directory.isEmpty then
      Left(
        "an installation model is reached with the service's certificate, and this service has " +
          s"none: $ServiceDirectoryKey is not set"
      )
    else
      val interval =
        if config.hasPath(ReloadIntervalKey) then
          FiniteDuration(config.getDuration(ReloadIntervalKey).toMillis, "ms")
        else FiniteDuration(1, "min")
      try Right(RotatingTls(Paths.get(directory), interval))
      catch case NonFatal(e) => Left(s"the service's certificate cannot be read: ${e.getMessage}")

  /**
   * A client context presenting the service's certificate and accepting only the model server's.
   * Built from the rotating managers, so a renewed certificate is presented on the next handshake
   * without the provider being built again.
   */
  def clientContext(tls: RotatingTls): (SSLContext, OpenAiProvider.ClientTls) =
    val trust   = tls.trustManagerRequiring(ServerIdentity)
    val context = SSLContext.getInstance("TLS")
    context.init(Array(tls.keyManager), Array(trust), SecureRandom())
    (context, OpenAiProvider.ClientTls(context.getSocketFactory, trust))

  /** A provider for a resolved installation model, through `tls`. */
  def provider(config: ResolvedConfig, tls: RotatingTls): ModelProvider =
    val (context, clientTls) = clientContext(tls)
    val inner = OpenAiProvider.at(
      config.name,
      config.url,
      config.model,
      None,
      shape = config.shape,
      quirks = config.quirks,
      tls = Some(clientTls)
    )
    Checked(inner, Capabilities(config.url.stripSuffix("/v1"), context))

  /** What each model can do, as the server says through `POST /api/show`; kept once known. */
  final class Capabilities(server: String, context: SSLContext):
    private val client =
      HttpClient.newBuilder().sslContext(context).connectTimeout(Duration.ofSeconds(10)).build()
    private val known = new ConcurrentHashMap[String, Set[String]]()

    /** The model's capabilities; a failure to ask is not kept, so the next call asks again. */
    def of(model: String): Future[Set[String]] =
      Option(known.get(model)) match
        case Some(capabilities) => Future.successful(capabilities)
        case None =>
          val request = HttpRequest
            .newBuilder(URI.create(s"$server/api/show"))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(Json.obj("model" -> Json.Str(model)).render))
            .build()
          client
            .sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .asScala
            .map { response =>
              if response.statusCode() != 200 then
                throw IllegalStateException(
                  s"the model server answered ${response.statusCode()} asking about \"$model\""
                )
              val capabilities = Json
                .parse(response.body())
                .toOption
                .flatMap(_("capabilities"))
                .flatMap(_.asArray)
                .getOrElse(Vector.empty)
                .flatMap(_.asString)
                .toSet
              known.put(model, capabilities): Unit
              capabilities
            }

  /** Why `request` cannot be sent to `model`, given what it can do; none when it can be. */
  def lacking(model: String, capabilities: Set[String], request: ModelRequest): Option[String] =
    if request.tools.nonEmpty && !capabilities.contains("tools") then
      Some(s"installation model \"$model\" lacks tool calling")
    else if request.settings.effort.isDefined && !capabilities.contains("thinking") then
      Some(s"installation model \"$model\" lacks thinking")
    else None

  /** `inner`, once the model is known to do what each request needs. */
  private final class Checked(inner: ModelProvider, capabilities: Capabilities)
      extends ModelProvider:
    def name: String      = inner.name
    def modelName: String = inner.modelName

    private def checked(request: ModelRequest): Future[Unit] =
      val model = request.settings.modelName
      capabilities
        .of(model)
        .transform(
          known => lacking(model, known, request).foreach(why => throw ModelCallFailed(name, why)),
          {
            case failed: ModelCallFailed => failed
            case other =>
              ModelCallFailed(name, s"could not ask the model server about \"$model\": $other")
          }
        )

    def complete(request: ModelRequest): Future[ModelResponse] =
      checked(request).flatMap(_ => inner.complete(request))

    override def stream(request: ModelRequest): Source[ModelChunk, NotUsed] =
      Source
        .futureSource(checked(request).map(_ => inner.stream(request)))
        .mapMaterializedValue(_ => NotUsed)
