package com.thinkmorestupidless.ankka.controlplane.erasure

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.thinkmorestupidless.ankka.controlplane.deploy.InstanceTopologies
import com.thinkmorestupidless.ankka.core.PlatformVariables
import com.thinkmorestupidless.ankka.core.keyring.{ErasureDestroyed, ErasureOrder, ErasureStatus}
import com.thinkmorestupidless.ankka.runtime.{AnkkaExecutors, RotatingTls}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Path, Paths}
import java.time.Duration
import scala.concurrent.Future
import scala.concurrent.duration.*

/**
 * What the control plane asks of the installation's keyring (feature 042,
 * `contracts/keyring-and-protocol.md`): destroy a data subject's key for a log entry, send a notice
 * again so every service's duties and handler run again, and say which services have completed. The
 * control plane's identity may call nothing else; it never reads a key.
 */
trait KeyringAdmin:
  def destroy(order: ErasureOrder): Future[ErasureDestroyed]
  def resend(seq: Long): Future[Unit]
  def status(seq: Long): Future[ErasureStatus]

object KeyringAdmin:

  /** The keyring's identity, which its certificate must carry. */
  val ServerIdentity: String = "ankka://platform/keyring"

  /** An installation with erasure off: every call refused, naming the setting. */
  val off: KeyringAdmin = new KeyringAdmin:
    private def refused[A] = Future.failed[A](
      IllegalStateException(s"erasure is off on this installation (${PlatformVariables.Erasure})")
    )
    def destroy(order: ErasureOrder): Future[ErasureDestroyed] = refused
    def resend(seq: Long): Future[Unit]                        = refused
    def status(seq: Long): Future[ErasureStatus]               = refused

  /** From the control plane's environment and its service certificate's mount. */
  def fromEnvironment(env: String => Option[String] = sys.env.get): KeyringAdmin =
    env(PlatformVariables.KeyringAddress).map(_.trim).filter(_.nonEmpty) match
      case None          => off
      case Some(address) =>
        val config    = com.thinkmorestupidless.ankka.runtime.ClusterConfig.load()
        val directory =
          Option
            .when(config.hasPath(InstanceTopologies.ServiceDirectoryKey))(
              config.getString(InstanceTopologies.ServiceDirectoryKey)
            )
            .filter(_.nonEmpty)
            .map(Paths.get(_))
        directory.fold(off)(Door(address, _))

  // The keyring's own definition of every message, so the two ends cannot disagree.
  import com.thinkmorestupidless.ankka.core.keyring.KeyringWire.given

  /** Over mutual TLS as the control plane, to the keyring at `address`. */
  final class Door(address: String, directory: Path, timeout: FiniteDuration = 10.seconds)
      extends KeyringAdmin:

    private lazy val tls = RotatingTls(directory, 1.minute)

    private def call(request: HttpRequest.Builder): Future[String] =
      Future {
        val http = HttpClient
          .newBuilder()
          .sslContext(tls.contextRequiring(ServerIdentity))
          .connectTimeout(Duration.ofMillis(timeout.toMillis))
          .build()
        val response = http.send(
          request.timeout(Duration.ofMillis(timeout.toMillis)).build(),
          HttpResponse.BodyHandlers.ofString()
        )
        if response.statusCode / 100 != 2 then
          throw IllegalStateException(
            s"the keyring answered ${response.statusCode}: ${response.body.take(300)}"
          )
        response.body
      }(using AnkkaExecutors.virtual)

    private def at(path: String) =
      HttpRequest.newBuilder(URI.create(address.stripSuffix("/") + path))

    def destroy(order: ErasureOrder): Future[ErasureDestroyed] =
      call(
        at("/v1/erasures")
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofByteArray(writeToArray(order)))
      ).map(body => readFromString[ErasureDestroyed](body))(using AnkkaExecutors.virtual)

    def resend(seq: Long): Future[Unit] =
      call(at(s"/v1/erasures/$seq/resend").POST(HttpRequest.BodyPublishers.noBody()))
        .map(_ => ())(using AnkkaExecutors.virtual)

    def status(seq: Long): Future[ErasureStatus] =
      call(at(s"/v1/erasures/$seq").GET())
        .map(body => readFromString[ErasureStatus](body))(using AnkkaExecutors.virtual)
