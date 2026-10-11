package com.thinkmorestupidless.ankka.runtime

import com.typesafe.config.Config
import io.netty.handler.ssl.SslContextBuilder
import io.r2dbc.postgresql.PostgresqlConnectionFactoryProvider
import io.r2dbc.postgresql.client.SSLMode
import io.r2dbc.spi.ConnectionFactoryOptions
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.persistence.r2dbc.ConnectionFactoryProvider.ConnectionFactoryOptionsCustomizer
import org.apache.pekko.pki.pem.{DERPrivateKeyLoader, PEMDecoder}

import java.io.ByteArrayInputStream
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.security.cert.{CertificateFactory, X509Certificate}
import java.security.{KeyStore, Principal, PrivateKey}
import java.util.function.Function as JFunction
import javax.net.ssl.{KeyManagerFactory, SSLEngine, X509ExtendedKeyManager}
import scala.jdk.CollectionConverters.*

/**
 * TLS to Postgres, and a client certificate instead of a password (feature 014).
 *
 * Plugged into pekko-persistence-r2dbc through its own `connection-factory-options-customizer`, so
 * the journal, the projections and ankka's own queries share one pool and one TLS setup. The
 * plugin's configuration knows a mode and a root certificate and nothing about a client
 * certificate; the Postgres driver beneath it does, and this is where ankka tells it.
 *
 * Configured by `ANKKA_DB_SSL_MODE`, `ANKKA_DB_SSL_ROOT_CERT`, `ANKKA_DB_SSL_CERT` and
 * `ANKKA_DB_SSL_KEY` — the operator sets them for a provisioned database, pointing at the files the
 * kubelet mounts, and a service that supplies its own database may set them itself. With no mode
 * set this changes nothing.
 *
 * The client key is read at handshake time, through a key manager that re-reads the files when they
 * change: the driver builds its TLS context once, and a certificate renewed every eight hours must
 * still reach the next connection a pool opens, without a restart.
 */
final class DatabaseTls(system: ActorSystem[?]) extends ConnectionFactoryOptionsCustomizer:

  override def apply(
      builder: ConnectionFactoryOptions.Builder,
      config: Config
  ): ConnectionFactoryOptions.Builder =
    // The plugin hands over the whole configuration, not the connection factory's block: read the
    // block the plugin is using, and fall back to the argument for a caller that passes the block.
    val location = "pekko.persistence.r2dbc.use-connection-factory"
    val block =
      if config.hasPath(location) && config.hasPath(config.getString(location)) then
        config.getConfig(config.getString(location))
      else config
    // A connection to a primary that was lost without closing its sockets is found dead by the
    // operating system, not left to hang a caller until the pool's own timeout (research R18).
    builder.option(PostgresqlConnectionFactoryProvider.TCP_KEEPALIVE, java.lang.Boolean.TRUE): Unit
    DatabaseTls.settings(block) match
      case None => builder
      case Some(settings) =>
        system.log.info(
          "database connections use TLS ({}{})",
          settings.mode,
          if settings.clientCertificate.isDefined then ", authenticated by certificate" else ""
        )
        builder
          .option(ConnectionFactoryOptions.SSL, java.lang.Boolean.TRUE)
          .option(PostgresqlConnectionFactoryProvider.SSL_MODE, SSLMode.fromValue(settings.mode))
        settings.rootCert.foreach(root =>
          builder.option(PostgresqlConnectionFactoryProvider.SSL_ROOT_CERT, root): Unit
        )
        settings.clientCertificate.foreach { (cert, key) =>
          val keys = DatabaseTls.RotatingKeyManager(Paths.get(cert), Paths.get(key))
          builder.option(
            PostgresqlConnectionFactoryProvider.SSL_CONTEXT_BUILDER_CUSTOMIZER,
            new JFunction[SslContextBuilder, SslContextBuilder]:
              def apply(b: SslContextBuilder): SslContextBuilder = b.keyManager(keys)
          ): Unit
        }
        builder

object DatabaseTls:

  final case class Settings(
      mode: String,
      rootCert: Option[String],
      clientCertificate: Option[(String, String)]
  )

  /**
   * `ssl.mode` (and friends) inside the connection factory's own block; none when the mode is
   * empty.
   */
  def settings(config: Config): Option[Settings] =
    def opt(key: String) =
      Option.when(config.hasPath(key))(config.getString(key)).map(_.trim).filter(_.nonEmpty)
    opt("ssl.mode").map { mode =>
      Settings(
        mode,
        opt("ssl.root-cert"),
        for cert <- opt("ssl.cert"); key <- opt("ssl.key") yield (cert, key)
      )
    }

  /**
   * One client identity, re-read when its files change — checked at most once a second, at the
   * moment a handshake asks for it. A failed reload keeps the previous identity, as `RotatingTls`
   * does.
   */
  final class RotatingKeyManager(certFile: Path, keyFile: Path) extends X509ExtendedKeyManager:

    private final case class Loaded(delegate: X509ExtendedKeyManager, stamp: Vector[Long], at: Long)

    @volatile private var loaded: Loaded = load()

    private def stamp: Vector[Long] =
      Vector(certFile, keyFile).map(f => Files.getLastModifiedTime(f.toRealPath()).toMillis)

    private def load(): Loaded =
      val key: PrivateKey = DERPrivateKeyLoader.load(
        PEMDecoder.decode(Files.readString(keyFile, StandardCharsets.US_ASCII))
      )
      val chain = CertificateFactory
        .getInstance("X.509")
        .generateCertificates(ByteArrayInputStream(Files.readAllBytes(certFile)))
        .asScala
        .collect { case c: X509Certificate => c: java.security.cert.Certificate }
        .toArray[java.security.cert.Certificate]
      val store = KeyStore.getInstance("PKCS12")
      store.load(null, null)
      store.setKeyEntry("client", key, Array.emptyCharArray, chain)
      val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
      factory.init(store, Array.emptyCharArray)
      val manager = factory.getKeyManagers.collectFirst { case m: X509ExtendedKeyManager => m }.get
      Loaded(manager, stamp, System.nanoTime())

    private def current: X509ExtendedKeyManager =
      val now = loaded
      if System.nanoTime() - now.at < 1_000_000_000L then now.delegate
      else
        val next =
          try if stamp != now.stamp then load() else now.copy(at = System.nanoTime())
          catch case _: Exception => now.copy(at = System.nanoTime())
        loaded = next
        next.delegate

    override def getClientAliases(keyType: String, issuers: Array[Principal]): Array[String] =
      current.getClientAliases(keyType, issuers)
    override def chooseClientAlias(
        keyType: Array[String],
        issuers: Array[Principal],
        socket: Socket
    ): String =
      current.chooseClientAlias(keyType, issuers, socket)
    override def chooseEngineClientAlias(
        keyType: Array[String],
        issuers: Array[Principal],
        engine: SSLEngine
    ): String =
      current.chooseEngineClientAlias(keyType, issuers, engine)
    override def getServerAliases(keyType: String, issuers: Array[Principal]): Array[String] =
      current.getServerAliases(keyType, issuers)
    override def chooseServerAlias(
        keyType: String,
        issuers: Array[Principal],
        socket: Socket
    ): String =
      current.chooseServerAlias(keyType, issuers, socket)
    override def getCertificateChain(alias: String): Array[X509Certificate] =
      current.getCertificateChain(alias)
    override def getPrivateKey(alias: String): PrivateKey = current.getPrivateKey(alias)
