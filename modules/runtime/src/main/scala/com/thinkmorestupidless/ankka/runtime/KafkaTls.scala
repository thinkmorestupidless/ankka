package com.thinkmorestupidless.ankka.runtime

import org.apache.kafka.common.security.auth.SslEngineFactory

import java.nio.file.Paths
import java.security.KeyStore
import java.util
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLEngine
import scala.concurrent.duration.{DurationInt, DurationLong, FiniteDuration}

/**
 * TLS to a broker with the certificate the service already holds, in the directory cert-manager
 * writes it to, followed as it is renewed.
 *
 * Kafka's own TLS reads a keystore once, when a client is made, and a service certificate is
 * renewed every few hours, so a client that lived a day would present an expired certificate. Kafka
 * asks this factory for an engine for every connection it opens instead, and each comes from
 * `RotatingTls`, which re-reads the files when they change: a renewal is used by the next
 * connection, with nothing restarted.
 *
 * Named to a client by `ssl.engine.factory.class`, with the directory in `ankka.tls.directory`. The
 * client checks that the broker's certificate names the host it connected to, and the broker knows
 * the service by its certificate's common name.
 */
final class KafkaTls extends SslEngineFactory:

  @volatile private var tls: RotatingTls = null

  override def configure(configs: util.Map[String, ?]): Unit =
    val directory = Option(configs.get(KafkaTls.DirectoryConfig))
      .map(_.toString.trim)
      .filter(_.nonEmpty)
      .getOrElse(
        throw IllegalArgumentException(
          s"${KafkaTls.DirectoryConfig} must name the directory holding tls.key, tls.crt and ca.crt"
        )
      )
    val interval = Option(configs.get(KafkaTls.ReloadConfig))
      .flatMap(v => v.toString.trim.toLongOption)
      .map(_.millis)
      .getOrElse(KafkaTls.ReloadInterval)
    tls = KafkaTls.shared(directory, interval)

  override def createClientSslEngine(
      peerHost: String,
      peerPort: Int,
      endpointIdentification: String
  ): SSLEngine = tls.clientEngine(peerHost, peerPort)

  override def createServerSslEngine(peerHost: String, peerPort: Int): SSLEngine =
    throw UnsupportedOperationException("a service's Kafka clients never accept connections")

  // Nothing in a client's configuration changes what this factory does: the files do, and
  // `RotatingTls` follows them itself.
  override def shouldBeRebuilt(nextConfigs: util.Map[String, AnyRef]): Boolean = false

  override def reconfigurableConfigs(): util.Set[String] = util.Set.of()

  override def keystore(): KeyStore   = null
  override def truststore(): KeyStore = null

  override def close(): Unit = ()

object KafkaTls:

  /** The client configuration that names the certificate's directory. */
  val DirectoryConfig: String = "ankka.tls.directory"

  /** How often a directory is checked for a renewed certificate, in milliseconds; optional. */
  val ReloadConfig: String = "ankka.tls.reload-interval-ms"

  private val ReloadInterval = 30.seconds

  // One `RotatingTls` per directory for the life of the process: Kafka makes a factory per client,
  // and every client of a service presents the same certificate.
  private val byDirectory = new ConcurrentHashMap[String, RotatingTls]()

  private def shared(directory: String, interval: FiniteDuration): RotatingTls =
    byDirectory.computeIfAbsent(directory, d => RotatingTls(Paths.get(d), interval))

  /** The properties that make a Kafka client use this factory for `directory`. */
  def clientProperties(directory: String): Map[String, String] = Map(
    "security.protocol"        -> "SSL",
    "ssl.engine.factory.class" -> classOf[KafkaTls].getName,
    DirectoryConfig            -> directory
  )
