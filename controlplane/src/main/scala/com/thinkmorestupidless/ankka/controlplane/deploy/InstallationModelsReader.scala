package com.thinkmorestupidless.ankka.controlplane.deploy

import com.thinkmorestupidless.ankka.controlplane.api.{ClusterModels, InstallationModels, ModelLine}
import com.thinkmorestupidless.ankka.core.{ModelServerDocumentJson, PlatformVariables}
import com.thinkmorestupidless.ankka.runtime.RotatingTls

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Path, Paths}
import java.time.Duration
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/**
 * What `GET /installation` says about the model server (feature 053), read live through its door
 * with the control plane's own certificate, which the keeper admits as a service of the platform.
 * Nothing is journaled: the server is the authority on what it serves.
 *
 * `None` when the control plane has no `ANKKA_MODELS_ADDRESS`: the installation has no model
 * server. A read that fails is the cluster with `ready: false` and the reason, never an error: the
 * rest of the installation's status is still true.
 */
trait InstallationModelsReader:
  def read(): Option[InstallationModels]

object InstallationModelsReader:

  /** The one cluster, until feature 050's remote clusters report theirs. */
  val Home: String = "home"

  /** The model server's identity, which the door's certificate must carry. */
  val ServerIdentity: String = "ankka://platform/models"

  val none: InstallationModelsReader = () => None

  /** From the control plane's environment and its service certificate's mount. */
  def fromEnvironment(env: String => Option[String] = sys.env.get): InstallationModelsReader =
    env(PlatformVariables.ModelsAddress).map(_.trim).filter(_.nonEmpty) match
      case None => none
      case Some(address) =>
        val config = com.thinkmorestupidless.ankka.runtime.ClusterConfig.load()
        val directory =
          Option
            .when(config.hasPath(InstanceTopologies.ServiceDirectoryKey))(
              config.getString(InstanceTopologies.ServiceDirectoryKey)
            )
            .filter(_.nonEmpty)
            .map(Paths.get(_))
        Door(address, directory)

  /** Reads `<address>/ankka/models` over mutual TLS from the certificate in `directory`. */
  final class Door(address: String, directory: Option[Path], timeout: FiniteDuration = 5.seconds)
      extends InstallationModelsReader:

    private lazy val tls = directory.map(d => RotatingTls(d, 1.minute))

    def read(): Option[InstallationModels] =
      Some(InstallationModels(Vector(cluster())))

    private def unread(why: String) =
      ClusterModels(Home, address, ready = false, detail = Some(why))

    private def cluster(): ClusterModels =
      tls match
        case None =>
          unread("the control plane has no service certificate to read the model server with")
        case Some(rotating) =>
          try
            val http = HttpClient
              .newBuilder()
              .sslContext(rotating.contextRequiring(ServerIdentity))
              .connectTimeout(Duration.ofMillis(timeout.toMillis))
              .build()
            val response = http.send(
              HttpRequest
                .newBuilder(URI.create(address.stripSuffix("/") + "/ankka/models"))
                .timeout(Duration.ofMillis(timeout.toMillis))
                .GET()
                .build(),
              HttpResponse.BodyHandlers.ofString()
            )
            if response.statusCode != 200 then
              unread(s"the model server answered ${response.statusCode}")
            else
              ModelServerDocumentJson.read(response.body) match
                case Left(why) => unread(why)
                case Right(document) =>
                  ClusterModels(
                    cluster = Home,
                    server = document.server,
                    ready = document.ready,
                    models =
                      document.models.map(m => ModelLine(m.name, m.state, m.digest, m.detail)),
                    detail = document.detail
                  )
          catch
            case _: InterruptedException =>
              Thread.currentThread().interrupt()
              unread("interrupted reading the model server")
            case NonFatal(e) => unread(s"the model server is not answering: ${e.getMessage}")
