package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.testpki.TestPki
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName

import java.nio.file.{Files, Path}
import java.nio.file.attribute.FileTime
import java.time.Instant
import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.util.Try

import SqlSyntax.sql

/**
 * The runtime's connection to Postgres over TLS with a client certificate and no password, against
 * a real Postgres whose `pg_hba` accepts nothing else — the shape a provisioned database has in a
 * cluster (feature 014). What CNPG does is `OperatorClusterSuite`'s; what the runtime does is this.
 */
class DatabaseTlsSuite extends munit.FunSuite:

  override val munitTimeout: Duration = 3.minutes

  private val serverAuthority = TestPki.root("database-server")
  private val clientAuthority = TestPki.root("database-client")
  private val Role            = "orders"

  private var postgres: TlsPostgres = scala.compiletime.uninitialized

  override def beforeAll(): Unit =
    val server = serverAuthority.issue(dnsNames = Seq("localhost"))
    val hba =
      """local all all trust
        |hostssl all all all cert clientcert=verify-full
        |""".stripMargin
    // The key must belong to the server's user and be private to it, which a copied-in file is not;
    // the entrypoint runs as root, so it fixes that before handing over.
    val prepare =
      "mkdir -p /tls && cp /seed/* /tls/ && chown postgres /tls/* && chmod 600 /tls/server.key && " +
        "exec docker-entrypoint.sh postgres -c ssl=on -c ssl_cert_file=/tls/server.crt " +
        "-c ssl_key_file=/tls/server.key -c ssl_ca_file=/tls/client-ca.crt -c hba_file=/tls/pg_hba.conf"
    val c = new TlsPostgres(DockerImageName.parse("postgres:17-alpine"))
    c.withEnv("POSTGRES_USER", Role)
      .withEnv("POSTGRES_DB", Role)
      .withEnv("POSTGRES_HOST_AUTH_METHOD", "trust")
      .withExposedPorts(5432)
      .withCopyToContainer(Transferable.of(server.certPem), "/seed/server.crt")
      .withCopyToContainer(Transferable.of(server.keyPem), "/seed/server.key")
      .withCopyToContainer(Transferable.of(clientAuthority.pem), "/seed/client-ca.crt")
      .withCopyToContainer(Transferable.of(hba), "/seed/pg_hba.conf")
      .withCommand("sh", "-c", prepare)
      .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2))
    c.start()
    postgres = c

  override def afterAll(): Unit = Option(postgres).foreach(_.stop())

  private def clientFiles(
      leaf: TestPki.Leaf,
      dir: Path = Files.createTempDirectory("db-client")
  ): Path =
    Files.writeString(dir.resolve("root.crt"), serverAuthority.pem)
    Files.writeString(dir.resolve("tls.crt"), leaf.certPem)
    Files.writeString(dir.resolve("tls.key"), leaf.keyPem)
    dir

  private def withDatabase[A](files: Path, mode: String = "verify-full", password: String = "")(
      body: Database => A
  ): A =
    val config = ConfigFactory
      .parseString(s"""
        |pekko.persistence.r2dbc.connection-factory {
        |  host = "localhost"
        |  port = ${postgres.getMappedPort(5432)}
        |  database = "$Role"
        |  user = "$Role"
        |  password = "$password"
        |  initial-size = 1
        |  max-size = 2
        |  max-idle-time = 1s
        |  ssl {
        |    mode = "$mode"
        |    root-cert = "${files.resolve("root.crt")}"
        |    cert = "${files.resolve("tls.crt")}"
        |    key = "${files.resolve("tls.key")}"
        |  }
        |}
        |""".stripMargin)
      .withFallback(ConfigFactory.load())
      .resolve()
    given system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "database-tls", config)
    try body(Database())
    finally
      system.terminate()
      Await.ready(system.whenTerminated, 20.seconds): Unit

  private def session(db: Database): (Boolean, String) =
    Await
      .result(
        db.queryOne(sql"SELECT ssl, client_dn FROM pg_stat_ssl WHERE pid = pg_backend_pid()")(row =>
          (
            row.get("ssl", classOf[java.lang.Boolean]).booleanValue,
            row.get("client_dn", classOf[String])
          )
        ),
        20.seconds
      )
      .get

  test("a service connects over TLS, authenticated by its certificate, with no password") {
    val files = clientFiles(clientAuthority.issue(cn = Some(Role)))
    withDatabase(files) { db =>
      val (ssl, dn) = session(db)
      assert(ssl, "the session is not encrypted")
      assertEquals(dn, s"/CN=$Role")
    }
  }

  test("a certificate naming another role does not log in as this one") {
    val files = clientFiles(clientAuthority.issue(cn = Some("payments")))
    withDatabase(files) { db =>
      val failure = Try(session(db)).failed.toOption
        .getOrElse(fail("logged in with another role's certificate"))
      assert(failure.toString.contains("certificate authentication failed"), failure.toString)
    }
  }

  test("a server the configured root did not sign is refused, naming the verification") {
    val files = clientFiles(clientAuthority.issue(cn = Some(Role)))
    Files.writeString(files.resolve("root.crt"), TestPki.root("impostor").pem)
    withDatabase(files) { db =>
      val failure =
        Try(session(db)).failed.toOption.getOrElse(fail("connected to an unverified server"))
      val text =
        Iterator.iterate[Throwable](failure)(_.getCause).takeWhile(_ != null).mkString(" / ")
      assert(text.toLowerCase.contains("certificat") || text.contains("PKIX"), text)
    }
  }

  test("a renewed client certificate reaches the next connection, without a restart") {
    val first = clientAuthority.issue(cn = Some(Role))
    val files = clientFiles(first)
    withDatabase(files) { db =>
      assert(session(db)._1)
      // Replace the key and certificate the way the kubelet does, then let the pool's idle
      // connections expire so the next query opens a new one.
      val renewed = clientAuthority.issue(cn = Some(Role))
      Files.writeString(files.resolve("tls.crt"), renewed.certPem)
      Files.writeString(files.resolve("tls.key"), renewed.keyPem)
      val later = FileTime.from(Instant.now().plusSeconds(5))
      Seq("tls.crt", "tls.key").foreach(n =>
        Files.setLastModifiedTime(files.resolve(n), later): Unit
      )
      Thread.sleep(3000)
      assert(session(db)._1, "the connection after renewal failed")
    }
  }

  test("with no mode set, nothing about the connection changes") {
    val config = ConfigFactory.parseString("ssl { mode = \"\" }")
    assertEquals(DatabaseTls.settings(config), None)
    val set = ConfigFactory.parseString(
      """ssl { mode = "verify-full", root-cert = "/r", cert = "/c", key = "/k" }"""
    )
    assertEquals(
      DatabaseTls.settings(set),
      Some(DatabaseTls.Settings("verify-full", Some("/r"), Some("/c" -> "/k")))
    )
  }

/** Concrete, purely to pin testcontainers' self type. */
private final class TlsPostgres(image: DockerImageName) extends GenericContainer[TlsPostgres](image)
