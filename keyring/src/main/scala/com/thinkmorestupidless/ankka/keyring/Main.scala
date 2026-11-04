package com.thinkmorestupidless.ankka.keyring

import com.thinkmorestupidless.ankka.http.MachineTokens
import com.thinkmorestupidless.ankka.runtime.{Database, DatabaseSecretStore, RotatingTls, SecretKey}
import com.thinkmorestupidless.ankka.runtime.storage.{ObjectStorages, StorageSettings}
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.slf4j.LoggerFactory

import java.net.http.HttpClient
import java.nio.file.{Files, Path, Paths}
import java.time.Duration
import java.util.concurrent.CountDownLatch
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

/**
 * The keyring's process (feature 042): reads its root key, replays the erasure log, then serves the
 * platform's programs over mutual TLS as `ankka://platform/keyring`.
 *
 * `run` returns the exit code, so a test can call it; `main` is the one-line wrapper. It refuses to
 * start naming every problem with its environment, and when its certificate is not the keyring's:
 * every service checks that the keyring it calls is the keyring.
 */
object Main:

  private val log = LoggerFactory.getLogger("ankka.keyring")

  /** The identity the keyring's certificate must carry. */
  val Identity: String = "ankka://platform/keyring"

  /** Where the pod mounts the certificate; a test points this at a directory of its own. */
  val ServiceDirectoryProperty: String = "ankka.keyring.service-directory"
  val DefaultServiceDirectory: String  = "/var/run/secrets/ankka/service"

  /** The platform bucket the control plane writes the log's second copy to. */
  val LogBucket: String = "platform.erasure-log"

  def main(args: Array[String]): Unit = sys.exit(run(args))

  def run(
      args: Array[String],
      env: String => Option[String] = sys.env.get,
      report: String => Unit = message => log.error(message),
      started: Keyring.Started => Unit = _ => ()
  ): Int =
    val _ = args
    KeyringSettings.fromEnvironment(env) match
      case Left(problems) =>
        problems.foreach(p => report(s"refusing to start: $p"))
        1
      case Right(settings) =>
        val directory = serviceDirectory
        if !Files.isRegularFile(directory.resolve("tls.crt")) then
          report(s"refusing to start: there is no certificate at $directory (tls.crt)")
          1
        else
          try
            val tls = RotatingTls(directory, 1.minute)
            identityMismatch(tls, directory) match
              case Some(problem) =>
                report(problem)
                1
              case None => serve(settings, tls, env, started)
          catch
            case error: Throwable =>
              report(s"the keyring failed to start: ${error.getMessage}")
              log.error("the keyring failed to start", error)
              1

  private def serve(
      settings: KeyringSettings,
      tls: RotatingTls,
      env: String => Option[String],
      started: Keyring.Started => Unit
  ): Int =
    val config = ConfigFactory
      .parseString("pekko.actor.provider = local")
      .withFallback(ConfigFactory.load())
    given system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "keyring", config)
    given ExecutionContext             = system.executionContext
    val database                       = Database()
    val http                           = HttpClient
      .newBuilder()
      .sslContext(tls.sslContext)
      .connectTimeout(Duration.ofSeconds(5))
      .build()
    val root = settings.root match
      case KeyringSettings.Root.Kms(name)      => RootKey.kms(name, RootKey.CloudKms(name))
      case KeyringSettings.Root.Postgres(text) =>
        val key = SecretKey.parse(text).fold(p => throw IllegalArgumentException(p), identity)
        RootKey.postgres(DatabaseSecretStore(database, Some(key)), database)
    val storage = StorageSettings
      .from(system.settings.config, sys.env, false)
      .flatMap(s => ObjectStorages.build(s, Right(None), () => ""))
      .fold(p => throw IllegalArgumentException(s"the platform bucket: $p"), identity)
    val grants = settings.grantsDirectory match
      case Some(dir)                                       => ProjectGrants.fromDirectory(dir)
      case None if env("KUBERNETES_SERVICE_HOST").nonEmpty => KubernetesGrants.inCluster()
      case None                                            => ProjectGrants.none
    val keyring = Keyring.start(
      database,
      root,
      grants,
      Vector(
        LogCopy.controlPlane(settings.controlPlane.toString, http),
        LogCopy.bucket(storage.storage.bucket(LogBucket))
      ),
      tls,
      settings.port,
      settings.probePort,
      settings.machines.map((issuer, jwks) =>
        MachineTokens.fetching(issuer, jwks, Some(serviceDirectory.resolve("ca.crt")))
      ),
      settings.replica
    )
    val stopped = new CountDownLatch(1)
    val stop    = () =>
      if stopped.getCount > 0 then
        log.info("stopping")
        keyring.stop()
        system.terminate()
        stopped.countDown()
    val hook = new Thread(() => stop())
    Runtime.getRuntime.addShutdownHook(hook)
    started(keyring.copy(stop = stop))
    try stopped.await()
    catch case _: InterruptedException => stop()
    try Runtime.getRuntime.removeShutdownHook(hook): Unit
    catch case _: IllegalStateException => ()
    0

  def serviceDirectory: Path =
    Paths.get(sys.props.getOrElse(ServiceDirectoryProperty, DefaultServiceDirectory))

  /** Why the certificate is not the keyring's, or nothing when it is. */
  def identityMismatch(tls: RotatingTls, directory: Path): Option[String] =
    val uris = RotatingTls.ankkaUris(tls.current)
    if uris.contains(Identity) then None
    else
      Some(
        s"refusing to start: the certificate at $directory names " +
          s"${if uris.isEmpty then "no ankka:// identity" else uris.mkString(", ")}, and the " +
          s"keyring's is $Identity"
      )
